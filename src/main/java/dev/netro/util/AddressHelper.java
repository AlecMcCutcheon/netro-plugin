package dev.netro.util;

/**
 * Hierarchical address from world coordinates.
 * Zones: mainnet (4000 blocks) → cluster (500) → localnet (100) → station (sequential).
 * Uses block X for tier indices; same formula can use Z if needed for 2D grid.
 */
public final class AddressHelper {

    public static final int MAINNET_SIZE = 4000;
    public static final int CLUSTER_SIZE = 500;
    public static final int LOCALNET_SIZE = 100;

    private AddressHelper() {}

    /** Mainnet index from block X (e.g. 2 for 2.x.x.x). */
    public static int mainnetFromX(int blockX) {
        return Math.floorDiv(blockX, MAINNET_SIZE);
    }

    /** Cluster index within mainnet (0-based). */
    public static int clusterFromX(int blockX) {
        int r = Math.floorMod(blockX, MAINNET_SIZE);
        return Math.floorDiv(r, CLUSTER_SIZE);
    }

    /** Localnet index within cluster (0-based). */
    public static int localnetFromX(int blockX) {
        int r = Math.floorMod(blockX, MAINNET_SIZE);
        r = Math.floorMod(r, CLUSTER_SIZE);
        return Math.floorDiv(r, LOCALNET_SIZE);
    }

    /** Three-tier prefix: mainnet.cluster.localnet (e.g. "2.4.7"). */
    public static String prefixFromX(int blockX) {
        int m = mainnetFromX(blockX);
        int c = clusterFromX(blockX);
        int l = localnetFromX(blockX);
        return m + "." + c + "." + l;
    }

    /** Four-tier station address given the station index in that localnet (1-based): mainnet.cluster.localnet.station (e.g. "2.4.7.3"). */
    public static String stationAddress(int blockX, int stationIndexInLocalnet) {
        return prefixFromX(blockX) + "." + stationIndexInLocalnet;
    }

    /** Five-tier terminal address: mainnet.cluster.localnet.station.terminal (e.g. "2.4.7.3.1"). */
    public static String terminalAddress(String stationAddressFourTier, int terminalIndex) {
        return stationAddressFourTier + "." + terminalIndex;
    }
}
