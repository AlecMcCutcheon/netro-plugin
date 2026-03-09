package dev.netro.database;

import dev.netro.model.Station;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class StationRepository {

    private static final String STATION_COLUMNS = "id, name, address, world, dimension, sign_x, sign_y, sign_z, created_at";

    private final Database database;

    public StationRepository(Database database) {
        this.database = database;
    }

    public Optional<Station> findById(String id) {
        return database.withConnection(conn -> findById(conn, id));
    }

    /** Use when already holding a connection (e.g. inside runAsyncRead callback). */
    public Optional<Station> findById(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT " + STATION_COLUMNS + " FROM stations WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rowToStation(rs)) : Optional.empty();
            }
        }
    }

    /** Find by exact address (2D format: 6-part station or 7-part terminal, colon-separated). */
    public Optional<Station> findByAddress(String address) {
        if (address == null || address.isBlank()) return Optional.empty();
        return findByAddressExact(address);
    }

    public Optional<Station> findByAddressExact(String address) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + STATION_COLUMNS + " FROM stations WHERE address = ?")) {
                ps.setString(1, address);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToStation(rs)) : Optional.empty();
                }
            }
        });
    }

    public Optional<Station> findByName(String name) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + STATION_COLUMNS + " FROM stations WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToStation(rs)) : Optional.empty();
                }
            }
        });
    }

    /** Case-insensitive lookup by station name. */
    public Optional<Station> findByNameIgnoreCase(String name) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + STATION_COLUMNS + " FROM stations WHERE LOWER(name) = LOWER(?)")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToStation(rs)) : Optional.empty();
                }
            }
        });
    }

    /** Case-insensitive lookup by station name in a specific dimension (0 = Overworld, 1 = Nether). Ensures terminals/nodes are only assigned to stations in the same dimension as the sign. */
    public Optional<Station> findByNameIgnoreCaseAndDimension(String name, int dimension) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + STATION_COLUMNS + " FROM stations WHERE LOWER(name) = LOWER(?) AND dimension = ?")) {
                ps.setString(1, name);
                ps.setInt(2, dimension);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToStation(rs)) : Optional.empty();
                }
            }
        });
    }

    public List<Station> findAll() {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + STATION_COLUMNS + " FROM stations ORDER BY name")) {
                try (ResultSet rs = ps.executeQuery()) {
                    List<Station> list = new ArrayList<>();
                    while (rs.next()) list.add(rowToStation(rs));
                    return list;
                }
            }
        });
    }

    /** Load stations by IDs (for distance sort over candidate set only). Returns empty list if ids empty. */
    public List<Station> findByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return database.withConnection(conn -> {
            List<Station> list = new ArrayList<>();
            String placeholders = ids.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("");
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + STATION_COLUMNS + " FROM stations WHERE id IN (" + placeholders + ")")) {
                int i = 1;
                for (String id : ids) {
                    ps.setString(i++, id);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(rowToStation(rs));
                }
            }
            return list;
        });
    }

    private static String addressPrefixLikePattern(String prefix) {
        if (prefix == null) return "%";
        if (prefix.contains(":")) return (prefix.endsWith(":") ? prefix : prefix + ":") + "%";
        return (prefix.endsWith(".") ? prefix : prefix + ".") + "%";
    }

    /** Stations whose address starts with prefix (colon format OV:E2:N3:01:02 or legacy dot). */
    public List<Station> findByAddressPrefix(String prefix) {
        String pattern = addressPrefixLikePattern(prefix);
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + STATION_COLUMNS + " FROM stations WHERE address LIKE ? OR address = ? ORDER BY address")) {
                ps.setString(1, pattern);
                ps.setString(2, prefix);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Station> list = new ArrayList<>();
                    while (rs.next()) list.add(rowToStation(rs));
                    return list;
                }
            }
        });
    }

    /** Find station at exact sign block (world + block coords). */
    public Optional<Station> findAtBlock(String world, int x, int y, int z) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + STATION_COLUMNS + " FROM stations WHERE world = ? AND sign_x = ? AND sign_y = ? AND sign_z = ?")) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToStation(rs)) : Optional.empty();
                }
            }
        });
    }

    /** Count stations whose address starts with the given prefix (colon OV:E2:N3:01:02 or legacy with trailing dot). */
    public int countStationsWithAddressPrefix(String addressPrefixWithTrailingDot) {
        String pattern = addressPrefixLikePattern(addressPrefixWithTrailingDot);
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) AS c FROM stations WHERE address LIKE ?")) {
                ps.setString(1, pattern);
                ResultSet rs = ps.executeQuery();
                int c = rs.next() ? rs.getInt("c") : 0;
                rs.close();
                return c;
            }
        });
    }

    public void deleteById(String id) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM stations WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void insert(Station station) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO stations (id, name, address, world, dimension, sign_x, sign_y, sign_z, created_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, station.getId());
                ps.setString(2, station.getName());
                ps.setString(3, station.getAddress());
                ps.setString(4, station.getWorld());
                ps.setInt(5, station.getDimension());
                ps.setInt(6, station.getSignX());
                ps.setInt(7, station.getSignY());
                ps.setInt(8, station.getSignZ());
                ps.setLong(9, station.getCreatedAt());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void updateAddressAndDimension(String stationId, String newAddress, int dimension) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE stations SET address = ?, dimension = ? WHERE id = ?")) {
                ps.setString(1, newAddress);
                ps.setInt(2, dimension);
                ps.setString(3, stationId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static Station rowToStation(ResultSet rs) throws SQLException {
        return new Station(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("address"),
            rs.getString("world"),
            rs.getInt("dimension"),
            rs.getInt("sign_x"),
            rs.getInt("sign_y"),
            rs.getInt("sign_z"),
            rs.getLong("created_at"));
    }
}
