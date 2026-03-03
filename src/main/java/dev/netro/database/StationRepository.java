package dev.netro.database;

import dev.netro.model.Station;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class StationRepository {

    private final Database database;

    public StationRepository(Database database) {
        this.database = database;
    }

    public Optional<Station> findById(String id) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, address, world, sign_x, sign_y, sign_z, created_at FROM stations WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToStation(rs)) : Optional.empty();
                }
            }
        });
    }

    public Optional<Station> findByAddress(String address) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, address, world, sign_x, sign_y, sign_z, created_at FROM stations WHERE address = ?")) {
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
                "SELECT id, name, address, world, sign_x, sign_y, sign_z, created_at FROM stations WHERE name = ?")) {
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
                "SELECT id, name, address, world, sign_x, sign_y, sign_z, created_at FROM stations WHERE LOWER(name) = LOWER(?)")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToStation(rs)) : Optional.empty();
                }
            }
        });
    }

    public List<Station> findAll() {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, address, world, sign_x, sign_y, sign_z, created_at FROM stations ORDER BY name")) {
                try (ResultSet rs = ps.executeQuery()) {
                    List<Station> list = new ArrayList<>();
                    while (rs.next()) list.add(rowToStation(rs));
                    return list;
                }
            }
        });
    }

    /** Stations whose address starts with prefix (e.g. "2.4.7" for localnet, "2.4" for cluster, "2" for mainnet). */
    public List<Station> findByAddressPrefix(String prefix) {
        String pattern = prefix.contains(".") ? prefix + ".%" : prefix + ".%";
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, address, world, sign_x, sign_y, sign_z, created_at FROM stations WHERE address LIKE ? OR address = ? ORDER BY address")) {
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
                "SELECT id, name, address, world, sign_x, sign_y, sign_z, created_at FROM stations WHERE world = ? AND sign_x = ? AND sign_y = ? AND sign_z = ?")) {
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

    /** Count stations in the same localnet (same mainnet.cluster.localnet). Used to assign next station index. */
    public int countStationsInLocalnet(String world, int mainnet, int cluster, int localnet) {
        String prefix = mainnet + "." + cluster + "." + localnet + ".";
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) AS c FROM stations WHERE world = ? AND address LIKE ?")) {
                ps.setString(1, world);
                ps.setString(2, prefix + "%");
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
                "INSERT INTO stations (id, name, address, world, sign_x, sign_y, sign_z, created_at) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setString(1, station.getId());
                ps.setString(2, station.getName());
                ps.setString(3, station.getAddress());
                ps.setString(4, station.getWorld());
                ps.setInt(5, station.getSignX());
                ps.setInt(6, station.getSignY());
                ps.setInt(7, station.getSignZ());
                ps.setLong(8, station.getCreatedAt());
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
            rs.getInt("sign_x"),
            rs.getInt("sign_y"),
            rs.getInt("sign_z"),
            rs.getLong("created_at"));
    }
}
