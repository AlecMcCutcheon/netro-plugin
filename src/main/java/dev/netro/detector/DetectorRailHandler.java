package dev.netro.detector;

import dev.netro.NetroPlugin;
import dev.netro.database.CartHeldCountRepository;
import dev.netro.database.CartRepository;
import dev.netro.database.ControllerRepository;
import dev.netro.database.DetectorRepository;
import dev.netro.database.JunctionHeldCountRepository;
import dev.netro.database.StationControllerRepository;
import dev.netro.database.StationDetectorRepository;
import dev.netro.database.JunctionRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Controller;
import dev.netro.model.Detector;
import dev.netro.model.Station;
import dev.netro.model.StationController;
import dev.netro.model.StationDetector;
import dev.netro.model.Junction;
import dev.netro.model.TransferNode;
import dev.netro.routing.RoutingEngine;
import dev.netro.util.DestinationResolver;
import org.bukkit.World;
import org.bukkit.entity.Minecart;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When a cart is on a detector rail: resolve direction, fire ENTRY/READY/CLEAR,
 * update held count, and set DIVERT/RELEASE controller bulbs.
 */
public class DetectorRailHandler {

    private static final long DEBOUNCE_MS_ENTRY_READY = 1_500;
    private static final long DEBOUNCE_MS_CLEAR = 600;
    private static final long DETECTOR_PULSE_MS = 1_000;

    private final NetroPlugin plugin;
    private final DetectorRepository detectorRepo;
    private final ControllerRepository controllerRepo;
    private final StationDetectorRepository stationDetectorRepo;
    private final StationControllerRepository stationControllerRepo;
    private final CartHeldCountRepository heldCountRepo;
    private final JunctionHeldCountRepository junctionHeldCountRepo;
    private final CartRepository cartRepo;
    private final TransferNodeRepository nodeRepo;
    private final JunctionRepository junctionRepo;
    private final StationRepository stationRepo;
    private final RoutingEngine routing;
    private final UnifiedCartSeenFlow unifiedCartSeenFlow;

    /** key: cartUuid|detectorId|role -> last fired time */
    private final Map<String, Long> lastFired = new ConcurrentHashMap<>();

    public DetectorRailHandler(NetroPlugin plugin) {
        this.plugin = plugin;
        var db = plugin.getDatabase();
        this.detectorRepo = new DetectorRepository(db);
        this.controllerRepo = new ControllerRepository(db);
        this.stationDetectorRepo = new StationDetectorRepository(db);
        this.stationControllerRepo = new StationControllerRepository(db);
        this.heldCountRepo = new CartHeldCountRepository(db);
        this.junctionHeldCountRepo = new JunctionHeldCountRepository(db);
        this.nodeRepo = new TransferNodeRepository(db);
        this.junctionRepo = new JunctionRepository(db);
        this.cartRepo = new CartRepository(db);
        this.stationRepo = new StationRepository(db);
        this.routing = plugin.getRoutingEngine();
        this.unifiedCartSeenFlow = new UnifiedCartSeenFlow(plugin, cartRepo, nodeRepo, junctionRepo, routing);
    }

    /**
     * Call when a cart has moved; (railX, railY, railZ) is the rail block the cart is on (or below).
     * If detectors exist at this rail, handles ENTRY/READY/CLEAR and controller activation; returns true.
     * Returns false if no detectors at this rail.
     */
    public boolean onCartOnDetectorRail(World world, int railX, int railY, int railZ, Minecart cart) {
        String worldName = world.getName();
        List<Detector> detectors = detectorRepo.findByRail(worldName, railX, railY, railZ);
        List<StationDetector> stationDetectors = stationDetectorRepo.findByRail(worldName, railX, railY, railZ);
        if (detectors.isEmpty() && stationDetectors.isEmpty()) return false;

        String cartUuid = cart.getUniqueId().toString();
        org.bukkit.block.Block railBlock = world.getBlockAt(railX, railY, railZ);
        org.bukkit.block.BlockFace cartCardinal = DirectionHelper.velocityAndRailToCardinal(cart.getVelocity(), railBlock);
        boolean debug = plugin.isDetectorDebugEnabled();

        double speedAtDetector = cart.getVelocity().length();
        boolean velocityControlled = applyStationVelocityClamps(cart, stationDetectors, cartCardinal)
            || applyNodeJunctionVelocityClamps(cart, detectors, cartCardinal);
        if (velocityControlled) plugin.notifyCartVelocityControlledByDetector(cartUuid, cart.getVelocity().length());
        final double speedForDebug = speedAtDetector;

        // Main thread: collect all minecart UUIDs that exist in the world (all worlds). Passed to async to trim ghosts from segment_occupancy before collision checks.
        final Set<String> existingCartUuids = new HashSet<>();
        for (World w : plugin.getServer().getWorlds()) {
            for (org.bukkit.entity.Entity e : w.getEntities()) {
                if (e instanceof Minecart) existingCartUuids.add(e.getUniqueId().toString());
            }
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<BulbAction> bulbActions = new ArrayList<>();

            UnifiedCartSeenFlow.Result flowResult = unifiedCartSeenFlow.run(cartUuid, world, cart, stationDetectors, detectors, existingCartUuids);
            boolean ranNoDestAtStart = flowResult.ranNoDestAtStart();

            for (StationDetector sd : stationDetectors) {
                String dir = DirectionHelper.cardinalToDirectionLabel(sd.getSignFacing(), cartCardinal);
                boolean setDestMatches = ("SET_DEST".equals(sd.getRule1Role()) && ruleMatches("SET_DEST", sd.getRule1Direction(), dir))
                    || (sd.getRule2Role() != null && "SET_DEST".equals(sd.getRule2Role()) && ruleMatches("SET_DEST", sd.getRule2Direction(), dir))
                    || (sd.getRule3Role() != null && "SET_DEST".equals(sd.getRule3Role()) && ruleMatches("SET_DEST", sd.getRule3Direction(), dir))
                    || (sd.getRule4Role() != null && "SET_DEST".equals(sd.getRule4Role()) && ruleMatches("SET_DEST", sd.getRule4Direction(), dir));
                if (setDestMatches && sd.getSetDestValue() != null && !sd.getSetDestValue().isEmpty()) {
                    DestinationResolver.resolveToAddress(stationRepo, nodeRepo, sd.getSetDestValue())
                        .ifPresent(addr -> {
                            cartRepo.setDestination(cartUuid, addr, sd.getStationId());
                            recheckTerminalReleaseForCart(cartUuid);
                        });
                }
                boolean anyRouteMatch = false;
                if ("ROUTE".equals(sd.getRule1Role()) && ruleMatches("ROUTE", sd.getRule1Direction(), dir)) anyRouteMatch = true;
                if (sd.getRule2Role() != null && "ROUTE".equals(sd.getRule2Role()) && ruleMatches("ROUTE", sd.getRule2Direction(), dir)) anyRouteMatch = true;
                if (sd.getRule3Role() != null && "ROUTE".equals(sd.getRule3Role()) && ruleMatches("ROUTE", sd.getRule3Direction(), dir)) anyRouteMatch = true;
                if (sd.getRule4Role() != null && "ROUTE".equals(sd.getRule4Role()) && ruleMatches("ROUTE", sd.getRule4Direction(), dir)) anyRouteMatch = true;
                String stationName = stationRepo.findById(sd.getStationId()).map(Station::getName).orElse(sd.getStationId());
                if (debug) {
                    plugin.getLogger().info("[Netro detector] rail " + railX + "," + railY + "," + railZ
                        + " station=" + stationName + " dir=" + dir
                        + " speed=" + String.format("%.3f", speedForDebug)
                        + " ROUTE_match=" + anyRouteMatch);
                }
                if (!anyRouteMatch) continue;
                Optional<String> nextHop = routing.getNextHopNodeAtStation(cartUuid, sd.getStationId());
                Optional<Map<String, Object>> cartDataForReason = cartRepo.find(cartUuid);
                boolean noDestination = cartDataForReason.isPresent()
                    && (cartDataForReason.get().get("destination_address") == null || "".equals(cartDataForReason.get().get("destination_address")));
                boolean cartNotInDb = cartDataForReason.isEmpty();
                // Fallback: run no-dest rule here only when the unified flow did not (e.g. no station context at this rail). See UnifiedCartSeenFlow.
                if (nextHop.isEmpty() && (noDestination || cartNotInDb) && !ranNoDestAtStart) {
                    String worldNameForDespawn = world.getName();
                    java.util.UUID cartUuidObj = cart.getUniqueId();
                    nextHop = routing.applyNoDestinationRule(cartUuid, sd.getStationId(), () ->
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            org.bukkit.World w = plugin.getServer().getWorld(worldNameForDespawn);
                            if (w != null) {
                                for (org.bukkit.entity.Entity e : w.getEntities()) {
                                    if (e.getUniqueId().equals(cartUuidObj)) {
                                        e.remove();
                                        break;
                                    }
                                }
                            }
                        }));
                }
                if (debug) {
                    String dest = cartDataForReason.map(d -> (String) d.get("destination_address")).orElse(null);
                    if (nextHop.isEmpty()) {
                        String reason = cartNotInDb ? "cart not in DB (no terminal to assign)"
                            : noDestination ? "cart had no destination, no terminals (cart removed)"
                                : "no route to destination";
                        List<StationController> controllers = stationControllerRepo.findByStation(sd.getStationId());
                        plugin.getLogger().info("[Netro detector] ROUTE station=" + stationName + " cart=" + cartUuid + " dest=" + dest + " nextHop=empty (" + reason + ") → NOT_TRANSFER on " + controllers.size() + " controller(s)");
                    } else {
                        String nextHopLabel = stationControllerTargetLabel(nextHop.get());
                        List<StationController> controllers = stationControllerRepo.findByStation(sd.getStationId());
                        plugin.getLogger().info("[Netro detector] ROUTE station=" + stationName + " cart=" + cartUuid + " dest=" + dest + " dir=" + dir + " nextHop=" + nextHopLabel + " → applying signals to " + controllers.size() + " controller(s)");
                    }
                }
                List<StationController> controllers = stationControllerRepo.findByStation(sd.getStationId());
                String oppositeDir = oppositeDirection(dir);
                // ROU:L clears TRA:R only on controllers for the chosen node; ROU:R clears TRA:L only on controllers for the chosen node. Add OFF first so dedupe keeps current-dir state.
                if (oppositeDir != null && nextHop.isPresent()) {
                    String chosenNodeId = nextHop.get();
                    for (StationController c : controllers) {
                        if (!c.getTargetNodeId().equals(chosenNodeId)) continue;
                        boolean hasOpposite = stationControllerRuleMatches(c, oppositeDir, "TRANSFER", "TRANSFER+")
                            || stationControllerRuleMatches(c, oppositeDir, "NOT_TRANSFER", "NOT_TRANSFER+");
                        if (hasOpposite) bulbActions.add(new BulbAction(c.getWorld(), c.getX(), c.getY(), c.getZ(), false, 0));
                    }
                }
                if (nextHop.isPresent()) {
                    String nextHopId = nextHop.get(); // When dispatch is blocked, routing returns terminal so TRA/NOT_TRA still fire (TRA=chosen, NOT_TRA=others).
                    for (StationController c : controllers) {
                        boolean hasTransferMatch = stationControllerRuleMatches(c, dir, "TRANSFER", "TRANSFER+");
                        boolean hasNotTransferMatch = stationControllerRuleMatches(c, dir, "NOT_TRANSFER", "NOT_TRANSFER+");
                        // TRA on for the chosen next-hop (when rule matches dir); NOT_TRA on for all other controllers (when rule matches dir). Applied even when blocked.
                        boolean isChosen = c.getTargetNodeId().equals(nextHopId);
                        boolean on = (isChosen && hasTransferMatch) || (!isChosen && hasNotTransferMatch);
                        bulbActions.add(new BulbAction(c.getWorld(), c.getX(), c.getY(), c.getZ(), on, 0));
                        if (debug) {
                            String targetLabel = stationControllerTargetLabel(c.getTargetNodeId());
                            plugin.getLogger().info("[Netro detector] ROUTE controller target=" + targetLabel + " dir=" + dir + " isChosen=" + isChosen + " hasTRA=" + hasTransferMatch + " hasNOT_TRA=" + hasNotTransferMatch + " signal=" + (on ? "ON" : "OFF"));
                        }
                    }
                } else {
                    for (StationController c : controllers) {
                        boolean hasNotTransferMatch = stationControllerRuleMatches(c, dir, "NOT_TRANSFER", "NOT_TRANSFER+");
                        bulbActions.add(new BulbAction(c.getWorld(), c.getX(), c.getY(), c.getZ(), hasNotTransferMatch, 0));
                        if (debug) {
                            String targetLabel = stationControllerTargetLabel(c.getTargetNodeId());
                            plugin.getLogger().info("[Netro detector] ROUTE controller target=" + targetLabel + " dir=" + dir + " nextHop=empty hasNOT_TRA=" + hasNotTransferMatch + " signal=" + (hasNotTransferMatch ? "ON" : "OFF"));
                        }
                    }
                }
            }

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

                if (ruleMatches(d.getRule1Role(), d.getRule1Direction(), dir)) {
                    processRule(d, d.getRule1Role(), d.getRule1Direction(), dir, cartUuid, bulbActions, debug, worldName, railX, railY, railZ);
                }
                if (d.getRule2Role() != null && ruleMatches(d.getRule2Role(), d.getRule2Direction(), dir)) {
                    processRule(d, d.getRule2Role(), d.getRule2Direction(), dir, cartUuid, bulbActions, debug, worldName, railX, railY, railZ);
                }
                if (d.getRule3Role() != null && ruleMatches(d.getRule3Role(), d.getRule3Direction(), dir)) {
                    processRule(d, d.getRule3Role(), d.getRule3Direction(), dir, cartUuid, bulbActions, debug, worldName, railX, railY, railZ);
                }
                if (d.getRule4Role() != null && ruleMatches(d.getRule4Role(), d.getRule4Direction(), dir)) {
                    processRule(d, d.getRule4Role(), d.getRule4Direction(), dir, cartUuid, bulbActions, debug, worldName, railX, railY, railZ);
                }
                bulbActions.add(new BulbAction(d.getWorld(), d.getX(), d.getY(), d.getZ(), true, DETECTOR_PULSE_MS));
            }

            dedupeBulbActions(bulbActions);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (BulbAction a : bulbActions) {
                    CopperBulbHelper.setPowered(plugin.getServer().getWorld(a.world), a.x, a.y, a.z, a.on);
                    if (a.pulseMs > 0) {
                        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                            CopperBulbHelper.setPowered(plugin.getServer().getWorld(a.world), a.x, a.y, a.z, false), a.pulseMs / 50);
                    }
                }
            });
        });
        return true;
    }

    /** Human-readable target for station controller debug: "StationName:NodeName". */
    private String stationControllerTargetLabel(String nodeId) {
        return nodeRepo.findById(nodeId)
            .map(n -> stationRepo.findById(n.getStationId()).map(Station::getName).orElse("") + ":" + n.getName())
            .orElse(nodeId);
    }

    /** Human-readable target for detector debug: "node=Station:Node (transfer|terminal)" or "junction=JunctionName". */
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
        if (d.getJunctionId() != null) {
            Optional<Junction> j = junctionRepo.findById(d.getJunctionId());
            return "junction=" + (j.map(Junction::getName).orElse(d.getJunctionId()));
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
     * When READY (terminal or junction) detects a cart, hold it at the center of the detector rail for 1 second.
     * We use velocity correction (not teleport): each tick we set velocity toward center until at center, then zero.
     * Same approach as MINV/MAXV so it works with a player in the cart. VehicleMoveEvent + timer both apply the correction.
     */
    private void scheduleTerminalReadyCartCenter(String cartUuid, String railWorldName, int railX, int railY, int railZ) {
        double cx = railX + 0.5;
        double cy = railY + 0.5;
        double cz = railZ + 0.5;
        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(cartUuid);
        } catch (IllegalArgumentException e) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            plugin.registerReadyHold(cartUuid, railWorldName, cx, cy, cz);
            World w = plugin.getServer().getWorld(railWorldName);
            if (w == null) return;
            Minecart cart = findMinecartByUuid(w, uuid);
            if (cart != null && cart.isValid()) {
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
                }, 21);
            }
        });
    }

    private static Minecart findMinecartByUuid(World world, java.util.UUID uuid) {
        for (org.bukkit.entity.Entity e : world.getEntities()) {
            if (e instanceof Minecart && e.getUniqueId().equals(uuid)) return (Minecart) e;
        }
        return null;
    }

    /** Distance within which the cart is considered at center; then we set velocity to zero. */
    private static final double READY_CENTER_TOLERANCE = 0.15;
    /** Max speed when correcting toward center (velocity-based, works with player in cart like MINV/MAXV). */
    private static final double READY_CORRECTION_SPEED = 0.25;

    /**
     * Move the cart toward (cx,cy,cz) using velocity only: if not at center, set velocity toward center;
     * once at center, set velocity to zero. Same approach as MINV/MAXV so it works with a player in the cart.
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

    /**
     * Apply MINV_ and MAXV_ rules from node/junction detectors at this rail. Same logic as station
     * (MINV floor, then MAXV ceiling, direction-aware). Run on main thread.
     * @return true if velocity was modified (so cruise can yield)
     */
    private boolean applyNodeJunctionVelocityClamps(Minecart cart, List<Detector> detectors, org.bukkit.block.BlockFace cartCardinal) {
        if (detectors == null || detectors.isEmpty()) return false;
        boolean changed = false;
        double maxMinV = 0;
        for (Detector d : detectors) {
            String sdDir = DirectionHelper.cardinalToDirectionLabel(d.getSignFacing(), cartCardinal);
            for (int i = 1; i <= 4; i++) {
                String role = i == 1 ? d.getRule1Role() : i == 2 ? d.getRule2Role() : i == 3 ? d.getRule3Role() : d.getRule4Role();
                String ruleDir = i == 1 ? d.getRule1Direction() : i == 2 ? d.getRule2Direction() : i == 3 ? d.getRule3Direction() : d.getRule4Direction();
                if (role == null || !role.startsWith("MINV_")) continue;
                Double minV = parseMinVValue(role);
                if (minV == null) continue;
                if (ruleDir != null && sdDir != null && !directionLabelsMatch(ruleDir, sdDir)) continue;
                maxMinV = Math.max(maxMinV, minV);
            }
        }
        if (maxMinV > 0) {
            Vector v = cart.getVelocity();
            double len = v.length();
            if (len < maxMinV) {
                Vector dirVec = new Vector(cartCardinal.getModX(), cartCardinal.getModY(), cartCardinal.getModZ());
                if (dirVec.lengthSquared() > 1e-9) {
                    cart.setVelocity(dirVec.normalize().multiply(maxMinV));
                    changed = true;
                }
            }
        }
        double minMax = Double.POSITIVE_INFINITY;
        for (Detector d : detectors) {
            String sdDir = DirectionHelper.cardinalToDirectionLabel(d.getSignFacing(), cartCardinal);
            for (int i = 1; i <= 4; i++) {
                String role = i == 1 ? d.getRule1Role() : i == 2 ? d.getRule2Role() : i == 3 ? d.getRule3Role() : d.getRule4Role();
                String ruleDir = i == 1 ? d.getRule1Direction() : i == 2 ? d.getRule2Direction() : i == 3 ? d.getRule3Direction() : d.getRule4Direction();
                if (role == null || !role.startsWith("MAXV_")) continue;
                Double maxV = parseMaxVValue(role);
                if (maxV == null) continue;
                if (ruleDir != null && sdDir != null && !directionLabelsMatch(ruleDir, sdDir)) continue;
                minMax = Math.min(minMax, maxV);
            }
        }
        if (minMax != Double.POSITIVE_INFINITY) {
            Vector vMax = cart.getVelocity();
            double lenMax = vMax.length();
            if (lenMax > minMax && lenMax > 1e-9) {
                cart.setVelocity(vMax.normalize().multiply(minMax));
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Apply MINV_ and MAXV_ rules from station detectors at this rail. MINV clamps velocity up (floor)
     * in the matching direction; MAXV clamps down (ceiling). Applied in that order. Run on main thread.
     * @return true if velocity was modified (so cruise can yield)
     */
    private boolean applyStationVelocityClamps(Minecart cart, List<StationDetector> stationDetectors, org.bukkit.block.BlockFace cartCardinal) {
        boolean changed = false;
        double maxMinV = 0;
        for (StationDetector sd : stationDetectors) {
            String sdDir = DirectionHelper.cardinalToDirectionLabel(sd.getSignFacing(), cartCardinal);
            for (int i = 1; i <= 4; i++) {
                String role = i == 1 ? sd.getRule1Role() : i == 2 ? sd.getRule2Role() : i == 3 ? sd.getRule3Role() : sd.getRule4Role();
                String ruleDir = i == 1 ? sd.getRule1Direction() : i == 2 ? sd.getRule2Direction() : i == 3 ? sd.getRule3Direction() : sd.getRule4Direction();
                if (role == null || !role.startsWith("MINV_")) continue;
                Double minV = parseMinVValue(role);
                if (minV == null) continue;
                if (ruleDir != null && sdDir != null && !directionLabelsMatch(ruleDir, sdDir)) continue;
                maxMinV = Math.max(maxMinV, minV);
            }
        }
        if (maxMinV > 0) {
            Vector v = cart.getVelocity();
            double len = v.length();
            if (len < maxMinV) {
                Vector dirVec = new Vector(cartCardinal.getModX(), cartCardinal.getModY(), cartCardinal.getModZ());
                if (dirVec.lengthSquared() > 1e-9) {
                    cart.setVelocity(dirVec.normalize().multiply(maxMinV));
                    changed = true;
                }
            }
        }

        double minMax = Double.POSITIVE_INFINITY;
        for (StationDetector sd : stationDetectors) {
            String sdDir = DirectionHelper.cardinalToDirectionLabel(sd.getSignFacing(), cartCardinal);
            for (int i = 1; i <= 4; i++) {
                String role = i == 1 ? sd.getRule1Role() : i == 2 ? sd.getRule2Role() : i == 3 ? sd.getRule3Role() : sd.getRule4Role();
                String ruleDir = i == 1 ? sd.getRule1Direction() : i == 2 ? sd.getRule2Direction() : i == 3 ? sd.getRule3Direction() : sd.getRule4Direction();
                if (role == null || !role.startsWith("MAXV_")) continue;
                Double maxV = parseMaxVValue(role);
                if (maxV == null) continue;
                if (ruleDir != null && sdDir != null && !directionLabelsMatch(ruleDir, sdDir)) continue;
                minMax = Math.min(minMax, maxV);
            }
        }
        if (minMax != Double.POSITIVE_INFINITY) {
            Vector vMax = cart.getVelocity();
            double lenMax = vMax.length();
            if (lenMax > minMax && lenMax > 1e-9) {
                cart.setVelocity(vMax.normalize().multiply(minMax));
                changed = true;
            }
        }
        return changed;
    }

    /** Parse MAXV_<number> to Double; null if invalid or out of range. */
    private static Double parseMaxVValue(String role) {
        if (role == null || !role.startsWith("MAXV_") || role.length() <= 5) return null;
        try {
            double v = Double.parseDouble(role.substring(5).trim());
            return v >= 0 && v <= 10 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parse MINV_<number> to Double; null if invalid or out of range. */
    private static Double parseMinVValue(String role) {
        if (role == null || !role.startsWith("MINV_") || role.length() <= 5) return null;
        try {
            double v = Double.parseDouble(role.substring(5).trim());
            return v >= 0 && v <= 10 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Direction on READY/CLEAR is the queue label (LEFT/RIGHT/either), not cart direction — so we don't require ruleDir == cartDir. */
    private static boolean ruleMatches(String role, String ruleDir, String cartDir) {
        if (role == null) return false;
        if ("READY".equals(role) || "CLEAR".equals(role)) return true;
        if (ruleDir == null) return true;
        return ruleDir.equals(cartDir);
    }

    private void processRule(Detector d, String role, String direction, String cartDir, String cartUuid, List<BulbAction> bulbActions, boolean debug,
            String railWorldName, int railX, int railY, int railZ) {
        String debounceKey = cartUuid + "|" + d.getId() + "|" + role;
        long now = System.currentTimeMillis();
        long debounce = "CLEAR".equals(role) ? DEBOUNCE_MS_CLEAR : DEBOUNCE_MS_ENTRY_READY;
        if (now - lastFired.getOrDefault(debounceKey, 0L) < debounce) return;
        lastFired.put(debounceKey, now);

        String nodeId = d.getNodeId();
        String junctionId = d.getJunctionId();
        if (nodeId == null && junctionId == null) return;

        // ENTRY at a node: segment boundary only (take off segment). Transfer nodes have no siding — always pass through (NOT_DIVERT). Applied regardless of dispatch block.
        if ("ENTRY".equals(role) && nodeId != null) {
            Optional<TransferNode> nodeOpt = nodeRepo.findById(nodeId);
            String pairedId = nodeOpt.map(TransferNode::getPairedNodeId).orElse(null);
            if (pairedId != null) cartRepo.removeSegmentOccupancyForCart(pairedId, nodeId, cartUuid);
            setNodeControllers(bulbActions, nodeId, "DIVERT", cartDir, false);
            setNodeControllers(bulbActions, nodeId, "DIVERT+", cartDir, false);
            setNodeControllers(bulbActions, nodeId, "NOT_DIVERT", cartDir, true);
            setNodeControllers(bulbActions, nodeId, "NOT_DIVERT+", cartDir, true);
            if (debug) plugin.getLogger().info("[Netro detector] ENTRY node=" + nodeId + " dir=" + cartDir + " (segment only, pass through)");
            return;
        }
        // ENTRY at a junction: cart leaves segment; decide divert vs NOT_DIVERT (way ahead clear?). NOT_DIVERT/DIVERT applied regardless of dispatch block elsewhere.
        if ("ENTRY".equals(role) && junctionId != null) {
            junctionRepo.findById(junctionId).ifPresent(j -> cartRepo.removeSegmentOccupancyForCart(j.getNodeAId(), j.getNodeBId(), cartUuid));
            String fromSide = normalizeQueueDirection(direction);
            boolean divert = routing.shouldDivertJunction(cartUuid, junctionId, fromSide);
            if (debug) plugin.getLogger().info("[Netro detector] ENTRY junction=" + junctionId + " fromSide=" + fromSide + " shouldDivert=" + divert + " → DIVERT=" + divert + " NOT_DIVERT=" + !divert);
            if (divert) {
                setJunctionControllers(bulbActions, junctionId, "DIVERT", cartDir, true);
                setJunctionControllers(bulbActions, junctionId, "DIVERT+", cartDir, true);
                setJunctionControllers(bulbActions, junctionId, "NOT_DIVERT", cartDir, false);
                setJunctionControllers(bulbActions, junctionId, "NOT_DIVERT+", cartDir, false);
            } else {
                setJunctionControllers(bulbActions, junctionId, "DIVERT", cartDir, false);
                setJunctionControllers(bulbActions, junctionId, "DIVERT+", cartDir, false);
                setJunctionControllers(bulbActions, junctionId, "NOT_DIVERT", cartDir, true);
                setJunctionControllers(bulbActions, junctionId, "NOT_DIVERT+", cartDir, true);
            }
            return;
        }

        if ("READY".equals(role) && nodeId != null) {
            boolean isTerminal = nodeRepo.findById(nodeId).map(TransferNode::isTerminal).orElse(false);
            if (!isTerminal) return;
            String queueDir = normalizeQueueDirection(direction);
            int slotIndex;
            String zone;
            if ("LEFT".equals(queueDir)) {
                int newCount = heldCountRepo.incrementLeft(nodeId);
                slotIndex = newCount - 1;
                zone = "node:" + nodeId + ":LEFT";
            } else if ("RIGHT".equals(queueDir)) {
                int newCount = heldCountRepo.incrementRight(nodeId);
                slotIndex = newCount - 1;
                zone = "node:" + nodeId + ":RIGHT";
            } else {
                slotIndex = heldCountRepo.increment(nodeId) - 1;
                zone = "node:" + nodeId;
            }
            cartRepo.setHeld(cartUuid, zone, slotIndex);
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
            List<String> heldInQueue = cartRepo.findHeldCartsAtNode(nodeId, queueDir);
            String firstToRelease = firstCartToRelease(heldInQueue, releaseReversed);
            boolean first = firstToRelease != null && firstToRelease.equals(cartUuid);
            boolean releaseOn = first && cartDest != null && !cartDest.isEmpty()
                && !destIsForThisTerminal(cartDest, terminalAddr)
                && (pairedId == null || routing.canDispatch(cartUuid, nodeId, pairedId).canGo());
            if (releaseOn) setNodeControllers(bulbActions, nodeId, "RELEASE", queueDir, true);
            if (debug) plugin.getLogger().info("[Netro detector] READY terminal node=" + nodeId + " queue=" + queueDir + " slot=" + slotIndex + " RELEASE=" + releaseOn);
            scheduleTerminalReadyCartCenter(cartUuid, railWorldName, railX, railY, railZ);
            return;
        }
        if ("READY".equals(role) && junctionId != null) {
            String queueDir = normalizeQueueDirection(direction);
            int slotIndex;
            String zone;
            if ("LEFT".equals(queueDir)) {
                int newCount = junctionHeldCountRepo.incrementLeft(junctionId);
                slotIndex = newCount - 1;
                zone = "junction:" + junctionId + ":LEFT";
            } else if ("RIGHT".equals(queueDir)) {
                int newCount = junctionHeldCountRepo.incrementRight(junctionId);
                slotIndex = newCount - 1;
                zone = "junction:" + junctionId + ":RIGHT";
            } else {
                slotIndex = junctionHeldCountRepo.increment(junctionId) - 1;
                zone = "junction:" + junctionId;
            }
            cartRepo.setHeld(cartUuid, zone, slotIndex);
            boolean releaseReversed = junctionRepo.findById(junctionId).map(Junction::isReleaseReversed).orElse(false);
            List<String> heldInQueue = cartRepo.findHeldCartsAtJunction(junctionId, queueDir);
            String firstToRelease = firstCartToRelease(heldInQueue, releaseReversed);
            boolean first = firstToRelease != null && firstToRelease.equals(cartUuid);
            boolean releaseOn = first && routing.canReleaseFromJunction(cartUuid, junctionId);
            if (releaseOn) setJunctionControllers(bulbActions, junctionId, "RELEASE", queueDir, true);
            if (debug) plugin.getLogger().info("[Netro detector] READY junction=" + junctionId + " queue=" + queueDir + " slot=" + slotIndex + " RELEASE=" + releaseOn);
            scheduleTerminalReadyCartCenter(cartUuid, railWorldName, railX, railY, railZ);
            return;
        }

        if ("CLEAR".equals(role) && nodeId != null) {
            Optional<TransferNode> nodeOpt = nodeRepo.findById(nodeId);
            String pairedId = nodeOpt.map(TransferNode::getPairedNodeId).orElse(null);
            boolean isTerminal = nodeOpt.map(TransferNode::isTerminal).orElse(false);
            if (isTerminal) {
                String queueDir = normalizeQueueDirection(direction);
                if ("LEFT".equals(queueDir)) heldCountRepo.decrementLeft(nodeId);
                else if ("RIGHT".equals(queueDir)) heldCountRepo.decrementRight(nodeId);
                else heldCountRepo.decrement(nodeId);
                cartRepo.clearHeld(cartUuid);
            }
            if (pairedId != null) {
                cartRepo.upsertSegmentOccupancy(nodeId, pairedId, cartUuid, "A_TO_B", "node:" + nodeId);
            }
            clearPlusControllersNode(bulbActions, nodeId);
            turnOffAllReleaseNode(bulbActions, nodeId);
            nodeOpt.map(TransferNode::getStationId).ifPresent(stationId ->
                clearStationTransferOnEntry(bulbActions, stationId, nodeId, direction));
            if (isTerminal) {
                boolean releaseReversed = nodeOpt.map(TransferNode::isReleaseReversed).orElse(false);
                String terminalAddr = nodeOpt.flatMap(n -> stationRepo.findById(n.getStationId()).map(s -> n.terminalAddress(s.getAddress()))).orElse(null);
                for (String evalDir : new String[] { "LEFT", "RIGHT", "" }) {
                    List<String> stillInQueue = cartRepo.findHeldCartsAtNode(nodeId, evalDir.isEmpty() ? "" : evalDir);
                    String nextToRelease = firstCartToRelease(stillInQueue, releaseReversed);
                    if (nextToRelease == null) continue;
                    String nextDest = cartRepo.find(nextToRelease).map(cartData -> (String) cartData.get("destination_address")).orElse(null);
                    if (nextDest == null || nextDest.isEmpty() || destIsForThisTerminal(nextDest, terminalAddr)) continue;
                    if (pairedId == null || routing.canDispatch(nextToRelease, nodeId, pairedId).canGo()) {
                        setNodeControllers(bulbActions, nodeId, "RELEASE", evalDir.isEmpty() ? null : evalDir, true);
                    }
                }
            }
            if (debug) plugin.getLogger().info("[Netro detector] CLEAR node=" + nodeId + " (segment register" + (isTerminal ? ", terminal queue" : "") + ")");
            return;
        }
        if ("CLEAR".equals(role) && junctionId != null) {
            String queueDir = normalizeQueueDirection(direction);
            if ("LEFT".equals(queueDir)) junctionHeldCountRepo.decrementLeft(junctionId);
            else if ("RIGHT".equals(queueDir)) junctionHeldCountRepo.decrementRight(junctionId);
            else junctionHeldCountRepo.decrement(junctionId);
            cartRepo.clearHeld(cartUuid);
            junctionRepo.findById(junctionId).ifPresent(j -> {
                String nodeA = j.getNodeAId(), nodeB = j.getNodeBId();
                String segDir = "LEFT".equals(queueDir) ? "B_TO_A" : "A_TO_B";
                cartRepo.upsertSegmentOccupancy(nodeA, nodeB, cartUuid, segDir, "junction:" + junctionId);
            });
            clearPlusControllersJunction(bulbActions, junctionId);
            turnOffAllReleaseJunction(bulbActions, junctionId);
            boolean releaseReversed = junctionRepo.findById(junctionId).map(Junction::isReleaseReversed).orElse(false);
            boolean anyRelease = false;
            for (String evalDir : new String[] { "LEFT", "RIGHT", "" }) {
                List<String> stillInQueue = cartRepo.findHeldCartsAtJunction(junctionId, evalDir.isEmpty() ? null : evalDir);
                String nextToRelease = firstCartToRelease(stillInQueue, releaseReversed);
                if (nextToRelease != null && routing.canReleaseFromJunction(nextToRelease, junctionId)) {
                    setJunctionControllers(bulbActions, junctionId, "RELEASE", evalDir.isEmpty() ? null : evalDir, true);
                    anyRelease = true;
                }
            }
            if (debug) plugin.getLogger().info("[Netro detector] CLEAR junction=" + junctionId + " queue=" + queueDir + " RELEASE(next)=" + anyRelease);
        }
    }

    /** Rule direction as queue label: "LEFT"/"RIGHT" or null for either. */
    private static String normalizeQueueDirection(String direction) {
        if (direction == null) return null;
        if ("LEFT".equalsIgnoreCase(direction) || "L".equalsIgnoreCase(direction)) return "LEFT";
        if ("RIGHT".equalsIgnoreCase(direction) || "R".equalsIgnoreCase(direction)) return "RIGHT";
        return null;
    }

    private void turnOffAllReleaseNode(List<BulbAction> out, String nodeId) {
        setNodeControllers(out, nodeId, "RELEASE", null, false);
        setNodeControllers(out, nodeId, "RELEASE", "LEFT", false);
        setNodeControllers(out, nodeId, "RELEASE", "RIGHT", false);
    }

    private void turnOffAllReleaseJunction(List<BulbAction> out, String junctionId) {
        setJunctionControllers(out, junctionId, "RELEASE", null, false);
        setJunctionControllers(out, junctionId, "RELEASE", "LEFT", false);
        setJunctionControllers(out, junctionId, "RELEASE", "RIGHT", false);
    }

    /** First cart that should get RELEASE: FIFO (index 0) or reversed/LIFO (last index). */
    private static String firstCartToRelease(List<String> held, boolean releaseReversed) {
        if (held == null || held.isEmpty()) return null;
        return releaseReversed ? held.get(held.size() - 1) : held.get(0);
    }

    /** On CLEAR: turn off only controllers with + (DIVERT+, NOT_DIVERT+). Plain DIVERT/NOT_DIVERT are left as-is; ENTRY drives those. */
    private void clearPlusControllersNode(List<BulbAction> out, String nodeId) {
        for (String dir : new String[] { "LEFT", "RIGHT", null }) {
            setNodeControllers(out, nodeId, "DIVERT+", dir, false);
            setNodeControllers(out, nodeId, "NOT_DIVERT+", dir, false);
        }
    }

    private void clearPlusControllersJunction(List<BulbAction> out, String junctionId) {
        for (String dir : new String[] { "LEFT", "RIGHT", null }) {
            setJunctionControllers(out, junctionId, "DIVERT+", dir, false);
            setJunctionControllers(out, junctionId, "NOT_DIVERT+", dir, false);
        }
    }

    private void setNodeControllers(List<BulbAction> out, String nodeId, String role, String direction, boolean on) {
        for (Controller c : controllerRepo.findByNodeAndRule(nodeId, role, direction)) {
            out.add(new BulbAction(c.getWorld(), c.getX(), c.getY(), c.getZ(), on, 0));
        }
    }

    private void setJunctionControllers(List<BulbAction> out, String junctionId, String role, String direction, boolean on) {
        for (Controller c : controllerRepo.findByJunctionAndRule(junctionId, role, direction)) {
            out.add(new BulbAction(c.getWorld(), c.getX(), c.getY(), c.getZ(), on, 0));
        }
    }

    /** True if controller has any rule that is one of the given roles (e.g. TRANSFER or TRANSFER+) and direction matches (or no direction). */
    private static boolean stationControllerRuleMatches(StationController c, String cartDir, String... roles) {
        for (String role : roles) {
            if (roleMatchesDir(c.getRule1Role(), c.getRule1Direction(), role, cartDir)) return true;
            if (roleMatchesDir(c.getRule2Role(), c.getRule2Direction(), role, cartDir)) return true;
            if (roleMatchesDir(c.getRule3Role(), c.getRule3Direction(), role, cartDir)) return true;
            if (roleMatchesDir(c.getRule4Role(), c.getRule4Direction(), role, cartDir)) return true;
        }
        return false;
    }

    private static boolean roleMatchesDir(String ruleRole, String ruleDir, String role, String cartDir) {
        if (ruleRole == null || !ruleRole.equals(role)) return false;
        if (ruleDir == null || cartDir == null) return ruleDir == null;
        return directionLabelsMatch(ruleDir, cartDir);
    }

    /** Treat L/LEFT and R/RIGHT as the same so NOT_TRA:L matches cart dir LEFT. */
    private static boolean directionLabelsMatch(String ruleDir, String cartDir) {
        if (ruleDir.equals(cartDir)) return true;
        return ("L".equalsIgnoreCase(ruleDir) && "LEFT".equalsIgnoreCase(cartDir))
            || ("LEFT".equalsIgnoreCase(ruleDir) && "L".equalsIgnoreCase(cartDir))
            || ("R".equalsIgnoreCase(ruleDir) && "RIGHT".equalsIgnoreCase(cartDir))
            || ("RIGHT".equalsIgnoreCase(ruleDir) && "R".equalsIgnoreCase(cartDir));
    }

    /** Opposite of LEFT is RIGHT and vice versa; null unchanged. Used so ROU:L clears TRA:R and ROU:R clears TRA:L. */
    private static String oppositeDirection(String dir) {
        if (dir == null) return null;
        if ("LEFT".equalsIgnoreCase(dir) || "L".equalsIgnoreCase(dir)) return "RIGHT";
        if ("RIGHT".equalsIgnoreCase(dir) || "R".equalsIgnoreCase(dir)) return "LEFT";
        return null;
    }

    /**
     * When the cart CLEARs a node (exits the branch): turn off TRA/NOT_TRA for that node's station controllers
     * so the switch resets after the cart has left the branch. Direction-aware (CLEAR:L clears TRA:L etc.).
     */
    private void clearStationTransferOnEntry(List<BulbAction> out, String stationId, String nodeId, String cartDir) {
        for (StationController sc : stationControllerRepo.findByStationAndTarget(stationId, nodeId)) {
            if (!shouldClearStationControllerOnEntry(sc, cartDir)) continue;
            out.add(new BulbAction(sc.getWorld(), sc.getX(), sc.getY(), sc.getZ(), false, 0));
        }
    }

    private static boolean shouldClearStationControllerOnEntry(StationController c, String cartDir) {
        if (stationControllerClearRuleMatches(c.getRule1Role(), c.getRule1Direction(), cartDir)) return true;
        if (stationControllerClearRuleMatches(c.getRule2Role(), c.getRule2Direction(), cartDir)) return true;
        if (stationControllerClearRuleMatches(c.getRule3Role(), c.getRule3Direction(), cartDir)) return true;
        if (stationControllerClearRuleMatches(c.getRule4Role(), c.getRule4Direction(), cartDir)) return true;
        return false;
    }

    /** True if this rule should be cleared on ENTRY: + variant clears any; TRA/NOT_TRA with no dir clears any; with dir clears only when dir matches. */
    private static boolean stationControllerClearRuleMatches(String ruleRole, String ruleDir, String cartDir) {
        if (ruleRole == null) return false;
        if ("TRANSFER+".equals(ruleRole) || "NOT_TRANSFER+".equals(ruleRole)) return true;
        if ("TRANSFER".equals(ruleRole) || "NOT_TRANSFER".equals(ruleRole)) {
            if (ruleDir == null) return true;
            return ruleDir.equals(cartDir);
        }
        return false;
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

    /**
     * Re-evaluate terminal RELEASE for the node where this cart is held (if any).
     * Call after changing a cart's destination so that if the cart is already at a terminal,
     * RELEASE is updated without needing the cart to pass the READY detector again.
     */
    public void recheckTerminalReleaseForCart(String cartUuid) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
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
            for (String evalDir : new String[] { "LEFT", "RIGHT", "" }) {
                List<String> stillInQueue = cartRepo.findHeldCartsAtNode(nodeId, evalDir.isEmpty() ? "" : evalDir);
                String nextToRelease = firstCartToRelease(stillInQueue, releaseReversed);
                if (nextToRelease == null) continue;
                String nextDest = cartRepo.find(nextToRelease).map(data -> (String) data.get("destination_address")).orElse(null);
                if (nextDest == null || nextDest.isEmpty() || destIsForThisTerminal(nextDest, terminalAddr)) continue;
                if (pairedId == null || routing.canDispatch(nextToRelease, nodeId, pairedId).canGo()) {
                    setNodeControllers(bulbActions, nodeId, "RELEASE", evalDir.isEmpty() ? null : evalDir, true);
                }
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (BulbAction a : bulbActions) {
                    org.bukkit.World w = plugin.getServer().getWorld(a.world);
                    if (w != null) CopperBulbHelper.setPowered(w, a.x, a.y, a.z, a.on);
                }
            });
        });
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
}
