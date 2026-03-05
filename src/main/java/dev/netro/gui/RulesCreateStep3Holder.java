package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.database.RuleRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Rule;
import dev.netro.util.DestinationResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.UUID;

import org.bukkit.enchantments.Enchantment;

/** Step 3 of create rule: choose action (SEND_ON, SEND_OFF, SET_RAIL_STATE, SET_CRUISE_SPEED). When editing, Save is shown. */
public class RulesCreateStep3Holder implements InventoryHolder {

    public static final int SIZE = 27;
    public static final int SLOT_SEND_ON = 10;
    public static final int SLOT_SEND_OFF = 12;
    public static final int SLOT_SET_RAIL = 14;
    public static final int SLOT_SET_CRUISE_SPEED = 16;
    public static final int SLOT_BACK = 22;
    public static final int SLOT_SAVE = 20;

    private final NetroPlugin plugin;
    private final String contextType;
    private final String contextId;
    private final String contextSide;
    private final String rulesTitle;
    private final String triggerType;
    private final boolean destinationPositive;
    private final String destinationId;
    private final Rule editRule;
    /** When set, "Set rail state" was chosen and this is the action_data (e.g. world,x,y,z,shape). Show in UI and save on Save. */
    private final String selectedRailStateActionData;
    private final Inventory inventory;

    public RulesCreateStep3Holder(NetroPlugin plugin, String contextType, String contextId, String contextSide,
                                 String rulesTitle, String triggerType, boolean destinationPositive, String destinationId) {
        this(plugin, contextType, contextId, contextSide, rulesTitle, triggerType, destinationPositive, destinationId, null, null);
    }

    public RulesCreateStep3Holder(NetroPlugin plugin, String contextType, String contextId, String contextSide,
                                 String rulesTitle, String triggerType, boolean destinationPositive, String destinationId, Rule editRule) {
        this(plugin, contextType, contextId, contextSide, rulesTitle, triggerType, destinationPositive, destinationId, editRule, null);
    }

    public RulesCreateStep3Holder(NetroPlugin plugin, String contextType, String contextId, String contextSide,
                                 String rulesTitle, String triggerType, boolean destinationPositive, String destinationId, Rule editRule, String selectedRailStateActionData) {
        this.plugin = plugin;
        this.contextType = contextType;
        this.contextId = contextId;
        this.contextSide = contextSide;
        this.rulesTitle = rulesTitle;
        this.triggerType = triggerType;
        this.destinationPositive = destinationPositive;
        this.destinationId = destinationId;
        this.editRule = editRule;
        this.selectedRailStateActionData = selectedRailStateActionData;
        String title = editRule != null ? "Rule: Action (editing)" : "Rule: Action";
        this.inventory = Bukkit.createInventory(this, SIZE, title);
        ItemStack sendOn = newItem(Material.REDSTONE_LAMP, "Turn bulb ON", List.of("Bulbs ON."));
        ItemStack sendOff = newItem(Material.GUNPOWDER, "Turn bulb OFF", List.of("Bulbs OFF."));
        ItemStack setRail;
        if (selectedRailStateActionData != null && !selectedRailStateActionData.isEmpty()) {
            java.util.List<String> entries = dev.netro.util.RailStateListEncoder.parseEntries(selectedRailStateActionData);
            String display = entries.size() == 1
                ? dev.netro.util.RailStateListEncoder.formatEntryDisplay(entries.get(0))
                : entries.size() + " rail(s) configured";
            setRail = newItem(Material.RAIL, "Set rail state", List.of("Chosen: " + display, "Save or click to change."));
            enchant(setRail);
        } else {
            setRail = newItem(Material.RAIL, "Set rail state", List.of("Pick rail with controller."));
            if (editRule != null && Rule.ACTION_SET_RAIL_STATE.equals(editRule.getActionType())) enchant(setRail);
        }
        ItemStack cruise = newItem(Material.POWERED_RAIL, "Set cart speed (cruise)", List.of("Speed 0.0–9.9."));
        if (editRule != null) {
            String at = editRule.getActionType();
            if (Rule.ACTION_SEND_ON.equals(at)) enchant(sendOn);
            else if (Rule.ACTION_SEND_OFF.equals(at)) enchant(sendOff);
            else if (Rule.ACTION_SET_CRUISE_SPEED.equals(at)) enchant(cruise);
        }
        inventory.setItem(SLOT_SEND_ON, sendOn);
        inventory.setItem(SLOT_SEND_OFF, sendOff);
        inventory.setItem(SLOT_SET_RAIL, setRail);
        inventory.setItem(SLOT_SET_CRUISE_SPEED, cruise);
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", List.of("Back (don't save).")));
        if (editRule != null || selectedRailStateActionData != null) {
            inventory.setItem(SLOT_SAVE, newItem(Material.EMERALD, "Save and return to rules", List.of("Save rule and return to rules list.")));
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
    public boolean isDestinationPositive() { return destinationPositive; }
    public String getDestinationId() { return destinationId; }
    public Rule getEditRule() { return editRule; }
    public boolean isEditMode() { return editRule != null; }
    public String getSelectedRailStateActionData() { return selectedRailStateActionData; }

    /** Create the rule with chosen action and close. Returns the new rule index for message. */
    public int createRule(String actionType) {
        return createRule(actionType, null);
    }

    /** Create the rule with action and optional action_data (e.g. cruise speed "2.5"). Destination stored in new-format when resolvable. */
    public int createRule(String actionType, String actionData) {
        var db = plugin.getDatabase();
        var stationRepo = new StationRepository(db);
        var nodeRepo = new TransferNodeRepository(db);
        RuleRepository repo = new RuleRepository(db, nodeRepo);
        int ruleIndex = repo.nextRuleIndex(contextType, contextId, contextSide);
        String storedDestId = DestinationResolver.normalizeToNewFormatForStorage(stationRepo, nodeRepo, destinationId);
        String storedActionData = Rule.ACTION_SET_DESTINATION.equals(actionType)
            ? DestinationResolver.normalizeToNewFormatForStorage(stationRepo, nodeRepo, actionData)
            : actionData;
        Rule rule = new Rule(
            UUID.randomUUID().toString(),
            contextType,
            contextId,
            contextSide,
            ruleIndex,
            triggerType,
            destinationPositive,
            storedDestId != null ? storedDestId : destinationId,
            actionType,
            storedActionData != null ? storedActionData : actionData,
            System.currentTimeMillis()
        );
        repo.insert(rule);
        return ruleIndex;
    }

    @Override
    public Inventory getInventory() { return inventory; }
}
