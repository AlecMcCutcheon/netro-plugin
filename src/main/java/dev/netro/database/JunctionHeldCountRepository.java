package dev.netro.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Per-junction held cart count. Incremented on READY, decremented on CLEAR. Supports single pool (held_count) or directional queues (left_held_count, right_held_count). Capacity is per-junction, per-side (capacity_left, capacity_right); defaults to 2 each when not set. */
public class JunctionHeldCountRepository {

    private static final int DEFAULT_CAPACITY_PER_SIDE = 2;
    private static final int DEFAULT_TOTAL_CAPACITY = 4;

    private final Database database;

    public JunctionHeldCountRepository(Database database) {
        this.database = database;
    }

    public int getHeldCount(String junctionId) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT held_count FROM junction_held_counts WHERE junction_id = ?")) {
                ps.setString(1, junctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt("held_count") : 0;
                }
            }
        });
    }

    public void setHeldCount(String junctionId, int count) {
        database.withConnection(conn -> {
            long now = System.currentTimeMillis();
            ensureRow(conn, junctionId, now);
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE junction_held_counts SET held_count = ?, updated_at = ? WHERE junction_id = ?")) {
                ps.setInt(1, Math.max(0, count));
                ps.setLong(2, now);
                ps.setString(3, junctionId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public int increment(String junctionId) {
        int current = getHeldCount(junctionId);
        setHeldCount(junctionId, current + 1);
        return current + 1;
    }

    public int decrement(String junctionId) {
        int current = getHeldCount(junctionId);
        setHeldCount(junctionId, Math.max(0, current - 1));
        return Math.max(0, current - 1);
    }

    private void ensureRow(Connection conn, String junctionId, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO junction_held_counts (junction_id, held_count, left_held_count, right_held_count, updated_at) VALUES (?,0,0,0,?) ON CONFLICT(junction_id) DO NOTHING")) {
            ps.setString(1, junctionId);
            ps.setLong(2, now);
            ps.executeUpdate();
        }
    }

    public int getLeftHeldCount(String junctionId) {
        return database.withConnection(conn -> {
            ensureRow(conn, junctionId, System.currentTimeMillis());
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT left_held_count FROM junction_held_counts WHERE junction_id = ?")) {
                ps.setString(1, junctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt("left_held_count") : 0;
                }
            }
        });
    }

    public int getRightHeldCount(String junctionId) {
        return database.withConnection(conn -> {
            ensureRow(conn, junctionId, System.currentTimeMillis());
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT right_held_count FROM junction_held_counts WHERE junction_id = ?")) {
                ps.setString(1, junctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt("right_held_count") : 0;
                }
            }
        });
    }

    public int incrementLeft(String junctionId) {
        return database.withConnection(conn -> {
            long now = System.currentTimeMillis();
            ensureRow(conn, junctionId, now);
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE junction_held_counts SET left_held_count = left_held_count + 1, updated_at = ? WHERE junction_id = ?")) {
                ps.setLong(1, now);
                ps.setString(2, junctionId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT left_held_count FROM junction_held_counts WHERE junction_id = ?")) {
                ps.setString(1, junctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt("left_held_count") : 0;
                }
            }
        });
    }

    public int incrementRight(String junctionId) {
        return database.withConnection(conn -> {
            long now = System.currentTimeMillis();
            ensureRow(conn, junctionId, now);
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE junction_held_counts SET right_held_count = right_held_count + 1, updated_at = ? WHERE junction_id = ?")) {
                ps.setLong(1, now);
                ps.setString(2, junctionId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT right_held_count FROM junction_held_counts WHERE junction_id = ?")) {
                ps.setString(1, junctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt("right_held_count") : 0;
                }
            }
        });
    }

    public int decrementLeft(String junctionId) {
        int current = getLeftHeldCount(junctionId);
        database.withConnection(conn -> {
            long now = System.currentTimeMillis();
            ensureRow(conn, junctionId, now);
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE junction_held_counts SET left_held_count = ?, updated_at = ? WHERE junction_id = ?")) {
                ps.setInt(1, Math.max(0, current - 1));
                ps.setLong(2, now);
                ps.setString(3, junctionId);
                ps.executeUpdate();
            }
            return null;
        });
        return Math.max(0, current - 1);
    }

    public int decrementRight(String junctionId) {
        int current = getRightHeldCount(junctionId);
        database.withConnection(conn -> {
            long now = System.currentTimeMillis();
            ensureRow(conn, junctionId, now);
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE junction_held_counts SET right_held_count = ?, updated_at = ? WHERE junction_id = ?")) {
                ps.setInt(1, Math.max(0, current - 1));
                ps.setLong(2, now);
                ps.setString(3, junctionId);
                ps.executeUpdate();
            }
            return null;
        });
        return Math.max(0, current - 1);
    }

    /**
     * Free slots at this junction for the given approach direction (LEFT or RIGHT).
     * Uses this junction's capacity_left / capacity_right (from DB; default 2 each if null).
     * When the junction uses directional queues (left_held_count + right_held_count > 0), returns
     * capacity for that side minus held for that side. Otherwise (single pool) returns total capacity minus held_count.
     * Convention: LEFT = nodeA side, RIGHT = nodeB side.
     */
    public int getFreeSlotsForDirection(String junctionId, String direction) {
        int left = getLeftHeldCount(junctionId);
        int right = getRightHeldCount(junctionId);
        int total = getHeldCount(junctionId);
        if (left + right > 0) {
            int capacityThisSide = getCapacityForDirection(junctionId, direction);
            int heldThisSide = "LEFT".equals(direction) ? left : right;
            return Math.max(0, capacityThisSide - heldThisSide);
        }
        int totalCapacity = getTotalCapacity(junctionId);
        return Math.max(0, totalCapacity - total);
    }

    /** Capacity for one side (LEFT or RIGHT). Returns DB value or DEFAULT_CAPACITY_PER_SIDE. */
    public int getCapacityForDirection(String junctionId, String direction) {
        return database.withConnection(conn -> {
            ensureRow(conn, junctionId, System.currentTimeMillis());
            String col = "LEFT".equals(direction) ? "capacity_left" : "capacity_right";
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + col + " FROM junction_held_counts WHERE junction_id = ?")) {
                ps.setString(1, junctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return DEFAULT_CAPACITY_PER_SIDE;
                    int v = rs.getInt(col);
                    return rs.wasNull() || v <= 0 ? DEFAULT_CAPACITY_PER_SIDE : v;
                }
            }
        });
    }

    /** Total capacity (single-pool mode). Sum of left+right capacity or DEFAULT_TOTAL_CAPACITY. */
    private int getTotalCapacity(String junctionId) {
        return database.withConnection(conn -> {
            ensureRow(conn, junctionId, System.currentTimeMillis());
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT capacity_left, capacity_right FROM junction_held_counts WHERE junction_id = ?")) {
                ps.setString(1, junctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return DEFAULT_TOTAL_CAPACITY;
                    int cl = rs.getInt("capacity_left");
                    int cr = rs.getInt("capacity_right");
                    if (rs.wasNull() || (cl <= 0 && cr <= 0)) return DEFAULT_TOTAL_CAPACITY;
                    if (cl <= 0) cl = DEFAULT_CAPACITY_PER_SIDE;
                    if (cr <= 0) cr = DEFAULT_CAPACITY_PER_SIDE;
                    return cl + cr;
                }
            }
        });
    }

    /** Set all junctions' held counts to 0. Used by /clearcarts for full cart-state reset. */
    public void resetAllHeldCounts() {
        database.withConnection(conn -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE junction_held_counts SET held_count = 0, left_held_count = 0, right_held_count = 0, updated_at = ?")) {
                ps.setLong(1, now);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
