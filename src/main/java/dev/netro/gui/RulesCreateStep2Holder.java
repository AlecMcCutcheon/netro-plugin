package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.model.Rule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Step 2 of create rule: choose "going to destination N" or "not going to N", then pick destination (or Any/Not any). When editing, Save and Next are shown. */
public class RulesCreateStep2Holder implements InventoryHolder {

    public static final int SIZE = 27;
    public static final int SLOT_GOING_TO = 10;
    public static final int SLOT_NOT_GOING_TO = 12;
    public static final int SLOT_ANY = 14;
    public static final int SLOT_NOT_ANY = 16;
    public static final int SLOT_BACK = 22;
    public static final int SLOT_SAVE = 20;
    public static final int SLOT_NEXT = 24;

    private final NetroPlugin plugin;
    private final String contextType;
    private final String contextId;
    private final String contextSide;
    private final String rulesTitle;
    private final String triggerType;
    private final Rule editRule;
    private final Inventory inventory;

    public RulesCreateStep2Holder(NetroPlugin plugin, String contextType, String contextId, String contextSide,
                                  String rulesTitle, String triggerType) {
        this(plugin, contextType, contextId, contextSide, rulesTitle, triggerType, null);
    }

    public RulesCreateStep2Holder(NetroPlugin plugin, String contextType, String contextId, String contextSide,
                                  String rulesTitle, String triggerType, Rule editRule) {
        this.plugin = plugin;
        this.contextType = contextType;
        this.contextId = contextId;
        this.contextSide = contextSide;
        this.rulesTitle = rulesTitle;
        this.triggerType = triggerType;
        this.editRule = editRule;
        String title = editRule != null ? "Rule: Destination (editing)" : "Rule: Destination";
        this.inventory = Bukkit.createInventory(this, SIZE, title);
        ItemStack goingTo = newItem(Material.LIME_DYE, "Going to destination...", List.of("Going to a destination."));
        ItemStack notGoingTo = newItem(Material.ORANGE_DYE, "Not going to destination...", List.of("Not going to a destination."));
        ItemStack any = newItem(Material.MINECART, "Any destination", List.of("Any destination."));
        ItemStack notAny = newItem(Material.BARRIER, "Not any destination", List.of("No destination."));
        if (editRule != null) {
            boolean pos = editRule.isDestinationPositive();
            String destId = editRule.getDestinationId();
            if (pos && destId != null && !destId.isEmpty()) enchant(goingTo);
            else if (!pos && destId != null && !destId.isEmpty()) enchant(notGoingTo);
            else if (pos) enchant(any);
            else enchant(notAny);
        }
        inventory.setItem(SLOT_GOING_TO, goingTo);
        inventory.setItem(SLOT_NOT_GOING_TO, notGoingTo);
        inventory.setItem(SLOT_ANY, any);
        inventory.setItem(SLOT_NOT_ANY, notAny);
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", List.of("Back.")));
        if (editRule != null) {
            inventory.setItem(SLOT_SAVE, newItem(Material.EMERALD, "Save and return to rules", List.of("Save rule and return to rules list.")));
            inventory.setItem(SLOT_NEXT, newItem(Material.SPECTRAL_ARROW, "Next", List.of("Next: action.")));
        }
    }

    private static void enchant(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            if (meta.hasDisplayName()) {
                String name = meta.getDisplayName();
                if (name != null && !name.startsWith("§l")) meta.setDisplayName("§l" + name);
            }
        }
        stack.setItemMeta(meta);
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
    public Rule getEditRule() { return editRule; }
    public boolean isEditMode() { return editRule != null; }

    @Override
    public Inventory getInventory() { return inventory; }
}
