package dev.netro.routing;

import dev.netro.NetroPlugin;
import dev.netro.database.CartHeldCountRepository;
import dev.netro.database.CartRepository;
import dev.netro.database.RouteCacheRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodePortalRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.gui.RulesMainHolder;
import dev.netro.model.Station;
import dev.netro.model.TransferNode;
import dev.netro.util.AddressHelper;
import dev.netro.util.DimensionHelper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.ArrayDeque;

/**
 * Core routing: Dijkstra, dispatch checks, reroute.
 * No collision detection: dispatch is only blocked when destination node is full or invalid.
 * Route cache uses stale-while-revalidate: expired entries are still returned; refresh runs in background (one per worker tick).
 */
public class RoutingEngine {

    private static final long REROUTE_TIMEOUT_MS = 30_000;
    /** Jitter range in world ticks (5 min at 20 tps) so expiry times are spread. */
    private static final long ROUTE_CACHE_JITTER_TICKS = 20L * 60 * 5;
    /** Legacy: updated_at values above this were stored as System.currentTimeMillis(); treat as expired. */
    private static final long LEGACY_MS_THRESHOLD = 1_000_000_000_000L;

    private final NetroPlugin plugin;
    private final StationRepository stationRepo;
    private final TransferNodeRepository nodeRepo;
    private final TransferNodePortalRepository portalRepo;
    private final CartRepository cartRepo;
    private final CartHeldCountRepository heldCountRepo;
    private final RouteCacheRepository routeCacheRepo;

    /** Queue of (fromStationId, destStationId) to refresh in background. Deduplicated by pendingRefreshKeys. */
    private final Queue<RouteRefreshKey> refreshQueue = new ConcurrentLinkedQueue<>();
    private final Set<RouteRefreshKey> pendingRefreshKeys = ConcurrentHashMap.newKeySet();

    private record RouteRefreshKey(String fromStationId, String destStationId) {}

    /** Result when the requested terminal is occupied: display text for the recommended alternative (or "No alternative terminal available."). */
    public static record TerminalOccupiedResult(String alternativeDisplay) {}

    /**
     * If the destination is a terminal and it is occupied, returns a result with the recommended closest alternative
     * (same-station free terminal first, then nearest other station with a free terminal). Otherwise returns empty.
     */
    public Optional<TerminalOccupiedResult> checkTerminalOccupiedAndGetAlternative(String destinationAddress) {
        if (destinationAddress == null || destinationAddress.isBlank()) return Optional.empty();
        Optional<AddressHelper.ParsedDestination> parsed = AddressHelper.parseDestination(destinationAddress);
        if (parsed.isEmpty() || parsed.get().terminalIndex() == null) return Optional.empty();
        String stationAddress = parsed.get().stationAddress();
        int terminalIndex = parsed.get().terminalIndex();
        Optional<Station> destStationOpt = stationRepo.findByAddress(stationAddress);
        if (destStationOpt.isEmpty()) return Optional.empty();
        String destStationId = destStationOpt.get().getId();
        Optional<TransferNode> nodeOpt = nodeRepo.findTerminalByIndex(destStationId, terminalIndex);
        if (nodeOpt.isEmpty()) return Optional.empty();
        String nodeId = nodeOpt.get().getId();
        if (heldCountRepo.getHeldCount(nodeId) == 0) return Optional.empty();

        String alternativeDisplay = findClosestAlternativeTerminalDisplay(destStationId, nodeId, destStationOpt.get());
        return Optional.of(new TerminalOccupiedResult(alternativeDisplay));
    }

    private String findClosestAlternativeTerminalDisplay(String destStationId, String occupiedNodeId, Station destStation) {
        List<String> freeAtSame = nodeRepo.findTerminalNodeIdsWithFreeSlot(destStationId, occupiedNodeId);
        if (!freeAtSame.isEmpty()) {
            Optional<TransferNode> altNode = nodeRepo.findById(freeAtSame.get(0));
            String altAddr = altNode.flatMap(n -> Optional.ofNullable(n.terminalAddress(destStation.getAddress()))).orElse(null);
            if (altAddr != null) return RulesMainHolder.formatDestinationId(altAddr, stationRepo, nodeRepo);
        }
        List<String> otherStationIds = new ArrayList<>(nodeRepo.findStationIdsWithAvailableTerminal());
        otherStationIds.remove(destStationId);
        if (otherStationIds.isEmpty()) return "No alternative terminal available.";
        List<Station> others = stationRepo.findByIds(otherStationIds);
        String destWorld = destStation.getWorld();
        int dx = destStation.getSignX(), dy = destStation.getSignY(), dz = destStation.getSignZ();
        Optional<Station> nearest = others.stream()
            .filter(s -> destWorld.equals(s.getWorld()))
            .min(Comparator.comparingLong(s -> sq(s.getSignX() - dx) + sq(s.getSignY() - dy) + sq(s.getSignZ() - dz)));
        if (nearest.isEmpty()) nearest = others.stream().min(Comparator.comparingLong(s -> sq(s.getSignX() - dx) + sq(s.getSignY() - dy) + sq(s.getSignZ() - dz)));
        if (nearest.isEmpty()) return "No alternative terminal available.";
        Station altStation = nearest.get();
        List<String> free = nodeRepo.findTerminalNodeIdsWithFreeSlot(altStation.getId(), null);
        if (free.isEmpty()) return "No alternative terminal available.";
        Optional<TransferNode> altNode = nodeRepo.findById(free.get(0));
        String altAddr = altNode.flatMap(n -> Optional.ofNullable(n.terminalAddress(altStation.getAddress()))).orElse(null);
        if (altAddr == null) return "No alternative terminal available.";
        return RulesMainHolder.formatDestinationId(altAddr, stationRepo, nodeRepo);
    }

    private static long sq(int n) { return (long) n * n; }

    public RoutingEngine(NetroPlugin plugin) {
        this.plugin = plugin;
        this.stationRepo = new StationRepository(plugin.getDatabase());
        this.nodeRepo = new TransferNodeRepository(plugin.getDatabase());
        this.portalRepo = new TransferNodePortalRepository(plugin.getDatabase());
        this.cartRepo = new CartRepository(plugin.getDatabase());
        this.heldCountRepo = new CartHeldCountRepository(plugin.getDatabase());
        this.routeCacheRepo = new RouteCacheRepository(plugin.getDatabase());
    }

    /** Edge cost: 1 hop + experienced blocks (1 block traveled = 1 cost; same dimension = raw distance, no Nether×8). */
    private int edgeCostBetweenStations(String stationAId, String stationBId) {
        Optional<Station> a = stationRepo.findById(stationAId);
        Optional<Station> b = stationRepo.findById(stationBId);
        if (a.isEmpty() || b.isEmpty()) return 1;
        int dimA = a.get().getDimension();
        int dimB = b.get().getDimension();
        double blocks = (dimA == dimB)
            ? horizontalDistance(a.get().getSignX(), a.get().getSignZ(), b.get().getSignX(), b.get().getSignZ())
            : DimensionHelper.overworldEquivalentBlocks(a.get(), b.get(), dimA, dimB);
        return Math.max(1, 1 + (int) Math.round(blocks));
    }

    /**
     * When both nodes have portal links, cost = experienced blocks: each block traveled (OW or Nether) = 1 cost.
     * Path: node A → portal A (same dim) → [other dim] → portal B (same dim) → node B.
     * We do not scale Nether by 8 in cost — the cart experiences 1 block in Nether = 1 cost (even though that equals 8 OW blocks).
     * - OW–OW: legA (OW) + legNether (Nether) + legB (OW).
     * - Nether–Nether: legA (Nether) + legOW (OW) + legB (Nether).
     * - Mixed: leg in A's dim + legNether (Nether) + leg in B's dim.
     * Node position = station sign (transfer nodes have no stored coords).
     * Returns empty if required centroids are missing (routing then uses station-to-station cost).
     */
    private Optional<Integer> portalLinkCostBetweenPair(String stationAId, String nodeAId, String stationBId, String nodeBId) {
        Optional<Station> aOpt = stationRepo.findById(stationAId);
        Optional<Station> bOpt = stationRepo.findById(stationBId);
        if (aOpt.isEmpty() || bOpt.isEmpty()) return Optional.empty();
        Station a = aOpt.get();
        Station b = bOpt.get();
        int dimA = a.getDimension();
        int dimB = b.getDimension();

        TransferNodePortalRepository.Centroid sameA = portalRepo.getCentroid(nodeAId, TransferNodePortalRepository.SIDE_SAME_DIMENSION);
        TransferNodePortalRepository.Centroid sameB = portalRepo.getCentroid(nodeBId, TransferNodePortalRepository.SIDE_SAME_DIMENSION);

        if (dimA == DimensionHelper.DIMENSION_OVERWORLD && dimB == DimensionHelper.DIMENSION_OVERWORLD) {
            TransferNodePortalRepository.Centroid otherA = portalRepo.getCentroid(nodeAId, TransferNodePortalRepository.SIDE_OTHER_DIMENSION);
            TransferNodePortalRepository.Centroid otherB = portalRepo.getCentroid(nodeBId, TransferNodePortalRepository.SIDE_OTHER_DIMENSION);
            if (sameA == null || sameB == null || otherA == null || otherB == null) return Optional.empty();
            double legA = horizontalDistance(a.getSignX(), a.getSignZ(), sameA.x(), sameA.z());
            double legNether = horizontalDistance(otherA.x(), otherA.z(), otherB.x(), otherB.z());
            double legB = horizontalDistance(sameB.x(), sameB.z(), b.getSignX(), b.getSignZ());
            double blocks = legA + legNether + legB;
            return Optional.of(Math.max(1, 1 + (int) Math.round(blocks)));
        }
        if (dimA == DimensionHelper.DIMENSION_NETHER && dimB == DimensionHelper.DIMENSION_NETHER) {
            TransferNodePortalRepository.Centroid otherA = portalRepo.getCentroid(nodeAId, TransferNodePortalRepository.SIDE_OTHER_DIMENSION);
            TransferNodePortalRepository.Centroid otherB = portalRepo.getCentroid(nodeBId, TransferNodePortalRepository.SIDE_OTHER_DIMENSION);
            if (sameA == null || sameB == null || otherA == null || otherB == null) return Optional.empty();
            double legA = horizontalDistance(a.getSignX(), a.getSignZ(), sameA.x(), sameA.z());
            double legOW = horizontalDistance(otherA.x(), otherA.z(), otherB.x(), otherB.z());
            double legB = horizontalDistance(sameB.x(), sameB.z(), b.getSignX(), b.getSignZ());
            double blocks = legA + legOW + legB;
            return Optional.of(Math.max(1, 1 + (int) Math.round(blocks)));
        }

        // Mixed OW–Nether
        TransferNodePortalRepository.Centroid otherA = portalRepo.getCentroid(nodeAId, TransferNodePortalRepository.SIDE_OTHER_DIMENSION);
        TransferNodePortalRepository.Centroid otherB = portalRepo.getCentroid(nodeBId, TransferNodePortalRepository.SIDE_OTHER_DIMENSION);
        if (sameA == null || sameB == null || otherA == null || otherB == null) return Optional.empty();
        double distA = horizontalDistance(a.getSignX(), a.getSignZ(), sameA.x(), sameA.z());
        double distB = horizontalDistance(sameB.x(), sameB.z(), b.getSignX(), b.getSignZ());
        double legNether = dimA == DimensionHelper.DIMENSION_NETHER
            ? horizontalDistance(sameA.x(), sameA.z(), otherB.x(), otherB.z())
            : horizontalDistance(otherA.x(), otherA.z(), sameB.x(), sameB.z());
        double blocks = distA + legNether + distB;
        return Optional.of(Math.max(1, 1 + (int) Math.round(blocks)));
    }

    private static double horizontalDistance(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * When destStationId is non-null and has a valid 6-part (2D) address, adds address-distance cost
     * so pathfinding prefers hops that move toward the destination in hierarchy (mainnet → cluster → localnet).
     * Pairs that connect different clusters/mainnets are used naturally without designating "default gateways".
     */
    private int addressCostTowardDest(String neighborStationId, String destAddress) {
        if (destAddress == null) return 0;
        Optional<Station> neighbor = stationRepo.findById(neighborStationId);
        if (neighbor.isEmpty()) return 0;
        String neighborAddr = neighbor.get().getAddress();
        if (neighborAddr == null) return 0;
        int d = AddressHelper.addressDistance(neighborAddr, destAddress);
        return d == Integer.MAX_VALUE ? 0 : d;
    }

    /** A* heuristic: address distance from station to destination (0 if no dest or invalid address). Admissible for our cost function. */
    private int heuristic(String stationId, String destAddress) {
        if (destAddress == null) return 0;
        return addressCostTowardDest(stationId, destAddress);
    }

    private DijkstraResult dijkstra(String startStationId) {
        return dijkstra(startStationId, null, null);
    }

    private DijkstraResult dijkstra(String startStationId, String destStationId) {
        return dijkstra(startStationId, destStationId, null);
    }

    /** Minimum cost from one station to another (OW-equivalent blocks). Returns Integer.MAX_VALUE if unreachable. Caches result and downstream hops. */
    private int costFromTo(String fromStationId, String toStationId) {
        if (fromStationId.equals(toStationId)) return 0;
        Optional<RouteCacheRepository.CachedRoute> cached = routeCacheRepo.get(fromStationId, toStationId);
        if (cached.isPresent()) return cached.get().cost();
        DijkstraResult dr = dijkstra(fromStationId, toStationId, null);
        RouteEntry e = dr.paths().get(toStationId);
        if (e != null) {
            fillDownstreamCache(dr, fromStationId, toStationId, getReferenceWorldTick());
            return e.cost();
        }
        return Integer.MAX_VALUE;
    }

    /** Current Minecraft world full time (ticks) from the first loaded world, or 0 if none. Used so cache TTL is in-game time, not real time. */
    private long getReferenceWorldTick() {
        if (plugin.getServer().getWorlds().isEmpty()) return 0L;
        return plugin.getServer().getWorlds().get(0).getFullTime();
    }

    /** Backtrack path from dest to start and save cache (from_station, dest_station, first_hop, cost) for each station on path except dest. nowTick = world full time (ticks) for updated_at. */
    private void fillDownstreamCache(DijkstraResult dr, String startStationId, String destStationId, long nowTick) {
        Map<String, String> prev = dr.prevStation();
        Map<String, Map<String, String>> edgeFromTo = dr.edgeFromTo();
        if (prev == null || edgeFromTo == null) return;
        RouteEntry destEntry = dr.paths().get(destStationId);
        if (destEntry == null) return;
        int totalCost = destEntry.cost();
        List<String> pathStations = new ArrayList<>();
        for (String s = destStationId; s != null; s = prev.get(s))
            pathStations.add(0, s);
        Map<String, RouteEntry> paths = dr.paths();
        for (int i = 0; i < pathStations.size() - 1; i++) {
            String fromStation = pathStations.get(i);
            if (fromStation.equals(destStationId)) continue;
            String nextStation = pathStations.get(i + 1);
            Map<String, String> edges = edgeFromTo.get(fromStation);
            if (edges == null) continue;
            String firstHopNodeId = edges.get(nextStation);
            if (firstHopNodeId == null) continue;
            RouteEntry fromEntry = paths.get(fromStation);
            int costFromHereToDest = fromEntry == null ? totalCost : totalCost - fromEntry.cost();
            routeCacheRepo.save(fromStation, destStationId, firstHopNodeId, costFromHereToDest, nowTick);
        }
        // Reverse path: same cost, each station gets cache toward start (paired node = first hop back).
        for (int i = pathStations.size() - 1; i >= 1; i--) {
            String fromStation = pathStations.get(i);
            if (fromStation.equals(startStationId)) continue;
            String nextStation = pathStations.get(i - 1);
            Map<String, String> edges = edgeFromTo.get(nextStation);
            if (edges == null) continue;
            String nodeIdAtNext = edges.get(fromStation);
            if (nodeIdAtNext == null) continue;
            String firstHopNodeIdBack = nodeRepo.findById(nodeIdAtNext).map(TransferNode::getPairedNodeId).orElse(null);
            if (firstHopNodeIdBack == null) continue;
            RouteEntry fromEntry = paths.get(fromStation);
            int costFromHereToStart = fromStation.equals(destStationId) ? totalCost : (fromEntry == null ? totalCost : totalCost - fromEntry.cost());
            routeCacheRepo.save(fromStation, startStationId, firstHopNodeIdBack, costFromHereToStart, nowTick);
        }
    }

    /** Per-pair jitter in ticks so cached routes don't all expire at once. */
    private static long effectiveTtlTicksWithJitter(long ttlTicks, String fromStationId, String destStationId) {
        if (ttlTicks <= 0) return ttlTicks;
        int h = (fromStationId + ":" + destStationId).hashCode();
        long jitter = (h & 0x7FFF_FFFFL) % (ROUTE_CACHE_JITTER_TICKS + 1);
        return ttlTicks + jitter;
    }

    /** Schedule a background refresh for this route. Deduplicated; safe to call from any thread. */
    public void scheduleRouteRefresh(String fromStationId, String destStationId) {
        if (fromStationId == null || destStationId == null) return;
        RouteRefreshKey key = new RouteRefreshKey(fromStationId, destStationId);
        if (pendingRefreshKeys.add(key)) {
            refreshQueue.add(key);
        }
    }

    /** Clear the refresh queue and pending set (e.g. after clearing all cache so queued refreshes don't repopulate). */
    public void clearRouteRefreshQueue() {
        refreshQueue.clear();
        pendingRefreshKeys.clear();
    }

    /** Enqueue all distinct (from, dest) pairs currently in the cache for background refresh. */
    public void scheduleRefreshForAllCachedPairs() {
        for (RouteCacheRepository.RouteCachePair pair : routeCacheRepo.listDistinctPairs()) {
            scheduleRouteRefresh(pair.fromStationId(), pair.destStationId());
        }
    }

    /** Build graph of which stations are connected (via ready, paired transfer nodes), then enqueue every reachable (from, dest) pair for cache build/refresh. */
    public void scheduleRefreshForAllReachablePairs() {
        Map<String, Set<String>> neighbors = new HashMap<>();
        for (Station s : stationRepo.findAll()) {
            String stationId = s.getId();
            Set<String> adj = new HashSet<>();
            for (TransferNode node : nodeRepo.findByStation(stationId).stream()
                .filter(n -> n.isReady() && n.isPaired())
                .collect(Collectors.toList())) {
                nodeRepo.findById(node.getPairedNodeId()).ifPresent(paired -> adj.add(paired.getStationId()));
            }
            if (!adj.isEmpty()) neighbors.put(stationId, adj);
        }
        List<RouteCacheRepository.RouteCachePair> pairs = new ArrayList<>();
        for (String fromId : neighbors.keySet()) {
            Set<String> reachable = new HashSet<>();
            Queue<String> q = new ArrayDeque<>();
            q.add(fromId);
            reachable.add(fromId);
            while (!q.isEmpty()) {
                String cur = q.poll();
                for (String next : neighbors.getOrDefault(cur, Set.of())) {
                    if (reachable.add(next)) q.add(next);
                }
            }
            for (String destId : reachable) {
                if (!fromId.equals(destId)) pairs.add(new RouteCacheRepository.RouteCachePair(fromId, destId));
            }
        }
        for (RouteCacheRepository.RouteCachePair pair : pairs) {
            scheduleRouteRefresh(pair.fromStationId(), pair.destStationId());
        }
    }

    /** Process one queued route refresh (run from worker task). Runs one Dijkstra and fillDownstreamCache to refresh cache and downstream/reverse entries. */
    public void processOneRouteRefresh() {
        RouteRefreshKey key = refreshQueue.poll();
        if (key == null) return;
        pendingRefreshKeys.remove(key);
        refreshRouteCache(key.fromStationId(), key.destStationId());
    }

    /** Run full path and fill cache for this pair. Recomputes path and overwrites cache (including any expired downstream entries on this path). */
    private void refreshRouteCache(String fromStationId, String destStationId) {
        DijkstraResult dr = dijkstra(fromStationId, destStationId, null);
        if (dr.paths().get(destStationId) != null) {
            fillDownstreamCache(dr, fromStationId, destStationId, getReferenceWorldTick());
        }
    }

    private DijkstraResult dijkstra(String startStationId, String destStationId, List<CandidateHop> outCandidatesFromStart) {
        final String destAddr;
        if (destStationId != null) {
            String a = stationRepo.findById(destStationId).map(Station::getAddress).orElse(null);
            destAddr = (a != null && AddressHelper.parseStationAddressTiers(a) == null) ? null : a;
        } else {
            destAddr = null;
        }

        Map<String, Integer> dist = new HashMap<>();
        Map<String, RouteEntry> result = new HashMap<>();
        Map<String, String> prevStation = new HashMap<>();
        Map<String, Map<String, String>> edgeFromTo = new HashMap<>();

        PriorityQueue<String> pq = new PriorityQueue<>(
            Comparator.comparingInt(id -> dist.getOrDefault(id, Integer.MAX_VALUE) + heuristic(id, destAddr)));
        dist.put(startStationId, 0);
        pq.add(startStationId);

        while (!pq.isEmpty()) {
            String current = pq.poll();
            int currentDist = dist.getOrDefault(current, Integer.MAX_VALUE);
            if (destStationId != null && current.equals(destStationId)) {
                return new DijkstraResult(result, prevStation, edgeFromTo);
            }

            List<TransferNode> nodes = nodeRepo.findByStation(current).stream()
                .filter(n -> n.isReady() && n.isPaired())
                .collect(Collectors.toList());

            for (TransferNode node : nodes) {
                nodeRepo.findById(node.getPairedNodeId()).ifPresent(paired -> {
                    String neighborStationId = paired.getStationId();
                    Optional<Integer> portalCostOpt = portalLinkCostBetweenPair(current, node.getId(), neighborStationId, paired.getId());
                    int edgeCostRaw = portalCostOpt.orElseGet(() -> edgeCostBetweenStations(current, neighborStationId));
                    int edgeCost = edgeCostRaw;
                    if (destAddr != null)
                        edgeCost += addressCostTowardDest(neighborStationId, destAddr);
                    if (outCandidatesFromStart != null && current.equals(startStationId))
                        outCandidatesFromStart.add(new CandidateHop(node.getId(), neighborStationId, edgeCost, portalCostOpt.isPresent()));

                    if (destStationId != null && !neighborStationId.equals(destStationId)) {
                        Optional<RouteCacheRepository.CachedRoute> cached = routeCacheRepo.get(neighborStationId, destStationId);
                        if (cached.isPresent()) {
                            int totalCostViaB = currentDist + edgeCostRaw + cached.get().cost();
                            if (totalCostViaB < dist.getOrDefault(destStationId, Integer.MAX_VALUE)) {
                                String firstHopFromStart = current.equals(startStationId)
                                    ? node.getId()
                                    : result.getOrDefault(current, new RouteEntry(null, 0, node.getId())).firstHopNodeId();
                                result.put(destStationId, new RouteEntry(destStationId, totalCostViaB, firstHopFromStart));
                                prevStation.put(destStationId, neighborStationId);
                                dist.put(destStationId, totalCostViaB);
                                dist.put(neighborStationId, currentDist + edgeCostRaw);
                                result.put(neighborStationId, new RouteEntry(neighborStationId, currentDist + edgeCostRaw, firstHopFromStart));
                                prevStation.put(neighborStationId, current);
                                edgeFromTo.computeIfAbsent(current, k -> new HashMap<>()).put(neighborStationId, node.getId());
                                edgeFromTo.computeIfAbsent(neighborStationId, k -> new HashMap<>()).put(destStationId, cached.get().firstHopNodeId());
                                pq.add(destStationId);
                            }
                            return;
                        }
                    }

                    int newDist = currentDist + edgeCost;
                    if (newDist < dist.getOrDefault(neighborStationId, Integer.MAX_VALUE)) {
                        dist.put(neighborStationId, newDist);
                        String firstHopNodeId = current.equals(startStationId)
                            ? node.getId()
                            : result.getOrDefault(current, new RouteEntry(null, 0, node.getId())).firstHopNodeId();
                        result.put(neighborStationId, new RouteEntry(neighborStationId, newDist, firstHopNodeId));
                        prevStation.put(neighborStationId, current);
                        edgeFromTo.computeIfAbsent(current, k -> new HashMap<>()).put(neighborStationId, node.getId());
                        pq.add(neighborStationId);
                    }
                });
            }
        }
        return new DijkstraResult(result, prevStation, edgeFromTo);
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
     * Called when a cart is destroyed or removed. Clears held state and, if it was held at a terminal,
     * cancels the held timeout and sets held count to 0.
     */
    public void onCartRemoved(String cartUuid) {
        Optional<Map<String, Object>> cartData = cartRepo.find(cartUuid);
        if (cartData.isPresent()) {
            String zone = (String) cartData.get().get("zone");
            if (zone != null && zone.startsWith("node:")) {
                String nodeId = CartRepository.nodeIdFromZone(zone);
                if (nodeId != null) {
                    plugin.getDetectorRailHandler().cancelAllTerminalTasksForNode(nodeId);
                    heldCountRepo.setHeldCount(nodeId, 0);
                }
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
        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info("[Netro routing] cart=" + cartUuid + " station=" + stationId + " dest=" + dest + " nextHop=" + formatNodeIdForLog(nextHop.get()));
        }
        return nextHop;
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

        java.util.List<String> freeAtCurrent = nodeRepo.findTerminalNodeIdsWithFreeSlot(currentStationId, null);
        if (!freeAtCurrent.isEmpty()) {
            String nodeId = freeAtCurrent.get(0);
            Optional<Station> st = stationRepo.findById(currentStationId);
            Optional<TransferNode> node = nodeRepo.findById(nodeId);
            String termAddr = node.flatMap(n -> st.map(s -> n.terminalAddress(s.getAddress()))).orElse(null);
            if (termAddr != null) {
                cartRepo.setDestination(cartUuid, termAddr, currentStationId);
                plugin.getDetectorRailHandler().recheckTerminalReleaseForCart(cartUuid);
                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().info("[Netro routing] no-destination rule: cart=" + cartUuid + " → set dest to terminal at current station " + termAddr);
                }
                return Optional.of(nodeId);
            }
        }

        java.util.List<String> otherStationIds = new java.util.ArrayList<>(nodeRepo.findStationIdsWithAvailableTerminal());
        otherStationIds.remove(currentStationId);
        for (String sid : otherStationIds) {
            java.util.List<String> free = nodeRepo.findTerminalNodeIdsWithFreeSlot(sid, null);
            if (free.isEmpty()) continue;
            Optional<TransferNode> term = nodeRepo.findById(free.get(0));
            Optional<Station> station = stationRepo.findById(sid);
            String termAddr = term.flatMap(t -> station.map(s -> t.terminalAddress(s.getAddress()))).orElse(null);
            if (termAddr != null) {
                cartRepo.setDestination(cartUuid, termAddr, currentStationId);
                plugin.getDetectorRailHandler().recheckTerminalReleaseForCart(cartUuid);
                Optional<String> firstHop = resolveNextHopNode(currentStationId, termAddr);
                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().info("[Netro routing] no-destination rule: cart=" + cartUuid + " → set dest to terminal at " + station.get().getName() + " " + termAddr);
                }
                return firstHop;
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
            plugin.getDetectorRailHandler().recheckTerminalReleaseForCart(cartUuid);
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[Netro routing] no-destination rule: cart not in DB, no terminals → added cart with no destination");
            }
        }
        return Optional.empty();
    }

    /**
     * If the cart has a destination but there is no route from the current station to it, sets the cart's
     * destination to the nearest available terminal (current station first, then any other) and returns the
     * old destination address so the caller can notify the player. Returns empty if the cart has no destination,
     * the destination is invalid, the cart is already at the destination station, or a route exists.
     * Uses route cache when possible to avoid Dijkstra on every detector pass.
     */
    public Optional<String> handleUnreachableAndRedirectToNearestTerminal(String cartUuid, String currentStationId) {
        return handleUnreachableAndRedirectToNearestTerminal(cartUuid, currentStationId, cartRepo.find(cartUuid));
    }

    /**
     * Overload that accepts pre-fetched cart data to avoid redundant find when caller already has it.
     */
    public Optional<String> handleUnreachableAndRedirectToNearestTerminal(String cartUuid, String currentStationId, Optional<Map<String, Object>> cartDataOpt) {
        if (cartDataOpt.isEmpty()) return Optional.empty();
        String dest = (String) cartDataOpt.get().get("destination_address");
        if (dest == null || dest.isEmpty()) return Optional.empty();

        Optional<AddressHelper.ParsedDestination> parsed = AddressHelper.parseDestination(dest);
        if (parsed.isEmpty()) return Optional.empty();
        Optional<Station> destStationOpt = stationRepo.findByAddress(parsed.get().stationAddress());
        if (destStationOpt.isEmpty()) return Optional.empty();
        String destStationId = destStationOpt.get().getId();

        if (currentStationId.equals(destStationId)) return Optional.empty();

        // Use route cache first: if we have a cached route, destination is reachable → no redirect.
        if (routeCacheRepo.get(currentStationId, destStationId).isPresent()) return Optional.empty();

        DijkstraResult dr = dijkstra(currentStationId, destStationId);
        if (dr.paths().containsKey(destStationId)) return Optional.empty();

        String oldDest = dest;
        java.util.List<String> freeAtCurrent = nodeRepo.findTerminalNodeIdsWithFreeSlot(currentStationId, null);
        if (!freeAtCurrent.isEmpty()) {
            Optional<TransferNode> node = nodeRepo.findById(freeAtCurrent.get(0));
            Optional<Station> st = stationRepo.findById(currentStationId);
            String termAddr = node.flatMap(n -> st.map(s -> n.terminalAddress(s.getAddress()))).orElse(null);
            if (termAddr != null) {
                cartRepo.setDestination(cartUuid, termAddr, currentStationId);
                plugin.getDetectorRailHandler().recheckTerminalReleaseForCart(cartUuid);
                if (plugin.isDebugEnabled())
                    plugin.getLogger().info("[Netro routing] unreachable dest " + oldDest + " → redirected cart " + cartUuid + " to nearest terminal " + termAddr);
                return Optional.of(oldDest);
            }
        }
        java.util.List<String> otherStationIds = new java.util.ArrayList<>(nodeRepo.findStationIdsWithAvailableTerminal());
        otherStationIds.remove(currentStationId);
        for (String sid : otherStationIds) {
            java.util.List<String> free = nodeRepo.findTerminalNodeIdsWithFreeSlot(sid, null);
            if (free.isEmpty()) continue;
            Optional<TransferNode> term = nodeRepo.findById(free.get(0));
            Optional<Station> station = stationRepo.findById(sid);
            String termAddr = term.flatMap(t -> station.map(s -> t.terminalAddress(s.getAddress()))).orElse(null);
            if (termAddr != null) {
                cartRepo.setDestination(cartUuid, termAddr, currentStationId);
                plugin.getDetectorRailHandler().recheckTerminalReleaseForCart(cartUuid);
                if (plugin.isDebugEnabled())
                    plugin.getLogger().info("[Netro routing] unreachable dest " + oldDest + " → redirected cart " + cartUuid + " to " + station.get().getName() + " " + termAddr);
                return Optional.of(oldDest);
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

        Optional<RouteCacheRepository.CachedRouteWithUpdatedAt> cached = routeCacheRepo.getWithUpdatedAt(fromStationId, destStationId);
        if (cached.isPresent()) {
            long ttlMs = plugin.getConfig().getLong("route-cache-ttl-ms", 1_800_000L);
            long ttlTicks = ttlMs > 0 ? (ttlMs * 20L / 1000L) : 0;
            long effectiveTtlTicks = effectiveTtlTicksWithJitter(ttlTicks, fromStationId, destStationId);
            long storedAt = cached.get().updatedAt();
            boolean legacyMs = storedAt > LEGACY_MS_THRESHOLD;
            long nowTick = getReferenceWorldTick();
            boolean expired = ttlTicks > 0 && (legacyMs || (nowTick - storedAt > effectiveTtlTicks));
            if (expired) {
                scheduleRouteRefresh(fromStationId, destStationId);
            }
            if (plugin.isDebugEnabled()) {
                String fromName = stationRepo.findById(fromStationId).map(Station::getName).orElse(fromStationId);
                String destName = stationRepo.findById(destStationId).map(Station::getName).orElse(destStationId);
                plugin.getLogger().info("[Netro routing] from=" + fromName + " to=" + destName + " cache hit firstHop->" + formatNodeIdForLog(cached.get().firstHopNodeId()) + " totalCost=" + cached.get().cost() + (expired ? " (expired, refreshing)" : ""));
            }
            return Optional.of(cached.get().firstHopNodeId());
        }

        List<CandidateHop> candidates = new ArrayList<>();
        DijkstraResult dr = dijkstra(fromStationId, destStationId, candidates);
        Map<String, RouteEntry> paths = dr.paths();
        RouteEntry entry = paths.get(destStationId);
        if (entry == null) return Optional.empty();

        fillDownstreamCache(dr, fromStationId, destStationId, getReferenceWorldTick());

        if (plugin.isDebugEnabled() && !candidates.isEmpty()) {
            String fromName = stationRepo.findById(fromStationId).map(Station::getName).orElse(fromStationId);
            String destName = stationRepo.findById(destStationId).map(Station::getName).orElse(destStationId);
            StringBuilder sb = new StringBuilder("[Netro routing] from=").append(fromName).append(" to=").append(destName)
                .append(" candidates=").append(candidates.size()).append(":");
            for (CandidateHop c : candidates) {
                String nodeLabel = formatNodeIdForLog(c.firstHopNodeId());
                int restCost = costFromTo(c.neighborStationId(), destStationId);
                int totalCost = restCost == Integer.MAX_VALUE ? Integer.MAX_VALUE : c.edgeCost() + restCost;
                sb.append(" [").append(nodeLabel).append(" totalCost=").append(totalCost == Integer.MAX_VALUE ? "∞" : totalCost)
                    .append(" firstEdge=").append(c.edgeCost()).append(c.usedPortalCost() ? ",portal" : ",fallback").append("]");
            }
            sb.append(" chosen=firstHop->").append(formatNodeIdForLog(entry.firstHopNodeId())).append(" totalCost=").append(entry.cost());
            plugin.getLogger().info(sb.toString());
        }
        return Optional.of(entry.firstHopNodeId());
    }

    /** Human-readable label for a node (StationName:NodeName) for debug logs. */
    private String formatNodeIdForLog(String nodeId) {
        return nodeRepo.findById(nodeId)
            .flatMap(n -> stationRepo.findById(n.getStationId()).map(st -> st.getName() + ":" + n.getName()))
            .orElse(nodeId);
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

    /** Result of Dijkstra: paths, predecessor chain and edge nodes for backtracking and downstream cache fill. */
    private record DijkstraResult(
        Map<String, RouteEntry> paths,
        Map<String, String> prevStation,
        Map<String, Map<String, String>> edgeFromTo
    ) {}

    /** For debug: one candidate first hop from start station (node, neighbor station, cost, whether portal cost was used). */
    public record CandidateHop(String firstHopNodeId, String neighborStationId, int edgeCost, boolean usedPortalCost) {}

    public record DispatchResult(boolean canGo, String reason) {
        public static DispatchResult clear() { return new DispatchResult(true, null); }
        public static DispatchResult blocked(String reason) { return new DispatchResult(false, reason); }
    }
}
