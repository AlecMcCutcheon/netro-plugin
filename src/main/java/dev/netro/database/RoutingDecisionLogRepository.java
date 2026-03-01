package dev.netro.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stores the last N routing decisions per cart (why we chose a transfer node or a terminal).
 * Used for debugging and querying; pruned to 5 entries per cart on each insert.
 */
public class RoutingDecisionLogRepository {

    private static final int KEEP_LAST_PER_CART = 5;

    private final Database database;

    public RoutingDecisionLogRepository(Database database) {
        this.database = database;
    }

    /**
     * Records a next-hop decision at a station and prunes to keep only the last
     * {@value #KEEP_LAST_PER_CART} entries for this cart.
     */
    public void logNextHop(String cartUuid, String stationId, String preferredNodeId,
                           String chosenNodeId, boolean canDispatch, String blockReason,
                           String destinationAddress) {
        database.withConnection(conn -> {
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO routing_decision_log (id, cart_uuid, created_at, station_id, decision_type, preferred_node_id, chosen_node_id, can_dispatch, block_reason, destination_address) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, cartUuid);
                ps.setLong(3, now);
                ps.setString(4, stationId);
                ps.setString(5, "next_hop");
                ps.setString(6, preferredNodeId);
                ps.setString(7, chosenNodeId);
                ps.setInt(8, canDispatch ? 1 : 0);
                ps.setString(9, blockReason);
                ps.setString(10, destinationAddress != null ? destinationAddress : "");
                ps.executeUpdate();
            }
            pruneToLastPerCart(conn, cartUuid);
            return null;
        });
    }

    private void pruneToLastPerCart(Connection conn, String cartUuid) throws SQLException {
        Long cutoff = null;
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT created_at FROM routing_decision_log WHERE cart_uuid = ? ORDER BY created_at DESC LIMIT 1 OFFSET ?")) {
            ps.setString(1, cartUuid);
            ps.setInt(2, KEEP_LAST_PER_CART);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) cutoff = rs.getLong("created_at");
            }
        }
        if (cutoff == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
            "DELETE FROM routing_decision_log WHERE cart_uuid = ? AND created_at < ?")) {
            ps.setString(1, cartUuid);
            ps.setLong(2, cutoff);
            ps.executeUpdate();
        }
    }

    /** Returns the last N routing decisions for this cart (newest first). */
    public List<Map<String, Object>> findLastByCart(String cartUuid, int limit) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, cart_uuid, created_at, station_id, decision_type, preferred_node_id, chosen_node_id, can_dispatch, block_reason, destination_address FROM routing_decision_log WHERE cart_uuid = ? ORDER BY created_at DESC LIMIT ?")) {
                ps.setString(1, cartUuid);
                ps.setInt(2, limit <= 0 ? KEEP_LAST_PER_CART : limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> list = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>();
                        row.put("id", rs.getString("id"));
                        row.put("cart_uuid", rs.getString("cart_uuid"));
                        row.put("created_at", rs.getLong("created_at"));
                        row.put("station_id", nullToEmpty(rs.getString("station_id")));
                        row.put("decision_type", nullToEmpty(rs.getString("decision_type")));
                        row.put("preferred_node_id", nullToEmpty(rs.getString("preferred_node_id")));
                        row.put("chosen_node_id", nullToEmpty(rs.getString("chosen_node_id")));
                        row.put("can_dispatch", rs.getInt("can_dispatch") != 0);
                        row.put("block_reason", nullToEmpty(rs.getString("block_reason")));
                        row.put("destination_address", nullToEmpty(rs.getString("destination_address")));
                        list.add(row);
                    }
                    return list;
                }
            }
        });
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
