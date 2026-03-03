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

/** Shown when clicking an existing rule: choose Edit or Delete. */
public class RulesEditOrDeleteHolder implements InventoryHolder {

    public static final int SIZE = 27;
    public static final int SLOT_EDIT = 11;
    public static final int SLOT_DELETE = 15;
    public static final int SLOT_BACK = 13;

    private final NetroPlugin plugin;
    private final Rule rule;
    private final String rulesTitle;
    private final Inventory inventory;

    public RulesEditOrDeleteHolder(NetroPlugin plugin, Rule rule, String rulesTitle) {
        this.plugin = plugin;
        this.rule = rule;
        this.rulesTitle = rulesTitle == null ? "Rules" : rulesTitle;
        this.inventory = Bukkit.createInventory(this, SIZE, "Rule " + rule.getRuleIndex() + ": Edit or Delete?");
        inventory.setItem(SLOT_EDIT, newItem(Material.FEATHER, "Edit rule", List.of("Edit rule.")));
        inventory.setItem(SLOT_DELETE, newItem(Material.REDSTONE, "Delete rule", List.of("Delete rule.")));
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", List.of("Back.")));
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

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
