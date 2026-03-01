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
            "INSERT INTO cart_held_counts (node_id, held_count, left_held_count, right_held_count, updated_at) VALUES (?,0,0,0,?) ON CONFLICT(node_id) DO NOTHING")) {
            ps.setString(1, nodeId);
            ps.setLong(2, now);
            ps.executeUpdate();
        }
    }

    public int getLeftHeldCount(String nodeId) {
        return database.withConnection(conn -> {
            ensureRow(conn, nodeId, System.currentTimeMillis());
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT left_held_count FROM cart_held_counts WHERE node_id = ?")) {
                ps.setString(1, nodeId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt("left_held_count") : 0;
                }
            }
        });
    }

    public int getRightHeldCount(String nodeId) {
        return database.withConnection(conn -> {
            ensureRow(conn, nodeId, System.currentTimeMillis());
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT right_held_count FROM cart_held_counts WHERE node_id = ?")) {
                ps.setString(1, nodeId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt("right_held_count") : 0;
                }
            }
        });
    }

    /** Increment left queue (READY:LEFT). Returns new count. */
    public int incrementLeft(String nodeId) {
        return database.withConnection(conn -> {
            long now = System.currentTimeMillis();
            ensureRow(conn, nodeId, now);
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE cart_held_counts SET left_held_count = left_held_count + 1, updated_at = ? WHERE node_id = ?")) {
                ps.setLong(1, now);
                ps.setString(2, nodeId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT left_held_count FROM cart_held_counts WHERE node_id = ?")) {
                ps.setString(1, nodeId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt("left_held_count") : 0;
                }
            }
        });
    }

    /** Increment right queue (READY:RIGHT). Returns new count. */
    public int incrementRight(String nodeId) {
        return database.withConnection(conn -> {
            long now = System.currentTimeMillis();
            ensureRow(conn, nodeId, now);
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE cart_held_counts SET right_held_count = right_held_count + 1, updated_at = ? WHERE node_id = ?")) {
                ps.setLong(1, now);
                ps.setString(2, nodeId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT right_held_count FROM cart_held_counts WHERE node_id = ?")) {
                ps.setString(1, nodeId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt("right_held_count") : 0;
                }
            }
        });
    }

    /** Decrement left queue (CLEAR:LEFT). Returns new count. */
    public int decrementLeft(String nodeId) {
        int current = getLeftHeldCount(nodeId);
        database.withConnection(conn -> {
            long now = System.currentTimeMillis();
            ensureRow(conn, nodeId, now);
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE cart_held_counts SET left_held_count = ?, updated_at = ? WHERE node_id = ?")) {
                ps.setInt(1, Math.max(0, current - 1));
                ps.setLong(2, now);
                ps.setString(3, nodeId);
                ps.executeUpdate();
            }
            return null;
        });
        return Math.max(0, current - 1);
    }

    /** Decrement right queue (CLEAR:RIGHT). Returns new count. */
    public int decrementRight(String nodeId) {
        int current = getRightHeldCount(nodeId);
        database.withConnection(conn -> {
            long now = System.currentTimeMillis();
            ensureRow(conn, nodeId, now);
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE cart_held_counts SET right_held_count = ?, updated_at = ? WHERE node_id = ?")) {
                ps.setInt(1, Math.max(0, current - 1));
                ps.setLong(2, now);
                ps.setString(3, nodeId);
                ps.executeUpdate();
            }
            return null;
        });
        return Math.max(0, current - 1);
    }

    /** Set all nodes' held counts to 0. Used by /clearcarts for full cart-state reset. */
    public void resetAllHeldCounts() {
        database.withConnection(conn -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE cart_held_counts SET held_count = 0, left_held_count = 0, right_held_count = 0, updated_at = ?")) {
                ps.setLong(1, now);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
