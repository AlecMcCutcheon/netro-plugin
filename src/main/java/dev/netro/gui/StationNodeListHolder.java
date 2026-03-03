package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.database.ControllerRepository;
import dev.netro.database.DetectorRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.TransferNode;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Lists transfers and terminals at a station. Click a node to open options (Open rules / Delete). */
public class StationNodeListHolder implements InventoryHolder {

    public static final int SIZE = 54;
    public static final int SLOT_BACK = 0;
    public static final int LIST_START = 9;

    private final NetroPlugin plugin;
    private final String stationId;
    private final String stationName;
    private final Inventory inventory;
    private final List<NodeEntry> entries = new ArrayList<>();

    public StationNodeListHolder(NetroPlugin plugin, String stationId, String stationName) {
        this.plugin = plugin;
        this.stationId = stationId;
        this.stationName = stationName == null ? "" : stationName;
        this.inventory = Bukkit.createInventory(this, SIZE, this.stationName + " — Transfers & Terminals");
        fill();
    }

    private void fill() {
        inventory.clear();
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", List.of("Close (no station selected).")));

        StationRepository stationRepo = new StationRepository(plugin.getDatabase());
        TransferNodeRepository nodeRepo = new TransferNodeRepository(plugin.getDatabase());
        DetectorRepository detectorRepo = new DetectorRepository(plugin.getDatabase());
        ControllerRepository controllerRepo = new ControllerRepository(plugin.getDatabase());

        List<TransferNode> nodes = nodeRepo.findByStation(stationId);
        int slot = LIST_START;
        for (TransferNode node : nodes) {
            if (slot >= SIZE) break;
            List<String> lore = new ArrayList<>();
            int detectors = detectorRepo.findByNodeId(node.getId()).size();
            int controllers = controllerRepo.findByNodeId(node.getId()).size();
            lore.add("Detectors: " + detectors + ", Controllers: " + controllers);
            if (!node.isTerminal()) {
                String pairedId = node.getPairedNodeId();
                if (pairedId != null && !pairedId.isEmpty()) {
                    String label = RulesMainHolder.formatStationNode(pairedId, stationRepo, nodeRepo);
                    lore.add("Paired: " + label);
                } else {
                    lore.add("Paired: No");
                }
            }
            String displayName = node.isTerminal() && node.getTerminalIndex() != null
                ? "Term " + (node.getTerminalIndex() + 1) + " (" + node.getName() + ")"
                : node.getName();
            Material icon = node.isTerminal() ? Material.RAIL : Material.MINECART;
            inventory.setItem(slot, newItem(icon, displayName, lore));
            entries.add(new NodeEntry(slot, node.getId(), node.isTerminal() ? "terminal" : "transfer", displayName));
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
    public String getStationId() { return stationId; }
    public String getStationName() { return stationName; }

    public NodeEntry getEntryAtSlot(int slot) {
        for (NodeEntry e : entries) {
            if (e.slot == slot) return e;
        }
        return null;
    }

    public boolean isBackSlot(int slot) { return slot == SLOT_BACK; }

    @Override
    public Inventory getInventory() { return inventory; }

    public static final class NodeEntry {
        public final int slot;
        public final String nodeId;
        public final String contextType;
        public final String displayName;

        NodeEntry(int slot, String nodeId, String contextType, String displayName) {
            this.slot = slot;
            this.nodeId = nodeId;
            this.contextType = contextType;
            this.displayName = displayName;
        }
    }
}
