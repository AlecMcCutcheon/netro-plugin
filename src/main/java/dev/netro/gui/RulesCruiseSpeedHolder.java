package dev.netro.gui;

import dev.netro.NetroPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import dev.netro.model.Rule;

import java.util.List;

/**
 * Pick cruise speed as first digit (0-9) and second digit (0-9) → speed X.Y (0.0–9.9).
 * Two numpads: 1-9 in 3x3, 0 below center. Empty row 0, empty cols 0 and 8, empty center col 4.
 * Cancel bottom-left, Confirm bottom-right. Selected digits shown enchanted.
 */
public class RulesCruiseSpeedHolder implements InventoryHolder {

    public static final int SIZE = 54;
    /** First numpad: cols 1-3, rows 1-4. Slot → digit (1-9 in 3x3, 0 below center). */
    private static final int[] FIRST_NUMPAD_SLOTS = { 10, 11, 12, 19, 20, 21, 28, 29, 30, 38 };
    private static final int[] FIRST_NUMPAD_DIGITS = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 0 };
    /** Second numpad: cols 5-7, rows 1-4. */
    private static final int[] SECOND_NUMPAD_SLOTS = { 14, 15, 16, 23, 24, 25, 32, 33, 34, 42 };
    private static final int[] SECOND_NUMPAD_DIGITS = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 0 };
    /** Row 0: label centered above first numpad (col 2), label centered above second numpad (col 6). */
    private static final int SLOT_LABEL_WHOLE = 2;
    private static final int SLOT_LABEL_TENTHS = 6;
    public static final int SLOT_CANCEL = 45;
    public static final int SLOT_CONFIRM = 53;

    private final NetroPlugin plugin;
    private final String contextType;
    private final String contextId;
    private final String contextSide;
    private final String rulesTitle;
    private final String triggerType;
    private final boolean destinationPositive;
    private final String destinationId;
    private final Rule editRule;
    private final Inventory inventory;
    /** 0-9 or null if not selected. */
    private Integer firstDigit = null;
    private Integer secondDigit = null;

    public RulesCruiseSpeedHolder(NetroPlugin plugin, String contextType, String contextId, String contextSide,
                                  String rulesTitle, String triggerType, boolean destinationPositive, String destinationId) {
        this(plugin, contextType, contextId, contextSide, rulesTitle, triggerType, destinationPositive, destinationId, null);
    }

    public RulesCruiseSpeedHolder(NetroPlugin plugin, String contextType, String contextId, String contextSide,
                                  String rulesTitle, String triggerType, boolean destinationPositive, String destinationId, Rule editRule) {
        this.plugin = plugin;
        this.contextType = contextType;
        this.contextId = contextId;
        this.contextSide = contextSide;
        this.rulesTitle = rulesTitle;
        this.triggerType = triggerType;
        this.destinationPositive = destinationPositive;
        this.destinationId = destinationId;
        this.editRule = editRule;
        this.inventory = Bukkit.createInventory(this, SIZE, editRule != null ? "Rule: Cruise speed (editing)" : "Rule: Cruise speed");
        if (editRule != null && editRule.getActionData() != null) {
            String data = editRule.getActionData();
            int dot = data.indexOf('.');
            if (dot >= 0 && dot + 1 < data.length()) {
                try {
                    int f = Integer.parseInt(data.substring(0, dot).trim());
                    int s = Integer.parseInt(data.substring(dot + 1).trim());
                    if (f >= 0 && f <= 9 && s >= 0 && s <= 9) {
                        firstDigit = f;
                        secondDigit = s;
                    }
                } catch (NumberFormatException ignored) { }
            }
        }
        fillLayout();
    }

    private void fillLayout() {
        inventory.clear();
        inventory.setItem(SLOT_LABEL_WHOLE, newItem(Material.OAK_SIGN, "Whole number", List.of("First digit 0–9.")));
        inventory.setItem(SLOT_LABEL_TENTHS, newItem(Material.CLOCK, "Tenths", List.of("Second digit 0–9.")));
        for (int i = 0; i < FIRST_NUMPAD_SLOTS.length; i++) {
            int slot = FIRST_NUMPAD_SLOTS[i];
            int digit = FIRST_NUMPAD_DIGITS[i];
            inventory.setItem(slot, digitItem(digit, firstDigit != null && firstDigit == digit));
        }
        for (int i = 0; i < SECOND_NUMPAD_SLOTS.length; i++) {
            int slot = SECOND_NUMPAD_SLOTS[i];
            int digit = SECOND_NUMPAD_DIGITS[i];
            inventory.setItem(slot, digitItem(digit, secondDigit != null && secondDigit == digit));
        }
        inventory.setItem(SLOT_CANCEL, newItem(Material.RED_WOOL, "Cancel", List.of("Back.")));
        String confirmLore = editRule != null ? "Update rule with speed " + formatSpeed() + "." : "Create rule with speed " + formatSpeed() + ".";
        inventory.setItem(SLOT_CONFIRM, newItem(Material.LIME_WOOL, "Confirm", List.of(confirmLore)));
    }

    private ItemStack digitItem(int digit, boolean selected) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String name = String.valueOf(digit);
            meta.setDisplayName(selected ? "§l" + name : name);
            if (selected) meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        }
        stack.setItemMeta(meta);
        return stack;
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

    private String formatSpeed() {
        if (firstDigit == null && secondDigit == null) return "—";
        int f = firstDigit != null ? firstDigit : 0;
        int s = secondDigit != null ? secondDigit : 0;
        return f + "." + s;
    }

    /** Speed string for rule action_data, e.g. "2.5". Default "0.0" if none selected. */
    public String getSpeedString() {
        int f = firstDigit != null ? firstDigit : 0;
        int s = secondDigit != null ? secondDigit : 0;
        return f + "." + s;
    }

    public void setFirstDigit(int slot) {
        Integer digit = digitAtFirstNumpad(slot);
        if (digit != null) {
            firstDigit = digit;
            fillLayout();
        }
    }

    public void setSecondDigit(int slot) {
        Integer digit = digitAtSecondNumpad(slot);
        if (digit != null) {
            secondDigit = digit;
            fillLayout();
        }
    }

    private static Integer digitAtFirstNumpad(int slot) {
        for (int i = 0; i < FIRST_NUMPAD_SLOTS.length; i++) {
            if (FIRST_NUMPAD_SLOTS[i] == slot) return FIRST_NUMPAD_DIGITS[i];
        }
        return null;
    }

    private static Integer digitAtSecondNumpad(int slot) {
        for (int i = 0; i < SECOND_NUMPAD_SLOTS.length; i++) {
            if (SECOND_NUMPAD_SLOTS[i] == slot) return SECOND_NUMPAD_DIGITS[i];
        }
        return null;
    }

    public boolean isFirstDigitSlot(int slot) {
        return digitAtFirstNumpad(slot) != null;
    }

    public boolean isSecondDigitSlot(int slot) {
        return digitAtSecondNumpad(slot) != null;
    }

    public boolean isCancelSlot(int slot) { return slot == SLOT_CANCEL; }
    public boolean isConfirmSlot(int slot) { return slot == SLOT_CONFIRM; }

    public NetroPlugin getPlugin() { return plugin; }
    public String getContextType() { return contextType; }
    public String getContextId() { return contextId; }
    public String getContextSide() { return contextSide; }
    public String getRulesTitle() { return rulesTitle; }
    public String getTriggerType() { return triggerType; }
    public boolean isDestinationPositive() { return destinationPositive; }
    public String getDestinationId() { return destinationId; }
    public Rule getEditRule() { return editRule; }

    @Override
    public Inventory getInventory() { return inventory; }
}
