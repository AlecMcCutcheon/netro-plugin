package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.database.RouteCacheRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Rule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows cached routes for this station and (when opened from a terminal/transfer) only routes that use that node as first hop.
 * Slots 0–44: route rows (click to remove). Slot 52: Clear all (for this node only). Slot 53: Back to Rules.
 */
public class RoutesHolder implements InventoryHolder {

    public static final int SIZE = 54;
    public static final int ROUTES_START = 0;
    public static final int ROUTES_END = 44;
    public static final int SLOT_CLEAR_ALL = 52;
    public static final int SLOT_BACK = 53;

    private final NetroPlugin plugin;
    private final String fromStationId;
    private final String contextType;
    private final String contextId;
    private final String contextSide;
    private final String title;
    private final Inventory inventory;
    private final List<RouteCacheRepository.RouteCacheRow> rows = new ArrayList<>();

    public RoutesHolder(NetroPlugin plugin, String fromStationId, String contextType, String contextId, String contextSide, String title) {
        this.plugin = plugin;
        this.fromStationId = fromStationId;
        this.contextType = contextType;
        this.contextId = contextId;
        this.contextSide = contextSide;
        this.title = title == null || title.isEmpty() ? "Routes" : title;
        this.inventory = Bukkit.createInventory(this, SIZE, this.title);
        fill();
    }

    private void fill() {
        inventory.clear();
        rows.clear();
        RouteCacheRepository repo = new RouteCacheRepository(plugin.getDatabase());
        StationRepository stationRepo = new StationRepository(plugin.getDatabase());
        TransferNodeRepository nodeRepo = new TransferNodeRepository(plugin.getDatabase());
        boolean isTerminal = Rule.CONTEXT_TERMINAL.equals(contextType);
        rows.addAll(isTerminal ? repo.findByFromStation(fromStationId) : repo.findByFromStationAndFirstHop(fromStationId, contextId));
        for (int i = 0; i < rows.size() && i <= ROUTES_END - ROUTES_START; i++) {
            RouteCacheRepository.RouteCacheRow r = rows.get(i);
            String destName = stationRepo.findById(r.destStationId()).map(s -> s.getName()).orElse(r.destStationId());
            String firstHopLabel = RulesMainHolder.formatStationNode(r.firstHopNodeId(), stationRepo, nodeRepo);
            List<String> lore = new ArrayList<>();
            lore.add("To: " + destName);
            lore.add("First hop: " + firstHopLabel);
            lore.add("Cost: " + r.cost());
            lore.add("Click to remove from cache");
            inventory.setItem(ROUTES_START + i, newItem(Material.RAIL, destName + " \u2192 " + firstHopLabel, lore));
        }
        String clearLore = isTerminal ? "Remove all cached routes from this station." : (contextId != null && !contextId.isEmpty() ? "Remove all cached routes for this transfer." : "Remove all cached routes from this station.");
        inventory.setItem(SLOT_CLEAR_ALL, newItem(Material.BARRIER, "Clear all", List.of(clearLore)));
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back to Rules", List.of("Close and return to rules.")));
    }

    private static ItemStack newItem(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) meta.setLore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    public NetroPlugin getPlugin() { return plugin; }
    public String getFromStationId() { return fromStationId; }
    public String getContextType() { return contextType; }
    public String getContextId() { return contextId; }
    public String getContextSide() { return contextSide; }
    public String getTitle() { return title; }
    public List<RouteCacheRepository.RouteCacheRow> getRows() { return new ArrayList<>(rows); }

    /** Route row at slot (0-based index into rows), or -1 if not a route slot / out of range. */
    public int getRouteIndexAtSlot(int slot) {
        if (slot < ROUTES_START || slot > ROUTES_END) return -1;
        int idx = slot - ROUTES_START;
        return idx < rows.size() ? idx : -1;
    }

    public RouteCacheRepository.RouteCacheRow getRouteAtSlot(int slot) {
        int idx = getRouteIndexAtSlot(slot);
        return idx >= 0 && idx < rows.size() ? rows.get(idx) : null;
    }

    public void refresh() {
        fill();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
