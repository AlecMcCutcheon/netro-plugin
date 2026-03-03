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
 * update held count, and set RELEASE controller bulbs.
 */
public class DetectorRailHandler {

    private static final long DEBOUNCE_MS_ENTRY_READY = 1_500;
    private static final long DEBOUNCE_MS_CLEAR = 600;
    private static final long DETECTOR_PULSE_MS = 1_000;

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
            List<CruiseSpeedAction> cruiseSpeedActions = new ArrayList<>();

            unifiedCartSeenFlow.run(cartUuid, world, cart, detectors, railX, railY, railZ, headingTowardStationId);

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
                    processRule(d, d.getRule1Role(), d.getRule1Direction(), dir, cartUuid, bulbActions, railStateActions, cruiseSpeedActions, debug, worldName, railX, railY, railZ);
                }
                if (d.getRule2Role() != null && ruleMatches(d.getRule2Role(), d.getRule2Direction(), dir)) {
                    processRule(d, d.getRule2Role(), d.getRule2Direction(), dir, cartUuid, bulbActions, railStateActions, cruiseSpeedActions, debug, worldName, railX, railY, railZ);
                }
                if (d.getRule3Role() != null && ruleMatches(d.getRule3Role(), d.getRule3Direction(), dir)) {
                    processRule(d, d.getRule3Role(), d.getRule3Direction(), dir, cartUuid, bulbActions, railStateActions, cruiseSpeedActions, debug, worldName, railX, railY, railZ);
                }
                if (d.getRule4Role() != null && ruleMatches(d.getRule4Role(), d.getRule4Direction(), dir)) {
                    processRule(d, d.getRule4Role(), d.getRule4Direction(), dir, cartUuid, bulbActions, railStateActions, cruiseSpeedActions, debug, worldName, railX, railY, railZ);
                }
                bulbActions.add(new BulbAction(d.getWorld(), d.getX(), d.getY(), d.getZ(), true, DETECTOR_PULSE_MS));
            }

            dedupeBulbActions(bulbActions);

            final List<CruiseSpeedAction> cruiseSpeedActionsFinal = cruiseSpeedActions;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
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
                    org.bukkit.World w = plugin.getServer().getWorld(r.world);
                    if (w == null) continue;
                    org.bukkit.block.Block block = w.getBlockAt(r.x, r.y, r.z);
                    if (block.getBlockData() instanceof org.bukkit.block.data.Rail railData) {
                        try {
                            railData.setShape(org.bukkit.block.data.Rail.Shape.valueOf(r.shapeName));
                            block.setBlockData(railData);
                        } catch (IllegalArgumentException ignored) { }
                    }
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
     * When READY (terminal) detects a cart, hold it at the center of the detector rail for 1 second.
     * We use velocity correction (not teleport): each tick we set velocity toward center until at center, then zero.
     * Velocity-based so it works with a player in the cart. VehicleMoveEvent + timer both apply the correction.
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

    private void processRule(Detector d, String role, String direction, String cartDir, String cartUuid, List<BulbAction> bulbActions, List<RailStateAction> railStateActions, List<CruiseSpeedAction> cruiseSpeedActions, boolean debug,
            String railWorldName, int railX, int railY, int railZ) {
        String debounceKey = cartUuid + "|" + d.getId() + "|" + role;
        long now = System.currentTimeMillis();
        long debounce = "CLEAR".equals(role) ? DEBOUNCE_MS_CLEAR : DEBOUNCE_MS_ENTRY_READY;
        if (now - lastFired.getOrDefault(debounceKey, 0L) < debounce) return;
        lastFired.put(debounceKey, now);

        String nodeId = d.getNodeId();
        if (nodeId == null) return;

        if ("ENTRY".equals(role) || "READY".equals(role) || "CLEAR".equals(role)) {
            String substitutedDest = applyBlockedRulesIfNeeded(d, cartUuid, cartDir);
            if ("ENTRY".equals(role) || "CLEAR".equals(role)) {
                evaluateRuleEngine(d, role, cartUuid, bulbActions, railStateActions, cruiseSpeedActions, railWorldName, cartDir, substitutedDest);
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
            int slotIndex = heldCountRepo.increment(nodeId) - 1;
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
            List<String> heldInQueue = cartRepo.findHeldCartsAtNode(nodeId, "");
            String firstToRelease = firstCartToRelease(heldInQueue, releaseReversed);
            boolean first = firstToRelease != null && firstToRelease.equals(cartUuid);
            boolean releaseOn = first && cartDest != null && !cartDest.isEmpty()
                && !destIsForThisTerminal(cartDest, terminalAddr)
                && (pairedId == null || routing.canDispatch(cartUuid, nodeId, pairedId).canGo());
            if (releaseOn) setNodeControllers(bulbActions, nodeId, "RELEASE", null, true);
            if (debug) plugin.getLogger().info("[Netro detector] READY terminal node=" + nodeId + " slot=" + slotIndex + " RELEASE=" + releaseOn);
            scheduleTerminalReadyCartCenter(cartUuid, railWorldName, railX, railY, railZ);
            return;
        }

        if ("CLEAR".equals(role) && nodeId != null) {
            Optional<TransferNode> nodeOpt = nodeRepo.findById(nodeId);
            String pairedId = nodeOpt.map(TransferNode::getPairedNodeId).orElse(null);
            boolean isTerminal = nodeOpt.map(TransferNode::isTerminal).orElse(false);
            if (isTerminal) {
                heldCountRepo.decrement(nodeId);
                cartRepo.clearHeld(cartUuid);
            }
            // No segment occupancy (no collision detection).
            clearPlusControllersNode(bulbActions, nodeId);
            turnOffAllReleaseNode(bulbActions, nodeId);
            if (isTerminal) {
                boolean releaseReversed = nodeOpt.map(TransferNode::isReleaseReversed).orElse(false);
                String terminalAddr = nodeOpt.flatMap(n -> stationRepo.findById(n.getStationId()).map(s -> n.terminalAddress(s.getAddress()))).orElse(null);
                List<String> stillInQueue = cartRepo.findHeldCartsAtNode(nodeId, "");
                String nextToRelease = firstCartToRelease(stillInQueue, releaseReversed);
                if (nextToRelease != null) {
                    String nextDest = cartRepo.find(nextToRelease).map(cartData -> (String) cartData.get("destination_address")).orElse(null);
                    if (nextDest != null && !nextDest.isEmpty() && !destIsForThisTerminal(nextDest, terminalAddr)
                        && (pairedId == null || routing.canDispatch(nextToRelease, nodeId, pairedId).canGo())) {
                        setNodeControllers(bulbActions, nodeId, "RELEASE", null, true);
                    }
                }
            }
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
                plugin.getLogger().info("[Netro rule] BLOCKED hop=" + destForRules + " → substitute to " + rule.getActionData());
            }
            return rule.getActionData();
        }

        String defaultSubstitute = defaultBlockedSubstitute(fromStationId, fromNodeId, nextHopNodeId, cartUuid);
        if (defaultSubstitute != null && plugin.isDebugEnabled()) {
            plugin.getLogger().info("[Netro rule] BLOCKED hop=" + destForRules + " (no rule) → default substitute to " + defaultSubstitute);
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
     * SEND_ON/SEND_OFF to controllers or SET_RAIL_STATE to the target rail.
     */
    private void evaluateRuleEngine(Detector d, String role, String cartUuid, List<BulbAction> bulbActions, List<RailStateAction> railStateActions, List<CruiseSpeedAction> cruiseSpeedActions, String railWorldName, String cartDir, String substitutedDestForRules) {
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
        plugin.getLogger().info("[Netro rule] context=" + contextType + ":" + contextId + " trigger=" + roleTriggerType
            + " cartDest=" + (cartDestForLog != null ? cartDestForLog : "null") + " localDest=" + (destForRules != null ? destForRules : "null")
            + " rules=" + rules.size());
        for (Rule rule : rules) {
            String triggerType = rule.getTriggerType();
            boolean forRole = roleTriggerType.equals(triggerType);
            boolean forDetected = Rule.TRIGGER_DETECTED.equals(triggerType);
            if (!forRole && !forDetected) continue;
            boolean cartMatchesDest = destinationMatchesForRule(destForRules, rule.getDestinationId());
            boolean fires = rule.isDestinationPositive() ? cartMatchesDest : !cartMatchesDest;
            plugin.getLogger().info("[Netro rule] rule#" + rule.getRuleIndex() + " destId=" + (rule.getDestinationId() != null ? rule.getDestinationId() : "any")
                + " positive=" + rule.isDestinationPositive() + " match=" + cartMatchesDest + " fires=" + fires + " action=" + rule.getActionType());
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
                String actionData = rule.getActionData();
                String world;
                int rx, ry, rz;
                String shapeName;
                String[] parts = actionData.split(",", -1);
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
                        shapeName = actionData;
                    }
                } else {
                    world = d.getWorld();
                    rx = d.getRailX();
                    ry = d.getRailY();
                    rz = d.getRailZ();
                    shapeName = actionData;
                }
                railStateActions.add(new RailStateAction(world, rx, ry, rz, shapeName));
            }
        }
    }

    /** Converts a node ID to the same destination-id format used by the rule picker (Station:Node or address.terminalIndex). */
    private Optional<String> nodeIdToDestinationId(String nodeId) {
        Optional<TransferNode> nodeOpt = nodeRepo.findById(nodeId);
        if (nodeOpt.isEmpty()) return Optional.empty();
        TransferNode node = nodeOpt.get();
        Optional<Station> stationOpt = stationRepo.findById(node.getStationId());
        if (stationOpt.isEmpty()) return Optional.empty();
        Station station = stationOpt.get();
        if (node.isTerminal() && node.getTerminalIndex() != null) {
            return Optional.of(station.getAddress() + "." + node.getTerminalIndex());
        }
        return Optional.of(station.getName() + ":" + node.getName());
    }

    /** Resolves a destination string to a node ID when it refers to a specific node (Station:Node or address.terminalIndex). */
    private Optional<String> resolveDestinationToNodeId(String dest) {
        if (dest == null || dest.isBlank()) return Optional.empty();
        String s = dest.strip();
        if (s.matches("[0-9.]+")) {
            return AddressHelper.parseDestination(s)
                .filter(p -> p.terminalIndex() != null)
                .flatMap(p -> stationRepo.findByAddress(p.stationAddress())
                    .flatMap(st -> nodeRepo.findTerminalByIndex(st.getId(), p.terminalIndex()))
                    .map(TransferNode::getId));
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
            List<String> stillInQueue = cartRepo.findHeldCartsAtNode(nodeId, "");
            String nextToRelease = firstCartToRelease(stillInQueue, releaseReversed);
            if (nextToRelease != null) {
                String nextDest = cartRepo.find(nextToRelease).map(data -> (String) data.get("destination_address")).orElse(null);
                if (nextDest != null && !nextDest.isEmpty() && !destIsForThisTerminal(nextDest, terminalAddr)
                    && (pairedId == null || routing.canDispatch(nextToRelease, nodeId, pairedId).canGo())) {
                    setNodeControllers(bulbActions, nodeId, "RELEASE", null, true);
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
