package dev.netro.database;

import dev.netro.model.Controller;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ControllerRepository {

    private final Database database;

    public ControllerRepository(Database database) {
        this.database = database;
    }

    public void insert(Controller c) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO controllers (id, node_id, world, x, y, z, sign_facing, rule_1_role, rule_1_direction, rule_2_role, rule_2_direction, rule_3_role, rule_3_direction, rule_4_role, rule_4_direction, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, c.getId());
                ps.setString(2, c.getNodeId());
                ps.setString(3, c.getWorld());
                ps.setInt(4, c.getX());
                ps.setInt(5, c.getY());
                ps.setInt(6, c.getZ());
                ps.setString(7, c.getSignFacing());
                ps.setString(8, c.getRule1Role());
                ps.setObject(9, c.getRule1Direction());
                ps.setObject(10, c.getRule2Role());
                ps.setObject(11, c.getRule2Direction());
                ps.setObject(12, c.getRule3Role());
                ps.setObject(13, c.getRule3Direction());
                ps.setObject(14, c.getRule4Role());
                ps.setObject(15, c.getRule4Direction());
                ps.setLong(16, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void deleteById(String id) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM controllers WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Update controller position after relocate. */
    public void updatePosition(String id, String world, int x, int y, int z) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE controllers SET world = ?, x = ?, y = ?, z = ? WHERE id = ?")) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                ps.setString(5, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Find controllers for this node that match role and direction (direction null = any). */
    public List<Controller> findByNodeAndRule(String nodeId, String role, String direction) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, node_id, world, x, y, z, sign_facing, rule_1_role, rule_1_direction, rule_2_role, rule_2_direction, rule_3_role, rule_3_direction, rule_4_role, rule_4_direction FROM controllers WHERE node_id = ? AND ( (rule_1_role = ? AND (rule_1_direction IS NULL OR rule_1_direction = ?)) OR (rule_2_role = ? AND (rule_2_direction IS NULL OR rule_2_direction = ?)) OR (rule_3_role = ? AND (rule_3_direction IS NULL OR rule_3_direction = ?)) OR (rule_4_role = ? AND (rule_4_direction IS NULL OR rule_4_direction = ?)) )")) {
                ps.setString(1, nodeId);
                ps.setString(2, role);
                ps.setString(3, direction);
                ps.setString(4, role);
                ps.setString(5, direction);
                ps.setString(6, role);
                ps.setString(7, direction);
                ps.setString(8, role);
                ps.setString(9, direction);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Controller> list = new ArrayList<>();
                    while (rs.next()) list.add(rowToController(rs));
                    return list;
                }
            }
        });
    }

    /** Find controller at this copper bulb position. */
    public Optional<Controller> findByBlock(String world, int x, int y, int z) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, node_id, world, x, y, z, sign_facing, rule_1_role, rule_1_direction, rule_2_role, rule_2_direction, rule_3_role, rule_3_direction, rule_4_role, rule_4_direction FROM controllers WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToController(rs)) : Optional.empty();
                }
            }
        });
    }

    public List<Controller> findByNodeId(String nodeId) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, node_id, world, x, y, z, sign_facing, rule_1_role, rule_1_direction, rule_2_role, rule_2_direction, rule_3_role, rule_3_direction, rule_4_role, rule_4_direction FROM controllers WHERE node_id = ?")) {
                ps.setString(1, nodeId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Controller> list = new ArrayList<>();
                    while (rs.next()) list.add(rowToController(rs));
                    return list;
                }
            }
        });
    }

    private static Controller rowToController(ResultSet rs) throws SQLException {
        return new Controller(
            rs.getString("id"),
            rs.getString("node_id"),
            rs.getString("world"),
            rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
            rs.getString("sign_facing"),
            rs.getString("rule_1_role"),
            rs.getString("rule_1_direction"),
            rs.getString("rule_2_role"),
            rs.getString("rule_2_direction"),
            rs.getString("rule_3_role"),
            rs.getString("rule_3_direction"),
            rs.getString("rule_4_role"),
            rs.getString("rule_4_direction")
        );
    }
}
