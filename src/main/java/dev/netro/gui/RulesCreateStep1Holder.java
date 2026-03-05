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

/** Step 1 of create rule: choose trigger (ENTERING, CLEARING, DETECTED, or BLOCKED). When editing, Save and Next are shown. */
public class RulesCreateStep1Holder implements InventoryHolder {

    public static final int SIZE = 27;
    public static final int SLOT_ENTERING = 10;
    public static final int SLOT_CLEARING = 12;
    /** When the chosen terminal is blocked (full); one rule per terminal. */
    public static final int SLOT_BLOCKED = 14;
    /** Whenever a cart is detected (any pass by the detector). */
    public static final int SLOT_DETECTED = 16;
    public static final int SLOT_BACK = 22;
    /** When editing: save current and return to rules list. */
    public static final int SLOT_SAVE = 20;
    /** When editing: continue to destination step. */
    public static final int SLOT_NEXT = 24;

    private final NetroPlugin plugin;
    private final String contextType;
    private final String contextId;
    private final String contextSide;
    private final String rulesTitle;
    /** When non-null, we are editing this rule; selected trigger is used for highlight and save. */
    private final Rule editRule;
    /** When editing, the trigger currently selected (for display and save). */
    private final String selectedTrigger;
    private final Inventory inventory;

    public RulesCreateStep1Holder(NetroPlugin plugin, String contextType, String contextId, String contextSide, String rulesTitle) {
        this(plugin, contextType, contextId, contextSide, rulesTitle, null, null);
    }

    public RulesCreateStep1Holder(NetroPlugin plugin, String contextType, String contextId, String contextSide, String rulesTitle, Rule editRule) {
        this(plugin, contextType, contextId, contextSide, rulesTitle, editRule, editRule != null ? editRule.getTriggerType() : null);
    }

    public RulesCreateStep1Holder(NetroPlugin plugin, String contextType, String contextId, String contextSide, String rulesTitle, Rule editRule, String selectedTrigger) {
        this.plugin = plugin;
        this.contextType = contextType;
        this.contextId = contextId;
        this.contextSide = contextSide;
        this.rulesTitle = rulesTitle;
        this.editRule = editRule;
        this.selectedTrigger = selectedTrigger != null ? selectedTrigger : (editRule != null ? editRule.getTriggerType() : null);
        String title = editRule != null ? "Rule: Trigger (editing)" : "Rule: Trigger";
        this.inventory = Bukkit.createInventory(this, SIZE, title);
        ItemStack entering = newItem(Material.OAK_DOOR, "When cart enters", List.of("Fires at ENTRY. (READY does not apply rules.)"));
        ItemStack clearing = newItem(Material.REPEATER, "When cart clears", List.of("Fires at CLEAR."));
        ItemStack blocked = newItem(Material.REDSTONE_TORCH, "When terminal blocked", List.of("Slot full; pick terminal, set redirect."));
        ItemStack detected = newItem(Material.DETECTOR_RAIL, "When cart detected", List.of("Fires when cart passes detector (ENTRY or CLEAR)."));
        if (Rule.TRIGGER_ENTERING.equals(this.selectedTrigger)) enchant(entering);
        if (Rule.TRIGGER_CLEARING.equals(this.selectedTrigger)) enchant(clearing);
        if (Rule.TRIGGER_BLOCKED.equals(this.selectedTrigger)) enchant(blocked);
        if (Rule.TRIGGER_DETECTED.equals(this.selectedTrigger)) enchant(detected);
        inventory.setItem(SLOT_ENTERING, entering);
        inventory.setItem(SLOT_CLEARING, clearing);
        inventory.setItem(SLOT_BLOCKED, blocked);
        inventory.setItem(SLOT_DETECTED, detected);
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", List.of("Back to rules.")));
        if (editRule != null) {
            inventory.setItem(SLOT_SAVE, newItem(Material.EMERALD, "Save and return to rules", List.of("Save rule and return to rules list.")));
            inventory.setItem(SLOT_NEXT, newItem(Material.SPECTRAL_ARROW, "Next", List.of("Next: destination.")));
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
    public Rule getEditRule() { return editRule; }
    /** When editing, the trigger to use when saving or going to step 2. */
    public String getSelectedTrigger() { return selectedTrigger; }
    public boolean isEditMode() { return editRule != null; }

    @Override
    public Inventory getInventory() { return inventory; }
}
