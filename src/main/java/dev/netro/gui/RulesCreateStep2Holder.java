package dev.netro.gui;

import dev.netro.NetroPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Step 2 of create rule: choose "going to destination N" or "not going to N", then pick destination (or Any/Not any). */
public class RulesCreateStep2Holder implements InventoryHolder {

    public static final int SIZE = 27;
    /** Opens destination picker for a specific destination (going to N). */
    public static final int SLOT_GOING_TO = 10;
    /** Opens destination picker for not going to N. */
    public static final int SLOT_NOT_GOING_TO = 12;
    public static final int SLOT_ANY = 14;
    public static final int SLOT_NOT_ANY = 16;
    public static final int SLOT_BACK = 22;

    private final NetroPlugin plugin;
    private final String contextType;
    private final String contextId;
    private final String contextSide;
    private final String rulesTitle;
    private final String triggerType;
    private final Inventory inventory;

    public RulesCreateStep2Holder(NetroPlugin plugin, String contextType, String contextId, String contextSide,
                                  String rulesTitle, String triggerType) {
        this.plugin = plugin;
        this.contextType = contextType;
        this.contextId = contextId;
        this.contextSide = contextSide;
        this.rulesTitle = rulesTitle;
        this.triggerType = triggerType;
        this.inventory = Bukkit.createInventory(this, SIZE, "Rule: Destination");
        inventory.setItem(SLOT_GOING_TO, newItem(Material.LIME_DYE, "Going to destination...", List.of("Cart is going to a specific destination.", "Opens list of destinations for this context.")));
        inventory.setItem(SLOT_NOT_GOING_TO, newItem(Material.ORANGE_DYE, "Not going to destination...", List.of("Cart is not going to a specific destination.", "Opens list of destinations for this context.")));
        inventory.setItem(SLOT_ANY, newItem(Material.MINECART, "Any destination", List.of("When cart has any destination (no specific target).")));
        inventory.setItem(SLOT_NOT_ANY, newItem(Material.BARRIER, "Not any destination", List.of("When cart has no destination.")));
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", List.of("Return to trigger choice.")));
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
    public String getTriggerType() { return triggerType; }

    @Override
    public Inventory getInventory() { return inventory; }
}
