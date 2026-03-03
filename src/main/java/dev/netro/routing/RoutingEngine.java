package dev.netro.routing;

import dev.netro.NetroPlugin;
import dev.netro.database.CartHeldCountRepository;
import dev.netro.database.CartRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Station;
import dev.netro.model.TransferNode;
import dev.netro.util.AddressHelper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Core routing: Dijkstra, dispatch checks, reroute.
 * No collision detection: dispatch is only blocked when destination node is full or invalid.
 */
public class RoutingEngine {

    private static final long REROUTE_TIMEOUT_MS = 30_000;

    private final NetroPlugin plugin;
    private final StationRepository stationRepo;
    private final TransferNodeRepository nodeRepo;
    private final CartRepository cartRepo;
    private final CartHeldCountRepository heldCountRepo;

    public RoutingEngine(NetroPlugin plugin) {
        this.plugin = plugin;
        this.stationRepo = new StationRepository(plugin.getDatabase());
        this.nodeRepo = new TransferNodeRepository(plugin.getDatabase());
        this.cartRepo = new CartRepository(plugin.getDatabase());
        this.heldCountRepo = new CartHeldCountRepository(plugin.getDatabase());
    }

    /** Edge cost: 1 hop + geographical distance (blocks/100) so shortest path prefers physically closer route when network loops. Same-world only. */
    private int edgeCostBetweenStations(String stationAId, String stationBId) {
        Optional<Station> a = stationRepo.findById(stationAId);
        Optional<Station> b = stationRepo.findById(stationBId);
        if (a.isEmpty() || b.isEmpty() || !a.get().getWorld().equals(b.get().getWorld())) return 1;
        double dx = a.get().getSignX() - b.get().getSignX();
        double dy = a.get().getSignY() - b.get().getSignY();
        double dz = a.get().getSignZ() - b.get().getSignZ();
        double blocks = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Math.max(1, 1 + (int) (blocks / 100));
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
                    int edgeCost = edgeCostBetweenStations(current, neighborStationId);
                    int newDist = currentDist + edgeCost;
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

    /** No collision detection: only blocks when nodes are invalid or destination is full. */
    public DispatchResult canDispatch(String cartUuid, String fromNodeId, String toNodeId) {
        TransferNode fromNode = nodeRepo.findById(fromNodeId).orElse(null);
        TransferNode toNode   = nodeRepo.findById(toNodeId).orElse(null);
        if (fromNode == null || toNode == null) return DispatchResult.blocked("Invalid nodes");
        int freeSlots = nodeRepo.countFreeSlots(toNodeId);
        if (freeSlots == 0) return DispatchResult.blocked("Destination transfer node is full");
        return DispatchResult.clear();
    }

    /**
     * Called when a cart is destroyed or removed. Clears held state and decrements held count if it was held at a node.
     */
    public void onCartRemoved(String cartUuid) {
        Optional<Map<String, Object>> cartData = cartRepo.find(cartUuid);
        if (cartData.isPresent()) {
            String zone = (String) cartData.get().get("zone");
            if (zone != null && zone.startsWith("node:")) {
                String nodeId = CartRepository.nodeIdFromZone(zone);
                if (nodeId != null) heldCountRepo.decrement(nodeId);
            }
        }
        cartRepo.clearHeld(cartUuid);
    }

    public Optional<String> checkReroute(String cartUuid, String currentStationId, String destination, String blockedNodeId) {
        Optional<Map<String,Object>> cartData = cartRepo.find(cartUuid);
        if (cartData.isEmpty()) return Optional.empty();
        long enteredAt = ((Number) cartData.get().get("entered_zone_at")).longValue();
        if (System.currentTimeMillis() - enteredAt < REROUTE_TIMEOUT_MS) return Optional.empty();
        String alternateNodeId = resolveNextHopNode(currentStationId, destination)
            .filter(id -> !id.equals(blockedNodeId))
            .orElse(null);
        return Optional.ofNullable(alternateNodeId);
    }

    /**
     * Returns the transfer node or terminal node id that a cart should go to at this station
     * (first hop toward its destination). Used by station detectors and station controllers
     * (TRANSFER/NOT_TRANSFER) for the "which branch at the station" decision.
     */
    public Optional<String> getNextHopNodeAtStation(String cartUuid, String stationId) {
        Optional<Map<String, Object>> cartData = cartRepo.find(cartUuid);
        if (cartData.isEmpty()) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[Netro routing] station=" + stationId + " cart=" + cartUuid + " no cart data (cart not yet in DB) → no next hop");
            }
            return Optional.empty();
        }
        String dest = (String) cartData.get().get("destination_address");
        if (dest == null || dest.isEmpty()) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[Netro routing] station=" + stationId + " cart=" + cartUuid + " no destination → no next hop (caller may apply no-destination rule)");
            }
            return Optional.empty();
        }
        Optional<String> nextHop = resolveNextHopNode(stationId, dest);
        if (nextHop.isEmpty()) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[Netro routing] station=" + stationId + " cart=" + cartUuid + " dest=" + dest + " resolveNextHop=empty (no route or dest unknown) → no next hop");
            }
            return Optional.empty();
        }
        String nextHopId = nextHop.get();
        Optional<TransferNode> nodeOpt = nodeRepo.findById(nextHopId);
        if (nodeOpt.isEmpty()) return Optional.of(nextHopId);
        String pairedId = nodeOpt.get().getPairedNodeId();
        DispatchResult dispatchResult = pairedId != null ? canDispatch(cartUuid, nextHopId, pairedId) : DispatchResult.clear();
        String chosenNodeId;
        if (pairedId != null && !dispatchResult.canGo()) {
            // Blocked: send cart to terminal to wait; we still return chosen so station controllers get TRA (terminal) and NOT_TRA (others).
            chosenNodeId = nodeRepo.findAvailableTerminal(stationId).map(TransferNode::getId).orElse(nextHopId);
        } else {
            chosenNodeId = nextHopId;
        }
        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info("[Netro routing] cart=" + cartUuid + " station=" + stationId
                + " finalDest=" + dest + " localDest=" + chosenNodeId
                + " preferred=" + nextHopId + " canDispatch=" + dispatchResult.canGo()
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
                if (plugin.isDebugEnabled()) {
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
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info("[Netro routing] no-destination rule: cart=" + cartUuid + " → set dest to terminal at " + otherWithTerminal.get().getName() + " " + termAddr);
                    }
                    return firstHop;
                }
            }
        }

        if (cartWasInDb) {
            cartRepo.deleteCart(cartUuid);
            onRemoved.run();
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[Netro routing] no-destination rule: no terminals anywhere → removed cart " + cartUuid);
            }
        } else {
            cartRepo.setDestination(cartUuid, null, currentStationId);
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[Netro routing] no-destination rule: cart not in DB, no terminals → added cart with no destination");
            }
        }
        return Optional.empty();
    }

    /** Resolves which transfer node or terminal (node id) is the first hop from this station toward the given destination. Uses on-the-fly shortest path (no stored routing table). */
    public Optional<String> resolveNextHopNode(String fromStationId, String destination) {
        Optional<AddressHelper.ParsedDestination> parsed = AddressHelper.parseDestination(destination);
        if (parsed.isEmpty()) return Optional.empty();
        Optional<Station> destStationOpt = stationRepo.findByAddress(parsed.get().stationAddress());
        if (destStationOpt.isEmpty()) return Optional.empty();
        Integer terminalIndex = parsed.get().terminalIndex();
        String destStationId = destStationOpt.get().getId();

        if (fromStationId.equals(destStationId)) {
            if (terminalIndex != null)
                return nodeRepo.findTerminalByIndex(destStationId, terminalIndex).map(TransferNode::getId);
            return nodeRepo.findAvailableTerminal(destStationId).map(TransferNode::getId);
        }

        Map<String, RouteEntry> paths = dijkstra(fromStationId);
        RouteEntry entry = paths.get(destStationId);
        if (entry == null) return Optional.empty();
        return Optional.of(entry.firstHopNodeId());
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
        Optional<AddressHelper.ParsedDestination> parsed = AddressHelper.parseDestination(destination);
        if (parsed.isEmpty()) return false;
        return stationRepo.findByAddress(parsed.get().stationAddress())
            .map(s -> s.getId().equals(stationId))
            .orElse(false);
    }

    public record RouteEntry(String destStationId, int cost, String firstHopNodeId) {}

    public record DispatchResult(boolean canGo, String reason) {
        public static DispatchResult clear() { return new DispatchResult(true, null); }
        public static DispatchResult blocked(String reason) { return new DispatchResult(false, reason); }
    }
}
