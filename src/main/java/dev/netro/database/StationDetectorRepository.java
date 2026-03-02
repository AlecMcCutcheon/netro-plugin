package dev.netro.database;

import dev.netro.model.BlockPos;
import dev.netro.model.StationDetector;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class StationDetectorRepository {

    private final Database database;

    public StationDetectorRepository(Database database) {
        this.database = database;
    }

    public void insert(StationDetector d) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO station_detectors (id, station_id, world, x, y, z, rail_x, rail_y, rail_z, sign_facing, rule_1_role, rule_1_direction, rule_2_role, rule_2_direction, rule_3_role, rule_3_direction, rule_4_role, rule_4_direction, set_dest_value, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, d.getId());
                ps.setString(2, d.getStationId());
                ps.setString(3, d.getWorld());
                ps.setInt(4, d.getX());
                ps.setInt(5, d.getY());
                ps.setInt(6, d.getZ());
                ps.setInt(7, d.getRailX());
                ps.setInt(8, d.getRailY());
                ps.setInt(9, d.getRailZ());
                ps.setString(10, d.getSignFacing());
                ps.setString(11, d.getRule1Role());
                ps.setObject(12, d.getRule1Direction());
                ps.setObject(13, d.getRule2Role());
                ps.setObject(14, d.getRule2Direction());
                ps.setObject(15, d.getRule3Role());
                ps.setObject(16, d.getRule3Direction());
                ps.setObject(17, d.getRule4Role());
                ps.setObject(18, d.getRule4Direction());
                ps.setString(19, d.getSetDestValue());
                ps.setLong(20, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void deleteById(String id) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM station_detectors WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Find all station detectors that watch this rail block (stored rail equals this block, or bulb is adjacent to this block so any rail next to the bulb counts). */
    public List<StationDetector> findByRail(String world, int railX, int railY, int railZ) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, station_id, world, x, y, z, rail_x, rail_y, rail_z, sign_facing, rule_1_role, rule_1_direction, rule_2_role, rule_2_direction, rule_3_role, rule_3_direction, rule_4_role, rule_4_direction, set_dest_value FROM station_detectors WHERE world = ? AND ( (rail_x = ? AND rail_y = ? AND rail_z = ?) OR (x = ? AND y = ? AND z = ?) OR (x = ? AND y = ? AND z = ?) OR (x = ? AND y = ? AND z = ?) OR (x = ? AND y = ? AND z = ?) )")) {
                ps.setString(1, world);
                ps.setInt(2, railX);
                ps.setInt(3, railY);
                ps.setInt(4, railZ);
                ps.setInt(5, railX + 1);
                ps.setInt(6, railY);
                ps.setInt(7, railZ);
                ps.setInt(8, railX - 1);
                ps.setInt(9, railY);
                ps.setInt(10, railZ);
                ps.setInt(11, railX);
                ps.setInt(12, railY);
                ps.setInt(13, railZ + 1);
                ps.setInt(14, railX);
                ps.setInt(15, railY);
                ps.setInt(16, railZ - 1);
                try (ResultSet rs = ps.executeQuery()) {
                    List<StationDetector> list = new ArrayList<>();
                    while (rs.next()) list.add(rowToDetector(rs));
                    return list;
                }
            }
        });
    }

    /** All station detector rail positions (world, rail_x, rail_z) for chunk loading. */
    public List<BlockPos> listAllRailPositions() {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT world, rail_x, rail_z FROM station_detectors");
                 ResultSet rs = ps.executeQuery()) {
                List<BlockPos> list = new ArrayList<>();
                while (rs.next()) list.add(new BlockPos(rs.getString("world"), rs.getInt("rail_x"), rs.getInt("rail_z")));
                return list;
            }
        });
    }

    /** Find station detector at this copper bulb position (for sign break). */
    public Optional<StationDetector> findByBlock(String world, int x, int y, int z) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, station_id, world, x, y, z, rail_x, rail_y, rail_z, sign_facing, rule_1_role, rule_1_direction, rule_2_role, rule_2_direction, rule_3_role, rule_3_direction, rule_4_role, rule_4_direction, set_dest_value FROM station_detectors WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToDetector(rs)) : Optional.empty();
                }
            }
        });
    }

    private static StationDetector rowToDetector(ResultSet rs) throws SQLException {
        String setDestValue = null;
        try {
            rs.findColumn("set_dest_value");
            setDestValue = rs.getString("set_dest_value");
        } catch (SQLException ignored) {
            // Column missing on older DBs
        }
        return new StationDetector(
            rs.getString("id"),
            rs.getString("station_id"),
            rs.getString("world"),
            rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
            rs.getInt("rail_x"), rs.getInt("rail_y"), rs.getInt("rail_z"),
            rs.getString("sign_facing"),
            rs.getString("rule_1_role"),
            rs.getString("rule_1_direction"),
            rs.getString("rule_2_role"),
            rs.getString("rule_2_direction"),
            rs.getString("rule_3_role"),
            rs.getString("rule_3_direction"),
            rs.getString("rule_4_role"),
            rs.getString("rule_4_direction"),
            setDestValue
        );
    }
}
