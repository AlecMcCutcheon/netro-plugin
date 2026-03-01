package dev.netro.database;

import dev.netro.model.Junction;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JunctionRepository {

    private final Database database;

    public JunctionRepository(Database database) {
        this.database = database;
    }

    public Optional<String> findSegmentPolyline(String nodeAId, String nodeBId) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT polyline FROM segment_polylines WHERE (node_a_id = ? AND node_b_id = ?) OR (node_a_id = ? AND node_b_id = ?)")) {
                ps.setString(1, nodeAId);
                ps.setString(2, nodeBId);
                ps.setString(3, nodeBId);
                ps.setString(4, nodeAId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString("polyline")) : Optional.empty();
                }
            }
        });
    }

    public List<Junction> findOnSegment(String nodeAId, String nodeBId) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, world, node_a_id, node_b_id, ref_x, ref_y, ref_z, setup_state, release_reversed FROM junctions " +
                "WHERE (node_a_id = ? AND node_b_id = ?) OR (node_a_id = ? AND node_b_id = ?) ORDER BY id")) {
                ps.setString(1, nodeAId);
                ps.setString(2, nodeBId);
                ps.setString(3, nodeBId);
                ps.setString(4, nodeAId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Junction> list = new ArrayList<>();
                    while (rs.next()) list.add(rowToJunction(rs));
                    return list;
                }
            }
        });
    }

    public List<Junction> findAll() {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, world, node_a_id, node_b_id, ref_x, ref_y, ref_z, setup_state, release_reversed FROM junctions ORDER BY name")) {
                try (ResultSet rs = ps.executeQuery()) {
                    List<Junction> list = new ArrayList<>();
                    while (rs.next()) list.add(rowToJunction(rs));
                    return list;
                }
            }
        });
    }

    public Optional<Junction> findById(String id) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, world, node_a_id, node_b_id, ref_x, ref_y, ref_z, setup_state, release_reversed FROM junctions WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToJunction(rs)) : Optional.empty();
                }
            }
        });
    }

    public Optional<Junction> findByName(String name) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, world, node_a_id, node_b_id, ref_x, ref_y, ref_z, setup_state, release_reversed FROM junctions WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToJunction(rs)) : Optional.empty();
                }
            }
        });
    }

    /** Find junction by name (case-insensitive). */
    public Optional<Junction> findByNameIgnoreCase(String name) {
        if (name == null || name.isEmpty()) return Optional.empty();
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, world, node_a_id, node_b_id, ref_x, ref_y, ref_z, setup_state, release_reversed FROM junctions WHERE LOWER(name) = LOWER(?)")) {
                ps.setString(1, name.strip());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToJunction(rs)) : Optional.empty();
                }
            }
        });
    }

    public void updateSegment(String junctionId, String nodeAId, String nodeBId) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE junctions SET node_a_id = ?, node_b_id = ?, setup_state = 'ready' WHERE id = ?")) {
                ps.setString(1, nodeAId);
                ps.setString(2, nodeBId);
                ps.setString(3, junctionId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void insert(String id, String name, String world, Integer refX, Integer refY, Integer refZ) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO junctions (id, name, world, ref_x, ref_y, ref_z, setup_state) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, name);
                ps.setString(3, world);
                ps.setObject(4, refX);
                ps.setObject(5, refY);
                ps.setObject(6, refZ);
                ps.setString(7, "pending_switches");
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void setReleaseReversed(String junctionId, boolean reversed) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE junctions SET release_reversed = ? WHERE id = ?")) {
                ps.setInt(1, reversed ? 1 : 0);
                ps.setString(2, junctionId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void updateSetupState(String junctionId, String setupState) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE junctions SET setup_state = ? WHERE id = ?")) {
                ps.setString(1, setupState);
                ps.setString(2, junctionId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static final int JUNCTION_CAPACITY = 4;

    /** Free slots at junction (capacity - held count). Uses junction_held_counts. */
    public int countFreeSlots(String junctionId) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(held_count, 0) FROM junction_held_counts WHERE junction_id = ?")) {
                ps.setString(1, junctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    int held = rs.next() ? rs.getInt(1) : 0;
                    return Math.max(0, JUNCTION_CAPACITY - held);
                }
            }
        });
    }

    private static Junction rowToJunction(ResultSet rs) throws SQLException {
        return new Junction(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("world"),
            rs.getString("node_a_id"),
            rs.getString("node_b_id"),
            getIntOrNull(rs, "ref_x"),
            getIntOrNull(rs, "ref_y"),
            getIntOrNull(rs, "ref_z"),
            rs.getString("setup_state"),
            rs.getInt("release_reversed") != 0);
    }

    private static Integer getIntOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}
