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
import java.util.Optional;

/** Station list for pairing: all stations except the current node's. Choosing a station opens PairNodePickerHolder. */
public class PairStationPickerHolder implements InventoryHolder {

    public static final int SIZE = 54;
    public static final int SLOT_BACK = 0;
    public static final int LIST_START = 9;

    private final NetroPlugin plugin;
    private final String currentNodeId;
    private final String rulesTitle;
    private final Inventory inventory;
    private final List<String> stationIds = new ArrayList<>();

    public PairStationPickerHolder(NetroPlugin plugin, String currentNodeId, String rulesTitle) {
        this.plugin = plugin;
        this.currentNodeId = currentNodeId;
        this.rulesTitle = rulesTitle == null ? "Rules" : rulesTitle;
        this.inventory = Bukkit.createInventory(this, SIZE, "Pair: Choose station");
        fillStations();
    }

    private void fillStations() {
        inventory.clear();
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", List.of("Return to rules.")));

        TransferNodeRepository nodeRepo = new TransferNodeRepository(plugin.getDatabase());
        StationRepository stationRepo = new StationRepository(plugin.getDatabase());
        Optional<TransferNode> current = nodeRepo.findById(currentNodeId);
        String excludeStationId = current.map(TransferNode::getStationId).orElse(null);

        List<Station> stations = stationRepo.findAll();
        int slot = LIST_START;
        for (Station st : stations) {
            if (st.getId().equals(excludeStationId)) continue;
            List<TransferNode> nodesAtStation = nodeRepo.findByStation(st.getId());
            boolean hasUnpaired = nodesAtStation.stream()
                .anyMatch(n -> !n.isTerminal() && (n.getPairedNodeId() == null || n.getPairedNodeId().isEmpty()));
            if (!hasUnpaired) continue;
            if (slot >= SIZE) break;
            stationIds.add(st.getId());
            inventory.setItem(slot, newItem(Material.MINECART, st.getName(), List.of("Station: " + st.getName())));
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
    public String getRulesTitle() { return rulesTitle; }

    public String getStationIdAtSlot(int slot) {
        int index = slot - LIST_START;
        if (index < 0 || index >= stationIds.size()) return null;
        return stationIds.get(index);
    }

    public boolean isBackSlot(int slot) { return slot == SLOT_BACK; }

    @Override
    public Inventory getInventory() { return inventory; }
}
