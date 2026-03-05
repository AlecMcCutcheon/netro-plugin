package dev.netro.database;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Portal blocks linked to a transfer node (for nether portal link).
 * side: 0 = same dimension as node, 1 = other dimension (e.g. nether side when node is OW).
 * If any block is removed the link for that node (all sides) is cleared by the block-break listener.
 */
public class TransferNodePortalRepository {

    /** Same dimension as the transfer node (e.g. overworld portal for an OW node). */
    public static final int SIDE_SAME_DIMENSION = 0;
    /** Other dimension (e.g. nether-side portal for an OW node). */
    public static final int SIDE_OTHER_DIMENSION = 1;

    private final Database database;

    public TransferNodePortalRepository(Database database) {
        this.database = database;
    }

    public record BlockPos(String world, int x, int y, int z) {
        public static BlockPos from(World w, int x, int y, int z) {
            return new BlockPos(w.getName(), x, y, z);
        }
    }

    public void saveBlocks(String nodeId, int side, List<BlockPos> blocks) {
        database.withConnection(conn -> {
            try (var del = conn.prepareStatement("DELETE FROM transfer_node_portal_blocks WHERE node_id = ? AND side = ?")) {
                del.setString(1, nodeId);
                del.setInt(2, side);
                del.executeUpdate();
            }
            if (blocks != null && !blocks.isEmpty()) {
                try (var ins = conn.prepareStatement(
                    "INSERT INTO transfer_node_portal_blocks (node_id, side, world, x, y, z) VALUES (?,?,?,?,?,?)")) {
                    for (BlockPos p : blocks) {
                        ins.setString(1, nodeId);
                        ins.setInt(2, side);
                        ins.setString(3, p.world);
                        ins.setInt(4, p.x);
                        ins.setInt(5, p.y);
                        ins.setInt(6, p.z);
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
            }
            return null;
        });
    }

    public List<BlockPos> getBlocks(String nodeId, int side) {
        return database.withConnection(conn -> {
            List<BlockPos> out = new ArrayList<>();
            try (var ps = conn.prepareStatement(
                "SELECT world, x, y, z FROM transfer_node_portal_blocks WHERE node_id = ? AND side = ?")) {
                ps.setString(1, nodeId);
                ps.setInt(2, side);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new BlockPos(rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z")));
                    }
                }
            }
            return out;
        });
    }

    /** Find node that has this block in its portal link (for block-break cleanup). */
    public String findNodeIdByBlock(String world, int x, int y, int z) {
        return database.withConnection(conn -> {
            try (var ps = conn.prepareStatement(
                "SELECT node_id FROM transfer_node_portal_blocks WHERE world = ? AND x = ? AND y = ? AND z = ? LIMIT 1")) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString("node_id") : null;
                }
            }
        });
    }

    public void deleteByNode(String nodeId) {
        database.withConnection(conn -> {
            try (var ps = conn.prepareStatement("DELETE FROM transfer_node_portal_blocks WHERE node_id = ?")) {
                ps.setString(1, nodeId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Centroid (average x, y, z) of portal blocks for the given side. Returns null if no blocks. */
    public Centroid getCentroid(String nodeId, int side) {
        List<BlockPos> blocks = getBlocks(nodeId, side);
        if (blocks == null || blocks.isEmpty()) return null;
        double x = 0, y = 0, z = 0;
        for (BlockPos p : blocks) {
            x += p.x;
            y += p.y;
            z += p.z;
        }
        int n = blocks.size();
        return new Centroid(blocks.get(0).world(), x / n, y / n, z / n);
    }

    public record Centroid(String world, double x, double y, double z) {}
}
