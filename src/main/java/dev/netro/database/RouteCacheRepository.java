package dev.netro.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persisted route cache: (from_station_id, dest_station_id) → first_hop_node_id, cost.
 * Populated when routing computes a path; used to skip Dijkstra on cache hit.
 */
public class RouteCacheRepository {

    private final Database database;

    public RouteCacheRepository(Database database) {
        this.database = database;
    }

    public record CachedRoute(String firstHopNodeId, int cost) {}

    /** Same as get() but includes updated_at for expiry check. */
    public record CachedRouteWithUpdatedAt(String firstHopNodeId, int cost, long updatedAt) {}

    public Optional<CachedRoute> get(String fromStationId, String destStationId) {
        return getWithUpdatedAt(fromStationId, destStationId).map(c -> new CachedRoute(c.firstHopNodeId(), c.cost()));
    }

    public Optional<CachedRouteWithUpdatedAt> getWithUpdatedAt(String fromStationId, String destStationId) {
        if (fromStationId == null || destStationId == null) return Optional.empty();
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT first_hop_node_id, cost, updated_at FROM route_cache WHERE from_station_id = ? AND dest_station_id = ?")) {
                ps.setString(1, fromStationId);
                ps.setString(2, destStationId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new CachedRouteWithUpdatedAt(
                        rs.getString("first_hop_node_id"), rs.getInt("cost"), rs.getLong("updated_at"))) : Optional.empty();
                }
            }
        });
    }

    /** Save cache row. updatedAtTick is Minecraft world full time (ticks) so TTL is based on in-game time, not real time. */
    public void save(String fromStationId, String destStationId, String firstHopNodeId, int cost, long updatedAtTick) {
        if (fromStationId == null || destStationId == null || firstHopNodeId == null) return;
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO route_cache (from_station_id, dest_station_id, first_hop_node_id, cost, updated_at) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, fromStationId);
                ps.setString(2, destStationId);
                ps.setString(3, firstHopNodeId);
                ps.setInt(4, cost);
                ps.setLong(5, updatedAtTick);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public record RouteCacheRow(String fromStationId, String destStationId, String firstHopNodeId, int cost) {}

    public List<RouteCacheRow> findByFromStation(String fromStationId) {
        if (fromStationId == null) return List.of();
        return findByFromStationAndFirstHop(fromStationId, null);
    }

    /** Routes from this station that use this node as first hop. If firstHopNodeId is null, returns all from station. */
    public List<RouteCacheRow> findByFromStationAndFirstHop(String fromStationId, String firstHopNodeId) {
        if (fromStationId == null) return List.of();
        return database.withConnection(conn -> {
            List<RouteCacheRow> list = new ArrayList<>();
            String sql = firstHopNodeId == null || firstHopNodeId.isEmpty()
                ? "SELECT from_station_id, dest_station_id, first_hop_node_id, cost FROM route_cache WHERE from_station_id = ? ORDER BY dest_station_id"
                : "SELECT from_station_id, dest_station_id, first_hop_node_id, cost FROM route_cache WHERE from_station_id = ? AND first_hop_node_id = ? ORDER BY dest_station_id";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, fromStationId);
                if (firstHopNodeId != null && !firstHopNodeId.isEmpty()) ps.setString(2, firstHopNodeId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new RouteCacheRow(
                            rs.getString("from_station_id"),
                            rs.getString("dest_station_id"),
                            rs.getString("first_hop_node_id"),
                            rs.getInt("cost")));
                    }
                }
            }
            return list;
        });
    }

    public void remove(String fromStationId, String destStationId) {
        if (fromStationId == null || destStationId == null) return;
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM route_cache WHERE from_station_id = ? AND dest_station_id = ?")) {
                ps.setString(1, fromStationId);
                ps.setString(2, destStationId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void removeAllFromStation(String fromStationId) {
        if (fromStationId == null) return;
        removeAllByFromStationAndFirstHop(fromStationId, null);
    }

    /** Remove all cached routes (entire table). Use for /netro clearcache. */
    public void deleteAll() {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM route_cache")) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** All distinct (from_station_id, dest_station_id) pairs in the cache. For proactive refresh. */
    public List<RouteCachePair> listDistinctPairs() {
        return database.withConnection(conn -> {
            List<RouteCachePair> list = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT from_station_id, dest_station_id FROM route_cache")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new RouteCachePair(rs.getString("from_station_id"), rs.getString("dest_station_id")));
                    }
                }
            }
            return list;
        });
    }

    public record RouteCachePair(String fromStationId, String destStationId) {}

    /** Remove cached routes from this station that use this node as first hop. If firstHopNodeId is null, removes all from station. */
    public void removeAllByFromStationAndFirstHop(String fromStationId, String firstHopNodeId) {
        if (fromStationId == null) return;
        database.withConnection(conn -> {
            if (firstHopNodeId == null || firstHopNodeId.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM route_cache WHERE from_station_id = ?")) {
                    ps.setString(1, fromStationId);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM route_cache WHERE from_station_id = ? AND first_hop_node_id = ?")) {
                    ps.setString(1, fromStationId);
                    ps.setString(2, firstHopNodeId);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }
}
