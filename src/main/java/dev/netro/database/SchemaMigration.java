package dev.netro.database;

import dev.netro.NetroPlugin;
import dev.netro.model.Rule;
import dev.netro.model.Station;
import dev.netro.util.AddressHelper;
import dev.netro.util.DestinationResolver;
import dev.netro.util.DimensionHelper;
import dev.netro.util.SignColors;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Migrates existing database: dimension column, 2D addresses from coordinates, and sign updates.
 */
public final class SchemaMigration {

    private final NetroPlugin plugin;
    private final Database database;

    public SchemaMigration(NetroPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    /**
     * Run migrations that require the database and plugin (e.g. to update sign blocks).
     * Call after Database.initialize().
     */
    public void run() {
        database.withConnection(conn -> {
            if (!hasDimensionColumn(conn)) {
                addDimensionColumn(conn);
                backfillDimensionOnly(conn);
            }
            ensurePortalLinkTable(conn);
            ensureRouteCacheTable(conn);
            return null;
        });
        migrateTo2DAddresses();
        database.withConnection(this::clearOldFormatCartDestinations);
        migrateRulesDestinationToNewFormat();
        updateStationSignsInWorld();
    }

    private void ensurePortalLinkTable(Connection conn) throws SQLException {
        boolean tableExists = false;
        try (ResultSet rs = conn.createStatement().executeQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='transfer_node_portal_blocks'")) {
            tableExists = rs.next();
        }
        if (!tableExists) {
            createPortalLinkTableWithSide(conn);
            plugin.getLogger().info("[Netro migration] Created transfer_node_portal_blocks table (with side) for nether portal links.");
            return;
        }
        if (hasPortalBlocksSideColumn(conn)) return;

        try (java.sql.Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE transfer_node_portal_blocks_new (" +
                "node_id TEXT NOT NULL REFERENCES transfer_nodes(id) ON DELETE CASCADE," +
                "side INTEGER NOT NULL DEFAULT 0," +
                "world TEXT NOT NULL," +
                "x INTEGER NOT NULL," +
                "y INTEGER NOT NULL," +
                "z INTEGER NOT NULL," +
                "PRIMARY KEY (node_id, side, world, x, y, z))");
            st.execute("CREATE INDEX idx_tn_portal_blocks_node_new ON transfer_node_portal_blocks_new(node_id)");
            st.execute("CREATE INDEX idx_tn_portal_blocks_block_new ON transfer_node_portal_blocks_new(world, x, y, z)");
            st.execute("INSERT INTO transfer_node_portal_blocks_new (node_id, side, world, x, y, z) SELECT node_id, 0, world, x, y, z FROM transfer_node_portal_blocks");
            st.execute("DROP TABLE transfer_node_portal_blocks");
            st.execute("ALTER TABLE transfer_node_portal_blocks_new RENAME TO transfer_node_portal_blocks");
            st.execute("CREATE INDEX IF NOT EXISTS idx_tn_portal_blocks_node ON transfer_node_portal_blocks(node_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_tn_portal_blocks_block ON transfer_node_portal_blocks(world, x, y, z)");
        }
        plugin.getLogger().info("[Netro migration] Added side column to transfer_node_portal_blocks (existing rows as side=0).");
    }

    private void createPortalLinkTableWithSide(Connection conn) throws SQLException {
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS transfer_node_portal_blocks (" +
                "node_id TEXT NOT NULL REFERENCES transfer_nodes(id) ON DELETE CASCADE," +
                "side INTEGER NOT NULL DEFAULT 0," +
                "world TEXT NOT NULL," +
                "x INTEGER NOT NULL," +
                "y INTEGER NOT NULL," +
                "z INTEGER NOT NULL," +
                "PRIMARY KEY (node_id, side, world, x, y, z))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_tn_portal_blocks_node ON transfer_node_portal_blocks(node_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_tn_portal_blocks_block ON transfer_node_portal_blocks(world, x, y, z)");
        }
    }

    private static boolean hasPortalBlocksSideColumn(Connection conn) throws SQLException {
        try (ResultSet rs = conn.createStatement().executeQuery("PRAGMA table_info(transfer_node_portal_blocks)")) {
            while (rs.next()) {
                if ("side".equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    private void ensureRouteCacheTable(Connection conn) throws SQLException {
        try (ResultSet rs = conn.createStatement().executeQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='route_cache'")) {
            if (rs.next()) return;
        }
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS route_cache (" +
                "from_station_id   TEXT NOT NULL REFERENCES stations(id) ON DELETE CASCADE," +
                "dest_station_id   TEXT NOT NULL REFERENCES stations(id) ON DELETE CASCADE," +
                "first_hop_node_id TEXT NOT NULL REFERENCES transfer_nodes(id) ON DELETE CASCADE," +
                "cost              INTEGER NOT NULL," +
                "updated_at        INTEGER NOT NULL," +
                "PRIMARY KEY (from_station_id, dest_station_id))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_route_cache_from ON route_cache(from_station_id)");
        }
        plugin.getLogger().info("[Netro migration] Created route_cache table for routing cache.");
    }

    private static boolean hasDimensionColumn(Connection conn) throws SQLException {
        try (ResultSet rs = conn.createStatement().executeQuery("PRAGMA table_info(stations)")) {
            while (rs.next()) {
                if ("dimension".equalsIgnoreCase(rs.getString("name")))
                    return true;
            }
        }
        return false;
    }

    private void addDimensionColumn(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "ALTER TABLE stations ADD COLUMN dimension INTEGER NOT NULL DEFAULT 0")) {
            ps.executeUpdate();
        }
        plugin.getLogger().info("[Netro migration] Added dimension column to stations.");
    }

    private void backfillDimensionOnly(Connection conn) throws SQLException {
        List<Station> stations = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT id, name, address, world, dimension, sign_x, sign_y, sign_z, created_at FROM stations");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                stations.add(new Station(
                    rs.getString("id"),
                    rs.getString("name"),
                    rs.getString("address"),
                    rs.getString("world"),
                    rs.getInt("dimension"),
                    rs.getInt("sign_x"),
                    rs.getInt("sign_y"),
                    rs.getInt("sign_z"),
                    rs.getLong("created_at")));
            }
        }
        for (Station s : stations) {
            int dimension = s.getDimension();
            if (dimension == 0) {
                org.bukkit.World w = plugin.getServer().getWorld(s.getWorld());
                if (w != null)
                    dimension = DimensionHelper.dimensionFromEnvironment(w.getEnvironment());
            }
            if (dimension != s.getDimension()) {
                try (PreparedStatement upd = conn.prepareStatement("UPDATE stations SET dimension = ? WHERE id = ?")) {
                    upd.setInt(1, dimension);
                    upd.setString(2, s.getId());
                    upd.executeUpdate();
                }
            }
        }
        plugin.getLogger().info("[Netro migration] Backfilled dimension for " + stations.size() + " stations.");
    }

    /**
     * Recompute every station's address from its <b>actual sign block position</b> (sign_x, sign_z, dimension)
     * using the current 2D format (6-part colon: OV:E2:N3:01:02:05; 2×2 cluster/localnet quads).
     * Groups by 100m×100m quadrant cell (regionPrefix2D from sign coords), assigns deterministic
     * station index per cell, updates DB and signs. Runs whenever any station has a non-current
     * (non–6-part colon) address, so re-running the server will re-migrate all stations.
     */
    private void migrateTo2DAddresses() {
        StationRepository repo = new StationRepository(database);
        List<Station> stations = repo.findAll();
        if (stations.isEmpty()) return;

        boolean anyOldFormat = stations.stream().anyMatch(s -> !AddressHelper.isNewFormatStationAddress(s.getAddress()));
        if (!anyOldFormat) {
            return;
        }

        Comparator<Station> order = Comparator
            .comparingInt(Station::getSignX)
            .thenComparingInt(Station::getSignZ)
            .thenComparingInt(Station::getSignY)
            .thenComparingLong(Station::getCreatedAt);

        Map<String, List<Station>> byCell = new LinkedHashMap<>();
        for (Station s : stations) {
            int dim = s.getDimension();
            String cell = AddressHelper.regionPrefix2D(dim, s.getSignX(), s.getSignZ());
            byCell.computeIfAbsent(cell, k -> new ArrayList<>()).add(s);
        }
        for (List<Station> cellStations : byCell.values()) {
            cellStations.sort(order);
        }

        int updated = 0;
        for (Station s : stations) {
            int dim = s.getDimension();
            int x = s.getSignX();
            int z = s.getSignZ();
            String cell = AddressHelper.regionPrefix2D(dim, x, z);
            List<Station> cellStations = byCell.get(cell);
            int indexInCell = cellStations.indexOf(s) + 1;
            String newAddress = AddressHelper.stationAddress(dim, x, z, indexInCell);
            if (!newAddress.equals(s.getAddress())) {
                repo.updateAddressAndDimension(s.getId(), newAddress, dim);
                org.bukkit.World w = plugin.getServer().getWorld(s.getWorld());
                if (w != null) {
                    Block block = w.getBlockAt(s.getSignX(), s.getSignY(), s.getSignZ());
                    if (block.getState() instanceof Sign sign) {
                        SignColors.applyStationSign(sign, s.getName(), newAddress);
                        sign.update();
                    }
                }
                updated++;
            }
        }
        if (updated > 0)
            plugin.getLogger().info("[Netro migration] Migrated " + updated + " stations to 2D addresses and updated signs.");
    }

    /**
     * Convert rules' destination_id and action_data from old format (1D/numeric or unknown) to new 6/7-part addresses
     * when they can be resolved (e.g. Station:Node or station name still resolves via DestinationResolver).
     */
    private void migrateRulesDestinationToNewFormat() {
        List<RuleRow> rows = new ArrayList<>();
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, destination_id, action_type, action_data FROM rules WHERE (destination_id IS NOT NULL AND destination_id != '') OR (action_type = 'SET_DESTINATION' AND action_data IS NOT NULL AND action_data != '')");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new RuleRow(
                        rs.getString("id"),
                        rs.getString("destination_id"),
                        rs.getString("action_type"),
                        rs.getString("action_data")));
                }
            }
            return null;
        });
        if (rows.isEmpty()) return;
        StationRepository stationRepo = new StationRepository(database);
        TransferNodeRepository nodeRepo = new TransferNodeRepository(database);
        int updated = 0;
        for (RuleRow row : rows) {
            String newDestId = DestinationResolver.normalizeToNewFormatForStorage(stationRepo, nodeRepo, row.destinationId);
            String newActionData = Rule.ACTION_SET_DESTINATION.equals(row.actionType)
                ? DestinationResolver.normalizeToNewFormatForStorage(stationRepo, nodeRepo, row.actionData)
                : row.actionData;
            if (newDestId != null && !newDestId.equals(row.destinationId)) {
                updateRuleDestination(row.id, newDestId, newActionData);
                updated++;
            } else if (newActionData != null && !newActionData.equals(row.actionData)) {
                updateRuleActionData(row.id, row.destinationId, newActionData);
                updated++;
            }
        }
        if (updated > 0) {
            plugin.getLogger().info("[Netro migration] Converted " + updated + " rule(s) destination/action to new address format.");
        }
    }

    private static final class RuleRow {
        final String id;
        final String destinationId;
        final String actionType;
        final String actionData;

        RuleRow(String id, String destinationId, String actionType, String actionData) {
            this.id = id;
            this.destinationId = destinationId;
            this.actionType = actionType;
            this.actionData = actionData;
        }
    }

    private void updateRuleDestination(String ruleId, String newDestinationId, String newActionData) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE rules SET destination_id = ?, action_data = ? WHERE id = ?")) {
                ps.setString(1, newDestinationId);
                ps.setString(2, newActionData);
                ps.setString(3, ruleId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void updateRuleActionData(String ruleId, String destinationId, String newActionData) {
        database.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE rules SET destination_id = ?, action_data = ? WHERE id = ?")) {
                ps.setString(1, destinationId);
                ps.setString(2, newActionData);
                ps.setString(3, ruleId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Clear cart destination_address when it is old (1D) format so routing uses only new addresses. */
    private Integer clearOldFormatCartDestinations(Connection conn) throws SQLException {
        List<String> cartIdsToClear = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT cart_uuid, destination_address FROM cart_segments WHERE destination_address IS NOT NULL AND destination_address != ''");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String addr = rs.getString("destination_address");
                if (addr == null || addr.isBlank()) continue;
                boolean oldFormat = AddressHelper.parseDestination(addr).isEmpty();
                if (oldFormat)
                    cartIdsToClear.add(rs.getString("cart_uuid"));
            }
        }
        int n = 0;
        for (String cartUuid : cartIdsToClear) {
            try (PreparedStatement upd = conn.prepareStatement("UPDATE cart_segments SET destination_address = NULL, origin_station_id = NULL WHERE cart_uuid = ?")) {
                upd.setString(1, cartUuid);
                n += upd.executeUpdate();
            }
        }
        if (n > 0)
            plugin.getLogger().info("[Netro migration] Cleared " + n + " cart destination(s) with old address format.");
        return null;
    }

    private void updateStationSignsInWorld() {
        StationRepository repo = new StationRepository(database);
        List<Station> stations = repo.findAll();
        int updated = 0;
        for (Station s : stations) {
            org.bukkit.World w = plugin.getServer().getWorld(s.getWorld());
            if (w == null) continue;
            Block block = w.getBlockAt(s.getSignX(), s.getSignY(), s.getSignZ());
            if (!(block.getState() instanceof Sign sign)) continue;
            String currentLine2 = getSignLine(sign, 2);
            String expectedAddress = s.getAddress();
            if (currentLine2 == null || currentLine2.isBlank() || !stripColor(currentLine2).equals(stripColor(expectedAddress))) {
                SignColors.applyStationSign(sign, s.getName(), expectedAddress);
                sign.update();
                updated++;
            }
        }
        if (updated > 0)
            plugin.getLogger().info("[Netro migration] Updated address on " + updated + " station signs.");
    }

    private static String getSignLine(Sign sign, int index) {
        SignSide front = sign.getSide(Side.FRONT);
        return front != null && index >= 0 && index < 4 ? front.getLine(index) : null;
    }

    private static String stripColor(String line) {
        if (line == null) return "";
        return line.replaceAll("§[0-9a-fk-or]", "");
    }
}
