package dev.netro.gui;

import dev.netro.NetroPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Confirmation before deleting a transfer node or terminal from the station UI. */
public class StationNodeConfirmDeleteHolder implements InventoryHolder {

    public static final int SIZE = 27;
    public static final int SLOT_CANCEL = 11;
    public static final int SLOT_CONFIRM = 15;

    private final NetroPlugin plugin;
    private final String stationId;
    private final String stationName;
    private final String nodeId;
    private final String contextType;
    private final String nodeDisplayName;
    private final Inventory inventory;

    public StationNodeConfirmDeleteHolder(NetroPlugin plugin, String stationId, String stationName,
                                          String nodeId, String contextType, String nodeDisplayName) {
        this.plugin = plugin;
        this.stationId = stationId;
        this.stationName = stationName;
        this.nodeId = nodeId;
        this.contextType = contextType;
        this.nodeDisplayName = nodeDisplayName == null ? nodeId : nodeDisplayName;
        this.inventory = Bukkit.createInventory(this, SIZE, "Delete " + this.nodeDisplayName + "?");
        inventory.setItem(SLOT_CANCEL, newItem(Material.LIME_WOOL, "Cancel", List.of("Keep this " + contextType + ". Return to options.")));
        inventory.setItem(SLOT_CONFIRM, newItem(Material.RED_WOOL, "Delete", List.of("Permanently delete this " + contextType + ". Detectors and controllers will be removed.")));
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

    public boolean isCancelSlot(int slot) { return slot == SLOT_CANCEL; }
    public boolean isConfirmSlot(int slot) { return slot == SLOT_CONFIRM; }

    @Override
    public Inventory getInventory() { return inventory; }
}
