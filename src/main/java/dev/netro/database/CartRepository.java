package dev.netro.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.UUID;

public class CartRepository {

    private final Database database;

    public CartRepository(Database database) {
        this.database = database;
    }

    public Optional<Map<String, Object>> find(String cartUuid) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT cart_uuid, destination_address, origin_station_id, current_node_id, next_node_id, zone, state, held_at_slot, entered_zone_at FROM cart_segments WHERE cart_uuid = ?")) {
                ps.setString(1, cartUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    Map<String, Object> m = new HashMap<>();
                    m.put("cart_uuid", rs.getString("cart_uuid"));
                    m.put("destination_address", rs.getString("destination_address"));
                    m.put("origin_station_id", rs.getString("origin_station_id"));
                    m.put("current_node_id", rs.getString("current_node_id"));
                    m.put("next_node_id", rs.getString("next_node_id"));
                    m.put("zone", rs.getString("zone"));
                    m.put("state", rs.getString("state"));
                    m.put("held_at_slot", rs.getObject("held_at_slot"));
                    m.put("entered_zone_at", rs.getLong("entered_zone_at"));
                    return Optional.of(m);
                }
            }
        });
    }

    /** Current destination address for the cart, or empty if none. */
    public Optional<String> getDestinationAddress(String cartUuid) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT destination_address FROM cart_segments WHERE cart_uuid = ?")) {
                ps.setString(1, cartUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    String addr = rs.getString("destination_address");
                    return (addr == null || addr.isEmpty()) ? Optional.empty() : Optional.of(addr);
                }
            }
        });
    }

    /** Segment can be stored as (from, to) or (to, from); opposing direction on (to, from) uses the flipped direction. */
    public List<Map<String, Object>> findOpposingCarts(String fromNodeId, String toNodeId, String direction) {
        String otherDir = "A_TO_B".equals(direction) ? "B_TO_A" : "A_TO_B";
        String flippedDir = "A_TO_B".equals(otherDir) ? "B_TO_A" : "A_TO_B";
        List<Map<String, Object>> list = new ArrayList<>();
        database.withConnection(conn -> {
            for (int run = 0; run < 2; run++) {
                String na = run == 0 ? fromNodeId : toNodeId;
                String nb = run == 0 ? toNodeId : fromNodeId;
                String dir = run == 0 ? otherDir : flippedDir;
                try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT cart_uuid, zone, direction, entered_at FROM segment_occupancy WHERE node_a_id = ? AND node_b_id = ? AND direction = ?")) {
                    ps.setString(1, na);
                    ps.setString(2, nb);
                    ps.setString(3, dir);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Map<String, Object> m = new HashMap<>();
                            m.put("cart_uuid", rs.getString("cart_uuid"));
                            m.put("zone", rs.getString("zone"));
                            m.put("direction", rs.getString("direction"));
                            m.put("entered_at", rs.getLong("entered_at"));
                            list.add(m);
                        }
                    }
                }
            }
            return list;
        });
        return list;
    }

    /** Count of carts on segment (fromNodeId, toNodeId) traveling in the given direction (A_TO_B = from→to). Tries both (from,to) and (to,from) orderings. */
    public int countCartsOnSegment(String fromNodeId, String toNodeId, String direction) {
        return database.withConnection(conn -> {
            int total = 0;
            String flippedDir = "A_TO_B".equals(direction) ? "B_TO_A" : "A_TO_B";
            for (int run = 0; run < 2; run++) {
                String na = run == 0 ? fromNodeId : toNodeId;
                String nb = run == 0 ? toNodeId : fromNodeId;
                String dir = run == 0 ? direction : flippedDir;
                try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM segment_occupancy WHERE node_a_id = ? AND node_b_id = ? AND direction = ?")) {
                    ps.setString(1, na);
                    ps.setString(2, nb);
                    ps.setString(3, dir);
                    try (ResultSet rs = ps.executeQuery()) {
                        total += rs.next() ? rs.getInt(1) : 0;
                    }
                }
            }
            return total;
        });
    }

    public void upsertSegmentOccupancy(String nodeAId, String nodeBId, String cartUuid, String direction, String zone) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO segment_occupancy (id, node_a_id, node_b_id, cart_uuid, direction, zone, entered_at) VALUES (?,?,?,?,?,?,?) " +
                "ON CONFLICT(node_a_id, node_b_id, cart_uuid) DO UPDATE SET zone=excluded.zone, entered_at=excluded.entered_at")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, nodeAId);
                ps.setString(3, nodeBId);
                ps.setString(4, cartUuid);
                ps.setString(5, direction);
                ps.setString(6, zone);
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void removeSegmentOccupancy(String nodeAId, String nodeBId, String cartUuid) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM segment_occupancy WHERE node_a_id = ? AND node_b_id = ? AND cart_uuid = ?")) {
                ps.setString(1, nodeAId);
                ps.setString(2, nodeBId);
                ps.setString(3, cartUuid);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Removes cart from segment (node1, node2) by trying both (node1, node2) and (node2, node1). Use when the cart arrived at one end and we don't know which order was used when it entered. */
    public void removeSegmentOccupancyForCart(String node1Id, String node2Id, String cartUuid) {
        removeSegmentOccupancy(node1Id, node2Id, cartUuid);
        removeSegmentOccupancy(node2Id, node1Id, cartUuid);
    }

    /**
     * Removes this cart from every segment. Call when a cart is seen at a detector so it is not
     * still recorded on another segment; collision prevention (canDispatch, shouldDivertJunction)
     * then sees at most one segment per cart. After this, ENTRY/CLEAR logic will re-add the cart
     * to the correct segment on CLEAR.
     */
    public void clearSegmentOccupancyForCart(String cartUuid) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM segment_occupancy WHERE cart_uuid = ?")) {
                ps.setString(1, cartUuid);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** All distinct cart UUIDs currently in segment_occupancy. Used to trim ghosts before collision checks. */
    public List<String> listCartUuidsInSegmentOccupancy() {
        return database.withConnection(conn -> {
            List<String> list = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT DISTINCT cart_uuid FROM segment_occupancy");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(rs.getString("cart_uuid"));
            }
            return list;
        });
    }

    /**
     * Opposing carts on this segment (direction) that are committed past the given junction.
     * Tries both (from,to) and (to,from) orderings so we find rows regardless of how segment was stored.
     */
    public List<String> findCommittedCarts(String fromNodeId, String toNodeId, String direction, String lastJunctionId) {
        if (lastJunctionId == null || lastJunctionId.isEmpty()) return List.of();
        String flippedDir = "A_TO_B".equals(direction) ? "B_TO_A" : "A_TO_B";
        String prefix = "junction:" + lastJunctionId;
        List<String> list = new ArrayList<>();
        database.withConnection(conn -> {
            for (int run = 0; run < 2; run++) {
                String na = run == 0 ? fromNodeId : toNodeId;
                String nb = run == 0 ? toNodeId : fromNodeId;
                String dir = run == 0 ? direction : flippedDir;
                try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT cart_uuid FROM segment_occupancy WHERE node_a_id = ? AND node_b_id = ? AND direction = ? AND (zone = ? OR zone LIKE ?)")) {
                    ps.setString(1, na);
                    ps.setString(2, nb);
                    ps.setString(3, dir);
                    ps.setString(4, prefix);
                    ps.setString(5, prefix + ":%");
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) list.add(rs.getString("cart_uuid"));
                    }
                }
            }
            return list;
        });
        return list;
    }

    /** Held carts at node. direction null = all at node; "" = single pool (zone = node:id only); "LEFT"/"RIGHT" = that queue. */
    public List<String> findHeldCartsAtNode(String nodeId, String direction) {
        return database.withConnection(conn -> {
            String zonePattern;
            if (direction == null) {
                zonePattern = null; // all: current_node_id = nodeId
            } else if (direction.isEmpty()) {
                zonePattern = "node:" + nodeId; // single pool only (no :LEFT/:RIGHT)
            } else {
                zonePattern = "node:" + nodeId + ":" + direction;
            }
            try (PreparedStatement ps = zonePattern == null
                ? conn.prepareStatement(
                    "SELECT cart_uuid FROM cart_segments WHERE current_node_id = ? AND state = 'held' ORDER BY held_at_slot")
                : conn.prepareStatement(
                    "SELECT cart_uuid FROM cart_segments WHERE zone = ? AND state = 'held' ORDER BY held_at_slot")) {
                if (zonePattern == null) ps.setString(1, nodeId);
                else ps.setString(1, zonePattern);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> list = new ArrayList<>();
                    while (rs.next()) list.add(rs.getString("cart_uuid"));
                    return list;
                }
            }
        });
    }

    public List<String> findHeldCartsAtNode(String nodeId) {
        return findHeldCartsAtNode(nodeId, null);
    }

    /** Held carts at junction. direction null = all; "" = single pool only; "LEFT"/"RIGHT" = that queue. */
    public List<String> findHeldCartsAtJunction(String junctionId, String direction) {
        return database.withConnection(conn -> {
            String zone;
            if (direction == null || direction.isEmpty()) zone = "junction:" + junctionId;
            else zone = "junction:" + junctionId + ":" + direction;
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT cart_uuid FROM cart_segments WHERE zone = ? AND state = 'held' ORDER BY held_at_slot")) {
                ps.setString(1, zone);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> list = new ArrayList<>();
                    while (rs.next()) list.add(rs.getString("cart_uuid"));
                    return list;
                }
            }
        });
    }

    public List<String> findHeldCartsAtJunction(String junctionId) {
        return findHeldCartsAtJunction(junctionId, null);
    }

    /** Clears held state so the cart is no longer in the queue at any node. */
    public void clearHeld(String cartUuid) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE cart_segments SET state = 'in_transit', zone = '', held_at_slot = NULL, current_node_id = NULL WHERE cart_uuid = ?")) {
                ps.setString(1, cartUuid);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Extract node id from zone (e.g. "node:X" or "node:X:LEFT" -> X). */
    public static String nodeIdFromZone(String zone) {
        if (zone == null || !zone.startsWith("node:")) return null;
        String rest = zone.substring(5);
        if (rest.endsWith(":LEFT") || rest.endsWith(":RIGHT")) return rest.substring(0, rest.length() - 5);
        return rest;
    }

    public void setHeld(String cartUuid, String zone, int slotIndex) {
        String nodeId = nodeIdFromZone(zone);
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO cart_segments (cart_uuid, zone, state, held_at_slot, entered_zone_at, current_node_id) VALUES (?,?,?,?,?,?) " +
                "ON CONFLICT(cart_uuid) DO UPDATE SET zone=excluded.zone, state='held', held_at_slot=excluded.held_at_slot, current_node_id=excluded.current_node_id")) {
                ps.setString(1, cartUuid);
                ps.setString(2, zone);
                ps.setString(3, "held");
                ps.setInt(4, slotIndex);
                ps.setLong(5, System.currentTimeMillis());
                ps.setString(6, nodeId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void setDestination(String cartUuid, String destinationAddress) {
        setDestination(cartUuid, destinationAddress, null);
    }

    public void setDestination(String cartUuid, String destinationAddress, String originStationId) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO cart_segments (cart_uuid, destination_address, origin_station_id, zone, state, entered_zone_at) VALUES (?,?,?,?,?,?) " +
                "ON CONFLICT(cart_uuid) DO UPDATE SET destination_address=excluded.destination_address, origin_station_id=excluded.origin_station_id")) {
                ps.setString(1, cartUuid);
                ps.setString(2, destinationAddress);
                ps.setString(3, originStationId);
                ps.setString(4, "");
                ps.setString(5, "in_transit");
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Clears the cart's destination (e.g. when it arrives at a terminal). Leaves other cart_segments fields unchanged. */
    public void clearDestination(String cartUuid) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE cart_segments SET destination_address = NULL, origin_station_id = NULL WHERE cart_uuid = ?")) {
                ps.setString(1, cartUuid);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Removes the cart from the DB (cart_segments and segment_occupancy). Used when no-destination rule removes a cart (no terminals exist). */
    public void deleteCart(String cartUuid) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM segment_occupancy WHERE cart_uuid = ?")) {
                ps.setString(1, cartUuid);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM cart_segments WHERE cart_uuid = ?")) {
                ps.setString(1, cartUuid);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** All cart UUIDs currently in cart_segments. Used by stale-cart cleanup to find carts that no longer exist as entities. */
    public List<String> listAllCartUuids() {
        return database.withConnection(conn -> {
            List<String> list = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT cart_uuid FROM cart_segments");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(rs.getString("cart_uuid"));
            }
            return list;
        });
    }

    /** Clears all cart state: cart_segments and segment_occupancy. Use /clearcarts when DB is stuck. Does not reset held counts (use ClearCartsCommand for full reset). */
    public void clearAllCartState() {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM segment_occupancy")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM cart_segments")) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<Map<String, Object>> findPressure(String stationId) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT station_id, inbound_slots_free, outbound_slots_free, accepting_inbound, updated_at FROM station_pressure WHERE station_id = ?")) {
                ps.setString(1, stationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    Map<String, Object> m = new HashMap<>();
                    m.put("station_id", rs.getString("station_id"));
                    m.put("inbound_slots_free", rs.getInt("inbound_slots_free"));
                    m.put("outbound_slots_free", rs.getInt("outbound_slots_free"));
                    m.put("accepting_inbound", rs.getInt("accepting_inbound") != 0);
                    m.put("updated_at", rs.getLong("updated_at"));
                    return Optional.of(m);
                }
            }
        });
    }

    public void upsertPressure(String stationId, int freeInbound, int freeOutbound, boolean acceptingInbound) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO station_pressure (station_id, inbound_slots_free, outbound_slots_free, accepting_inbound, updated_at) VALUES (?,?,?,?,?) " +
                "ON CONFLICT(station_id) DO UPDATE SET inbound_slots_free=excluded.inbound_slots_free, outbound_slots_free=excluded.outbound_slots_free, accepting_inbound=excluded.accepting_inbound, updated_at=excluded.updated_at")) {
                ps.setString(1, stationId);
                ps.setInt(2, freeInbound);
                ps.setInt(3, freeOutbound);
                ps.setInt(4, acceptingInbound ? 1 : 0);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
