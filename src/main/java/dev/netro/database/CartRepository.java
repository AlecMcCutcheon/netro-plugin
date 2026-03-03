package dev.netro.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

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

    /** Removes the cart from the DB (cart_segments). Used when no-destination rule removes a cart (no terminals exist). */
    public void deleteCart(String cartUuid) {
        database.withConnection(conn -> {
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

    /** Clears all cart state: cart_segments. Does not reset held counts. */
    public void clearAllCartState() {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM cart_segments")) {
                ps.executeUpdate();
            }
            return null;
        });
    }

}
