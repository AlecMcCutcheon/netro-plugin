package dev.netro.database;

import dev.netro.model.StationController;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class StationControllerRepository {

    private final Database database;

    public StationControllerRepository(Database database) {
        this.database = database;
    }

    public void insert(StationController c) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO station_controllers (id, station_id, target_node_id, world, x, y, z, sign_facing, rule_1_role, rule_1_direction, rule_2_role, rule_2_direction, rule_3_role, rule_3_direction, rule_4_role, rule_4_direction, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, c.getId());
                ps.setString(2, c.getStationId());
                ps.setString(3, c.getTargetNodeId());
                ps.setString(4, c.getWorld());
                ps.setInt(5, c.getX());
                ps.setInt(6, c.getY());
                ps.setInt(7, c.getZ());
                ps.setString(8, c.getSignFacing());
                ps.setString(9, c.getRule1Role());
                ps.setObject(10, c.getRule1Direction());
                ps.setObject(11, c.getRule2Role());
                ps.setObject(12, c.getRule2Direction());
                ps.setObject(13, c.getRule3Role());
                ps.setObject(14, c.getRule3Direction());
                ps.setObject(15, c.getRule4Role());
                ps.setObject(16, c.getRule4Direction());
                ps.setLong(17, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void deleteById(String id) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM station_controllers WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Find all station controllers for this station (for TRANSFER/NOT_TRANSFER by target). */
    public List<StationController> findByStation(String stationId) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, station_id, target_node_id, world, x, y, z, sign_facing, rule_1_role, rule_1_direction, rule_2_role, rule_2_direction, rule_3_role, rule_3_direction, rule_4_role, rule_4_direction FROM station_controllers WHERE station_id = ?")) {
                ps.setString(1, stationId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<StationController> list = new ArrayList<>();
                    while (rs.next()) list.add(rowToController(rs));
                    return list;
                }
            }
        });
    }

    /** Find station controllers for this station that match target and (optionally) role/direction. */
    public List<StationController> findByStationAndTarget(String stationId, String targetNodeId) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, station_id, target_node_id, world, x, y, z, sign_facing, rule_1_role, rule_1_direction, rule_2_role, rule_2_direction, rule_3_role, rule_3_direction, rule_4_role, rule_4_direction FROM station_controllers WHERE station_id = ? AND target_node_id = ?")) {
                ps.setString(1, stationId);
                ps.setString(2, targetNodeId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<StationController> list = new ArrayList<>();
                    while (rs.next()) list.add(rowToController(rs));
                    return list;
                }
            }
        });
    }

    /** Find station controller at this copper bulb position (for sign break). */
    public Optional<StationController> findByBlock(String world, int x, int y, int z) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, station_id, target_node_id, world, x, y, z, sign_facing, rule_1_role, rule_1_direction, rule_2_role, rule_2_direction, rule_3_role, rule_3_direction, rule_4_role, rule_4_direction FROM station_controllers WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
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

    private static StationController rowToController(ResultSet rs) throws SQLException {
        return new StationController(
            rs.getString("id"),
            rs.getString("station_id"),
            rs.getString("target_node_id"),
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
