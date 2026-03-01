package dev.netro.routing;

import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Junction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Computes segment geometry between two transfer nodes and optional junctions:
 * orders junctions from A to B and returns leg lengths (blocks) for distance-based dispatch.
 */
public class SegmentGeometry {

    private static int distSq(int x1, int y1, int z1, int x2, int y2, int z2) {
        int dx = x1 - x2, dy = y1 - y2, dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double dist(int x1, int y1, int z1, int x2, int y2, int z2) {
        return Math.sqrt(distSq(x1, y1, z1, x2, y2, z2));
    }

    private final String fromNodeId;
    private final String toNodeId;
    private final List<Junction> orderedJunctions;
    private final double[] legLengths;
    /** For junction at ordered index i (from A), distance from B to that junction (blocks). */
    private final double[] distanceFromBToJunction;
    private final Map<String, Integer> junctionIdToOrderIndex;

    public SegmentGeometry(String fromNodeId, String toNodeId,
                           List<Junction> junctions,
                           TransferNodeRepository nodeRepo) {
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        int[] refA = nodeRepo.getNodeRefPoint(fromNodeId).orElse(null);
        int[] refB = nodeRepo.getNodeRefPoint(toNodeId).orElse(null);
        if (refA == null || refB == null) {
            orderedJunctions = List.of();
            legLengths = new double[0];
            distanceFromBToJunction = new double[0];
            junctionIdToOrderIndex = Map.of();
            return;
        }

        if (junctions == null || junctions.isEmpty()) {
            orderedJunctions = List.of();
            legLengths = new double[] { dist(refA[0], refA[1], refA[2], refB[0], refB[1], refB[2]) };
            distanceFromBToJunction = new double[0];
            junctionIdToOrderIndex = Map.of();
            return;
        }

        List<Junction> ordered = new ArrayList<>(junctions);
        ordered.sort((a, b) -> {
            int ax = a.getRefX() != null ? a.getRefX() : 0;
            int ay = a.getRefY() != null ? a.getRefY() : 0;
            int az = a.getRefZ() != null ? a.getRefZ() : 0;
            int bx = b.getRefX() != null ? b.getRefX() : 0;
            int by = b.getRefY() != null ? b.getRefY() : 0;
            int bz = b.getRefZ() != null ? b.getRefZ() : 0;
            return Integer.compare(
                distSq(refA[0], refA[1], refA[2], ax, ay, az),
                distSq(refA[0], refA[1], refA[2], bx, by, bz));
        });
        orderedJunctions = ordered;

        int n = ordered.size();
        legLengths = new double[n + 1];
        int px = refA[0], py = refA[1], pz = refA[2];
        for (int i = 0; i < n; i++) {
            Junction j = ordered.get(i);
            int jx = j.getRefX() != null ? j.getRefX() : 0;
            int jy = j.getRefY() != null ? j.getRefY() : 0;
            int jz = j.getRefZ() != null ? j.getRefZ() : 0;
            legLengths[i] = dist(px, py, pz, jx, jy, jz);
            px = jx; py = jy; pz = jz;
        }
        legLengths[n] = dist(px, py, pz, refB[0], refB[1], refB[2]);

        distanceFromBToJunction = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int k = i + 1; k <= n; k++) sum += legLengths[k];
            distanceFromBToJunction[i] = sum;
        }
        Map<String, Integer> jMap = new HashMap<>();
        for (int i = 0; i < n; i++) jMap.put(ordered.get(i).getId(), i);
        junctionIdToOrderIndex = jMap;
    }

    public List<Junction> getOrderedJunctions() { return orderedJunctions; }
    public double[] getLegLengths() { return legLengths; }

    /** Distance from our node (fromNode) to our first junction (blocks). */
    public double getOurDistanceToFirstJunction() {
        if (legLengths.length == 0) return 0;
        return legLengths[0];
    }

    /**
     * For an opposing cart in zone "pre_junction_&lt;junctionId&gt;", returns estimated blocks remaining
     * until they reach that junction (based on leg length and time elapsed).
     * Assumes cart speed blocks per second.
     */
    public Optional<Double> getOpposingBlocksRemainingToJunction(String junctionId, long enteredAtMs, double blocksPerSecond) {
        Integer idx = junctionIdToOrderIndex.get(junctionId);
        if (idx == null || idx >= distanceFromBToJunction.length) return Optional.empty();
        double legLengthFromB = distanceFromBToJunction[idx];
        long elapsedMs = System.currentTimeMillis() - enteredAtMs;
        double blocksTraveled = (elapsedMs / 1000.0) * blocksPerSecond;
        double remaining = Math.max(0, legLengthFromB - blocksTraveled);
        return Optional.of(remaining);
    }

    /** Leg length from B to the given junction (for opposing carts in pre_junction_X). */
    public Optional<Double> getLegLengthFromBToJunction(String junctionId) {
        Integer idx = junctionIdToOrderIndex.get(junctionId);
        if (idx == null || idx >= distanceFromBToJunction.length) return Optional.empty();
        return Optional.of(distanceFromBToJunction[idx]);
    }
}
