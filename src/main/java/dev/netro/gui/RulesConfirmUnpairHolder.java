package dev.netro.gui;

import dev.netro.NetroPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Confirmation UI for unpairing a transfer node. Cancel reopens Rules main; Confirm clears both sides. */
public class RulesConfirmUnpairHolder implements InventoryHolder {

    public static final int SIZE = 27;
    public static final int SLOT_CANCEL = 11;
    public static final int SLOT_CONFIRM = 15;

    private final NetroPlugin plugin;
    private final String nodeId;
    private final String pairedLabel;
    private final String rulesTitle;
    private final Inventory inventory;

    public RulesConfirmUnpairHolder(NetroPlugin plugin, String nodeId, String pairedLabel, String rulesTitle) {
        this.plugin = plugin;
        this.nodeId = nodeId;
        this.pairedLabel = pairedLabel == null ? "?" : pairedLabel;
        this.rulesTitle = rulesTitle == null ? "Rules" : rulesTitle;
        this.inventory = Bukkit.createInventory(this, SIZE, "Unpair from " + this.pairedLabel + "?");
        inventory.setItem(SLOT_CANCEL, newItem(Material.LIME_WOOL, "Cancel", List.of("Keep pairing.", "Return to rules.")));
        inventory.setItem(SLOT_CONFIRM, newItem(Material.RED_WOOL, "Unpair", List.of("Clear pairing on both nodes.", "Routes will be rebuilt.")));
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
    public String getNodeId() { return nodeId; }
    public String getRulesTitle() { return rulesTitle; }

    @Override
    public Inventory getInventory() { return inventory; }
}
