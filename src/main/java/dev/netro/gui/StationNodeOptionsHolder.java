package dev.netro.gui;

import dev.netro.NetroPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** After clicking a node in the station list: Open rules or Delete. */
public class StationNodeOptionsHolder implements InventoryHolder {

    public static final int SIZE = 27;
    public static final int SLOT_BACK = 0;
    public static final int SLOT_OPEN_RULES = 12;
    public static final int SLOT_RELOCATE = 13;
    public static final int SLOT_DELETE = 14;

    private final NetroPlugin plugin;
    private final String stationId;
    private final String stationName;
    private final String nodeId;
    private final String contextType;
    private final String nodeDisplayName;
    private final Inventory inventory;

    public StationNodeOptionsHolder(NetroPlugin plugin, String stationId, String stationName,
                                    String nodeId, String contextType, String nodeDisplayName) {
        this.plugin = plugin;
        this.stationId = stationId;
        this.stationName = stationName;
        this.nodeId = nodeId;
        this.contextType = contextType;
        this.nodeDisplayName = nodeDisplayName == null ? nodeId : nodeDisplayName;
        this.inventory = Bukkit.createInventory(this, SIZE, this.nodeDisplayName + " — Options");
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", List.of("Return to station list.")));
        inventory.setItem(SLOT_OPEN_RULES, newItem(Material.BOOK, "Open rules", List.of("Open rules for this " + contextType + ".")));
        inventory.setItem(SLOT_RELOCATE, newItem(Material.ENDER_PEARL, "Relocate", List.of("Move a detector or controller to a new spot. Click the block to move to (placed above).")));
        inventory.setItem(SLOT_DELETE, newItem(Material.RED_WOOL, "Delete", List.of("Delete this " + contextType + ". Requires confirmation.")));
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
    public String getStationId() { return stationId; }
    public String getStationName() { return stationName; }
    public String getNodeId() { return nodeId; }
    public String getContextType() { return contextType; }
    public String getNodeDisplayName() { return nodeDisplayName; }

    public boolean isBackSlot(int slot) { return slot == SLOT_BACK; }
    public boolean isOpenRulesSlot(int slot) { return slot == SLOT_OPEN_RULES; }
    public boolean isRelocateSlot(int slot) { return slot == SLOT_RELOCATE; }
    public boolean isDeleteSlot(int slot) { return slot == SLOT_DELETE; }

    @Override
    public Inventory getInventory() { return inventory; }
}
