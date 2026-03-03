package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Station;
import dev.netro.model.TransferNode;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Transfer nodes (no terminals) at a chosen station for pairing. Choosing a node sets pairing both ways and triggers route rebuild. */
public class PairNodePickerHolder implements InventoryHolder {

    public static final int SIZE = 54;
    public static final int SLOT_BACK = 0;
    public static final int LIST_START = 9;

    private final NetroPlugin plugin;
    private final String currentNodeId;
    private final String chosenStationId;
    private final String rulesTitle;
    private final Inventory inventory;
    private final List<String> nodeIds = new ArrayList<>();

    public PairNodePickerHolder(NetroPlugin plugin, String currentNodeId, String chosenStationId, String rulesTitle) {
        this.plugin = plugin;
        this.currentNodeId = currentNodeId;
        this.chosenStationId = chosenStationId;
        this.rulesTitle = rulesTitle == null ? "Rules" : rulesTitle;
        this.inventory = Bukkit.createInventory(this, SIZE, "Pair: Choose transfer node");
        fillNodes();
    }

    private void fillNodes() {
        inventory.clear();
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", List.of("Return to station list.")));

        TransferNodeRepository nodeRepo = new TransferNodeRepository(plugin.getDatabase());
        StationRepository stationRepo = new StationRepository(plugin.getDatabase());
        String stationName = stationRepo.findById(chosenStationId).map(Station::getName).orElse(chosenStationId);

        List<TransferNode> nodes = nodeRepo.findByStation(chosenStationId);
        int slot = LIST_START;
        for (TransferNode node : nodes) {
            if (node.isTerminal()) continue;
            if (node.getId().equals(currentNodeId)) continue;
            if (node.getPairedNodeId() != null && !node.getPairedNodeId().isEmpty()) continue;
            if (slot >= SIZE) break;
            nodeIds.add(node.getId());
            inventory.setItem(slot, newItem(Material.MINECART, stationName + ":" + node.getName(), List.of("Transfer node: " + node.getName())));
            slot++;
        }
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
    public String getCurrentNodeId() { return currentNodeId; }
    public String getChosenStationId() { return chosenStationId; }
    public String getRulesTitle() { return rulesTitle; }

    public String getNodeIdAtSlot(int slot) {
        int index = slot - LIST_START;
        if (index < 0 || index >= nodeIds.size()) return null;
        return nodeIds.get(index);
    }

    public boolean isBackSlot(int slot) { return slot == SLOT_BACK; }

    @Override
    public Inventory getInventory() { return inventory; }
}
