package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.database.RuleRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Rule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.UUID;

/** Step 3 of create rule: choose action (SEND_ON, SEND_OFF, or SET_RAIL_STATE). */
public class RulesCreateStep3Holder implements InventoryHolder {

    public static final int SIZE = 27;
    public static final int SLOT_SEND_ON = 10;
    public static final int SLOT_SEND_OFF = 12;
    public static final int SLOT_SET_RAIL = 14;
    public static final int SLOT_SET_CRUISE_SPEED = 16;
    public static final int SLOT_BACK = 22;

    private final NetroPlugin plugin;
    private final String contextType;
    private final String contextId;
    private final String contextSide;
    private final String rulesTitle;
    private final String triggerType;
    private final boolean destinationPositive;
    private final String destinationId;
    private final Inventory inventory;

    public RulesCreateStep3Holder(NetroPlugin plugin, String contextType, String contextId, String contextSide,
                                 String rulesTitle, String triggerType, boolean destinationPositive, String destinationId) {
        this.plugin = plugin;
        this.contextType = contextType;
        this.contextId = contextId;
        this.contextSide = contextSide;
        this.rulesTitle = rulesTitle;
        this.triggerType = triggerType;
        this.destinationPositive = destinationPositive;
        this.destinationId = destinationId;
        this.inventory = Bukkit.createInventory(this, SIZE, "Rule: Action");
        inventory.setItem(SLOT_SEND_ON, newItem(Material.REDSTONE_LAMP, "Turn bulb ON", List.of("Action: SEND_ON", "Set controller bulbs to on.")));
        inventory.setItem(SLOT_SEND_OFF, newItem(Material.GUNPOWDER, "Turn bulb OFF", List.of("Action: SEND_OFF", "Set controller bulbs to off.")));
        inventory.setItem(SLOT_SET_RAIL, newItem(Material.RAIL, "Set rail state", List.of("Action: SET_RAIL_STATE", "Set the detector rail shape. Then use Railroad Controller on a rail to pick N/S/E/W directions.")));
        inventory.setItem(SLOT_SET_CRUISE_SPEED, newItem(Material.POWERED_RAIL, "Set cart speed (cruise)", List.of("Action: SET_CRUISE_SPEED", "Set the cart's cruise speed (0.0–9.9).")));
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", List.of("Return to destination choice.")));
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

    /** Create the rule with chosen action and close. Returns the new rule index for message. */
    public int createRule(String actionType) {
        return createRule(actionType, null);
    }

    /** Create the rule with action and optional action_data (e.g. cruise speed "2.5"). */
    public int createRule(String actionType, String actionData) {
        var db = plugin.getDatabase();
        RuleRepository repo = new RuleRepository(db, new TransferNodeRepository(db));
        int ruleIndex = repo.nextRuleIndex(contextType, contextId, contextSide);
        Rule rule = new Rule(
            UUID.randomUUID().toString(),
            contextType,
            contextId,
            contextSide,
            ruleIndex,
            triggerType,
            destinationPositive,
            destinationId,
            actionType,
            actionData,
            System.currentTimeMillis()
        );
        repo.insert(rule);
        return ruleIndex;
    }

    @Override
    public Inventory getInventory() { return inventory; }
}
