package dev.netro.database;

import dev.netro.model.Rule;
import dev.netro.model.TransferNode;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RuleRepository {

    private final Database database;
    private final TransferNodeRepository nodeRepo;

    public RuleRepository(Database database) {
        this.database = database;
        this.nodeRepo = null;
    }

    public RuleRepository(Database database, TransferNodeRepository nodeRepo) {
        this.database = database;
        this.nodeRepo = nodeRepo;
    }

    public void insert(Rule rule) {
        String stationId = null;
        String nodeName = null;
        if (nodeRepo != null && ("transfer".equals(rule.getContextType()) || "terminal".equals(rule.getContextType()))) {
            Optional<TransferNode> node = nodeRepo.findById(rule.getContextId());
            if (node.isPresent()) {
                stationId = node.get().getStationId();
                nodeName = node.get().getName();
            }
        }
        final String s = stationId;
        final String n = nodeName;
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO rules (id, context_type, context_id, context_side, rule_index, trigger_type, destination_positive, destination_id, action_type, action_data, created_at, station_id, node_name) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, rule.getId());
                ps.setString(2, rule.getContextType());
                ps.setString(3, rule.getContextId());
                ps.setString(4, rule.getContextSide());
                ps.setInt(5, rule.getRuleIndex());
                ps.setString(6, rule.getTriggerType());
                ps.setInt(7, rule.isDestinationPositive() ? 1 : 0);
                ps.setString(8, rule.getDestinationId());
                ps.setString(9, rule.getActionType());
                ps.setString(10, rule.getActionData());
                ps.setLong(11, rule.getCreatedAt());
                ps.setString(12, s);
                ps.setString(13, n);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void update(Rule rule) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE rules SET trigger_type=?, destination_positive=?, destination_id=?, action_type=?, action_data=? WHERE id=?")) {
                ps.setString(1, rule.getTriggerType());
                ps.setInt(2, rule.isDestinationPositive() ? 1 : 0);
                ps.setString(3, rule.getDestinationId());
                ps.setString(4, rule.getActionType());
                ps.setString(5, rule.getActionData());
                ps.setString(6, rule.getId());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void deleteById(String id) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM rules WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Delete all rules for a context (e.g. when deleting a transfer node or terminal). */
    public void deleteByContext(String contextType, String contextId) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM rules WHERE context_type = ? AND context_id = ?")) {
                ps.setString(1, contextType);
                ps.setString(2, contextId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<Rule> findById(String id) {
        return database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, context_type, context_id, context_side, rule_index, trigger_type, destination_positive, destination_id, action_type, action_data, created_at FROM rules WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowToRule(rs)) : Optional.empty();
                }
            }
        });
    }

    /** List rules for a context, ordered by rule_index. */
    public List<Rule> findByContext(String contextType, String contextId, String contextSide) {
        return database.withConnection(conn -> {
            String sql = contextSide == null
                ? "SELECT id, context_type, context_id, context_side, rule_index, trigger_type, destination_positive, destination_id, action_type, action_data, created_at FROM rules WHERE context_type = ? AND context_id = ? AND context_side IS NULL ORDER BY rule_index"
                : "SELECT id, context_type, context_id, context_side, rule_index, trigger_type, destination_positive, destination_id, action_type, action_data, created_at FROM rules WHERE context_type = ? AND context_id = ? AND context_side = ? ORDER BY rule_index";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, contextType);
                ps.setString(2, contextId);
                if (contextSide != null) ps.setString(3, contextSide);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Rule> list = new ArrayList<>();
                    while (rs.next()) list.add(rowToRule(rs));
                    return list;
                }
            }
        });
    }

    /** Next rule_index for this context (max + 1 or 0). */
    public int nextRuleIndex(String contextType, String contextId, String contextSide) {
        return database.withConnection(conn -> {
            String sql = contextSide == null
                ? "SELECT COALESCE(MAX(rule_index), -1) + 1 FROM rules WHERE context_type = ? AND context_id = ? AND context_side IS NULL"
                : "SELECT COALESCE(MAX(rule_index), -1) + 1 FROM rules WHERE context_type = ? AND context_id = ? AND context_side = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, contextType);
                ps.setString(2, contextId);
                if (contextSide != null) ps.setString(3, contextSide);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** After deleting a rule, decrement rule_index for all rules in same context with index > deletedIndex. */
    public void reindexAfterDelete(String contextType, String contextId, String contextSide, int deletedIndex) {
        database.withConnection(conn -> {
            String sql = contextSide == null
                ? "UPDATE rules SET rule_index = rule_index - 1 WHERE context_type = ? AND context_id = ? AND context_side IS NULL AND rule_index > ?"
                : "UPDATE rules SET rule_index = rule_index - 1 WHERE context_type = ? AND context_id = ? AND context_side = ? AND rule_index > ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, contextType);
                ps.setString(2, contextId);
                if (contextSide != null) ps.setString(3, contextSide);
                ps.setInt(contextSide == null ? 3 : 4, deletedIndex);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Reassign rules that were attached to a deleted transfer/terminal node to a newly recreated node with the same station and name. */
    public void reassignRulesToNode(String newNodeId, String stationId, String nodeName) {
        if (stationId == null || nodeName == null) return;
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE rules SET context_id = ? WHERE context_type IN ('transfer','terminal') AND station_id = ? AND LOWER(COALESCE(node_name,'')) = LOWER(?)")) {
                ps.setString(1, newNodeId);
                ps.setString(2, stationId);
                ps.setString(3, nodeName);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static Rule rowToRule(ResultSet rs) throws SQLException {
        return new Rule(
            rs.getString("id"),
            rs.getString("context_type"),
            rs.getString("context_id"),
            rs.getString("context_side"),
            rs.getInt("rule_index"),
            rs.getString("trigger_type"),
            rs.getInt("destination_positive") != 0,
            rs.getString("destination_id"),
            rs.getString("action_type"),
            rs.getString("action_data"),
            rs.getLong("created_at")
        );
    }
}
