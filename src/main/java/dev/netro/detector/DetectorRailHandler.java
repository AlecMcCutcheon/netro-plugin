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
import org.bukkit.World;
import org.bukkit.entity.Minecart;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
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

    /** Deferred rail state: apply when cart is within radius of each rail. key: cartUuid. */
    private static final double DEFERRED_RAIL_STATE_RADIUS = 2.0;
    private static final long DEFERRED_RAIL_STATE_TIMEOUT_MS = 30_000;
    private static final long DEFERRED_RAIL_STATE_INTERVAL_TICKS = 2;
    private final Map<String, DeferredRailStateTask> deferredRailStateTasks = new ConcurrentHashMap<>();

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
     * If detectors exist at this rail, handles ENTRY/READY/CLEAR and controller activation; returns true.
     * Returns false if no detectors at this rail.
     */
    public boolean onCartOnDetectorRail(World world, int railX, int railY, int railZ, Minecart cart) {
        String worldName = world.getName();
        List<Detector> detectors = detectorRepo.findByRail(worldName, railX, railY, railZ);
        if (detectors.isEmpty()) return false;

        String cartUuid = cart.getUniqueId().toString();
        org.bukkit.block.Block railBlock = world.getBlockAt(railX, railY, railZ);
        org.bukkit.block.BlockFace cartCardinal = DirectionHelper.velocityAndRailToCardinal(cart.getVelocity(), railBlock);
        boolean debug = plugin.isDebugEnabled();

        final double speedForDebug = cart.getVelocity().length();
        final java.util.Optional<String> headingTowardStationId = resolveHeadingTowardStation(detectors, cartCardinal);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<BulbAction> bulbActions = new ArrayList<>();
            List<RailStateAction> railStateActions = new ArrayList<>();
            List<RailStateAction> deferredRailStateActions = new ArrayList<>();
            List<CruiseSpeedAction> cruiseSpeedActions = new ArrayList<>();

            cancelDeferredRailStateForCart(cartUuid);

            unifiedCartSeenFlow.run(cartUuid, world, cart, detectors, railX, railY, railZ, headingTowardStationId);

            String currentStationId = detectors.stream()
                .filter(d -> d.getNodeId() != null)
                .findFirst()
                .flatMap(d -> nodeRepo.findById(d.getNodeId()).map(TransferNode::getStationId))
                .orElse(null);
            if (currentStationId != null) {
                Optional<String> unreachable = routing.handleUnreachableAndRedirectToNearestTerminal(cartUuid, currentStationId);
                if (unreachable.isPresent()) {
                    final String oldDest = unreachable.get();
                    final World worldRef = world;
                    plugin.getServer().getScheduler().runTask(plugin, () -> notifyUnreachableRedirect(worldRef, cartUuid, oldDest));
                }
            }

            final String[] readyCenterNodeIdRef = new String[1];
            for (Detector d : detectors) {
                String dir = DirectionHelper.cardinalToDirectionLabel(d.getSignFacing(), cartCardinal);
                if (debug) {
                    String targetLabel = detectorTargetLabel(d);
                    StringBuilder sb = new StringBuilder("[Netro detector] rail ").append(railX).append(",").append(railY).append(",").append(railZ)
                        .append(" ").append(targetLabel).append(" dir=").append(dir)
                        .append(" speed=").append(String.format("%.3f", speedForDebug))
                        .append(" rule1=").append(d.getRule1Role()).append(d.getRule1Direction() != null ? ":" + d.getRule1Direction() : "");
                    if (d.getRule2Role() != null) sb.append(" rule2=").append(d.getRule2Role()).append(d.getRule2Direction() != null ? ":" + d.getRule2Direction() : "");
                    if (d.getRule3Role() != null) sb.append(" rule3=").append(d.getRule3Role()).append(d.getRule3Direction() != null ? ":" + d.getRule3Direction() : "");
                    if (d.getRule4Role() != null) sb.append(" rule4=").append(d.getRule4Role()).append(d.getRule4Direction() != null ? ":" + d.getRule4Direction() : "");
                    plugin.getLogger().info(sb.toString());
                }

                boolean m1 = ruleMatches(d.getRule1Role(), d.getRule1Direction(), dir);
                logRuleMatch(debug, d, 1, d.getRule1Role(), d.getRule1Direction(), dir, m1);
                if (m1) processRule(d, d.getRule1Role(), d.getRule1Direction(), dir, cartUuid, bulbActions, railStateActions, deferredRailStateActions, cruiseSpeedActions, debug, worldName, railX, railY, railZ, readyCenterNodeIdRef);
                if (d.getRule2Role() != null) {
                    boolean m2 = ruleMatches(d.getRule2Role(), d.getRule2Direction(), dir);
                    logRuleMatch(debug, d, 2, d.getRule2Role(), d.getRule2Direction(), dir, m2);
                    if (m2) processRule(d, d.getRule2Role(), d.getRule2Direction(), dir, cartUuid, bulbActions, railStateActions, deferredRailStateActions, cruiseSpeedActions, debug, worldName, railX, railY, railZ, readyCenterNodeIdRef);
                }
                if (d.getRule3Role() != null) {
                    boolean m3 = ruleMatches(d.getRule3Role(), d.getRule3Direction(), dir);
                    logRuleMatch(debug, d, 3, d.getRule3Role(), d.getRule3Direction(), dir, m3);
                    if (m3) processRule(d, d.getRule3Role(), d.getRule3Direction(), dir, cartUuid, bulbActions, railStateActions, deferredRailStateActions, cruiseSpeedActions, debug, worldName, railX, railY, railZ, readyCenterNodeIdRef);
                }
                if (d.getRule4Role() != null) {
                    boolean m4 = ruleMatches(d.getRule4Role(), d.getRule4Direction(), dir);
                    logRuleMatch(debug, d, 4, d.getRule4Role(), d.getRule4Direction(), dir, m4);
                    if (m4) processRule(d, d.getRule4Role(), d.getRule4Direction(), dir, cartUuid, bulbActions, railStateActions, deferredRailStateActions, cruiseSpeedActions, debug, worldName, railX, railY, railZ, readyCenterNodeIdRef);
                }
                bulbActions.add(new BulbAction(d.getWorld(), d.getX(), d.getY(), d.getZ(), true, DETECTOR_PULSE_MS));
            }

            final List<RailStateAction> deferredCopy = new ArrayList<>();
            if (!deferredRailStateActions.isEmpty()) {
                for (RailStateAction r : deferredRailStateActions) deferredCopy.add(r.copy());
            }

            dedupeBulbActions(bulbActions);

            final String cartUuidFinal = cartUuid;
            final String readyNodeId = readyCenterNodeIdRef[0];
            final List<CruiseSpeedAction> cruiseSpeedActionsFinal = cruiseSpeedActions;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                World w = plugin.getServer().getWorld(worldName);
                java.util.UUID uuid;
                try {
                    uuid = java.util.UUID.fromString(cartUuidFinal);
                } catch (IllegalArgumentException e) {
                    uuid = null;
                }
                Minecart resolvedCart = (w != null && uuid != null) ? findMinecartByUuid(w, uuid) : null;

                for (CruiseSpeedAction a : cruiseSpeedActionsFinal) {
                    plugin.getCartControllerGuiListener().applyRuleCruiseSpeed(a.cartUuid, a.magnitude);
                }
                for (BulbAction a : bulbActions) {
                    CopperBulbHelper.setPowered(plugin.getServer().getWorld(a.world), a.x, a.y, a.z, a.on);
                    if (a.pulseMs > 0) {
                        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                            CopperBulbHelper.setPowered(plugin.getServer().getWorld(a.world), a.x, a.y, a.z, false), a.pulseMs / 50);
                    }
                }
                for (RailStateAction r : railStateActions) {
                    org.bukkit.World ww = plugin.getServer().getWorld(r.world);
                    if (ww == null) continue;
                    org.bukkit.block.Block block = ww.getBlockAt(r.x, r.y, r.z);
                    if (block.getBlockData() instanceof org.bukkit.block.data.Rail railData) {
                        try {
                            railData.setShape(org.bukkit.block.data.Rail.Shape.valueOf(r.shapeName));
                            block.setBlockData(railData);
                        } catch (IllegalArgumentException ignored) { }
                    }
                }

                if (readyNodeId != null && resolvedCart != null && resolvedCart.isValid()) {
                    runTerminalReadyCartCenterWithCart(resolvedCart, readyNodeId, cartUuidFinal, worldName, railX, railY, railZ);
                }
                if (!deferredCopy.isEmpty()) {
                    startDeferredRailStateTask(cartUuidFinal, deferredCopy, resolvedCart);
                }
            });
        });
        return true;
    }

    /** Human-readable target for detector debug: "node=Station:Node (transfer|terminal)". */
    private String detectorTargetLabel(Detector d) {
        if (d.getNodeId() != null) {
            Optional<TransferNode> node = nodeRepo.findById(d.getNodeId());
            if (node.isPresent()) {
                String stName = stationRepo.findById(node.get().getStationId()).map(Station::getName).orElse("?");
                String kind = node.get().isTerminal() ? "terminal" : "transfer";
                return "node=" + stName + ":" + node.get().getName() + " (" + kind + ")";
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
        final BukkitTask[] taskRef = new BukkitTask[1];
        taskRef[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!cartRef.isValid()) {
                if (taskRef[0] != null) taskRef[0].cancel();
                return;
            }
            applyReadyHoldVelocityCorrection(cartRef, cx, cy, cz);
        }, 1, 1);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (taskRef[0] != null) taskRef[0].cancel();
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
        if (!cart.getPassengers().isEmpty() && cart.getPassengers().get(0) instanceof org.bukkit.entity.Player player) {
            player.sendTitle("No route to destination", destDisplay, 10, 70, 20);
        } else {
            plugin.getServer().broadcastMessage("[Netro] Cart has no route to " + destDisplay + "; redirected to nearest terminal.");
        }
    }

    /** Distance within which the cart is considered at center; then we set velocity to zero. */
    private static final double READY_CENTER_TOLERANCE = 0.15;
    /** Max speed when correcting toward center (velocity-based, works with player in cart). */
    private static final double READY_CORRECTION_SPEED = 0.25;

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
        double speed = Math.min(dist * 0.5, READY_CORRECTION_SPEED);
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
        for (Detector d : detectors) {
            if (d.getNodeId() == null) continue;
            Optional<TransferNode> nodeOpt = nodeRepo.findById(d.getNodeId());
            if (nodeOpt.isEmpty() || nodeOpt.get().isTerminal()) continue;
            TransferNode node = nodeOpt.get();
            if (node.getPairedNodeId() == null) continue;
            String dir = DirectionHelper.cardinalToDirectionLabel(d.getSignFacing(), cartCardinal);
            boolean clearFires = ("CLEAR".equals(d.getRule1Role()) && ruleMatches(d.getRule1Role(), d.getRule1Direction(), dir))
                || (d.getRule2Role() != null && "CLEAR".equals(d.getRule2Role()) && ruleMatches(d.getRule2Role(), d.getRule2Direction(), dir))
                || (d.getRule3Role() != null && "CLEAR".equals(d.getRule3Role()) && ruleMatches(d.getRule3Role(), d.getRule3Direction(), dir))
                || (d.getRule4Role() != null && "CLEAR".equals(d.getRule4Role()) && ruleMatches(d.getRule4Role(), d.getRule4Direction(), dir));
            if (clearFires) {
                Optional<TransferNode> paired = nodeRepo.findById(node.getPairedNodeId());
                if (paired.isPresent()) return Optional.of(paired.get().getStationId());
            }
        }
        return Optional.empty();
    }

    private void processRule(Detector d, String role, String direction, String cartDir, String cartUuid, List<BulbAction> bulbActions, List<RailStateAction> railStateActions, List<RailStateAction> deferredRailStateActions, List<CruiseSpeedAction> cruiseSpeedActions, boolean debug,
            String railWorldName, int railX, int railY, int railZ, String[] readyCenterNodeIdRef) {
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

        if ("ENTRY".equals(role) || "READY".equals(role) || "CLEAR".equals(role)) {
            String substitutedDest = applyBlockedRulesIfNeeded(d, cartUuid, cartDir);
            if ("ENTRY".equals(role) || "CLEAR".equals(role)) {
                if (debug) plugin.getLogger().info("[Netro detector] evaluating rules node=" + nodeId + " role=" + role);
                evaluateRuleEngine(d, role, cartUuid, bulbActions, railStateActions, deferredRailStateActions, cruiseSpeedActions, railWorldName, cartDir, substitutedDest);
            }
        }

        // ENTRY at a node: no segment occupancy (no collision detection).
        if ("ENTRY".equals(role) && nodeId != null) {
            if (debug) plugin.getLogger().info("[Netro detector] ENTRY node=" + nodeId + " dir=" + cartDir);
            return;
        }

        if ("READY".equals(role) && nodeId != null) {
            boolean isTerminal = nodeRepo.findById(nodeId).map(TransferNode::isTerminal).orElse(false);
            if (!isTerminal) return;
            String zone = "node:" + nodeId;
            // Only one cart held per terminal: clear any other cart previously held at this node
            for (String uuid : cartRepo.findHeldCartsAtNode(nodeId, "")) {
                if (!uuid.equals(cartUuid)) cartRepo.clearHeld(uuid);
            }
            heldCountRepo.setHeldCount(nodeId, 1);
            cartRepo.setHeld(cartUuid, zone, 0);
            if (plugin.getChunkLoadService() != null) {
                plugin.getChunkLoadService().ensureCartTaskRunning();
            }
            cancelTerminalTimeout(nodeId);
            cancelTerminalPolling(nodeId);
            cancelPostReleaseTimeout(nodeId);
            BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                terminalTimeoutTasks.remove(nodeId);
                doubleCheckCartGoneAndClearOrResumePolling(nodeId);
            }, TERMINAL_HELD_TIMEOUT_TICKS);
            terminalTimeoutTasks.put(nodeId, task);
            Optional<TransferNode> nodeOpt = nodeRepo.findById(nodeId);
            TransferNode node = nodeOpt.orElse(null);
            String pairedId = node != null ? node.getPairedNodeId() : null;
            String terminalAddr = node != null ? node.terminalAddress(
                stationRepo.findById(node.getStationId()).map(Station::getAddress).orElse(null)) : null;
            Optional<String> cartDestOpt = cartRepo.find(cartUuid).map(cartData -> (String) cartData.get("destination_address"));
            String cartDest = cartDestOpt.orElse(null);
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
                schedulePostReleaseTimeout(nodeId);
            }
            if (debug) plugin.getLogger().info("[Netro detector] READY terminal node=" + nodeId + " held=1 RELEASE=" + releaseOn);
            readyCenterNodeIdRef[0] = nodeId;
            return;
        }

        if ("CLEAR".equals(role) && nodeId != null) {
            Optional<TransferNode> nodeOpt = nodeRepo.findById(nodeId);
            boolean isTerminal = nodeOpt.map(TransferNode::isTerminal).orElse(false);
            if (isTerminal) {
                cancelTerminalTimeout(nodeId);
                cancelTerminalPolling(nodeId);
                cancelPostReleaseTimeout(nodeId);
                plugin.getServer().getScheduler().runTask(plugin, () -> doubleCheckCartGoneAndClearOrResumePolling(nodeId));
            }
            clearPlusControllersNode(bulbActions, nodeId);
            turnOffAllReleaseNode(bulbActions, nodeId);
            if (debug) plugin.getLogger().info("[Netro detector] CLEAR node=" + nodeId + " (segment register" + (isTerminal ? ", terminal slot" : "") + ")");
        }
    }

    /**
     * If the cart's next hop is blocked (e.g. terminal full), returns a substituted destination to pass to normal rules.
     * Does not change the cart's stored destination; the substitute is used only for this rule evaluation so the cart is "temporarily rerouted".
     * If a BLOCKED rule exists for this hop, returns its action_data; else applies default policy: available terminal at current station,
     * else at another station, else another transfer node at current station that isn't occupied. Returns null when not blocked or no substitute.
     */
    private String applyBlockedRulesIfNeeded(Detector d, String cartUuid, String cartDir) {
        String nodeId = d.getNodeId();
        if (nodeId == null) return null;
        String cartDest = cartRepo.find(cartUuid).map(c -> (String) c.get("destination_address")).orElse(null);
        if (cartDest == null || cartDest.isEmpty()) return null;

        Optional<TransferNode> nodeOpt = nodeRepo.findById(nodeId);
        String fromStationId = nodeOpt.map(TransferNode::getStationId).orElse(null);
        if (fromStationId == null) return null;
        Optional<String> nextHopNodeIdOpt = routing.resolveNextHopNode(fromStationId, cartDest);
        if (nextHopNodeIdOpt.isEmpty()) return null;
        String nextHopNodeId = nextHopNodeIdOpt.get();
        String fromNodeId = nodeId;
        if (routing.canDispatch(cartUuid, fromNodeId, nextHopNodeId).canGo()) return null;

        String contextType = nodeRepo.findById(nodeId).map(TransferNode::isTerminal).orElse(false) ? Rule.CONTEXT_TERMINAL : Rule.CONTEXT_TRANSFER;
        String contextId = nodeId;
        String contextSide = null;
        String destForRules = nodeIdToDestinationId(nextHopNodeId).orElse(cartDest);

        List<Rule> rules = ruleRepo.findByContext(contextType, contextId, contextSide);
        for (Rule rule : rules) {
            if (!Rule.TRIGGER_BLOCKED.equals(rule.getTriggerType())) continue;
            if (!Rule.ACTION_SET_DESTINATION.equals(rule.getActionType()) || rule.getActionData() == null || rule.getActionData().isEmpty()) continue;
            if (!destinationMatchesForRule(destForRules, rule.getDestinationId())) continue;
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[Netro rule] BLOCKED hop=" + formatDestForLog(destForRules) + " → substitute to " + formatDestForLog(rule.getActionData()));
            }
            return rule.getActionData();
        }

        String defaultSubstitute = defaultBlockedSubstitute(fromStationId, fromNodeId, nextHopNodeId, cartUuid);
        if (defaultSubstitute != null && plugin.isDebugEnabled()) {
            plugin.getLogger().info("[Netro rule] BLOCKED hop=" + formatDestForLog(destForRules) + " (no rule) → default substitute to " + formatDestForLog(defaultSubstitute));
        }
        return defaultSubstitute;
    }

    /** Default policy when hop is blocked and no BLOCKED rule: available terminal at current station, else at another station, else another unoccupied transfer node. */
    private String defaultBlockedSubstitute(String currentStationId, String fromNodeId, String blockedNextHopNodeId, String cartUuid) {
        Optional<TransferNode> termAtCurrent = nodeRepo.findAvailableTerminal(currentStationId);
        if (termAtCurrent.isPresent()) {
            return nodeIdToDestinationId(termAtCurrent.get().getId()).orElse(null);
        }
        Optional<Station> otherStation = stationRepo.findAll().stream()
            .filter(s -> !s.getId().equals(currentStationId))
            .filter(s -> nodeRepo.findAvailableTerminal(s.getId()).isPresent())
            .findFirst();
        if (otherStation.isPresent()) {
            Optional<TransferNode> term = nodeRepo.findAvailableTerminal(otherStation.get().getId());
            if (term.isPresent()) {
                return nodeIdToDestinationId(term.get().getId()).orElse(null);
            }
        }
        return nodeRepo.findByStation(currentStationId).stream()
            .filter(n -> !n.getId().equals(blockedNextHopNodeId))
            .filter(n -> routing.canDispatch(cartUuid, fromNodeId, n.getId()).canGo())
            .findFirst()
            .flatMap(n -> nodeIdToDestinationId(n.getId()))
            .orElse(null);
    }

    /**
     * Evaluate rule-based actions for this context: load rules for the detector's context (node),
     * match trigger (ENTERING for ENTRY, CLEARING for CLEAR) and destination condition, then apply
     * SEND_ON/SEND_OFF to controllers or add SET_RAIL_STATE targets to deferred list (applied when cart is near each rail).
     */
    private void evaluateRuleEngine(Detector d, String role, String cartUuid, List<BulbAction> bulbActions, List<RailStateAction> railStateActions, List<RailStateAction> deferredRailStateActions, List<CruiseSpeedAction> cruiseSpeedActions, String railWorldName, String cartDir, String substitutedDestForRules) {
        String nodeId = d.getNodeId();
        if (nodeId == null) return;
        Optional<TransferNode> nodeOpt = nodeRepo.findById(nodeId);
        String contextType = nodeOpt.map(TransferNode::isTerminal).orElse(false) ? Rule.CONTEXT_TERMINAL : Rule.CONTEXT_TRANSFER;
        String contextId = nodeId;
        String contextSide = null;
        String fromStationId = nodeOpt.map(TransferNode::getStationId).orElse(null);
        String roleTriggerType = "CLEAR".equals(role) ? Rule.TRIGGER_CLEARING : Rule.TRIGGER_ENTERING;
        String destForRules;
        if (substitutedDestForRules != null && !substitutedDestForRules.isEmpty()) {
            destForRules = substitutedDestForRules;
        } else {
            String cartDest = cartRepo.find(cartUuid)
                .map(c -> (String) c.get("destination_address"))
                .orElse(null);
            destForRules = cartDest;
            if (cartDest != null && !cartDest.isEmpty() && fromStationId != null) {
                Optional<String> nextHopNodeId = routing.resolveNextHopNode(fromStationId, cartDest);
                destForRules = nextHopNodeId.flatMap(this::nodeIdToDestinationId).orElse(cartDest);
            }
        }
        String cartDestForLog = cartRepo.find(cartUuid).map(c -> (String) c.get("destination_address")).orElse(null);
        List<Rule> rules = ruleRepo.findByContext(contextType, contextId, contextSide);
        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info("[Netro rule] context=" + contextType + ":" + contextId + " trigger=" + roleTriggerType
                + " cartDest=" + formatDestForLog(cartDestForLog) + " localDest=" + formatDestForLog(destForRules)
                + " rules=" + rules.size());
        }
        for (Rule rule : rules) {
            String triggerType = rule.getTriggerType();
            boolean forRole = roleTriggerType.equals(triggerType);
            boolean forDetected = Rule.TRIGGER_DETECTED.equals(triggerType);
            if (!forRole && !forDetected) continue;
            boolean cartMatchesDest = destinationMatchesForRule(destForRules, rule.getDestinationId());
            boolean fires = rule.isDestinationPositive() ? cartMatchesDest : !cartMatchesDest;
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[Netro rule] rule#" + rule.getRuleIndex() + " destId=" + formatDestForLog(rule.getDestinationId() != null ? rule.getDestinationId() : "any")
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

    /** Format destination for logs/GUI: show resolved name (e.g. Snowy1:Main) instead of raw address. Handles null, empty, and "any". */
    private String formatDestForLog(String dest) {
        if (dest == null || dest.isEmpty()) return "null";
        if ("any".equals(dest)) return "any";
        return RulesMainHolder.formatDestinationId(dest, stationRepo, nodeRepo);
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

    private static boolean isCartOnDetectorBlock(Minecart cart, String worldName, int railX, int railY, int railZ) {
        if (cart == null || !cart.isValid() || !cart.getWorld().getName().equals(worldName)) return false;
        org.bukkit.block.Block at = cart.getLocation().getBlock();
        int bx = at.getX(), by = at.getY(), bz = at.getZ();
        return (bx == railX && (by == railY || by == railY + 1) && bz == railZ);
    }

    private static final long TERMINAL_POLL_INTERVAL_TICKS = 20; // 1 second

    /**
     * Start polling every 1s: is cart still on READY rail? dest set? → RELEASE on, stop polling, start 5s post-RELEASE timeout.
     * Call on main thread after the 1s velocity correction ends. If initialCart is non-null it is reused to avoid entity search each tick.
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
            if (!isCartOnDetectorBlock(cart, wName, rx, ry, rz)) return; // cart left or not on rail; 5s timeout or CLEAR will double-check
            Optional<TransferNode> nodeOpt = nodeRepo.findById(nodeId);
            if (nodeOpt.isEmpty() || !nodeOpt.get().isTerminal()) return;
            TransferNode node = nodeOpt.get();
            String terminalAddr = node.terminalAddress(stationRepo.findById(node.getStationId()).map(Station::getAddress).orElse(null));
            String cartDest = cartRepo.find(cartUuid).map(c -> (String) c.get("destination_address")).orElse(null);
            if (cartDest == null || cartDest.isEmpty() || destIsForThisTerminal(cartDest, terminalAddr)) return;
            String pairedId = node.getPairedNodeId();
            if (pairedId != null && !routing.canDispatch(cartUuid, nodeId, pairedId).canGo()) return;
            // Dest set and outbound → RELEASE on, stop polling, start 5s post-RELEASE
            cancelTerminalPolling(nodeId);
            cancelTerminalTimeout(nodeId);
            List<BulbAction> bulbActions = new ArrayList<>();
            turnOffAllReleaseNode(bulbActions, nodeId);
            setNodeControllers(bulbActions, nodeId, "RELEASE", null, true);
            for (BulbAction a : bulbActions) {
                World ww = plugin.getServer().getWorld(a.world);
                if (ww != null) CopperBulbHelper.setPowered(ww, a.x, a.y, a.z, a.on);
            }
            schedulePostReleaseTimeout(nodeId);
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
            cart = findMinecartByUuid(plugin.getServer(), uuid);
            if (cart != null) info.cartRef = cart;
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

        DeferredRailStateTask(long startTime, List<RailStateAction> pending, BukkitTask task, Minecart cartRef) {
            this.startTime = startTime;
            this.pending = pending;
            this.task = task;
            this.cartRef = cartRef;
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
