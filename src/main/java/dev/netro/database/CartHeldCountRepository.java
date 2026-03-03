package dev.netro.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Per-node held cart count. Incremented on READY, decremented on CLEAR. */
public class CartHeldCountRepository {

    private final Database database;

    public CartHeldCountRepository(Database database) {
        this.database = database;
    }

    public int getHeldCount(String nodeId) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT held_count FROM cart_held_counts WHERE node_id = ?")) {
                ps.setString(1, nodeId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt("held_count") : 0;
                }
            }
        });
    }

    public void setHeldCount(String nodeId, int count) {
        database.withConnection(conn -> {
            long now = System.currentTimeMillis();
            ensureRow(conn, nodeId, now);
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE cart_held_counts SET held_count = ?, updated_at = ? WHERE node_id = ?")) {
                ps.setInt(1, Math.max(0, count));
                ps.setLong(2, now);
                ps.setString(3, nodeId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Increment held count by 1 (READY). Returns new count. */
    public int increment(String nodeId) {
        int current = getHeldCount(nodeId);
        setHeldCount(nodeId, current + 1);
        return current + 1;
    }

    /** Decrement held count by 1 (CLEAR). Returns new count. */
    public int decrement(String nodeId) {
        int current = getHeldCount(nodeId);
        setHeldCount(nodeId, Math.max(0, current - 1));
        return Math.max(0, current - 1);
    }

    private void ensureRow(java.sql.Connection conn, String nodeId, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO cart_held_counts (node_id, held_count, updated_at) VALUES (?,0,?) ON CONFLICT(node_id) DO NOTHING")) {
            ps.setString(1, nodeId);
            ps.setLong(2, now);
            ps.executeUpdate();
        }
    }

    /** Set all nodes' held counts to 0. */
    public void resetAllHeldCounts() {
        database.withConnection(conn -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE cart_held_counts SET held_count = 0, updated_at = ?")) {
                ps.setLong(1, now);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
