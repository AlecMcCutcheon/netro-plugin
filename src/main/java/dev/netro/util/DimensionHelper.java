package dev.netro.util;

import dev.netro.model.Station;

/**
 * Nether ↔ Overworld coordinate conversion and Overworld-equivalent distance for routing.
 * Minecraft uses an 8:1 ratio: 1 block in the Nether = 8 blocks in the Overworld (X and Z; Y is not scaled).
 * Use this when computing route costs so that paths through the Nether are compared fairly to
 * direct Overworld paths (e.g. Nether shortcut is chosen when it is actually shorter in OW-equivalent blocks).
 *
 * <h3>Routing integration (when Nether/dimension support is added)</h3>
 * <ul>
 *   <li>Same dimension Overworld: edge cost = 1 + overworldEquivalentBlocks(…)/100 (current behaviour).</li>
 *   <li>Same dimension Nether: edge cost = 1 + overworldEquivalentBlocks(…)/100 (helper already scales N→OW by 8).</li>
 *   <li>Cross-dimension (portal hop): cost = 1 + overworldEquivalentBlocks(ow_x, ow_z, OW, n_x, n_z, N)/100;
 *       that is the distance from the Overworld station to the Nether station’s equivalent OW position (n_x*8, n_z*8),
 *       so “how many Overworld blocks the cart effectively moves” for that link.</li>
 * </ul>
 */
public final class DimensionHelper {

    /** 1 Nether block = this many Overworld blocks (X and Z). */
    public static final int NETHER_TO_OVERWORLD_RATIO = 8;

    /** Dimension index for Overworld (e.g. in addresses and station dimension). */
    public static final int DIMENSION_OVERWORLD = 0;
    /** Dimension index for Nether. */
    public static final int DIMENSION_NETHER = 1;

    private DimensionHelper() {}

    // ─── Coordinate conversion (X and Z only; Y is 1:1 in Minecraft) ─────────────────────────────

    /** Nether X → equivalent Overworld X. */
    public static int netherToOverworldX(int netherX) {
        return netherX * NETHER_TO_OVERWORLD_RATIO;
    }

    /** Nether Z → equivalent Overworld Z. */
    public static int netherToOverworldZ(int netherZ) {
        return netherZ * NETHER_TO_OVERWORLD_RATIO;
    }

    /** Overworld X → equivalent Nether X. */
    public static int overworldToNetherX(int overworldX) {
        return Math.floorDiv(overworldX, NETHER_TO_OVERWORLD_RATIO);
    }

    /** Overworld Z → equivalent Nether Z. */
    public static int overworldToNetherZ(int overworldZ) {
        return Math.floorDiv(overworldZ, NETHER_TO_OVERWORLD_RATIO);
    }

    // ─── Overworld-equivalent distance for routing ──────────────────────────────────────────────

    /**
     * Horizontal (X,Z) block distance in Overworld-equivalent blocks.
     * <ul>
     *   <li><b>Same dimension Overworld:</b> 1:1 block distance.</li>
     *   <li><b>Same dimension Nether:</b> Nether block distance × 8 (cart’s perceived distance in N = 8× in OW terms).</li>
     *   <li><b>Cross-dimension (portal hop OW↔Nether):</b> Treated as <b>Overworld distance only</b>. We do not know where
     *       the portal boundary is along the path, so we cannot split the hop into OW part + Nether part. The pair is
     *       costed as the distance in Overworld coordinates from the OW station to the Nether station’s OW-equivalent
     *       position (Nether x,z → x×8, z×8). No Nether block count is added for the portal hop.</li>
     * </ul>
     *
     * @param x1         station 1 sign X
     * @param z1         station 1 sign Z
     * @param dimension1 0 = Overworld, 1 = Nether
     * @param x2         station 2 sign X
     * @param z2         station 2 sign Z
     * @param dimension2 0 = Overworld, 1 = Nether
     * @return distance in Overworld-equivalent blocks (always ≥ 0)
     */
    public static double overworldEquivalentBlocks(int x1, int z1, int dimension1,
                                                   int x2, int z2, int dimension2) {
        long owX1 = dimension1 == DIMENSION_NETHER ? netherToOverworldX(x1) : x1;
        long owZ1 = dimension1 == DIMENSION_NETHER ? netherToOverworldZ(z1) : z1;
        long owX2 = dimension2 == DIMENSION_NETHER ? netherToOverworldX(x2) : x2;
        long owZ2 = dimension2 == DIMENSION_NETHER ? netherToOverworldZ(z2) : z2;
        double dx = owX1 - owX2;
        double dz = owZ1 - owZ2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Same as {@link #overworldEquivalentBlocks(int, int, int, int, int, int)} but using each station’s
     * sign coordinates and dimension. Use when stations have a dimension field (e.g. after Nether support).
     * If a station has no dimension, treat as Overworld (0).
     *
     * @param a first station (must not be null)
     * @param b second station (must not be null)
     * @param dimensionA 0 = Overworld, 1 = Nether for station A
     * @param dimensionB 0 = Overworld, 1 = Nether for station B
     * @return distance in Overworld-equivalent blocks
     */
    public static double overworldEquivalentBlocks(Station a, Station b, int dimensionA, int dimensionB) {
        return overworldEquivalentBlocks(
            a.getSignX(), a.getSignZ(), dimensionA,
            b.getSignX(), b.getSignZ(), dimensionB);
    }

    /**
     * Resolve dimension from Bukkit world environment for use in addresses and routing.
     * NORMAL → 0 (Overworld), NETHER → 1, THE_END → 2 (reserved; not yet used for addressing).
     */
    public static int dimensionFromEnvironment(org.bukkit.World.Environment env) {
        if (env == null) return DIMENSION_OVERWORLD;
        return switch (env) {
            case NETHER -> DIMENSION_NETHER;
            case NORMAL -> DIMENSION_OVERWORLD;
            default -> DIMENSION_OVERWORLD;
        };
    }
}
