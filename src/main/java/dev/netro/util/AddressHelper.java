package dev.netro.util;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Hierarchical 2D address from world coordinates (blockX, blockZ).
 * <p>
 * TOTAL = aggregate linear allocation of 4 children. Not side length, not area.
 * Only sizing rule: child_total = parent_total ÷ 4.
 * MAINNET_TOTAL = 1600 → 4 Cluster Quads (2×2 layout) → CLUSTER_TOTAL = 400 each.
 * CLUSTER_TOTAL = 400 → 4 LocalNet Quads (2×2 layout) → LOCALNET_TOTAL = 100 each.
 * No ÷2, no side-length math, no area math. 2×2 defines structure (4 children), not dimensions.
 * <p>
 * Address format (colon-separated): OV:E2:N3:01:02:05 for station, OV:E2:N3:01:02:05:01 for terminal.
 * OV = Overworld, NE = Nether. Mainnet X and Z separate (E2, N3). Cluster, localnet, station, terminal = 2 digits (01–04, 01–99).
 */
public final class AddressHelper {

    /** Parsed destination: station address and optional terminal index. */
    public record ParsedDestination(String stationAddress, Integer terminalIndex) {}

    private static final String SEP = ":";
    private static final String DIM_OVERWORLD = "OV";
    private static final String DIM_NETHER = "NE";

    /** MainNet total = 1600 (allocation). Four Cluster Quads (2×2), each total 400. */
    public static final int MAINNET_TOTAL = 1600;
    /** Cluster Quad total = MAINNET_TOTAL ÷ 4 = 400. Four LocalNet Quads (2×2), each total 100. */
    public static final int CLUSTER_TOTAL = 400;
    /** LocalNet Quad total = CLUSTER_TOTAL ÷ 4 = 100. */
    public static final int LOCALNET_TOTAL = 100;

    /** Block extent of mainnet cell: 400 m (scale 1/4 of 1600). */
    public static final int MAINNET_SIZE = 400;
    /** Boundary for 2×2 cluster split: half of mainnet (200). Cluster quad = 200×200 m. */
    private static final int MAINNET_CENTER = 200;
    /** Block extent of one cluster quad (2×2 split of mainnet): 200 m. */
    public static final int CLUSTER_SIZE = MAINNET_CENTER;
    /** Boundary for 2×2 localnet split within cluster: half of cluster (100). Localnet cell = 100×100 m. */
    private static final int CLUSTER_HALF = 100;
    /** Block extent of one localnet quad (2×2 split of cluster): 100 m. For boundary drawing. */
    public static final int LOCALNET_SIZE = CLUSTER_HALF;

    /** Local offset range within one localnet cell (0 to 99). Each (cq,lq) cell is 100×100 m. */
    public static final int QUADRANT_CELL_SIZE = CLUSTER_HALF;

    private static final String E = "E";
    private static final String W = "W";
    private static final String N = "N";
    private static final String S = "S";

    /**
     * Quadrant 1–4 from position in 2×2 grid (low bit of index). Mapping: (0,0)→3, (1,0)→4, (0,1)→1, (1,1)→2
     * (NegX+PosZ=1, PosX+PosZ=2, NegX+NegZ=3, PosX+NegZ=4). Uses index bit only, no ÷2 for size.
     */
    private static int quadrantFromPosition(int col01, int row01) {
        return 1 + col01 + (row01 == 0 ? 2 : 0);
    }

    private AddressHelper() {}

    // ─── Mainnet (2D: points on the plane, labeled with cardinals) ─────────────────────────────

    public static int mainnetFromX(int blockX) {
        return Math.floorDiv(blockX, MAINNET_SIZE);
    }

    public static int mainnetFromZ(int blockZ) {
        return Math.floorDiv(blockZ, MAINNET_SIZE);
    }

    public static String mainnetXLabel(int mainnetX) {
        if (mainnetX >= 0) return E + mainnetX;
        return W + Math.abs(mainnetX);
    }

    public static String mainnetZLabel(int mainnetZ) {
        if (mainnetZ >= 0) return S + mainnetZ;
        return N + Math.abs(mainnetZ);
    }

    /** Full mainnet label (single token): e.g. "E2N3" for (2, -3). */
    public static String mainnetLabel(int mainnetX, int mainnetZ) {
        return mainnetXLabel(mainnetX) + mainnetZLabel(mainnetZ);
    }

    /** Dimension label for address: OV (Overworld) or NE (Nether). */
    public static String dimensionLabel(int dimension) {
        return dimension == 0 ? DIM_OVERWORLD : DIM_NETHER;
    }

    /** Parse dimension label to 0 (Overworld) or 1 (Nether), or null if invalid. */
    public static Integer parseDimension(String label) {
        if (label == null) return null;
        String s = label.strip().toUpperCase();
        if (DIM_OVERWORLD.equals(s)) return 0;
        if (DIM_NETHER.equals(s)) return 1;
        return null;
    }

    /** Two-digit string for cluster/localnet/station/terminal (01–99). */
    public static String formatTwoDigits(int n) {
        if (n < 0 || n > 99) return String.valueOf(n);
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    /** True if address is the current format: OV:E2:N3:01:02:05 (6 parts, 2-digit quadrants and station). */
    public static boolean isNewFormatStationAddress(String address) {
        if (address == null || address.isBlank()) return false;
        String[] parts = address.strip().split(SEP, -1);
        if (parts.length != 6) return false;
        if (parseDimension(parts[0].strip()) == null) return false;
        if (parseMainnetCardinal(parts[1].strip()) == null || parseMainnetCardinal(parts[2].strip()) == null) return false;
        try {
            int cq = Integer.parseInt(parts[3].strip());
            int lq = Integer.parseInt(parts[4].strip());
            int st = Integer.parseInt(parts[5].strip());
            return cq >= 1 && cq <= 4 && lq >= 1 && lq <= 4 && st >= 1 && st <= 99;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Pattern for combined mainnet token: E2N3, W1S4, etc. */
    private static final Pattern MAINNET_TOKEN = Pattern.compile("^([EW])(\\d+)([NS])(\\d+)$", Pattern.CASE_INSENSITIVE);

    public static int[] parseMainnetToken(String token) {
        if (token == null || token.isBlank()) return null;
        java.util.regex.Matcher m = MAINNET_TOKEN.matcher(token.strip());
        if (!m.matches()) return null;
        try {
            int x = Integer.parseInt(m.group(2));
            int z = Integer.parseInt(m.group(4));
            int mX = "W".equalsIgnoreCase(m.group(1)) ? -x : x;
            int mZ = "N".equalsIgnoreCase(m.group(3)) ? -z : z;
            return new int[] { mX, mZ };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Integer parseMainnetCardinal(String token) {
        if (token == null || token.isBlank()) return null;
        String t = token.strip().toUpperCase();
        if (t.startsWith(E)) {
            try { return Integer.parseInt(t.substring(1).strip()); } catch (NumberFormatException e) { return null; }
        }
        if (t.startsWith(W)) {
            try { return -Integer.parseInt(t.substring(1).strip()); } catch (NumberFormatException e) { return null; }
        }
        if (t.startsWith(S)) {
            try { return Integer.parseInt(t.substring(1).strip()); } catch (NumberFormatException e) { return null; }
        }
        if (t.startsWith(N)) {
            try { return -Integer.parseInt(t.substring(1).strip()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    // ─── Cluster quadrant (1–4): 2×2 split of mainnet at MAINNET_CENTER (200) ─────────────────

    /**
     * Cluster quadrant 1–4: one split in X and one in Z (2×2 quads). Boundary at MAINNET_CENTER (200) in mainnet-local.
     * Top-left=1, top-right=2, bottom-left=3, bottom-right=4.
     */
    public static int clusterQuadrant1to4(int blockX, int blockZ) {
        int mainnetLocalX = Math.floorMod(blockX, MAINNET_SIZE);
        int mainnetLocalZ = Math.floorMod(blockZ, MAINNET_SIZE);
        int col01 = mainnetLocalX >= MAINNET_CENTER ? 1 : 0;
        int row01 = mainnetLocalZ >= MAINNET_CENTER ? 1 : 0;
        return quadrantFromPosition(col01, row01);
    }

    /** Cluster quad origin X in mainnet-local [0,400). cq 1,3→0; cq 2,4→200. */
    private static int clusterOriginX(int cq) {
        return (cq == 2 || cq == 4) ? MAINNET_CENTER : 0;
    }

    /** Cluster quad origin Z in mainnet-local [0,400). cq 1,2→200; cq 3,4→0. */
    private static int clusterOriginZ(int cq) {
        return (cq == 1 || cq == 2) ? MAINNET_CENTER : 0;
    }

    /**
     * Localnet quadrant 1–4: 2×2 split within cluster (200×200). Boundary at 100 within cluster.
     */
    public static int localnetQuadrant1to4(int blockX, int blockZ) {
        int cq = clusterQuadrant1to4(blockX, blockZ);
        int mainnetLocalX = Math.floorMod(blockX, MAINNET_SIZE);
        int mainnetLocalZ = Math.floorMod(blockZ, MAINNET_SIZE);
        int localX = mainnetLocalX - clusterOriginX(cq);
        int localZ = mainnetLocalZ - clusterOriginZ(cq);
        int col01 = localX >= CLUSTER_HALF ? 1 : 0;
        int row01 = localZ >= CLUSTER_HALF ? 1 : 0;
        return quadrantFromPosition(col01, row01);
    }

    // ─── Cell origin (100×100 localnet cell) for (cq, lq) in mainnet-local offsets ───────────

    /** Cell origin X in mainnet-local for (cq, lq). Cluster 200×200, localnet cell 100×100. */
    private static final int[][] CELL_ORIGIN_X = {
        { 0, 100, 0, 100 },    // cq 1: lq 1,2,3,4
        { 200, 300, 200, 300 }, // cq 2
        { 0, 100, 0, 100 },    // cq 3
        { 200, 300, 200, 300 }  // cq 4
    };
    private static final int[][] CELL_ORIGIN_Z = {
        { 300, 300, 200, 200 },  // cq 1
        { 300, 300, 200, 200 },  // cq 2
        { 100, 100, 0, 0 },     // cq 3
        { 100, 100, 0, 0 }      // cq 4
    };

    /** Local offset 0–99 within the 100×100 quadrant cell (for display / whereami). */
    public static int localOffsetInCellX(int blockX, int blockZ) {
        int mX = mainnetFromX(blockX);
        int cq = clusterQuadrant1to4(blockX, blockZ);
        int lq = localnetQuadrant1to4(blockX, blockZ);
        int baseX = mX * MAINNET_SIZE + CELL_ORIGIN_X[cq - 1][lq - 1];
        return Math.floorMod(blockX - baseX, QUADRANT_CELL_SIZE);
    }

    public static int localOffsetInCellZ(int blockX, int blockZ) {
        int mZ = mainnetFromZ(blockZ);
        int cq = clusterQuadrant1to4(blockX, blockZ);
        int lq = localnetQuadrant1to4(blockX, blockZ);
        int baseZ = mZ * MAINNET_SIZE + CELL_ORIGIN_Z[cq - 1][lq - 1];
        return Math.floorMod(blockZ - baseZ, QUADRANT_CELL_SIZE);
    }

    /**
     * Reconstruct world block X from region (mainnet + cq + lq) and local offset (0–99) in the 100×100 cell.
     */
    public static int worldXFromRegionAndLocal(int mainnetX, int mainnetZ, int cq, int lq, int localX, int localZ) {
        int ox = CELL_ORIGIN_X[cq - 1][lq - 1];
        return mainnetX * MAINNET_SIZE + ox + localX;
    }

    public static int worldZFromRegionAndLocal(int mainnetX, int mainnetZ, int cq, int lq, int localX, int localZ) {
        int oz = CELL_ORIGIN_Z[cq - 1][lq - 1];
        return mainnetZ * MAINNET_SIZE + oz + localZ;
    }

    // ─── Station address (6-part: OV:E2:N3:01:02:05) ────────────────────────────────────────

    /**
     * Build station address: OV:E2:N3:01:02:05 (dim:mainnetX:mainnetZ:cluster:localnet:station).
     */
    public static String stationAddress(int dimension, int blockX, int blockZ, int stationIndexInCell) {
        int mX = mainnetFromX(blockX);
        int mZ = mainnetFromZ(blockZ);
        int cq = clusterQuadrant1to4(blockX, blockZ);
        int lq = localnetQuadrant1to4(blockX, blockZ);
        return dimensionLabel(dimension) + SEP + mainnetXLabel(mX) + SEP + mainnetZLabel(mZ)
            + SEP + formatTwoDigits(cq) + SEP + formatTwoDigits(lq) + SEP + formatTwoDigits(stationIndexInCell);
    }

    /** Terminal address: station address + terminal index (2-digit). Terminal index 0-based → display 01, 02, … */
    public static String terminalAddress(String stationAddressSixPart, int terminalIndex) {
        return stationAddressSixPart + SEP + formatTwoDigits(terminalIndex + 1);
    }

    /** Region prefix (2D): OV:E2:N3:01:02 (no station). */
    public static String regionPrefix2D(int dimension, int blockX, int blockZ) {
        int mX = mainnetFromX(blockX);
        int mZ = mainnetFromZ(blockZ);
        int cq = clusterQuadrant1to4(blockX, blockZ);
        int lq = localnetQuadrant1to4(blockX, blockZ);
        return dimensionLabel(dimension) + SEP + mainnetXLabel(mX) + SEP + mainnetZLabel(mZ)
            + SEP + formatTwoDigits(cq) + SEP + formatTwoDigits(lq);
    }

    /** Cluster prefix: OV:E2:N3:01 (dim:mainnetX:mainnetZ:cluster). */
    public static String clusterPrefix2D(int dimension, int blockX, int blockZ) {
        int mX = mainnetFromX(blockX);
        int mZ = mainnetFromZ(blockZ);
        int cq = clusterQuadrant1to4(blockX, blockZ);
        return dimensionLabel(dimension) + SEP + mainnetXLabel(mX) + SEP + mainnetZLabel(mZ) + SEP + formatTwoDigits(cq);
    }

    /** Mainnet prefix: OV:E2:N3 (dim:mainnetX:mainnetZ). */
    public static String mainnetPrefix2D(int dimension, int mainnetX, int mainnetZ) {
        return dimensionLabel(dimension) + SEP + mainnetXLabel(mainnetX) + SEP + mainnetZLabel(mainnetZ);
    }

    /**
     * Parse station address to [dim, mainnetX, mainnetZ, cq, lq, station].
     * Format OV:E2:N3:01:02:05 (6 parts) or terminal 7 parts; tiers from first 6 parts.
     */
    public static int[] parseStationAddressTiers(String address) {
        if (address == null || address.isBlank()) return null;
        String[] parts = address.strip().split(SEP, -1);
        if (parts.length >= 6) return parseStationAddressTiersFromParts(parts, 0);
        return null;
    }

    private static int[] parseStationAddressTiersFromParts(String[] parts, int start) {
        if (start + 6 > parts.length) return null;
        try {
            Integer dim = parseDimension(parts[start].strip());
            if (dim == null) return null;
            Integer mX = parseMainnetCardinal(parts[start + 1].strip());
            Integer mZ = parseMainnetCardinal(parts[start + 2].strip());
            if (mX == null || mZ == null) return null;
            int cq = Integer.parseInt(parts[start + 3].strip());
            int lq = Integer.parseInt(parts[start + 4].strip());
            int st = Integer.parseInt(parts[start + 5].strip());
            if (cq < 1 || cq > 4 || lq < 1 || lq > 4 || st < 1 || st > 99) return null;
            return new int[] { dim, mX, mZ, cq, lq, st };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Address distance for routing (mainnet, cq, lq, station). */
    public static int addressDistance(String addressA, String addressB) {
        int[] a = parseStationAddressTiers(addressA);
        int[] b = parseStationAddressTiers(addressB);
        if (a == null || b == null) return Integer.MAX_VALUE;
        int dimWeight = 10_000 * Math.abs(a[0] - b[0]);
        int mainnetWeight = 100 * (Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]));
        int cqWeight = 10 * Math.abs(a[3] - b[3]);
        int lqWeight = Math.abs(a[4] - b[4]);
        int stationWeight = Math.abs(a[5] - b[5]);
        return dimWeight + mainnetWeight + cqWeight + lqWeight + stationWeight;
    }

    /**
     * Parse destination: 6-part (OV:…:05) → station; 7-part → station (first 6 parts) + terminal index (01 → 0).
     */
    public static Optional<ParsedDestination> parseDestination(String destination) {
        if (destination == null || destination.isBlank()) return Optional.empty();
        String s = destination.strip();
        String[] parts = s.split(SEP, -1);
        if (parts.length == 6 && parseStationAddressTiers(s) != null) {
            return Optional.of(new ParsedDestination(s, null));
        }
        if (parts.length == 7 && parseStationAddressTiers(String.join(SEP, parts[0], parts[1], parts[2], parts[3], parts[4], parts[5])) != null) {
            try {
                int termNum = Integer.parseInt(parts[6].strip());
                if (termNum < 1 || termNum > 99) return Optional.empty();
                String stationAddress = parts[0] + SEP + parts[1] + SEP + parts[2] + SEP + parts[3] + SEP + parts[4] + SEP + parts[5];
                return Optional.of(new ParsedDestination(stationAddress, termNum - 1));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
