package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.model.Rule;
import dev.netro.util.RailStateListEncoder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Small menu for one rail state entry: Delete, Reconfigure, Back. */
public class RailStateEntryOptionsHolder implements InventoryHolder {

    public static final int SIZE = 27;
    public static final int SLOT_DELETE = 11;
    public static final int SLOT_RECONFIGURE = 15;
    public static final int SLOT_BACK = 22;

    private final NetroPlugin plugin;
    private final RulesRailStateListHolder listHolder;
    private final int entryIndex;
    private final String entry;
    private final Inventory inventory;

    public RailStateEntryOptionsHolder(NetroPlugin plugin, RulesRailStateListHolder listHolder, int entryIndex) {
        this.plugin = plugin;
        this.listHolder = listHolder;
        this.entryIndex = entryIndex;
        this.entry = listHolder.getEntryAt(entryIndex);
        this.inventory = Bukkit.createInventory(this, SIZE, "Rail state entry");
        fillLayout();
    }

    private void fillLayout() {
        String display = RailStateListEncoder.formatEntryDisplay(entry);
        inventory.setItem(4, newItem(Material.RAIL, "Entry #" + (entryIndex + 1), List.of(display)));
        inventory.setItem(SLOT_DELETE, newItem(Material.BARRIER, "Delete", List.of("Remove this rail from the rule.")));
        inventory.setItem(SLOT_RECONFIGURE, newItem(Material.REPEATER, "Reconfigure", List.of("Pick a different rail or shape.")));
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", List.of("Back to rail state list.")));
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

    public RulesRailStateListHolder getListHolder() { return listHolder; }
    public int getEntryIndex() { return entryIndex; }
    public String getEntry() { return entry; }

    @Override
    public Inventory getInventory() { return inventory; }
}
