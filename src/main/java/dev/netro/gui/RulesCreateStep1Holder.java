package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.model.Rule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Step 1 of create rule: choose trigger (ENTERING, CLEARING, DETECTED, or BLOCKED). */
public class RulesCreateStep1Holder implements InventoryHolder {

    public static final int SIZE = 27;
    public static final int SLOT_ENTERING = 10;
    public static final int SLOT_CLEARING = 12;
    /** When the chosen terminal is blocked (full); one rule per terminal. */
    public static final int SLOT_BLOCKED = 14;
    /** Whenever a cart is detected (any pass by the detector). */
    public static final int SLOT_DETECTED = 16;
    public static final int SLOT_BACK = 22;

    private final NetroPlugin plugin;
    private final String contextType;
    private final String contextId;
    private final String contextSide;
    private final String rulesTitle;
    private final Inventory inventory;

    public RulesCreateStep1Holder(NetroPlugin plugin, String contextType, String contextId, String contextSide, String rulesTitle) {
        this.plugin = plugin;
        this.contextType = contextType;
        this.contextId = contextId;
        this.contextSide = contextSide;
        this.rulesTitle = rulesTitle;
        this.inventory = Bukkit.createInventory(this, SIZE, "Rule: Trigger");
        inventory.setItem(SLOT_ENTERING, newItem(Material.LIME_DYE, "When cart enters", List.of("Trigger: ENTERING", "Fires when cart passes ENTRY/READY.")));
        inventory.setItem(SLOT_CLEARING, newItem(Material.ORANGE_DYE, "When cart clears", List.of("Trigger: CLEARING", "Fires when cart passes CLEAR.")));
        inventory.setItem(SLOT_BLOCKED, newItem(Material.REDSTONE_TORCH, "When terminal blocked", List.of("Trigger: BLOCKED", "When the chosen terminal slot is full. Pick a terminal, then set redirect destination.")));
        inventory.setItem(SLOT_DETECTED, newItem(Material.MINECART, "When cart detected", List.of("Trigger: DETECTED", "Fires whenever a cart passes the detector (any direction).")));
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", List.of("Return to rules list.")));
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
    public String getContextType() { return contextType; }
    public String getContextId() { return contextId; }
    public String getContextSide() { return contextSide; }
    public String getRulesTitle() { return rulesTitle; }

    @Override
    public Inventory getInventory() { return inventory; }
}
