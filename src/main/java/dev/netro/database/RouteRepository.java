package dev.netro.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class RouteRepository {

    private final Database database;

    public RouteRepository(Database database) {
        this.database = database;
    }

    public List<RouteRow> findAll() {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, color, station_ids FROM routes ORDER BY name")) {
                try (ResultSet rs = ps.executeQuery()) {
                    List<RouteRow> list = new ArrayList<>();
                    while (rs.next()) list.add(new RouteRow(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("color"),
                        rs.getString("station_ids")));
                    return list;
                }
            }
        });
    }

    public RouteRow findByName(String name) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, color, station_ids FROM routes WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new RouteRow(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("color"),
                        rs.getString("station_ids")) : null;
                }
            }
        });
    }

    public void insert(String id, String name, String color, String stationIdsJson) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO routes (id, name, color, station_ids) VALUES (?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, name);
                ps.setString(3, color != null ? color : "blue");
                ps.setString(4, stationIdsJson != null ? stationIdsJson : "[]");
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void deleteByName(String name) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM routes WHERE name = ?")) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public static class RouteRow {
        public final String id;
        public final String name;
        public final String color;
        public final String stationIds;

        public RouteRow(String id, String name, String color, String stationIds) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.stationIds = stationIds;
        }
    }
}
