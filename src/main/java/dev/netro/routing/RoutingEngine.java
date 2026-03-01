package dev.netro.routing;

import dev.netro.NetroPlugin;
import dev.netro.database.CartHeldCountRepository;
import dev.netro.database.CartRepository;
import dev.netro.database.JunctionHeldCountRepository;
import dev.netro.database.JunctionRepository;
import dev.netro.database.RoutingDecisionLogRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Junction;
import dev.netro.model.Station;
import dev.netro.model.TransferNode;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Core routing: Dijkstra, dispatch checks, reroute, pressure.
 * Physical execution is via detectors/controllers (copper bulbs); no rail block control.
 */
public class RoutingEngine {

    private static final long REROUTE_TIMEOUT_MS = 30_000;

    private final NetroPlugin plugin;
    private final StationRepository stationRepo;
    private final TransferNodeRepository nodeRepo;
    private final JunctionRepository junctionRepo;
    private final CartRepository cartRepo;
    private final CartHeldCountRepository heldCountRepo;
    private final JunctionHeldCountRepository junctionHeldCountRepo;
    private final RoutingDecisionLogRepository routingDecisionLogRepo;

    public RoutingEngine(NetroPlugin plugin) {
        this.plugin = plugin;
        this.stationRepo = new StationRepository(plugin.getDatabase());
        this.nodeRepo = new TransferNodeRepository(plugin.getDatabase());
        this.junctionRepo = new JunctionRepository(plugin.getDatabase());
        this.cartRepo = new CartRepository(plugin.getDatabase());
        this.heldCountRepo = new CartHeldCountRepository(plugin.getDatabase());
        this.junctionHeldCountRepo = new JunctionHeldCountRepository(plugin.getDatabase());
        this.routingDecisionLogRepo = new RoutingDecisionLogRepository(plugin.getDatabase());
    }

    public void onNodePaired(String nodeAId, String nodeBId) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            nodeRepo.findById(nodeAId).ifPresent(na ->
                nodeRepo.findById(nodeBId).ifPresent(nb -> {
                    stationRepo.findById(na.getStationId()).ifPresent(this::rebuildRoutingTable);
                    stationRepo.findById(nb.getStationId()).ifPresent(this::rebuildRoutingTable);
                }));
        });
    }

    private void rebuildRoutingTable(Station origin) {
        Map<String, RouteEntry> paths = dijkstra(origin.getId());
        for (Map.Entry<String, RouteEntry> e : paths.entrySet()) {
            stationRepo.findById(e.getKey()).ifPresent(dest -> {
                RouteEntry route = e.getValue();
                nodeRepo.upsertRoutingEntry(UUID.randomUUID().toString(),
                    origin.getId(), dest.getAddress(), route.firstHopNodeId(), route.cost());
                for (int depth = 3; depth >= 1; depth--) {
                    nodeRepo.upsertRoutingEntry(UUID.randomUUID().toString(),
                        origin.getId(), dest.getAddressPrefix(depth), route.firstHopNodeId(), route.cost());
                }
                nodeRepo.findTerminals(dest.getId()).forEach(terminal -> {
                    if (terminal.getTerminalIndex() != null) {
                        String terminalAddress = dest.getAddress() + "." + terminal.getTerminalIndex();
                        nodeRepo.upsertRoutingEntry(UUID.randomUUID().toString(),
                            origin.getId(), terminalAddress, route.firstHopNodeId(), route.cost());
                    }
                });
            });
        }
    }

    private Map<String, RouteEntry> dijkstra(String startStationId) {
        Map<String, Integer> dist = new HashMap<>();
        Map<String, RouteEntry> result = new HashMap<>();
        PriorityQueue<String> pq = new PriorityQueue<>(
            Comparator.comparingInt(id -> dist.getOrDefault(id, Integer.MAX_VALUE)));
        dist.put(startStationId, 0);
        pq.add(startStationId);

        while (!pq.isEmpty()) {
            String current = pq.poll();
            int currentDist = dist.getOrDefault(current, Integer.MAX_VALUE);
            List<TransferNode> nodes = nodeRepo.findByStation(current).stream()
                .filter(n -> n.isReady() && n.isPaired())
                .collect(Collectors.toList());

            for (TransferNode node : nodes) {
                nodeRepo.findById(node.getPairedNodeId()).ifPresent(paired -> {
                    String neighborStationId = paired.getStationId();
                    int newDist = currentDist + 1;
                    if (newDist < dist.getOrDefault(neighborStationId, Integer.MAX_VALUE)) {
                        dist.put(neighborStationId, newDist);
                        String firstHopNodeId = current.equals(startStationId)
                            ? node.getId()
                            : result.getOrDefault(current, new RouteEntry(null, 0, node.getId())).firstHopNodeId();
                        result.put(neighborStationId, new RouteEntry(neighborStationId, newDist, firstHopNodeId));
                        pq.add(neighborStationId);
                    }
                });
            }
        }
        return result;
    }

    public DispatchResult canDispatch(String cartUuid, String fromNodeId, String toNodeId) {
        TransferNode fromNode = nodeRepo.findById(fromNodeId).orElse(null);
        TransferNode toNode   = nodeRepo.findById(toNodeId).orElse(null);
        if (fromNode == null || toNode == null) return DispatchResult.blocked("Invalid nodes");

        int freeSlots = nodeRepo.countFreeSlots(toNodeId);
        if (freeSlots == 0) return DispatchResult.blocked("Destination transfer node is full");

        List<Junction> junctions = junctionRepo.findOnSegment(fromNodeId, toNodeId);
        if (junctions.isEmpty()) {
            List<Map<String,Object>> opposing = cartRepo.findOpposingCarts(fromNodeId, toNodeId, "A_TO_B");
            if (!opposing.isEmpty()) return DispatchResult.blocked("Opposing traffic on segment, no junction to mediate");
            return DispatchResult.clear(junctions);
        }

        List<Junction> orderedJunctions = new SegmentGeometry(fromNodeId, toNodeId, junctions, nodeRepo).getOrderedJunctions();
        Junction lastJunction = orderedJunctions.isEmpty() ? junctions.get(junctions.size() - 1) : orderedJunctions.get(orderedJunctions.size() - 1);
        String opposingDirection = "B_TO_A";
        List<String> committed = cartRepo.findCommittedCarts(fromNodeId, toNodeId, opposingDirection, lastJunction.getId());
        committed.removeIf(cartUuid::equals);
        if (!committed.isEmpty()) {
            if (plugin.isRoutingDebugEnabled()) {
                String fromName = nodeRepo.findById(fromNodeId).map(n -> stationRepo.findById(n.getStationId()).map(Station::getName).orElse("") + ":" + n.getName()).orElse(fromNodeId);
                String toName = nodeRepo.findById(toNodeId).map(n -> stationRepo.findById(n.getStationId()).map(Station::getName).orElse("") + ":" + n.getName()).orElse(toNodeId);
                plugin.getLogger().info("[Netro routing] canDispatch blocked: Opposing cart committed past last junction segment=" + fromName + "->" + toName + " committedCartUuids=" + committed);
            }
            return DispatchResult.blocked("Opposing cart committed past last junction");
        }

        List<Map<String, Object>> opposing = cartRepo.findOpposingCarts(fromNodeId, toNodeId, "A_TO_B");
        opposing.removeIf(m -> cartUuid.equals(m.get("cart_uuid")));
        int sameDirectionCount = cartRepo.countCartsOnSegment(fromNodeId, toNodeId, "A_TO_B");
        int slotsNeeded = sameDirectionCount + 1;
        if (!opposing.isEmpty()) {
            for (Junction j : orderedJunctions.isEmpty() ? junctions : orderedJunctions) {
                String approachSide = j.getNodeAId().equals(fromNodeId) ? "LEFT" : "RIGHT";
                int junctionSlots = junctionHeldCountRepo.getFreeSlotsForDirection(j.getId(), approachSide);
                if (junctionSlots < slotsNeeded)
                    return DispatchResult.blocked("Junction " + j.getName() + " has " + junctionSlots + " slot(s) for " + approachSide + " approach, need " + slotsNeeded + " (same-direction on segment + this cart); opposing traffic");
            }
        }
        return DispatchResult.clear(junctions);
    }

    /**
     * Called when a cart is destroyed or removed. Clears held state, segment occupancy, and decrements held count if it was held at a node or junction.
     * Uses the correct left/right or generic decrement based on zone (e.g. node:X:LEFT → decrementLeft(X)).
     */
    public void onCartRemoved(String cartUuid) {
        Optional<Map<String, Object>> cartData = cartRepo.find(cartUuid);
        if (cartData.isPresent()) {
            String zone = (String) cartData.get().get("zone");
            if (zone != null && zone.startsWith("node:")) {
                String nodeId = CartRepository.nodeIdFromZone(zone);
                if (nodeId != null) {
                    if (zone.endsWith(":LEFT")) heldCountRepo.decrementLeft(nodeId);
                    else if (zone.endsWith(":RIGHT")) heldCountRepo.decrementRight(nodeId);
                    else heldCountRepo.decrement(nodeId);
                }
            } else if (zone != null && zone.startsWith("junction:")) {
                String rest = zone.substring(9);
                String junctionId = rest.endsWith(":LEFT") || rest.endsWith(":RIGHT")
                    ? rest.substring(0, rest.length() - 5) : rest;
                if (!junctionId.isEmpty()) {
                    if (rest.endsWith(":LEFT")) junctionHeldCountRepo.decrementLeft(junctionId);
                    else if (rest.endsWith(":RIGHT")) junctionHeldCountRepo.decrementRight(junctionId);
                    else junctionHeldCountRepo.decrement(junctionId);
                }
            }
        }
        cartRepo.clearHeld(cartUuid);
        cartRepo.clearSegmentOccupancyForCart(cartUuid);
    }

    public Optional<String> checkReroute(String cartUuid, String currentStationId, String destination, String blockedNodeId) {
        Optional<Map<String,Object>> cartData = cartRepo.find(cartUuid);
        if (cartData.isEmpty()) return Optional.empty();
        long enteredAt = ((Number) cartData.get().get("entered_zone_at")).longValue();
        if (System.currentTimeMillis() - enteredAt < REROUTE_TIMEOUT_MS) return Optional.empty();
        String alternateNodeId = nodeRepo.lookupNextHopNode(currentStationId, destination)
            .filter(id -> !id.equals(blockedNodeId))
            .orElse(null);
        return Optional.ofNullable(alternateNodeId);
    }

    /**
     * Returns the transfer node or terminal node id that a cart should go to at this station
     * (first hop toward its destination). Intended for use by <b>station detectors and station
     * controllers</b> (TRANSFER/NOT_TRANSFER) — the "which branch at the station" decision.
     * If the preferred segment has opposing traffic (cart left junction heading this way), returns
     * an available terminal at this station so the cart waits until the way is clear (terminal
     * release already checks segment and junction before releasing).
     */
    public Optional<String> getNextHopNodeAtStation(String cartUuid, String stationId) {
        Optional<Map<String, Object>> cartData = cartRepo.find(cartUuid);
        if (cartData.isEmpty()) {
            if (plugin.isRoutingDebugEnabled()) {
                plugin.getLogger().info("[Netro routing] station=" + stationId + " cart=" + cartUuid + " no cart data (cart not yet in DB) → no next hop");
            }
            return Optional.empty();
        }
        String dest = (String) cartData.get().get("destination_address");
        if (dest == null || dest.isEmpty()) {
            if (plugin.isRoutingDebugEnabled()) {
                plugin.getLogger().info("[Netro routing] station=" + stationId + " cart=" + cartUuid + " no destination → no next hop (caller may apply no-destination rule)");
            }
            return Optional.empty();
        }
        Optional<String> nextHop = resolveNextHopNode(stationId, dest);
        if (nextHop.isEmpty()) {
            if (plugin.isRoutingDebugEnabled()) {
                plugin.getLogger().info("[Netro routing] station=" + stationId + " cart=" + cartUuid + " dest=" + dest + " resolveNextHop=empty (no route or dest unknown) → no next hop");
            }
            return Optional.empty();
        }
        String nextHopId = nextHop.get();
        Optional<TransferNode> nodeOpt = nodeRepo.findById(nextHopId);
        if (nodeOpt.isEmpty()) return Optional.of(nextHopId);
        String pairedId = nodeOpt.get().getPairedNodeId();
        DispatchResult dispatchResult = pairedId != null ? canDispatch(cartUuid, nextHopId, pairedId) : DispatchResult.clear(List.of());
        String chosenNodeId;
        if (pairedId != null && !dispatchResult.canGo()) {
            // Blocked: send cart to terminal to wait; we still return chosen so station controllers get TRA (terminal) and NOT_TRA (others).
            chosenNodeId = nodeRepo.findAvailableTerminal(stationId).map(TransferNode::getId).orElse(nextHopId);
        } else {
            chosenNodeId = nextHopId;
        }
        if (plugin.getConfig().getBoolean("routing-decision-log", true)) {
            routingDecisionLogRepo.logNextHop(cartUuid, stationId, nextHopId, chosenNodeId,
                dispatchResult.canGo(), dispatchResult.reason(), dest);
        }
        if (plugin.isRoutingDebugEnabled()) {
            plugin.getLogger().info("[Netro routing] cart=" + cartUuid + " station=" + stationId
                + " preferred=" + nextHopId + " chosen=" + chosenNodeId
                + " canDispatch=" + dispatchResult.canGo()
                + (dispatchResult.reason() != null ? " reason=" + dispatchResult.reason() : ""));
        }
        return Optional.of(chosenNodeId);
    }

    /**
     * No-destination rule: when a cart has no destination, set destination to the nearest available terminal
     * (current station first, then any other station with an available terminal), or remove the cart if no terminals exist.
     * Used by {@link dev.netro.detector.UnifiedCartSeenFlow} and by the station-loop fallback in DetectorRailHandler when the unified flow did not run no-dest.
     *
     * @param cartUuid       cart UUID
     * @param currentStationId station the cart is at (station detector fired here)
     * @param onRemoved     run when the cart is removed from the DB (e.g. schedule main-thread despawn of the entity)
     * @return optional next-hop node id if destination was set to a terminal (use for TRANSFER/NOT_TRANSFER); empty if cart was removed or not in DB
     */
    public Optional<String> applyNoDestinationRule(String cartUuid, String currentStationId, Runnable onRemoved) {
        Optional<Map<String, Object>> cartData = cartRepo.find(cartUuid);
        boolean cartWasInDb = cartData.isPresent();
        if (cartWasInDb) {
            String dest = (String) cartData.get().get("destination_address");
            if (dest != null && !dest.isEmpty()) return Optional.empty();
        }

        Optional<TransferNode> atCurrent = nodeRepo.findAvailableTerminal(currentStationId);
        if (atCurrent.isPresent()) {
            Optional<Station> st = stationRepo.findById(currentStationId);
            String termAddr = st.flatMap(s -> Optional.ofNullable(atCurrent.get().terminalAddress(s.getAddress()))).orElse(null);
            if (termAddr != null) {
                cartRepo.setDestination(cartUuid, termAddr, currentStationId);
                if (plugin.isRoutingDebugEnabled()) {
                    plugin.getLogger().info("[Netro routing] no-destination rule: cart=" + cartUuid + " → set dest to terminal at current station " + termAddr);
                }
                return Optional.of(atCurrent.get().getId());
            }
        }

        Optional<Station> otherWithTerminal = stationRepo.findAll().stream()
            .filter(s -> !s.getId().equals(currentStationId))
            .filter(s -> nodeRepo.findAvailableTerminal(s.getId()).isPresent())
            .findFirst();
        if (otherWithTerminal.isPresent()) {
            Optional<TransferNode> term = nodeRepo.findAvailableTerminal(otherWithTerminal.get().getId());
            if (term.isPresent()) {
                String termAddr = term.get().terminalAddress(otherWithTerminal.get().getAddress());
                if (termAddr != null) {
                    cartRepo.setDestination(cartUuid, termAddr, currentStationId);
                    Optional<String> firstHop = resolveNextHopNode(currentStationId, termAddr);
                    if (plugin.isRoutingDebugEnabled()) {
                        plugin.getLogger().info("[Netro routing] no-destination rule: cart=" + cartUuid + " → set dest to terminal at " + otherWithTerminal.get().getName() + " " + termAddr);
                    }
                    return firstHop;
                }
            }
        }

        if (cartWasInDb) {
            cartRepo.deleteCart(cartUuid);
            onRemoved.run();
            if (plugin.isRoutingDebugEnabled()) {
                plugin.getLogger().info("[Netro routing] no-destination rule: no terminals anywhere → removed cart " + cartUuid);
            }
        } else {
            cartRepo.setDestination(cartUuid, null, currentStationId);
            if (plugin.isRoutingDebugEnabled()) {
                plugin.getLogger().info("[Netro routing] no-destination rule: cart not in DB, no terminals → added cart with no destination");
            }
        }
        return Optional.empty();
    }

    /** Returns the last N routing decisions for this cart (newest first), for querying why we sent it to a node or terminal. */
    public List<Map<String, Object>> getLastRoutingDecisions(String cartUuid, int limit) {
        return routingDecisionLogRepo.findLastByCart(cartUuid, limit);
    }

    /** Resolves which transfer node or terminal (node id) is the first hop from this station toward the given destination. Used by {@link #getNextHopNodeAtStation} (station detector path) and by {@link #shouldDivert} (node-level ENTRY). */
    public Optional<String> resolveNextHopNode(String fromStationId, String destination) {
        String[] parts = destination.split("\\.");
        if (parts.length == 5) {
            String stationAddress = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
            int terminalIdx = Integer.parseInt(parts[4]);
            return stationRepo.findByAddress(stationAddress)
                .flatMap(s -> nodeRepo.findTerminalByIndex(s.getId(), terminalIdx))
                .map(TransferNode::getId)
                .or(() -> nodeRepo.lookupNextHopNode(fromStationId, stationAddress));
        }
        return stationRepo.findByAddress(destination).flatMap(destStation -> {
            if (destStation.getId().equals(fromStationId))
                return nodeRepo.findAvailableTerminal(destStation.getId()).map(TransferNode::getId);
            return nodeRepo.lookupNextHopNode(fromStationId, destination);
        }).or(() -> nodeRepo.lookupNextHopNode(fromStationId, destination));
    }

    /** Whether the cart is held at this node (state "held", zone "node:"+nodeId). */
    public Optional<Boolean> isCartOutboundAtNode(String cartUuid, String nodeId) {
        Optional<Map<String, Object>> data = cartRepo.find(cartUuid);
        if (data.isEmpty()) return Optional.empty();
        String state = (String) data.get().get("state");
        String zone = (String) data.get().get("zone");
        boolean outbound = "held".equals(state) && ("node:" + nodeId).equals(zone);
        return Optional.of(outbound);
    }

    public boolean isDestinationStation(String stationId, String destination) {
        String stationAddress = destination.contains(".") && destination.split("\\.").length == 5
            ? destination.substring(0, destination.lastIndexOf('.'))
            : destination;
        return stationRepo.findByAddress(stationAddress)
            .map(s -> s.getId().equals(stationId))
            .orElse(false);
    }

    /**
     * Whether we should divert this cart into the siding at this node (fire DIVERT controller).
     * Used when ENTRY fires <b>at a node</b> — node-level "hold vs pass through" (mini junction).
     * The <b>station-level</b> "which terminal/transfer node at the station" decision is
     * intended to be driven by station detectors via {@link #getNextHopNodeAtStation} and
     * station controllers (TRANSFER/NOT_TRANSFER), not by ENTRY.
     * True if cart has destination, next hop uses this node's pair, and (destination is here or path not clear).
     */
    public boolean shouldDivert(String cartUuid, String nodeId) {
        Optional<TransferNode> nodeOpt = nodeRepo.findById(nodeId);
        if (nodeOpt.isEmpty()) return false;
        TransferNode node = nodeOpt.get();
        String pairedId = node.getPairedNodeId();
        if (pairedId == null) return false;
        Optional<Map<String, Object>> cartData = cartRepo.find(cartUuid);
        if (cartData.isEmpty()) return false;
        String dest = (String) cartData.get().get("destination_address");
        if (dest == null || dest.isEmpty()) return false;
        String fromStationId = node.getStationId();
        Optional<String> nextHop = resolveNextHopNode(fromStationId, dest);
        if (nextHop.isEmpty() || !nextHop.get().equals(pairedId)) return false;
        if (isDestinationStation(fromStationId, dest)) return true;
        return !canDispatch(cartUuid, nodeId, pairedId).canGo();
    }

    /**
     * Whether we should divert this cart into the junction siding (fire DIVERT controller).
     * Decision is made at ENTRY: order of arrival is who triggered ENTRY first. Divert only when
     * the junction has room and the way ahead is not clear (opposing traffic on the segment we would exit onto).
     * fromSide = "LEFT" or "RIGHT" (which side of the junction the cart entered from); convention LEFT = nodeA side, RIGHT = nodeB side.
     */
    public boolean shouldDivertJunction(String cartUuid, String junctionId, String fromSide) {
        if (junctionHeldCountRepo.getFreeSlotsForDirection(junctionId, fromSide != null ? fromSide : "LEFT") <= 0) return false;
        Optional<Junction> j = junctionRepo.findById(junctionId);
        if (j.isEmpty()) return false;
        String nodeA = j.get().getNodeAId();
        String nodeB = j.get().getNodeBId();
        if (nodeA == null || nodeB == null) return false;
        boolean wayAheadClear;
        if ("LEFT".equals(fromSide)) {
            wayAheadClear = cartRepo.findOpposingCarts(nodeA, nodeB, "A_TO_B").isEmpty();
        } else if ("RIGHT".equals(fromSide)) {
            wayAheadClear = cartRepo.findOpposingCarts(nodeA, nodeB, "B_TO_A").isEmpty();
        } else {
            wayAheadClear = cartRepo.findOpposingCarts(nodeA, nodeB, "A_TO_B").isEmpty()
                && cartRepo.findOpposingCarts(nodeA, nodeB, "B_TO_A").isEmpty();
        }
        return !wayAheadClear;
    }

    /** Whether we can release a cart from this junction (segment clear). Uses junction node_a_id, node_b_id for segment. */
    public boolean canReleaseFromJunction(String cartUuid, String junctionId) {
        Optional<Junction> j = junctionRepo.findById(junctionId);
        if (j.isEmpty()) return false;
        String nodeA = j.get().getNodeAId();
        String nodeB = j.get().getNodeBId();
        if (nodeA == null || nodeB == null) return false;
        return canDispatch(cartUuid, nodeA, nodeB).canGo() || canDispatch(cartUuid, nodeB, nodeA).canGo();
    }

    public void recalculatePressure(String stationId) {
        List<TransferNode> nodes = nodeRepo.findByStation(stationId);
        int freeInbound = 0, freeOutbound = 0;
        for (TransferNode node : nodes) {
            int free = nodeRepo.countFreeSlots(node.getId());
            freeInbound += free;
            freeOutbound += free;
        }
        cartRepo.upsertPressure(stationId, freeInbound, freeOutbound, freeInbound > 0);
    }

    public record RouteEntry(String destStationId, int cost, String firstHopNodeId) {}

    public record DispatchResult(boolean canGo, String reason, List<Junction> junctions) {
        public static DispatchResult clear(List<Junction> junctions) { return new DispatchResult(true, null, junctions); }
        public static DispatchResult blocked(String reason) { return new DispatchResult(false, reason, List.of()); }
    }
}
