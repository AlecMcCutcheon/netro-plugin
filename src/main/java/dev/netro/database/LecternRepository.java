package dev.netro.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class LecternRepository {

    private final Database database;

    public LecternRepository(Database database) {
        this.database = database;
    }

    /** True if (world, x, y, z) is already registered as a lectern. */
    public boolean isBlockUsedByLectern(String world, int x, int y, int z) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM lecterns l INNER JOIN stations s ON l.station_id = s.id WHERE s.world = ? AND l.x = ? AND l.y = ? AND l.z = ? LIMIT 1")) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public void insertLectern(String id, String stationId, String label, int x, int y, int z) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO lecterns (id, station_id, label, x, y, z, current_level) VALUES (?,?,?,?,?,?,0)")) {
                ps.setString(1, id);
                ps.setString(2, stationId);
                ps.setString(3, label);
                ps.setInt(4, x);
                ps.setInt(5, y);
                ps.setInt(6, z);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void insertBinding(String id, String stationId, String lecternLabel, String eventType, int targetLevel) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO signal_bindings (id, station_id, lectern_label, event_type, target_level) VALUES (?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, stationId);
                ps.setString(3, lecternLabel);
                ps.setString(4, eventType);
                ps.setInt(5, targetLevel);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public List<LecternRow> findByStation(String stationId) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, station_id, label, x, y, z, current_level FROM lecterns WHERE station_id = ? ORDER BY label")) {
                ps.setString(1, stationId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<LecternRow> list = new ArrayList<>();
                    while (rs.next()) list.add(new LecternRow(
                        rs.getString("id"),
                        rs.getString("station_id"),
                        rs.getString("label"),
                        rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                        rs.getInt("current_level")));
                    return list;
                }
            }
        });
    }

    public static class LecternRow {
        public final String id;
        public final String stationId;
        public final String label;
        public final int x, y, z;
        public final int currentLevel;

        public LecternRow(String id, String stationId, String label, int x, int y, int z, int currentLevel) {
            this.id = id;
            this.stationId = stationId;
            this.label = label;
            this.x = x;
            this.y = y;
            this.z = z;
            this.currentLevel = currentLevel;
        }
    }
}
