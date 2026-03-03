package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.database.RuleRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Rule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.Rail;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.UUID;

/** Step 4 of create rule: choose rail shape for SET_RAIL_STATE, then create rule. */
public class RulesSetRailStateHolder implements InventoryHolder {

    public static final int SIZE = 27;
    public static final int SLOT_BACK = 22;

    private static final int[] SLOT_FOR_SHAPE = { 10, 11, 12, 13, 14, 15 };
    private static final Rail.Shape[] SHAPES = {
        Rail.Shape.NORTH_SOUTH,
        Rail.Shape.EAST_WEST,
        Rail.Shape.NORTH_EAST,
        Rail.Shape.NORTH_WEST,
        Rail.Shape.SOUTH_EAST,
        Rail.Shape.SOUTH_WEST
    };

    private final NetroPlugin plugin;
    private final String contextType;
    private final String contextId;
    private final String contextSide;
    private final String rulesTitle;
    private final String triggerType;
    private final boolean destinationPositive;
    private final String destinationId;
    private final Inventory inventory;

    public RulesSetRailStateHolder(NetroPlugin plugin, String contextType, String contextId, String contextSide,
                                   String rulesTitle, String triggerType, boolean destinationPositive, String destinationId) {
        this.plugin = plugin;
        this.contextType = contextType;
        this.contextId = contextId;
        this.contextSide = contextSide;
        this.rulesTitle = rulesTitle;
        this.triggerType = triggerType;
        this.destinationPositive = destinationPositive;
        this.destinationId = destinationId;
        this.inventory = Bukkit.createInventory(this, SIZE, "Rule: Rail shape");
        for (int i = 0; i < SHAPES.length && i < SLOT_FOR_SHAPE.length; i++) {
            Rail.Shape shape = SHAPES[i];
            String name = shape.name().replace("_", " ");
            inventory.setItem(SLOT_FOR_SHAPE[i], newItem(Material.RAIL, name, List.of("Set detector rail to " + name)));
        }
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", List.of("Return to action choice.")));
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

    public Rail.Shape getShapeAtSlot(int slot) {
        for (int i = 0; i < SLOT_FOR_SHAPE.length; i++) {
            if (SLOT_FOR_SHAPE[i] == slot && i < SHAPES.length) return SHAPES[i];
        }
        return null;
    }

    public boolean isBackSlot(int slot) { return slot == SLOT_BACK; }

    public int createRuleWithShape(Rail.Shape shape) {
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
            Rule.ACTION_SET_RAIL_STATE,
            shape.name(),
            System.currentTimeMillis()
        );
        repo.insert(rule);
        return ruleIndex;
    }

    @Override
    public Inventory getInventory() { return inventory; }
}
