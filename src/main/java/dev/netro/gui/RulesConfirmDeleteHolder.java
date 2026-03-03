package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.database.RuleRepository;
import dev.netro.model.Rule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Confirmation UI for deleting a rule. Cancel reopens Rules main; Confirm deletes and reindexes. */
public class RulesConfirmDeleteHolder implements InventoryHolder {

    public static final int SIZE = 27;
    public static final int SLOT_CANCEL = 11;
    public static final int SLOT_CONFIRM = 15;

    private final NetroPlugin plugin;
    private final Rule rule;
    private final String rulesTitle;
    private final Inventory inventory;

    public RulesConfirmDeleteHolder(NetroPlugin plugin, Rule rule, String rulesTitle) {
        this.plugin = plugin;
        this.rule = rule;
        this.rulesTitle = rulesTitle == null ? "Rules" : rulesTitle;
        this.inventory = Bukkit.createInventory(this, SIZE, "Delete Rule " + rule.getRuleIndex() + "?");
        inventory.setItem(SLOT_CANCEL, newItem(Material.LIME_WOOL, "Cancel", List.of("Keep this rule.", "Return to rules list.")));
        inventory.setItem(SLOT_CONFIRM, newItem(Material.RED_WOOL, "Delete", List.of("Permanently delete this rule.", "Other rules will be renumbered.")));
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
    public Rule getRule() { return rule; }
    public String getRulesTitle() { return rulesTitle; }

    public void deleteRule() {
        RuleRepository repo = new RuleRepository(plugin.getDatabase());
        repo.deleteById(rule.getId());
        repo.reindexAfterDelete(rule.getContextType(), rule.getContextId(), rule.getContextSide(), rule.getRuleIndex());
    }

    @Override
    public Inventory getInventory() { return inventory; }
}
