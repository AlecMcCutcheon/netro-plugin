package dev.netro.detector;

import dev.netro.NetroPlugin;
import dev.netro.database.CartHeldCountRepository;
import dev.netro.database.CartRepository;
import dev.netro.database.ControllerRepository;
import dev.netro.database.DetectorRepository;
import dev.netro.database.RuleRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Controller;
import dev.netro.model.Detector;
import dev.netro.model.Rule;
import dev.netro.model.Station;
import dev.netro.model.TransferNode;
import dev.netro.gui.RulesMainHolder;
import dev.netro.routing.RoutingEngine;
import dev.netro.util.AddressHelper;
import dev.netro.util.DestinationResolver;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When a cart is on a detector rail: resolve direction, fire ENTRY/READY/CLEAR,
 * set held state (detection-based for terminals with 5s timeout), and set RELEASE controller bulbs.
 */
public class DetectorRailHandler {

    private static final long DEBOUNCE_MS_ENTRY_READY = 1_500;
    private static final long DEBOUNCE_MS_CLEAR = 600;
    private static final long DETECTOR_PULSE_MS = 1_000;
    /** After READY stops firing (cart no longer detected), held is cleared after this many ticks unless CLEAR runs first. */
    private static final long TERMINAL_HELD_TIMEOUT_TICKS = 100; // 5 seconds

    private final NetroPlugin plugin;
    private final DetectorRepository detectorRepo;
    private final ControllerRepository controllerRepo;
    private final CartHeldCountRepository heldCountRepo;
    private final CartRepository cartRepo;
    private final TransferNodeRepository nodeRepo;
    private final StationRepository stationRepo;
    private final RuleRepository ruleRepo;
    private final RoutingEngine routing;
    private final UnifiedCartSeenFlow unifiedCartSeenFlow;

    /** key: cartUuid|detectorId|role -> last fired time */
    private final Map<String, Long> lastFired = new ConcurrentHashMap<>();

    /** key: nodeId -> scheduled task that clears held after TERMINAL_HELD_TIMEOUT_TICKS when cart no longer detected (or triggers double-check). */
    private final Map<String, BukkitTask> terminalTimeoutTasks = new ConcurrentHashMap<>();
    /** key: nodeId -> repeating task that polls every 1s: cart still on rail? dest set? → RELEASE and stop. */
    private final Map<String, BukkitTask> terminalPollingTasks = new ConcurrentHashMap<>();
    /** key: nodeId -> cart ref for polling (reuse to avoid entity search each tick). Cleared when polling cancelled. */
    private final Map<String, Minecart> terminalPollingCartRef = new ConcurrentHashMap<>();
    /** key: nodeId -> task that runs 5s after RELEASE was turned on; then double-check and clear or resume polling. */
    private final Map<String, BukkitTask> terminalPostReleaseTimeoutTasks = new ConcurrentHashMap<>();

    /** Max block updates (bulbs + rails) per tick in apply step; rest run next tick(s) to smooth cost. */
    private static final int BLOCK_UPDATES_PER_TICK = 4;
    /** Ready hold: velocity correction is applied from CartListener when cart moves; we only schedule 1s then start polling (no separate velocity timer). */

    /** Deferred rail state: apply only when cart is within radius of that rail (loop every N ticks). key: cartUuid. */
    private static final double DEFERRED_RAIL_STATE_RADIUS = 2.0;
    private static final long DEFERRED_RAIL_STATE_TIMEOUT_MS = 30_000;
    /** How often the "cart near rail?" check runs; rails are applied only when cart is in range. */
    private static final long DEFERRED_RAIL_STATE_INTERVAL_TICKS = 5;
    private final Map<String, DeferredRailStateTask> deferredRailStateTasks = new ConcurrentHashMap<>();

    /** Rules by context (contextType|contextId|contextSide) to avoid repeated DB load per detector pass. */
    private final Map<String, List<Rule>> ruleCache = new ConcurrentHashMap<>();

    /** Reused per detector pass (main thread only); cleared at start of each run to avoid allocating 3 maps per cart. */
    private final Map<String, TransferNode> nodeCacheReused = new LinkedHashMap<>();
    private final Map<String, Station> stationCacheReused = new LinkedHashMap<>();
    private final Map<String, String> destDisplayCacheReused = new HashMap<>();

    /** Call when rules are created, updated, or deleted so the next detector pass sees fresh rules. */
    public void invalidateRuleCache() {
        ruleCache.clear();
    }

    /** Result of async read: cart data, first detector node's station id, and pre-fetched node/station maps for all detectors at this rail. */
    private record DetectorInitialData(
        Optional<Map<String, Object>> cartDataOpt,
        String currentStationId,
        Map<String, TransferNode> nodeMap,
        Map<String, Station> stationMap
    ) {
        DetectorInitialData(Optional<Map<String, Object>> cartDataOpt, String currentStationId) {
            this(cartDataOpt, currentStationId, new HashMap<>(), new HashMap<>());
        }
    }

    /** Everything the main thread must apply after async detector run (no DB, no heavy logic). */
    private record DetectorApplyResult(
        List<BulbAction> bulbActions,
        List<RailStateAction> railStateActions,
        List<RailStateAction> deferredRailStateActions,
        List<CruiseSpeedAction> cruiseSpeedActions,
        String readyNodeId,
        String titleToShow,
        String subtitleToShow,
        String unreachableOldDest,
        boolean removeCart,
        List<String> recheckCartUuids,
        boolean ensureCartTaskRunning,
        List<Runnable> mainThreadRunnables
    ) {}

    private List<Rule> getRulesCached(String contextType, String contextId, String contextSide) {
        String key = contextType + "|" + contextId + "|" + (contextSide != null ? contextSide : "");
        return ruleCache.computeIfAbsent(key, k -> ruleRepo.findByContext(contextType, contextId, contextSide));
    }

    public DetectorRailHandler(NetroPlugin plugin) {
        this.plugin = plugin;
        var db = plugin.getDatabase();
        this.detectorRepo = new DetectorRepository(db);
        this.controllerRepo = new ControllerRepository(db);
        this.heldCountRepo = new CartHeldCountRepository(db);
        this.nodeRepo = new TransferNodeRepository(db);
        this.cartRepo = new CartRepository(db);
        this.stationRepo = new StationRepository(db);
        this.ruleRepo = new RuleRepository(db);
        this.routing = plugin.getRoutingEngine();
        this.unifiedCartSeenFlow = new UnifiedCartSeenFlow(plugin, cartRepo, stationRepo, nodeRepo, routing);
    }

    /**
     * Call when a cart has moved; (railX, railY, railZ) is the rail block the cart is on (or below).
     * All DB and decision logic runs async; main thread only does: read block/velocity for direction,
     * then (after async) apply result (bulbs, rails, title, etc.). Deferred rail-state is only
     * replaced when a detector returns a new non-empty list, not cancelled on every detector hit.
     * Returns true so the caller can keep processing if needed.
     */
    public boolean onCartOnDetectorRail(World world, int railX, int railY, int railZ, Minecart cart) {
        final String worldName = world.getName();
        String cartUuid = cart.getUniqueId().toString();
        org.bukkit.block.Block railBlock = world.getBlockAt(railX, railY, railZ);
        org.bukkit.block.BlockFace cartCardinal = DirectionHelper.velocityAndRailToCardinal(cart.getVelocity(), railBlock);
        final boolean debug = plugin.isDebugEnabled();

        // Do not cancel deferred rail-state here: that would kill the task from a previous detector before rails are applied when the cart hits a later detector (e.g. terminal) that has no SET_RAIL_STATE. Replacement happens only in startDeferredRailStateTask when starting a new task.

        // All DB and logic off main thread. Main only applies the result in applyDetectorResult.
        plugin.getDatabase().runAsyncRead(conn -> {
            List<Detector> detectors = detectorRepo.findByRail(conn, worldName, railX, railY, railZ);
            if (detectors.isEmpty()) return null;

            Optional<Map<String, Object>> cartData = cartRepo.find(conn, cartUuid);
            String currentStationId = null;
            Map<String, TransferNode> nodeMap = new HashMap<>();
            Map<String, Station> stationMap = new HashMap<>();
            for (Detector d : detectors) {
                if (d.getNodeId() == null) continue;
                String nid = d.getNodeId();
                if (nodeMap.containsKey(nid)) continue;
                Optional<TransferNode> node = nodeRepo.findById(conn, nid);
                if (node.isEmpty()) continue;
                TransferNode n = node.get();
                nodeMap.put(nid, n);
                stationRepo.findById(conn, n.getStationId()).ifPresent(s -> stationMap.put(s.getId(), s));
                if (currentStationId == null) currentStationId = n.getStationId();
            }
            Optional<String> headingTowardStationId = resolveHeadingTowardStation(detectors, cartCardinal, nodeMap, conn);
            UnifiedCartSeenFlow.AsyncOut asyncOut = new UnifiedCartSeenFlow.AsyncOut();
            unifiedCartSeenFlow.runWithWorldName(cartUuid, worldName, detectors, railX, railY, railZ, headingTowardStationId, cartData, Optional.ofNullable(currentStationId), asyncOut);
            Optional<Map<String, Object>> cartDataOpt = cartData;
            Optional<String> unreachableOldDest = (currentStationId != null && cartDataOpt.isPresent())
                ? routing.handleUnreachableAndRedirectToNearestTerminal(cartUuid, currentStationId, cartDataOpt) : Optional.empty();

            List<BulbAction> bulbActions = new ArrayList<>();
            List<RailStateAction> railStateActions = new ArrayList<>();
            List<RailStateAction> deferredRailStateActions = new ArrayList<>();
            List<CruiseSpeedAction> cruiseSpeedActions = new ArrayList<>();
            List<Runnable> mainThreadRunnables = new ArrayList<>();
            String[] readyCenterNodeIdRef = new String[1];
            String[] titleRef = new String[2];
            Map<String, TransferNode> nodeCacheReused = new LinkedHashMap<>(nodeMap);
            Map<String, Station> stationCacheReused = new LinkedHashMap<>(stationMap);
            Map<String, String> destDisplayCacheReused = new HashMap<>();
            for (Detector d : detectors) {
                String dir = DirectionHelper.cardinalToDirectionLabel(d.getSignFacing(), cartCardinal);
                boolean m1 = ruleMatches(d.getRule1Role(), d.getRule1Direction(), dir);
                if (m1) processRule(d, d.getRule1Role(), d.getRule1Direction(), dir, cartUuid, cartDataOpt, bulbActions, railStateActions, deferredRailStateActions, cruiseSpeedActions, debug, worldName, railX, railY, railZ, readyCenterNodeIdRef, titleRef, nodeCacheReused, stationCacheReused, destDisplayCacheReused, mainThreadRunnables);
                if (d.getRule2Role() != null) { boolean m2 = ruleMatches(d.getRule2Role(), d.getRule2Direction(), dir); if (m2) processRule(d, d.getRule2Role(), d.getRule2Direction(), dir, cartUuid, cartDataOpt, bulbActions, railStateActions, deferredRailStateActions, cruiseSpeedActions, debug, worldName, railX, railY, railZ, readyCenterNodeIdRef, titleRef, nodeCacheReused, stationCacheReused, destDisplayCacheReused, mainThreadRunnables); }
                if (d.getRule3Role() != null) { boolean m3 = ruleMatches(d.getRule3Role(), d.getRule3Direction(), dir); if (m3) processRule(d, d.getRule3Role(), d.getRule3Direction(), dir, cartUuid, cartDataOpt, bulbActions, railStateActions, deferredRailStateActions, cruiseSpeedActions, debug, worldName, railX, railY, railZ, readyCenterNodeIdRef, titleRef, nodeCacheReused, stationCacheReused, destDisplayCacheReused, mainThreadRunnables); }
                if (d.getRule4Role() != null) { boolean m4 = ruleMatches(d.getRule4Role(), d.getRule4Direction(), dir); if (m4) processRule(d, d.getRule4Role(), d.getRule4Direction(), dir, cartUuid, cartDataOpt, bulbActions, railStateActions, deferredRailStateActions, cruiseSpeedActions, debug, worldName, railX, railY, railZ, readyCenterNodeIdRef, titleRef, nodeCacheReused, stationCacheReused, destDisplayCacheReused, mainThreadRunnables); }
                bulbActions.add(new BulbAction(d.getWorld(), d.getX(), d.getY(), d.getZ(), true, DETECTOR_PULSE_MS));
            }
            List<RailStateAction> deferredCopy = new ArrayList<>();
            for (RailStateAction r : deferredRailStateActions) deferredCopy.add(r.copy());
            dedupeBulbActions(bulbActions);
            return new DetectorApplyResult(bulbActions, railStateActions, deferredCopy, cruiseSpeedActions, readyCenterNodeIdRef[0], titleRef[0], titleRef[1], unreachableOldDest.orElse(null), asyncOut.removeCart, new ArrayList<>(asyncOut.recheckCartUuids), asyncOut.ensureCartTaskRunning, mainThreadRunnables);
        }, result -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (result != null) applyDetectorResult(cartUuid, world, worldName, railX, railY, railZ, result, cart);
        }));
        return true;
    }

    /**
     * Apply a DetectorApplyResult on the main thread. No DB.
     * Light work (recheck, mainThreadRunnables, etc.) runs this tick; block updates, title, ready/deferred
     * run next tick to avoid transfer/terminal lag spike. cartRef from detector event used when valid to skip entity lookup.
     */
    private void applyDetectorResult(String cartUuid, World world, String worldName, int railX, int railY, int railZ, DetectorApplyResult result, Minecart cartRef) {
        if (result == null) return;
        World w = (world != null && world.getName().equals(worldName)) ? world : plugin.getServer().getWorld(worldName);
        java.util.UUID uuid;
        try { uuid = java.util.UUID.fromString(cartUuid); } catch (IllegalArgumentException e) { uuid = null; }
        Minecart resolvedCart = (cartRef != null && cartRef.isValid()) ? cartRef : (w != null && uuid != null ? findMinecartByUuidNear(w, uuid, railX, railY, railZ, 3) : null);
        if (result.removeCart() && resolvedCart != null && resolvedCart.isValid()) {
            resolvedCart.remove();
            resolvedCart = null;
        }
        for (String u : result.recheckCartUuids()) recheckTerminalReleaseForCart(u);
        if (result.ensureCartTaskRunning() && plugin.getChunkLoadService() != null) plugin.getChunkLoadService().ensureCartTaskRunning();
        if (result.unreachableOldDest() != null) notifyUnreachableRedirect(w != null ? w : plugin.getServer().getWorld(worldName), cartUuid, result.unreachableOldDest());
        for (Runnable r : result.mainThreadRunnables()) r.run();

        final World wFinal = w;
        final Minecart resolvedForDeferred = resolvedCart;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> applyStep1Rails(cartUuid, wFinal, worldName, railX, railY, railZ, result, resolvedForDeferred), 1L);
    }

    /** Step 1 (tick+1): rail state loop only; then schedule step 2 one tick later. */
    private void applyStep1Rails(String cartUuid, World w, String worldName, int railX, int railY, int railZ, DetectorApplyResult result, Minecart resolvedCartRef) {
        if (result == null) return;
        World wResolved = (w != null && w.getName().equals(worldName)) ? w : plugin.getServer().getWorld(worldName);
        for (RailStateAction r : result.railStateActions()) {
            org.bukkit.World rw = (wResolved != null && r.world.equals(worldName)) ? wResolved : plugin.getServer().getWorld(r.world);
            if (rw == null) continue;
            org.bukkit.block.Block block = rw.getBlockAt(r.x, r.y, r.z);
            if (!(block.getBlockData() instanceof org.bukkit.block.data.Rail railData)) continue;
            org.bukkit.block.data.Rail.Shape desired;
            try { desired = org.bukkit.block.data.Rail.Shape.valueOf(r.shapeName); } catch (IllegalArgumentException e) { continue; }
            if (railData.getShape() != desired) {
                railData.setShape(desired);
                block.setBlockData(railData);
            }
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> applyStep2ReadyCenter(cartUuid, wResolved, worldName, railX, railY, railZ, result, resolvedCartRef), 1L);
    }

    /** Step 2 (tick+2): ready center only; then schedule step 3 one tick later. */
    private void applyStep2ReadyCenter(String cartUuid, World w, String worldName, int railX, int railY, int railZ, DetectorApplyResult result, Minecart resolvedCartRef) {
        if (result == null) return;
        Minecart resolvedCart = resolveCart(cartUuid, w, worldName, railX, railY, railZ, resolvedCartRef);
        if (result.readyNodeId() != null && resolvedCart != null && resolvedCart.isValid()) runTerminalReadyCartCenterWithCart(resolvedCart, result.readyNodeId(), cartUuid, worldName, railX, railY, railZ);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> applyStep3DeferredRail(cartUuid, w, worldName, railX, railY, railZ, result, resolvedCart), 1L);
    }

    /** Step 3 (tick+3): deferred rail task only; then schedule step 4 one tick later. */
    private void applyStep3DeferredRail(String cartUuid, World w, String worldName, int railX, int railY, int railZ, DetectorApplyResult result, Minecart resolvedCartRef) {
        if (result == null) return;
        Minecart resolvedCart = resolveCart(cartUuid, w, worldName, railX, railY, railZ, resolvedCartRef);
        if (!result.deferredRailStateActions().isEmpty()) startDeferredRailStateTask(cartUuid, result.deferredRailStateActions(), resolvedCart);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> applyStep4Title(cartUuid, w, worldName, railX, railY, railZ, result, resolvedCart), 1L);
    }

    /** Step 4 (tick+4): title only; then schedule step 5 one tick later. */
    private void applyStep4Title(String cartUuid, World w, String worldName, int railX, int railY, int railZ, DetectorApplyResult result, Minecart resolvedCartRef) {
        if (result == null) return;
        Minecart resolvedCart = resolveCart(cartUuid, w, worldName, railX, railY, railZ, resolvedCartRef);
        String titleToShow = result.titleToShow();
        String subtitleToShow = result.subtitleToShow();
        if ((titleToShow != null || subtitleToShow != null) && resolvedCart != null && resolvedCart.isValid() && !resolvedCart.getPassengers().isEmpty() && resolvedCart.getPassengers().get(0) instanceof org.bukkit.entity.Player player) {
            if (plugin.isTitleMessagesEnabled(player)) player.sendTitle(titleToShow != null ? titleToShow : "", subtitleToShow != null ? subtitleToShow : "", 10, 70, 20);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> applyStep5Cruise(cartUuid, w, worldName, result), 1L);
    }

    /** Step 5 (tick+5): cruise only; then schedule step 6 one tick later. */
    private void applyStep5Cruise(String cartUuid, World w, String worldName, DetectorApplyResult result) {
        if (result == null) return;
        World wResolved = (w != null && w.getName().equals(worldName)) ? w : plugin.getServer().getWorld(worldName);
        for (CruiseSpeedAction a : result.cruiseSpeedActions()) plugin.getCartControllerGuiListener().applyRuleCruiseSpeed(a.cartUuid, a.magnitude);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> applyStep6Bulbs(wResolved, worldName, result), 1L);
    }

    /** Step 6 (tick+6): bulbs only. */
    private void applyStep6Bulbs(World w, String worldName, DetectorApplyResult result) {
        if (result == null) return;
        scheduleBlockUpdatesSpread(result.bulbActions(), w, worldName);
    }

    private Minecart resolveCart(String cartUuid, World w, String worldName, int railX, int railY, int railZ, Minecart ref) {
        if (ref != null && ref.isValid()) return ref;
        java.util.UUID uuid;
        try { uuid = java.util.UUID.fromString(cartUuid); } catch (IllegalArgumentException e) { return null; }
        World wResolved = (w != null && w.getName().equals(worldName)) ? w : plugin.getServer().getWorld(worldName);
        return (wResolved != null && uuid != null) ? findMinecartByUuidNear(wResolved, uuid, railX, railY, railZ, 3) : null;
    }

    /** Apply bulb updates in batches of BLOCK_UPDATES_PER_TICK per tick to avoid spikes. Rail states are applied immediately so transfer nodes set rails before the cart passes. */
    private void scheduleBlockUpdatesSpread(List<BulbAction> bulbActions, World w, String worldName) {
        applyBulbBatch(bulbActions, 0, w, worldName);
    }

    private void applyBulbBatch(List<BulbAction> bulbActions, int fromIdx, World w, String worldName) {
        int toIdx = Math.min(fromIdx + BLOCK_UPDATES_PER_TICK, bulbActions.size());
        for (int i = fromIdx; i < toIdx; i++) {
            BulbAction a = bulbActions.get(i);
            org.bukkit.World bulbWorld = (w != null && a.world.equals(worldName)) ? w : plugin.getServer().getWorld(a.world);
            if (bulbWorld != null) {
                CopperBulbHelper.setPowered(bulbWorld, a.x, a.y, a.z, a.on);
                if (a.pulseMs > 0) {
                    final org.bukkit.World pulseWorld = bulbWorld;
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> CopperBulbHelper.setPowered(pulseWorld, a.x, a.y, a.z, false), a.pulseMs / 50);
                }
            }
        }
        if (toIdx < bulbActions.size()) {
            final int nextFrom = toIdx;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> applyBulbBatch(bulbActions, nextFrom, w, worldName), 1L);
        }
    }

    /** Human-readable target for detector debug: "node=Station:Node (transfer|terminal)". Uses pre-filled caches when present to avoid DB on hot path. */
    private String detectorTargetLabel(Detector d) {
        if (d.getNodeId() != null) {
            TransferNode node = nodeCacheReused.get(d.getNodeId());
            if (node == null) {
                Optional<TransferNode> opt = nodeRepo.findById(d.getNodeId());
                node = opt.orElse(null);
            }
            if (node != null) {
                Station st = stationCacheReused.get(node.getStationId());
                String stName = st != null ? st.getName() : (stationRepo.findById(node.getStationId()).map(Station::getName).orElse("?"));
                String kind = node.isTerminal() ? "terminal" : "transfer";
                return "node=" + stName + ":" + node.getName() + " (" + kind + ")";
            }
            return "node=" + d.getNodeId();
        }
        return "detector=" + d.getId();
    }

    /** True when the cart's destination is this terminal (so we clear dest and do not RELEASE). */
    private boolean destIsForThisTerminal(String cartDest, String terminalAddr) {
        if (terminalAddr == null || cartDest == null || cartDest.isEmpty()) return false;
        if (terminalAddr.equals(cartDest)) return true;
        return DestinationResolver.resolveToAddress(stationRepo, nodeRepo, cartDest)
            .map(resolved -> resolved.equals(terminalAddr))
            .orElse(false);
    }

    /**
     * READY center with cart already resolved (called from apply task). Hold at center for 1s then start polling.
     * Call on main thread with valid cart.
     */
    private void runTerminalReadyCartCenterWithCart(Minecart cart, String nodeId, String cartUuid, String railWorldName, int railX, int railY, int railZ) {
        double cx = railX + 0.5;
        double cy = railY + 0.5;
        double cz = railZ + 0.5;
        plugin.registerReadyHold(cartUuid, railWorldName, cx, cy, cz);
        applyReadyHoldVelocityCorrection(cart, cx, cy, cz);
        final Minecart cartRef = cart;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.removeReadyHold(cartUuid);
            startTerminalPolling(nodeId, cartUuid, railWorldName, railX, railY, railZ, cartRef);
        }, 21);
    }

    /**
     * Fallback when cart was not available in apply task (e.g. world unload). Finds cart then runs READY center.
     */
    private void scheduleTerminalReadyCartCenter(String nodeId, String cartUuid, String railWorldName, int railX, int railY, int railZ) {
        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(cartUuid);
        } catch (IllegalArgumentException e) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            World w = plugin.getServer().getWorld(railWorldName);
            if (w == null) return;
            Minecart cart = findMinecartByUuid(w, uuid);
            if (cart != null && cart.isValid()) {
                runTerminalReadyCartCenterWithCart(cart, nodeId, cartUuid, railWorldName, railX, railY, railZ);
            }
        });
    }

    private static Minecart findMinecartByUuid(World world, java.util.UUID uuid) {
        for (Minecart m : world.getEntitiesByClass(Minecart.class)) {
            if (m.getUniqueId().equals(uuid)) return m;
        }
        return null;
    }

    /** Prefer when the cart is known to be near (e.g. just triggered detector at railX, railY, railZ). Uses nearby entities then full scan fallback. */
    private static Minecart findMinecartByUuidNear(World world, java.util.UUID uuid, int centerX, int centerY, int centerZ, int radius) {
        if (world == null || uuid == null) return null;
        org.bukkit.Location center = new org.bukkit.Location(world, centerX + 0.5, centerY + 0.5, centerZ + 0.5);
        for (org.bukkit.entity.Entity e : world.getNearbyEntities(center, radius, radius, radius)) {
            if (e instanceof Minecart m && m.getUniqueId().equals(uuid)) return m;
        }
        return findMinecartByUuid(world, uuid);
    }

    /** Called on main thread when a cart's destination was unreachable and has been redirected to nearest terminal. */
    private void notifyUnreachableRedirect(World world, String cartUuid, String oldDest) {
        String destDisplay = RulesMainHolder.formatDestinationId(oldDest, stationRepo, nodeRepo);
        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(cartUuid);
        } catch (IllegalArgumentException e) {
            return;
        }
        Minecart cart = world != null ? findMinecartByUuid(world, uuid) : null;
        if (cart == null || !cart.isValid()) {
            plugin.getServer().broadcastMessage("[Netro] Cart had no route to " + destDisplay + "; redirected to nearest terminal.");
            return;
        }
        Optional<String> newDest = cartRepo.find(cartUuid).map(c -> (String) c.get("destination_address"));
        String subtitle = newDest.filter(s -> s != null && !s.isEmpty())
            .map(s -> "Redirected to " + RulesMainHolder.formatDestinationId(s, stationRepo, nodeRepo))
            .orElse("Redirected to nearest terminal.");
        if (!cart.getPassengers().isEmpty() && cart.getPassengers().get(0) instanceof org.bukkit.entity.Player player) {
            if (plugin.isTitleMessagesEnabled(player)) {
                player.sendTitle("No route to destination", subtitle, 10, 70, 20);
            }
        } else {
            plugin.getServer().broadcastMessage("[Netro] Cart has no route to " + destDisplay + "; " + subtitle);
        }
    }

    /** Distance within which the cart is considered at center; then we set velocity to zero. */
    private static final double READY_CENTER_TOLERANCE = 0.15;
    /** Max speed when correcting toward center (velocity-based, works with player in cart). */
    private static final double READY_CORRECTION_SPEED = 0.5;

    /**
     * Move the cart toward (cx,cy,cz) using velocity only: if not at center, set velocity toward center;
     * once at center, set velocity to zero. Velocity-based so it works with a player in the cart.
     */
    public void applyReadyHoldVelocityCorrection(Minecart cart, double cx, double cy, double cz) {
        org.bukkit.Location loc = cart.getLocation();
        double dx = cx - loc.getX();
        double dy = cy - loc.getY();
        double dz = cz - loc.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < READY_CENTER_TOLERANCE) {
            cart.setVelocity(new Vector(0, 0, 0));
            return;
        }
        double speed = Math.min(dist * 0.9, READY_CORRECTION_SPEED);
        cart.setVelocity(new Vector(dx / dist * speed, dy / dist * speed, dz / dist * speed));
    }

    /** When debug: log whether rule slot matched or not and why (direction/cartDir). */
    private void logRuleMatch(boolean debug, Detector d, int slot, String role, String ruleDir, String cartDir, boolean match) {
        if (!debug) return;
        String roleDir = (role != null ? role : "?") + (ruleDir != null && !ruleDir.isEmpty() ? ":" + ruleDir : "");
        if (match) {
            plugin.getLogger().info("[Netro detector] rule" + slot + " " + roleDir + " cartDir=" + cartDir + " → match, processRule");
        } else {
            String reason = (role == null) ? "no role" : (cartDir == null) ? "cart not LEFT/RIGHT (ahead/back)" : "direction " + ruleDir + " != " + cartDir;
            plugin.getLogger().info("[Netro detector] rule" + slot + " " + roleDir + " cartDir=" + (cartDir != null ? cartDir : "null") + " → no match (" + reason + ")");
        }
    }

    /** ENTRY and CLEAR use cart direction when rule specifies one; READY ignores direction (single slot at terminal). */
    private static boolean ruleMatches(String role, String ruleDir, String cartDir) {
        if (role == null) return false;
        if ("READY".equals(role)) return true;
        if ("CLEAR".equals(role)) {
            if (ruleDir == null || ruleDir.isEmpty()) return true;
            return directionLabelsMatch(ruleDir, cartDir);
        }
        if (ruleDir == null) return true;
        return directionLabelsMatch(ruleDir, cartDir);
    }

    /**
     * When the cart is seen at a transfer node and CLEAR matches, the cart is leaving that node and heading toward
     * the paired node's station. Returns that station id so the unified flow can set destination to a terminal there.
     */
    private Optional<String> resolveHeadingTowardStation(List<Detector> detectors, org.bukkit.block.BlockFace cartCardinal) {
        return resolveHeadingTowardStation(detectors, cartCardinal, null, null);
    }

    /** Async variant: use nodeMap for nodes, conn for paired lookup. */
    private Optional<String> resolveHeadingTowardStation(List<Detector> detectors, org.bukkit.block.BlockFace cartCardinal, Map<String, TransferNode> nodeMap, java.sql.Connection conn) {
        try {
            for (Detector d : detectors) {
                if (d.getNodeId() == null) continue;
                Optional<TransferNode> nodeOpt = (nodeMap != null && conn != null)
                    ? (nodeMap.containsKey(d.getNodeId()) ? Optional.of(nodeMap.get(d.getNodeId())) : nodeRepo.findById(conn, d.getNodeId()))
                    : nodeRepo.findById(d.getNodeId());
                if (nodeOpt.isEmpty() || nodeOpt.get().isTerminal()) continue;
                TransferNode node = nodeOpt.get();
                if (node.getPairedNodeId() == null) continue;
                String dir = DirectionHelper.cardinalToDirectionLabel(d.getSignFacing(), cartCardinal);
                boolean clearFires = ("CLEAR".equals(d.getRule1Role()) && ruleMatches(d.getRule1Role(), d.getRule1Direction(), dir))
                    || (d.getRule2Role() != null && "CLEAR".equals(d.getRule2Role()) && ruleMatches(d.getRule2Role(), d.getRule2Direction(), dir))
                    || (d.getRule3Role() != null && "CLEAR".equals(d.getRule3Role()) && ruleMatches(d.getRule3Role(), d.getRule3Direction(), dir))
                    || (d.getRule4Role() != null && "CLEAR".equals(d.getRule4Role()) && ruleMatches(d.getRule4Role(), d.getRule4Direction(), dir));
                if (clearFires) {
                    Optional<TransferNode> paired = (conn != null) ? nodeRepo.findById(conn, node.getPairedNodeId()) : nodeRepo.findById(node.getPairedNodeId());
                    if (paired.isPresent()) return Optional.of(paired.get().getStationId());
                }
            }
            return Optional.empty();
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void processRule(Detector d, String role, String direction, String cartDir, String cartUuid, Optional<Map<String, Object>> cartDataOpt, List<BulbAction> bulbActions, List<RailStateAction> railStateActions, List<RailStateAction> deferredRailStateActions, List<CruiseSpeedAction> cruiseSpeedActions, boolean debug,
            String railWorldName, int railX, int railY, int railZ, String[] readyCenterNodeIdRef, String[] titleRef,
            Map<String, TransferNode> nodeCache, Map<String, Station> stationCache, Map<String, String> destDisplayCache,
            List<Runnable> mainThreadActions) {
        String nodeId = d.getNodeId();
        if (debug) plugin.getLogger().info("[Netro detector] processRule node=" + (nodeId != null ? nodeId : "null") + " role=" + role + " cartDir=" + cartDir);

        String debounceKey = cartUuid + "|" + d.getId() + "|" + role;
        long now = System.currentTimeMillis();
        long debounce = "CLEAR".equals(role) ? DEBOUNCE_MS_CLEAR : DEBOUNCE_MS_ENTRY_READY;
        if (now - lastFired.getOrDefault(debounceKey, 0L) < debounce) {
            if (debug) plugin.getLogger().info("[Netro detector] processRule skipped: debounced");
            return;
        }
        lastFired.put(debounceKey, now);

        if (nodeId == null) {
            if (debug) plugin.getLogger().info("[Netro detector] processRule skipped: no nodeId");
            return;
        }

        if ("ENTRY".equals(role) || "CLEAR".equals(role)) {
            if (debug) plugin.getLogger().info("[Netro detector] evaluating rules node=" + nodeId + " role=" + role);
            evaluateRuleEngine(d, role, cartUuid, cartDataOpt, bulbActions, railStateActions, deferredRailStateActions, cruiseSpeedActions, railWorldName, cartDir, null, nodeCache, stationCache, destDisplayCache, debug);
        }

        // ENTRY at a node: no segment occupancy (no collision detection).
        if ("ENTRY".equals(role) && nodeId != null) {
            if (debug) plugin.getLogger().info("[Netro detector] ENTRY node=" + nodeId + " dir=" + cartDir);
            return;
        }

        if ("READY".equals(role) && nodeId != null) {
            TransferNode nodeForTerminal = nodeCache.computeIfAbsent(nodeId, id -> nodeRepo.findById(id).orElse(null));
            boolean isTerminal = nodeForTerminal != null && nodeForTerminal.isTerminal();
            if (!isTerminal) return;
            String zone = "node:" + nodeId;
            // Only one cart held per terminal: clear any other cart previously held at this node
            for (String uuid : cartRepo.findHeldCartsAtNode(nodeId, "")) {
                if (!uuid.equals(cartUuid)) cartRepo.clearHeld(uuid);
            }
            heldCountRepo.setHeldCount(nodeId, 1);
            cartRepo.setHeld(cartUuid, zone, 0);
            final String nid = nodeId;
            if (mainThreadActions != null) {
                mainThreadActions.add(() -> {
                    if (plugin.getChunkLoadService() != null) plugin.getChunkLoadService().ensureCartTaskRunning();
                    cancelTerminalTimeout(nid);
                    cancelTerminalPolling(nid);
                    cancelPostReleaseTimeout(nid);
                    BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        terminalTimeoutTasks.remove(nid);
                        doubleCheckCartGoneAndClearOrResumePolling(nid);
                    }, TERMINAL_HELD_TIMEOUT_TICKS);
                    terminalTimeoutTasks.put(nid, task);
                });
            } else {
                if (plugin.getChunkLoadService() != null) plugin.getChunkLoadService().ensureCartTaskRunning();
                cancelTerminalTimeout(nodeId);
                cancelTerminalPolling(nodeId);
                cancelPostReleaseTimeout(nodeId);
                BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    terminalTimeoutTasks.remove(nodeId);
                    doubleCheckCartGoneAndClearOrResumePolling(nodeId);
                }, TERMINAL_HELD_TIMEOUT_TICKS);
                terminalTimeoutTasks.put(nodeId, task);
            }
            TransferNode node = nodeCache.computeIfAbsent(nodeId, id -> nodeRepo.findById(id).orElse(null));
            String pairedId = node != null ? node.getPairedNodeId() : null;
            Station station = node != null ? stationCache.computeIfAbsent(node.getStationId(), id -> stationRepo.findById(id).orElse(null)) : null;
            String terminalAddr = node != null && station != null ? node.terminalAddress(station.getAddress()) : null;
            String cartDest = cartDataOpt.flatMap(m -> Optional.ofNullable((String) m.get("destination_address"))).orElse(null);
            if (terminalAddr != null && destIsForThisTerminal(cartDest, terminalAddr)) {
                cartRepo.clearDestination(cartUuid);
            }
            boolean releaseReversed = node != null && node.isReleaseReversed();
            List<String> heldInQueue = cartRepo.findHeldCartsAtNode(nodeId, "");
            String firstToRelease = firstCartToRelease(heldInQueue, releaseReversed);
            boolean first = firstToRelease != null && firstToRelease.equals(cartUuid);
            boolean releaseOn = first && cartDest != null && !cartDest.isEmpty()
                && !destIsForThisTerminal(cartDest, terminalAddr)
                && (pairedId == null || routing.canDispatch(cartUuid, nodeId, pairedId).canGo());
            if (releaseOn) {
                setNodeControllers(bulbActions, nodeId, "RELEASE", null, true);
                if (mainThreadActions != null) {
                    mainThreadActions.add(() -> schedulePostReleaseTimeout(nodeId));
                } else {
                    schedulePostReleaseTimeout(nodeId);
                }
            }
            String stationName = station != null ? station.getName() : "?";
            String terminalName = node != null ? node.getName() : "?";
            titleRef[0] = "Now arriving";
            titleRef[1] = stationName + ", Terminal " + terminalName;
            if (releaseOn) {
                titleRef[0] = "Now departing";
                titleRef[1] = cartDest != null && !cartDest.isEmpty()
                    ? "Final destination: " + destDisplayCache.computeIfAbsent(cartDest, k -> RulesMainHolder.formatDestinationId(k, stationRepo, nodeRepo))
                    : "";
            }
            if (debug) plugin.getLogger().info("[Netro detector] READY terminal node=" + nodeId + " held=1 RELEASE=" + releaseOn);
            if (!releaseOn) readyCenterNodeIdRef[0] = nodeId; // do not hold/center when releasing immediately
            return;
        }

        if ("CLEAR".equals(role) && nodeId != null) {
            TransferNode node = nodeCache.computeIfAbsent(nodeId, id -> nodeRepo.findById(id).orElse(null));
            boolean isTerminal = node != null && node.isTerminal();
            String cartDest = cartDataOpt.flatMap(m -> Optional.ofNullable((String) m.get("destination_address"))).orElse(null);
            if (isTerminal) {
                titleRef[0] = "Now departing";
                titleRef[1] = cartDest != null && !cartDest.isEmpty()
                    ? "Final destination: " + destDisplayCache.computeIfAbsent(cartDest, k -> RulesMainHolder.formatDestinationId(k, stationRepo, nodeRepo))
                    : "";
            } else {
                String fromStationId = node != null ? node.getStationId() : null;
                if (fromStationId != null && cartDest != null && !cartDest.isEmpty()) {
                    Optional<String> nextHopOpt = routing.resolveNextHopNode(fromStationId, cartDest);
                    Optional<String> destStationIdOpt = resolveDestinationStationId(cartDest);
                    boolean titleSet = false;
                    String nextNodeId = nextHopOpt.orElse(null);
                    TransferNode nextNode = nextNodeId != null ? nodeCache.computeIfAbsent(nextNodeId, id -> nodeRepo.findById(id).orElse(null)) : null;
                    if (nextNode != null && !nextNode.getStationId().equals(fromStationId)) {
                        String nextStationId = nextNode.getStationId();
                        Station fromStation = stationCache.computeIfAbsent(fromStationId, id -> stationRepo.findById(id).orElse(null));
                        Station toStation = stationCache.computeIfAbsent(nextStationId, id -> stationRepo.findById(id).orElse(null));
                        if (fromStation != null && toStation != null) {
                            titleRef[0] = "Leaving";
                            titleRef[1] = fromStation.getName() + " → " + toStation.getName();
                            titleSet = true;
                        }
                    }
                    if (!titleSet && destStationIdOpt.isPresent() && !destStationIdOpt.get().equals(fromStationId)) {
                        Station fromStation = stationCache.computeIfAbsent(fromStationId, id -> stationRepo.findById(id).orElse(null));
                        Station toStation = stationCache.computeIfAbsent(destStationIdOpt.get(), id -> stationRepo.findById(id).orElse(null));
                        if (fromStation != null && toStation != null) {
                            titleRef[0] = "Leaving";
                            titleRef[1] = fromStation.getName() + " → " + toStation.getName();
                        }
                    }
                }
            }
            if (isTerminal) {
                final String clearNodeId = nodeId;
                if (mainThreadActions != null) {
                    mainThreadActions.add(() -> {
                        cancelTerminalTimeout(clearNodeId);
                        cancelTerminalPolling(clearNodeId);
                        cancelPostReleaseTimeout(clearNodeId);
                        plugin.getServer().getScheduler().runTask(plugin, () -> doubleCheckCartGoneAndClearOrResumePolling(clearNodeId));
                    });
                } else {
                    cancelTerminalTimeout(nodeId);
                    cancelTerminalPolling(nodeId);
                    cancelPostReleaseTimeout(nodeId);
                    plugin.getServer().getScheduler().runTask(plugin, () -> doubleCheckCartGoneAndClearOrResumePolling(nodeId));
                }
            }
            clearPlusControllersNode(bulbActions, nodeId);
            turnOffAllReleaseNode(bulbActions, nodeId);
            if (debug) plugin.getLogger().info("[Netro detector] CLEAR node=" + nodeId + " (segment register" + (isTerminal ? ", terminal slot" : "") + ")");
        }
    }

    /** Resolve a destination string (address or StationName:NodeName) to the destination station id for "Leaving X for Y" display. */
    private Optional<String> resolveDestinationStationId(String cartDest) {
        if (cartDest == null || cartDest.isEmpty()) return Optional.empty();
        Optional<AddressHelper.ParsedDestination> parsed = AddressHelper.parseDestination(cartDest);
        if (parsed.isPresent()) {
            return stationRepo.findByAddress(parsed.get().stationAddress()).map(Station::getId);
        }
        int colon = cartDest.indexOf(':');
        if (colon > 0 && colon < cartDest.length() - 1) {
            String stationName = cartDest.substring(0, colon).strip();
            return stationRepo.findByNameIgnoreCase(stationName).map(Station::getId);
        }
        return stationRepo.findByAddress(cartDest).map(Station::getId);
    }

    /**
     * Evaluate rule-based actions for this context: load rules for the detector's context (node),
     * match trigger (ENTERING for ENTRY, CLEARING for CLEAR) and destination condition, then apply
     * SEND_ON/SEND_OFF to controllers or add SET_RAIL_STATE to deferred list only (applied in a loop
     * every DEFERRED_RAIL_STATE_INTERVAL_TICKS when cart is within DEFERRED_RAIL_STATE_RADIUS of that rail).
     */
    private void evaluateRuleEngine(Detector d, String role, String cartUuid, Optional<Map<String, Object>> cartDataOpt, List<BulbAction> bulbActions, List<RailStateAction> railStateActions, List<RailStateAction> deferredRailStateActions, List<CruiseSpeedAction> cruiseSpeedActions, String railWorldName, String cartDir, String substitutedDestForRules,
            Map<String, TransferNode> nodeCache, Map<String, Station> stationCache, Map<String, String> destDisplayCache, boolean debug) {
        String nodeId = d.getNodeId();
        if (nodeId == null) return;
        TransferNode node = nodeCache.computeIfAbsent(nodeId, id -> nodeRepo.findById(id).orElse(null));
        String contextType = node != null && node.isTerminal() ? Rule.CONTEXT_TERMINAL : Rule.CONTEXT_TRANSFER;
        String contextId = nodeId;
        String contextSide = null;
        String fromStationId = node != null ? node.getStationId() : null;
        String roleTriggerType = "CLEAR".equals(role) ? Rule.TRIGGER_CLEARING : Rule.TRIGGER_ENTERING;
        String cartDest = cartDataOpt.flatMap(m -> Optional.ofNullable((String) m.get("destination_address"))).orElse(null);
        String destForRules;
        if (substitutedDestForRules != null && !substitutedDestForRules.isEmpty()) {
            destForRules = substitutedDestForRules;
        } else {
            destForRules = cartDest;
            if (cartDest != null && !cartDest.isEmpty() && fromStationId != null) {
                Optional<String> nextHopNodeId = routing.resolveNextHopNode(fromStationId, cartDest);
                destForRules = nextHopNodeId.flatMap(this::nodeIdToDestinationId).orElse(cartDest);
            }
        }
        String cartDestForLog = cartDest;
        List<Rule> rules = getRulesCached(contextType, contextId, contextSide);
        if (debug) {
            plugin.getLogger().info("[Netro rule] context=" + contextType + ":" + contextId + " trigger=" + roleTriggerType
                + " cartDest=" + formatDestForLog(cartDestForLog, destDisplayCache) + " localDest=" + formatDestForLog(destForRules, destDisplayCache)
                + " rules=" + rules.size());
        }
        for (Rule rule : rules) {
            String triggerType = rule.getTriggerType();
            boolean forRole = roleTriggerType.equals(triggerType);
            boolean forDetected = Rule.TRIGGER_DETECTED.equals(triggerType);
            if (!forRole && !forDetected) continue;
            boolean cartMatchesDest = destinationMatchesForRule(destForRules, rule.getDestinationId());
            boolean fires = rule.isDestinationPositive() ? cartMatchesDest : !cartMatchesDest;
            if (debug) {
                plugin.getLogger().info("[Netro rule] rule#" + rule.getRuleIndex() + " destId=" + formatDestForLog(rule.getDestinationId() != null ? rule.getDestinationId() : "any", destDisplayCache)
                    + " positive=" + rule.isDestinationPositive() + " match=" + cartMatchesDest + " fires=" + fires + " action=" + rule.getActionType());
            }
            if (!fires) continue;
            if (Rule.ACTION_SET_CRUISE_SPEED.equals(rule.getActionType()) && rule.getActionData() != null && !rule.getActionData().isEmpty()) {
                try {
                    double speedVal = Double.parseDouble(rule.getActionData().trim());
                    double magnitude = Math.max(0, Math.min(1, speedVal / 10.0));
                    cruiseSpeedActions.add(new CruiseSpeedAction(cartUuid, magnitude));
                } catch (NumberFormatException ignored) { }
            } else if (Rule.ACTION_SEND_ON.equals(rule.getActionType()) || Rule.ACTION_SEND_OFF.equals(rule.getActionType())) {
                boolean on = Rule.ACTION_SEND_ON.equals(rule.getActionType());
                String ruleRole = "RULE:" + rule.getRuleIndex();
                for (Controller c : controllerRepo.findByNodeAndRule(d.getNodeId(), ruleRole, null)) {
                    bulbActions.add(new BulbAction(c.getWorld(), c.getX(), c.getY(), c.getZ(), on, 0));
                }
            } else if (Rule.ACTION_SET_RAIL_STATE.equals(rule.getActionType()) && rule.getActionData() != null && !rule.getActionData().isEmpty()) {
                for (String entry : dev.netro.util.RailStateListEncoder.parseEntries(rule.getActionData())) {
                    String world;
                    int rx, ry, rz;
                    String shapeName;
                    String[] parts = entry.split(",", -1);
                    if (parts.length == 5) {
                        try {
                            world = parts[0];
                            rx = Integer.parseInt(parts[1]);
                            ry = Integer.parseInt(parts[2]);
                            rz = Integer.parseInt(parts[3]);
                            shapeName = parts[4];
                        } catch (NumberFormatException e) {
                            world = d.getWorld();
                            rx = d.getRailX();
                            ry = d.getRailY();
                            rz = d.getRailZ();
                            shapeName = entry;
                        }
                    } else {
                        world = d.getWorld();
                        rx = d.getRailX();
                        ry = d.getRailY();
                        rz = d.getRailZ();
                        shapeName = entry;
                    }
                    deferredRailStateActions.add(new RailStateAction(world, rx, ry, rz, shapeName));
                }
            }
        }
    }

    /** Converts a node ID to the same destination-id format used by the rule picker (Station:Node or 7-part address with colon for terminals). */
    private Optional<String> nodeIdToDestinationId(String nodeId) {
        Optional<TransferNode> nodeOpt = nodeRepo.findById(nodeId);
        if (nodeOpt.isEmpty()) return Optional.empty();
        TransferNode node = nodeOpt.get();
        Optional<Station> stationOpt = stationRepo.findById(node.getStationId());
        if (stationOpt.isEmpty()) return Optional.empty();
        Station station = stationOpt.get();
        if (node.isTerminal() && node.getTerminalIndex() != null) {
            return Optional.of(AddressHelper.terminalAddress(station.getAddress(), node.getTerminalIndex()));
        }
        return Optional.of(station.getName() + ":" + node.getName());
    }

    /** Resolves a destination string to a node ID when it refers to a specific node (6/7-part address or Station:Node name). */
    private Optional<String> resolveDestinationToNodeId(String dest) {
        if (dest == null || dest.isBlank()) return Optional.empty();
        String s = dest.strip();
        Optional<AddressHelper.ParsedDestination> parsed = AddressHelper.parseDestination(s);
        if (parsed.isPresent()) {
            Optional<Station> stOpt = stationRepo.findByAddress(parsed.get().stationAddress());
            if (stOpt.isEmpty()) return Optional.empty();
            Integer termIdx = parsed.get().terminalIndex();
            if (termIdx != null) {
                return nodeRepo.findTerminalByIndex(stOpt.get().getId(), termIdx).map(TransferNode::getId);
            }
            return Optional.empty();
        }
        int colon = s.indexOf(':');
        if (colon >= 0) {
            String namePart = s.substring(0, colon).strip();
            String indexPart = s.substring(colon + 1).strip();
            Optional<Station> station = stationRepo.findByNameIgnoreCase(namePart)
                .or(() -> stationRepo.findByAddress(namePart));
            if (station.isEmpty()) return Optional.empty();
            if (indexPart.isEmpty()) return Optional.empty();
            try {
                int terminalIndex = Integer.parseInt(indexPart);
                return nodeRepo.findTerminalByIndex(station.get().getId(), terminalIndex).map(TransferNode::getId);
            } catch (NumberFormatException ignored) {
            }
            return nodeRepo.findByNameAtStation(station.get().getId(), indexPart).map(TransferNode::getId);
        }
        return Optional.empty();
    }

    /** Format destination for logs/GUI: show resolved name (e.g. Snowy1:Main) instead of raw address. Handles null, empty, and "any". Uses cache when provided. */
    private String formatDestForLog(String dest, Map<String, String> destDisplayCache) {
        if (dest == null || dest.isEmpty()) return "null";
        if ("any".equals(dest)) return "any";
        String key = dest;
        return destDisplayCache.computeIfAbsent(key, k -> RulesMainHolder.formatDestinationId(k, stationRepo, nodeRepo));
    }

    /** True if cart destination matches the rule's destination_id. Uses node ID when both refer to specific nodes so different nodes at the same station do not match. */
    private boolean destinationMatchesForRule(String cartDest, String ruleDestinationId) {
        if (ruleDestinationId == null || ruleDestinationId.isEmpty()) return true;
        if (cartDest == null || cartDest.isEmpty()) return false;
        if (ruleDestinationId.equals(cartDest)) return true;
        Optional<String> cartNodeId = resolveDestinationToNodeId(cartDest);
        Optional<String> ruleNodeId = resolveDestinationToNodeId(ruleDestinationId);
        if (cartNodeId.isPresent() && ruleNodeId.isPresent()) {
            return cartNodeId.get().equals(ruleNodeId.get());
        }
        Optional<String> cartResolved = DestinationResolver.resolveToAddress(stationRepo, nodeRepo, cartDest);
        Optional<String> ruleResolved = DestinationResolver.resolveToAddress(stationRepo, nodeRepo, ruleDestinationId);
        return cartResolved.isPresent() && ruleResolved.isPresent() && cartResolved.get().equals(ruleResolved.get());
    }

    private void turnOffAllReleaseNode(List<BulbAction> out, String nodeId) {
        setNodeControllers(out, nodeId, "RELEASE", null, false);
        setNodeControllers(out, nodeId, "RELEASE", "LEFT", false);
        setNodeControllers(out, nodeId, "RELEASE", "RIGHT", false);
    }

    private void turnOffAllReleaseNode(java.sql.Connection conn, List<BulbAction> out, String nodeId) throws java.sql.SQLException {
        setNodeControllers(conn, out, nodeId, "RELEASE", null, false);
        setNodeControllers(conn, out, nodeId, "RELEASE", "LEFT", false);
        setNodeControllers(conn, out, nodeId, "RELEASE", "RIGHT", false);
    }

    /** Cancel the 5s held timeout for this terminal node (e.g. on CLEAR or when cart is removed). */
    public void cancelTerminalTimeout(String nodeId) {
        BukkitTask t = terminalTimeoutTasks.remove(nodeId);
        if (t != null) t.cancel();
    }

    /** Cancel all terminal-related tasks for this node (timeout, polling, post-RELEASE). Use when cart is removed. */
    public void cancelAllTerminalTasksForNode(String nodeId) {
        cancelTerminalTimeout(nodeId);
        cancelTerminalPolling(nodeId);
        cancelPostReleaseTimeout(nodeId);
    }

    private void cancelTerminalPolling(String nodeId) {
        BukkitTask t = terminalPollingTasks.remove(nodeId);
        if (t != null) t.cancel();
        terminalPollingCartRef.remove(nodeId);
    }

    private void startTerminalPolling(String nodeId, String cartUuid, String railWorldName, int railX, int railY, int railZ) {
        startTerminalPolling(nodeId, cartUuid, railWorldName, railX, railY, railZ, null);
    }

    private void cancelPostReleaseTimeout(String nodeId) {
        BukkitTask t = terminalPostReleaseTimeoutTasks.remove(nodeId);
        if (t != null) t.cancel();
    }

    /** READY detector rail position for this node (for "cart on rail" check). */
    private Optional<Detector> getReadyDetectorForNode(String nodeId) {
        return detectorRepo.findByNodeId(nodeId).stream().filter(d -> d.hasRole("READY")).findFirst();
    }

    /**
     * Syncs physical cart presence on READY rails to held state so routing sees terminals as occupied.
     * Call on main thread. Registers any cart sitting on a READY rail (e.g. placed there or there on chunk load)
     * so that terminal shows as full and other carts are redirected instead of routing into it.
     */
    public void syncCartsOnReadyRailsToHeld() {
        List<Detector> readyDetectors = detectorRepo.findWithRole("READY");
        for (Detector d : readyDetectors) {
            String nodeId = d.getNodeId();
            if (nodeId == null) continue;
            if (!nodeRepo.findById(nodeId).map(TransferNode::isTerminal).orElse(false)) continue;
            World world = plugin.getServer().getWorld(d.getWorld());
            if (world == null) continue;
            int railX = d.getRailX(), railY = d.getRailY(), railZ = d.getRailZ();
            Location center = new Location(world, railX + 0.5, railY + 0.5, railZ + 0.5);
            for (Entity entity : world.getNearbyEntities(center, 0.6, 0.6, 0.6)) {
                if (!(entity instanceof Minecart cart) || !cart.isValid()) continue;
                if (!isCartOnDetectorBlock(cart, d.getWorld(), railX, railY, railZ)) continue;
                String cartUuid = cart.getUniqueId().toString();
                if (cartRepo.find(cartUuid).isEmpty()) {
                    cartRepo.setDestination(cartUuid, null, null);
                }
                for (String uuid : cartRepo.findHeldCartsAtNode(nodeId, "")) {
                    if (!uuid.equals(cartUuid)) cartRepo.clearHeld(uuid);
                }
                heldCountRepo.setHeldCount(nodeId, 1);
                cartRepo.setHeld(cartUuid, "node:" + nodeId, 0);
                break;
            }
        }
    }

    private static boolean isCartOnDetectorBlock(Minecart cart, String worldName, int railX, int railY, int railZ) {
        if (cart == null || !cart.isValid() || !cart.getWorld().getName().equals(worldName)) return false;
        org.bukkit.block.Block at = cart.getLocation().getBlock();
        int bx = at.getX(), by = at.getY(), bz = at.getZ();
        return (bx == railX && (by == railY || by == railY + 1) && bz == railZ);
    }

    private static final long TERMINAL_POLL_INTERVAL_TICKS = 40; // 2 seconds; reduces main-thread and DB work per READY terminal

    /** Result of async terminal poll: either no release needed, or list of bulb actions to apply on main. */
    private record TerminalPollResult(boolean shouldRelease, List<BulbAction> bulbActions) {
        static TerminalPollResult noRelease() { return new TerminalPollResult(false, List.of()); }
        static TerminalPollResult release(List<BulbAction> actions) { return new TerminalPollResult(true, actions); }
    }

    /**
     * Start polling: is cart still on READY rail? dest set? → RELEASE on, stop polling.
     * Main thread only does cart/block check each tick; DB and bulb list building run async.
     */
    private void startTerminalPolling(String nodeId, String cartUuid, String railWorldName, int railX, int railY, int railZ, Minecart initialCart) {
        cancelTerminalPolling(nodeId);
        if (initialCart != null && initialCart.isValid()) {
            terminalPollingCartRef.put(nodeId, initialCart);
        }
        final String wName = railWorldName;
        final int rx = railX, ry = railY, rz = railZ;
        BukkitTask poll = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World w = plugin.getServer().getWorld(wName);
            if (w == null) return;
            java.util.UUID uuid;
            try {
                uuid = java.util.UUID.fromString(cartUuid);
            } catch (IllegalArgumentException e) {
                cancelTerminalPolling(nodeId);
                return;
            }
            Minecart cart = terminalPollingCartRef.get(nodeId);
            if (cart == null || !cart.isValid()) {
                cart = findMinecartByUuid(w, uuid);
                if (cart != null) terminalPollingCartRef.put(nodeId, cart);
            }
            if (!isCartOnDetectorBlock(cart, wName, rx, ry, rz)) return;

            plugin.getDatabase().runAsyncRead(conn -> {
                try {
                    Optional<TransferNode> nodeOpt = nodeRepo.findById(conn, nodeId);
                    if (nodeOpt.isEmpty() || !nodeOpt.get().isTerminal()) return TerminalPollResult.noRelease();
                    TransferNode node = nodeOpt.get();
                    String terminalAddr = node.terminalAddress(stationRepo.findById(conn, node.getStationId()).map(Station::getAddress).orElse(null));
                    String cartDest = cartRepo.find(conn, cartUuid).map(c -> (String) c.get("destination_address")).orElse(null);
                    if (cartDest == null || cartDest.isEmpty() || destIsForThisTerminal(cartDest, terminalAddr)) return TerminalPollResult.noRelease();
                    String pairedId = node.getPairedNodeId();
                    if (pairedId != null) {
                        TransferNode fromNode = nodeRepo.findById(conn, nodeId).orElse(null);
                        TransferNode toNode = nodeRepo.findById(conn, pairedId).orElse(null);
                        if (fromNode == null || toNode == null) return TerminalPollResult.noRelease();
                        if (nodeRepo.countFreeSlots(conn, pairedId) == 0) return TerminalPollResult.noRelease();
                    }
                    List<BulbAction> bulbActions = new ArrayList<>();
                    turnOffAllReleaseNode(conn, bulbActions, nodeId);
                    setNodeControllers(conn, bulbActions, nodeId, "RELEASE", null, true);
                    return TerminalPollResult.release(bulbActions);
                } catch (java.sql.SQLException e) {
                    throw new RuntimeException(e);
                }
            }, result -> {
                if (result == null || !result.shouldRelease()) return;
                plugin.removeReadyHold(cartUuid);
                cancelTerminalPolling(nodeId);
                cancelTerminalTimeout(nodeId);
                for (BulbAction a : result.bulbActions()) {
                    World ww = plugin.getServer().getWorld(a.world);
                    if (ww != null) CopperBulbHelper.setPowered(ww, a.x, a.y, a.z, a.on);
                }
                schedulePostReleaseTimeout(nodeId);
            });
        }, TERMINAL_POLL_INTERVAL_TICKS, TERMINAL_POLL_INTERVAL_TICKS);
        terminalPollingTasks.put(nodeId, poll);
    }

    private static final long POST_RELEASE_TIMEOUT_TICKS = 100; // 5 seconds

    private void schedulePostReleaseTimeout(String nodeId) {
        cancelPostReleaseTimeout(nodeId);
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            terminalPostReleaseTimeoutTasks.remove(nodeId);
            doubleCheckCartGoneAndClearOrResumePolling(nodeId);
        }, POST_RELEASE_TIMEOUT_TICKS);
        terminalPostReleaseTimeoutTasks.put(nodeId, task);
    }

    /**
     * After 5s (from READY or from RELEASE) or when CLEAR fires: verify cart is gone from READY rail.
     * If gone → clear held, turn off RELEASE. If still there → turn off RELEASE, resume polling, reschedule 5s timeout.
     * Call on main thread.
     */
    void doubleCheckCartGoneAndClearOrResumePolling(String nodeId) {
        Optional<Detector> readyDet = getReadyDetectorForNode(nodeId);
        if (readyDet.isEmpty()) return;
        Detector d = readyDet.get();
        String worldName = d.getWorld();
        int railX = d.getRailX(), railY = d.getRailY(), railZ = d.getRailZ();
        List<String> held = cartRepo.findHeldCartsAtNode(nodeId, "");
        if (held.isEmpty()) {
            heldCountRepo.setHeldCount(nodeId, 0);
            List<BulbAction> bulbActions = new ArrayList<>();
            turnOffAllReleaseNode(bulbActions, nodeId);
            for (BulbAction a : bulbActions) {
                World w = plugin.getServer().getWorld(a.world);
                if (w != null) CopperBulbHelper.setPowered(w, a.x, a.y, a.z, a.on);
            }
            return;
        }
        String cartUuid = held.get(0);
        World w = plugin.getServer().getWorld(worldName);
        if (w == null) {
            heldCountRepo.setHeldCount(nodeId, 0);
            for (String uuid2 : new ArrayList<>(held)) cartRepo.clearHeld(uuid2);
            List<BulbAction> bulbActions = new ArrayList<>();
            turnOffAllReleaseNode(bulbActions, nodeId);
            for (BulbAction a : bulbActions) {
                World ww = plugin.getServer().getWorld(a.world);
                if (ww != null) CopperBulbHelper.setPowered(ww, a.x, a.y, a.z, a.on);
            }
            cancelPostReleaseTimeout(nodeId);
            return;
        }
        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(cartUuid);
        } catch (IllegalArgumentException e) {
            return;
        }
        Minecart cart = findMinecartByUuid(w, uuid);
        if (!isCartOnDetectorBlock(cart, worldName, railX, railY, railZ)) {
            List<String> toClear = new ArrayList<>(cartRepo.findHeldCartsAtNode(nodeId, ""));
            heldCountRepo.setHeldCount(nodeId, 0);
            for (String uuid2 : toClear) cartRepo.clearHeld(uuid2);
            List<BulbAction> bulbActions = new ArrayList<>();
            turnOffAllReleaseNode(bulbActions, nodeId);
            for (BulbAction a : bulbActions) {
                World ww = plugin.getServer().getWorld(a.world);
                if (ww != null) CopperBulbHelper.setPowered(ww, a.x, a.y, a.z, a.on);
            }
            cancelPostReleaseTimeout(nodeId);
        } else {
            List<BulbAction> bulbActions = new ArrayList<>();
            turnOffAllReleaseNode(bulbActions, nodeId);
            for (BulbAction a : bulbActions) {
                World ww = plugin.getServer().getWorld(a.world);
                if (ww != null) CopperBulbHelper.setPowered(ww, a.x, a.y, a.z, a.on);
            }
            startTerminalPolling(nodeId, cartUuid, worldName, railX, railY, railZ);
            BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                terminalTimeoutTasks.remove(nodeId);
                doubleCheckCartGoneAndClearOrResumePolling(nodeId);
            }, TERMINAL_HELD_TIMEOUT_TICKS);
            terminalTimeoutTasks.put(nodeId, task);
        }
    }

    /** First cart that should get RELEASE: FIFO (index 0) or reversed/LIFO (last index). */
    private static String firstCartToRelease(List<String> held, boolean releaseReversed) {
        if (held == null || held.isEmpty()) return null;
        return releaseReversed ? held.get(held.size() - 1) : held.get(0);
    }

    /** On CLEAR: set RELEASE controller bulbs from routing decision. */
    private void clearPlusControllersNode(List<BulbAction> out, String nodeId) {
    }

    private void setNodeControllers(List<BulbAction> out, String nodeId, String role, String direction, boolean on) {
        for (Controller c : controllerRepo.findByNodeAndRule(nodeId, role, direction)) {
            out.add(new BulbAction(c.getWorld(), c.getX(), c.getY(), c.getZ(), on, 0));
        }
    }

    private void setNodeControllers(java.sql.Connection conn, List<BulbAction> out, String nodeId, String role, String direction, boolean on) throws java.sql.SQLException {
        for (Controller c : controllerRepo.findByNodeAndRule(conn, nodeId, role, direction)) {
            out.add(new BulbAction(c.getWorld(), c.getX(), c.getY(), c.getZ(), on, 0));
        }
    }

    /** Treat L/LEFT and R/RIGHT as the same so NOT_TRA:L matches cart dir LEFT. */
    private static boolean directionLabelsMatch(String ruleDir, String cartDir) {
        if (ruleDir.equals(cartDir)) return true;
        return ("L".equalsIgnoreCase(ruleDir) && "LEFT".equalsIgnoreCase(cartDir))
            || ("LEFT".equalsIgnoreCase(ruleDir) && "L".equalsIgnoreCase(cartDir))
            || ("R".equalsIgnoreCase(ruleDir) && "RIGHT".equalsIgnoreCase(cartDir))
            || ("RIGHT".equalsIgnoreCase(ruleDir) && "R".equalsIgnoreCase(cartDir));
    }

    /** Merge same (world,x,y,z): keep last on state; max pulseMs. */
    private static void dedupeBulbActions(List<BulbAction> list) {
        Map<String, BulbAction> byPos = new LinkedHashMap<>();
        for (BulbAction a : list) {
            String key = a.world + "|" + a.x + "|" + a.y + "|" + a.z;
            BulbAction existing = byPos.get(key);
            if (existing == null) {
                byPos.put(key, a);
            } else {
                byPos.put(key, new BulbAction(a.world, a.x, a.y, a.z, a.on || existing.on, Math.max(a.pulseMs, existing.pulseMs)));
            }
        }
        list.clear();
        list.addAll(byPos.values());
    }

    /** Cancel any deferred rail-state task for this cart (e.g. when cart hits another detector). Call from async ok. */
    private void cancelDeferredRailStateForCart(String cartUuid) {
        DeferredRailStateTask info = deferredRailStateTasks.remove(cartUuid);
        if (info != null) info.task.cancel();
    }

    /** Find minecart entity by UUID. Must be called from main thread. */
    private static Minecart findMinecartByUuid(org.bukkit.Server server, java.util.UUID uuid) {
        if (uuid == null) return null;
        for (World w : server.getWorlds()) {
            for (Minecart m : w.getEntitiesByClass(Minecart.class)) {
                if (uuid.equals(m.getUniqueId())) return m;
            }
        }
        return null;
    }

    /**
     * Start deferred rail-state task: apply each rail when cart is within DEFERRED_RAIL_STATE_RADIUS.
     * Call from main thread. If cart is non-null and valid it is reused each tick to avoid entity search.
     */
    private void startDeferredRailStateTask(String cartUuid, List<RailStateAction> pending, Minecart cart) {
        cancelDeferredRailStateForCart(cartUuid);
        if (pending == null || pending.isEmpty()) return;
        long startTime = System.currentTimeMillis();
        java.util.UUID uuid = java.util.UUID.fromString(cartUuid);
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tickDeferredRailState(cartUuid, uuid), 0L, DEFERRED_RAIL_STATE_INTERVAL_TICKS);
        deferredRailStateTasks.put(cartUuid, new DeferredRailStateTask(startTime, pending, task, cart));
    }

    /** One tick of deferred rail-state: check cart position, apply rails in range, timeout or remove when done. Runs on main thread. */
    private void tickDeferredRailState(String cartUuid, java.util.UUID uuid) {
        DeferredRailStateTask info = deferredRailStateTasks.get(cartUuid);
        if (info == null) return;
        if (System.currentTimeMillis() - info.startTime > DEFERRED_RAIL_STATE_TIMEOUT_MS) {
            info.task.cancel();
            deferredRailStateTasks.remove(cartUuid);
            return;
        }
        Minecart cart = info.cartRef;
        if (cart == null || !cart.isValid()) {
            if (info.worldName != null) {
                World w = plugin.getServer().getWorld(info.worldName);
                if (w != null) cart = findMinecartByUuid(w, uuid);
            }
            if (cart == null) cart = findMinecartByUuid(plugin.getServer(), uuid);
            if (cart != null) {
                info.cartRef = cart;
                if (cart.getWorld() != null) info.worldName = cart.getWorld().getName();
            }
        }
        if (cart == null || !cart.isValid()) {
            info.task.cancel();
            deferredRailStateTasks.remove(cartUuid);
            return;
        }
        org.bukkit.Location loc = cart.getLocation();
        double cx = loc.getX();
        double cy = loc.getY();
        double cz = loc.getZ();
        for (java.util.Iterator<RailStateAction> it = info.pending.iterator(); it.hasNext(); ) {
            RailStateAction r = it.next();
            double dx = cx - (r.x + 0.5);
            double dy = cy - (r.y + 0.5);
            double dz = cz - (r.z + 0.5);
            if (dx * dx + dy * dy + dz * dz <= DEFERRED_RAIL_STATE_RADIUS * DEFERRED_RAIL_STATE_RADIUS) {
                World w = plugin.getServer().getWorld(r.world);
                if (w != null) {
                    org.bukkit.block.Block block = w.getBlockAt(r.x, r.y, r.z);
                    if (block.getBlockData() instanceof org.bukkit.block.data.Rail railData) {
                        try {
                            org.bukkit.block.data.Rail.Shape desired = org.bukkit.block.data.Rail.Shape.valueOf(r.shapeName);
                            if (railData.getShape() != desired) {
                                railData.setShape(desired);
                                block.setBlockData(railData);
                            }
                        } catch (IllegalArgumentException ignored) { }
                    }
                }
                it.remove();
            }
        }
        if (info.pending.isEmpty()) {
            info.task.cancel();
            deferredRailStateTasks.remove(cartUuid);
        }
    }

    /**
     * Re-evaluate terminal RELEASE for the node where this cart is held (if any).
     * Call after changing a cart's destination so that if the cart is already at a terminal,
     * RELEASE is updated without needing the cart to pass the READY detector again.
     * Runs one tick later on the main thread so the destination write is committed and visible.
     */
    public void recheckTerminalReleaseForCart(String cartUuid) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Optional<Map<String, Object>> cartData = cartRepo.find(cartUuid);
            if (cartData.isEmpty()) return;
            String zone = (String) cartData.get().get("zone");
            if (zone == null || !zone.startsWith("node:")) return;
            String rest = zone.substring(5);
            int colon = rest.indexOf(':');
            String nodeId = colon > 0 ? rest.substring(0, colon) : rest;
            Optional<TransferNode> nodeOpt = nodeRepo.findById(nodeId);
            if (nodeOpt.isEmpty() || !nodeOpt.get().isTerminal()) return;

            List<BulbAction> bulbActions = new ArrayList<>();
            turnOffAllReleaseNode(bulbActions, nodeId);
            String pairedId = nodeOpt.get().getPairedNodeId();
            boolean releaseReversed = nodeOpt.get().isReleaseReversed();
            String terminalAddr = nodeOpt.flatMap(n -> stationRepo.findById(n.getStationId()).map(s -> n.terminalAddress(s.getAddress()))).orElse(null);
            List<String> stillInQueue = cartRepo.findHeldCartsAtNode(nodeId, "");
            String nextToRelease = firstCartToRelease(stillInQueue, releaseReversed);
            if (nextToRelease != null) {
                String nextDest = cartRepo.find(nextToRelease).map(data -> (String) data.get("destination_address")).orElse(null);
                if (nextDest != null && !nextDest.isEmpty() && !destIsForThisTerminal(nextDest, terminalAddr)
                    && (pairedId == null || routing.canDispatch(nextToRelease, nodeId, pairedId).canGo())) {
                    plugin.removeReadyHold(nextToRelease);
                    setNodeControllers(bulbActions, nodeId, "RELEASE", null, true);
                }
            }
            for (BulbAction a : bulbActions) {
                org.bukkit.World w = plugin.getServer().getWorld(a.world);
                if (w != null) CopperBulbHelper.setPowered(w, a.x, a.y, a.z, a.on);
            }
        }, 1L);
    }

    private static final class BulbAction {
        final String world;
        final int x, y, z;
        final boolean on;
        final long pulseMs;

        BulbAction(String world, int x, int y, int z, boolean on, long pulseMs) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.on = on;
            this.pulseMs = pulseMs;
        }
    }

    private static final class RailStateAction {
        final String world;
        final int x, y, z;
        final String shapeName;

        RailStateAction(String world, int x, int y, int z, String shapeName) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.shapeName = shapeName;
        }

        RailStateAction copy() {
            return new RailStateAction(world, x, y, z, shapeName);
        }
    }

    private static final class DeferredRailStateTask {
        final long startTime;
        final List<RailStateAction> pending;
        final BukkitTask task;
        /** Reused each tick to avoid entity search; set when found if initially null. */
        Minecart cartRef;
        /** World name when task was created (or when cart was last found) so we try that world first when re-finding. */
        String worldName;

        DeferredRailStateTask(long startTime, List<RailStateAction> pending, BukkitTask task, Minecart cartRef) {
            this.startTime = startTime;
            this.pending = pending;
            this.task = task;
            this.cartRef = cartRef;
            this.worldName = (cartRef != null && cartRef.isValid() && cartRef.getWorld() != null) ? cartRef.getWorld().getName() : null;
        }
    }

    private static final class CruiseSpeedAction {
        final String cartUuid;
        final double magnitude;

        CruiseSpeedAction(String cartUuid, double magnitude) {
            this.cartUuid = cartUuid;
            this.magnitude = magnitude;
        }
    }
}
