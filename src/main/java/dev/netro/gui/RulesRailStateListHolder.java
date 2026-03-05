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

/**
 * List of rail state entries for one rule (SET_RAIL_STATE). Top slots = entries; Save and return to rules; Create rail state; Back.
 * When there are no entries and we open from Step 3 we skip to "right-click rail" flow instead (handled by caller).
 */
public class RulesRailStateListHolder implements InventoryHolder {

    public static final int SIZE = 54;
    public static final int ENTRIES_START = 0;
    public static final int ENTRIES_END = 44;
    public static final int SLOT_SAVE_AND_RETURN = 45;
    public static final int SLOT_CREATE = 49;
    public static final int SLOT_BACK = 53;

    private final NetroPlugin plugin;
    private final String contextType;
    private final String contextId;
    private final String contextSide;
    private final String rulesTitle;
    private final String triggerType;
    private final boolean destinationPositive;
    private final String destinationId;
    private final Rule editRule;
    /** Encoded rail state entries (entry|entry|... or single entry). */
    private final String encodedRailStateData;
    private final List<String> entries;
    private final Inventory inventory;

    public RulesRailStateListHolder(NetroPlugin plugin, String contextType, String contextId, String contextSide,
                                    String rulesTitle, String triggerType, boolean destinationPositive, String destinationId,
                                    Rule editRule, String encodedRailStateData) {
        this.plugin = plugin;
        this.contextType = contextType;
        this.contextId = contextId;
        this.contextSide = contextSide;
        this.rulesTitle = rulesTitle;
        this.triggerType = triggerType;
        this.destinationPositive = destinationPositive;
        this.destinationId = destinationId;
        this.editRule = editRule;
        this.encodedRailStateData = encodedRailStateData != null ? encodedRailStateData : "";
        this.entries = RailStateListEncoder.parseEntries(this.encodedRailStateData);
        this.inventory = Bukkit.createInventory(this, SIZE, "Rule: Set rail state");
        fillLayout();
    }

    private void fillLayout() {
        for (int i = ENTRIES_START; i <= ENTRIES_END; i++) {
            int idx = i - ENTRIES_START;
            if (idx < entries.size()) {
                String entry = entries.get(idx);
                String display = RailStateListEncoder.formatEntryDisplay(entry);
                inventory.setItem(i, newItem(Material.RAIL, "Rail #" + (idx + 1) + ": " + display,
                    List.of("Click to delete or reconfigure.")));
            } else {
                inventory.setItem(i, null);
            }
        }
        inventory.setItem(SLOT_SAVE_AND_RETURN, newItem(Material.EMERALD, "Save and return to rules", List.of("Save rule and return to rules list.")));
        inventory.setItem(SLOT_CREATE, newItem(Material.WRITABLE_BOOK, "Create rail state", List.of("Right-click a rail with Railroad Controller to add another.")));
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", List.of("Back to action menu (don't save).")));
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

    public boolean isEntrySlot(int slot) {
        return slot >= ENTRIES_START && slot <= ENTRIES_END && (slot - ENTRIES_START) < entries.size();
    }

    public int getEntryIndexForSlot(int slot) {
        if (!isEntrySlot(slot)) return -1;
        return slot - ENTRIES_START;
    }

    public String getEntryAt(int index) {
        if (index < 0 || index >= entries.size()) return null;
        return entries.get(index);
    }

    public NetroPlugin getPlugin() { return plugin; }
    public String getContextType() { return contextType; }
    public String getContextId() { return contextId; }
    public String getContextSide() { return contextSide; }
    public String getRulesTitle() { return rulesTitle; }
    public String getTriggerType() { return triggerType; }
    public boolean isDestinationPositive() { return destinationPositive; }
    public String getDestinationId() { return destinationId; }
    public Rule getEditRule() { return editRule; }
    public String getEncodedRailStateData() { return encodedRailStateData; }
    public List<String> getEntries() { return entries; }

    @Override
    public Inventory getInventory() { return inventory; }
}
