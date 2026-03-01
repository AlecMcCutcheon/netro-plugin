package dev.netro.database;

import dev.netro.model.TransferNode;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TransferNodeRepository {

    private final Database database;

    public TransferNodeRepository(Database database) {
        this.database = database;
    }

    public Optional<TransferNode> findById(String id) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, station_id, paired_node_id, setup_state, is_terminal, terminal_index, release_reversed, created_at FROM transfer_nodes WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToNode(rs)) : Optional.empty();
                }
            }
        });
    }

    public Optional<TransferNode> findByName(String name) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, station_id, paired_node_id, setup_state, is_terminal, terminal_index, release_reversed, created_at FROM transfer_nodes WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToNode(rs)) : Optional.empty();
                }
            }
        });
    }

    /** Find first transfer node with this name at any station (case-insensitive). For detector/controller sign line 2. */
    public Optional<TransferNode> findByNameAcrossStations(String nodeName) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, station_id, paired_node_id, setup_state, is_terminal, terminal_index, release_reversed, created_at FROM transfer_nodes WHERE LOWER(name) = LOWER(?) LIMIT 1")) {
                ps.setString(1, nodeName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToNode(rs)) : Optional.empty();
                }
            }
        });
    }

    /** Find node at a station by name (case-insensitive). Use for per-station uniqueness and station:node lookup. */
    public Optional<TransferNode> findByNameAtStation(String stationId, String nodeName) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, station_id, paired_node_id, setup_state, is_terminal, terminal_index, release_reversed, created_at FROM transfer_nodes WHERE station_id = ? AND LOWER(name) = LOWER(?)")) {
                ps.setString(1, stationId);
                ps.setString(2, nodeName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToNode(rs)) : Optional.empty();
                }
            }
        });
    }

    public void insert(TransferNode node) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO transfer_nodes (id, name, station_id, paired_node_id, setup_state, is_terminal, terminal_index, release_reversed, created_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, node.getId());
                ps.setString(2, node.getName());
                ps.setString(3, node.getStationId());
                ps.setObject(4, node.getPairedNodeId());
                ps.setString(5, node.getSetupState().toDb());
                ps.setInt(6, node.isTerminal() ? 1 : 0);
                ps.setObject(7, node.getTerminalIndex());
                ps.setInt(8, node.isReleaseReversed() ? 1 : 0);
                ps.setLong(9, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void setPaired(String nodeId, String pairedNodeId) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE transfer_nodes SET paired_node_id = ?, setup_state = 'ready' WHERE id = ?")) {
                ps.setString(1, pairedNodeId);
                ps.setString(2, nodeId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void setTerminal(String nodeId, int terminalIndex) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE transfer_nodes SET is_terminal = 1, terminal_index = ?, setup_state = 'ready' WHERE id = ?")) {
                ps.setInt(1, terminalIndex);
                ps.setString(2, nodeId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void setReleaseReversed(String nodeId, boolean reversed) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE transfer_nodes SET release_reversed = ? WHERE id = ?")) {
                ps.setInt(1, reversed ? 1 : 0);
                ps.setString(2, nodeId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void setSetupComplete(String nodeId) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE transfer_nodes SET setup_state = 'ready' WHERE id = ?")) {
                ps.setString(1, nodeId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public int countTerminalsAtStation(String stationId) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) AS c FROM transfer_nodes WHERE station_id = ? AND is_terminal = 1")) {
                ps.setString(1, stationId);
                ResultSet rs = ps.executeQuery();
                int c = rs.next() ? rs.getInt("c") : 0;
                rs.close();
                return c;
            }
        });
    }

    public List<TransferNode> findByStation(String stationId) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, station_id, paired_node_id, setup_state, is_terminal, terminal_index, release_reversed, created_at FROM transfer_nodes WHERE station_id = ?")) {
                ps.setString(1, stationId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<TransferNode> list = new ArrayList<>();
                    while (rs.next()) list.add(rowToNode(rs));
                    return list;
                }
            }
        });
    }

    public List<TransferNode> findTerminals(String stationId) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, station_id, paired_node_id, setup_state, is_terminal, terminal_index, release_reversed, created_at FROM transfer_nodes WHERE station_id = ? AND is_terminal = 1")) {
                ps.setString(1, stationId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<TransferNode> list = new ArrayList<>();
                    while (rs.next()) list.add(rowToNode(rs));
                    return list;
                }
            }
        });
    }

    public Optional<TransferNode> findTerminalByIndex(String stationId, int terminalIndex) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, station_id, paired_node_id, setup_state, is_terminal, terminal_index, release_reversed, created_at FROM transfer_nodes WHERE station_id = ? AND is_terminal = 1 AND terminal_index = ?")) {
                ps.setString(1, stationId);
                ps.setInt(2, terminalIndex);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToNode(rs)) : Optional.empty();
                }
            }
        });
    }

    public Optional<TransferNode> findAvailableTerminal(String stationId) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, station_id, paired_node_id, setup_state, is_terminal, terminal_index, release_reversed, created_at FROM transfer_nodes WHERE station_id = ? AND is_terminal = 1 AND setup_state = 'ready' LIMIT 1")) {
                ps.setString(1, stationId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToNode(rs)) : Optional.empty();
                }
            }
        });
    }

    public void upsertRoutingEntry(String id, String stationId, String destPrefix, String nextHopNodeId, int cost) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO routing_entries (id, station_id, dest_prefix, next_hop_node_id, cost) VALUES (?,?,?,?,?) " +
                "ON CONFLICT(station_id, dest_prefix) DO UPDATE SET next_hop_node_id=excluded.next_hop_node_id, cost=excluded.cost")) {
                ps.setString(1, id);
                ps.setString(2, stationId);
                ps.setString(3, destPrefix);
                ps.setString(4, nextHopNodeId);
                ps.setInt(5, cost);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<String> lookupNextHopNode(String stationId, String destination) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT next_hop_node_id FROM routing_entries WHERE station_id = ? AND (dest_prefix = ? OR ? LIKE dest_prefix || '.%') ORDER BY length(dest_prefix) DESC LIMIT 1")) {
                ps.setString(1, stationId);
                ps.setString(2, destination);
                ps.setString(3, destination);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString("next_hop_node_id")) : Optional.empty();
                }
            }
        });
    }

    public Optional<String> findNodeIdAtBlock(String world, int x, int y, int z) {
        return Optional.empty();
    }

    /** Deletes the transfer node and all its block data (switches, hold switches, gate slots). Cascade will remove child rows. */
    public void deleteNodeAndAllBlockData(String nodeId) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM transfer_nodes WHERE id = ?")) {
                ps.setString(1, nodeId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public boolean isRailBlockUsedInWorld(String world, int x, int y, int z) {
        return false;
    }

    /** Reference point for segment geometry: node's station sign position. */
    public Optional<int[]> getNodeRefPoint(String nodeId) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT s.sign_x, s.sign_y, s.sign_z FROM transfer_nodes tn JOIN stations s ON tn.station_id = s.id WHERE tn.id = ?")) {
                ps.setString(1, nodeId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(new int[] { rs.getInt(1), rs.getInt(2), rs.getInt(3) });
                }
            }
        });
    }

    private static final int NODE_CAPACITY = 4;

    /** Free slots at node (capacity - held count). Uses cart_held_counts. */
    public int countFreeSlots(String nodeId) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(held_count, 0) FROM cart_held_counts WHERE node_id = ?")) {
                ps.setString(1, nodeId);
                try (ResultSet rs = ps.executeQuery()) {
                    int held = rs.next() ? rs.getInt(1) : 0;
                    return Math.max(0, NODE_CAPACITY - held);
                }
            }
        });
    }

    private static TransferNode rowToNode(ResultSet rs) throws SQLException {
        TransferNode n = new TransferNode(rs.getString("id"), rs.getString("name"));
        n.setStationId(rs.getString("station_id"));
        n.setPairedNodeId(rs.getString("paired_node_id"));
        n.setSetupState(TransferNode.SetupState.fromDb(rs.getString("setup_state")));
        n.setTerminal(rs.getInt("is_terminal") != 0);
        int ti = rs.getInt("terminal_index");
        n.setTerminalIndex(rs.wasNull() ? null : ti);
        n.setReleaseReversed(rs.getInt("release_reversed") != 0);
        return n;
    }
}
