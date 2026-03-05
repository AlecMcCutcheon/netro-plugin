package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodePortalRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Station;
import dev.netro.util.DimensionHelper;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Portal Link sub-GUI: Overworld side, Nether side, Clear, Back.
 * If transfer node is in Overworld: Overworld side (same) + Nether side (other).
 * If transfer node is in Nether: Nether side (same) + Overworld side (other).
 * Buttons show enchanted when that side has portal blocks set.
 */
public class PortalLinkHolder implements InventoryHolder {

    public static final int SIZE = 27;
    public static final int SLOT_BACK = 11;
    public static final int SLOT_OVERWORLD_SIDE = 12;
    public static final int SLOT_NETHER_SIDE = 14;
    public static final int SLOT_CLEAR = 16;

    private final NetroPlugin plugin;
    private final String nodeId;
    private final String pairedNodeId;
    private final String pairedLabel;
    private final String rulesTitle;
    private final Inventory inventory;

    public PortalLinkHolder(NetroPlugin plugin, String nodeId, String pairedNodeId, String pairedLabel, String rulesTitle) {
        this.plugin = plugin;
        this.nodeId = nodeId;
        this.pairedNodeId = pairedNodeId == null ? "" : pairedNodeId;
        this.pairedLabel = pairedLabel == null ? "?" : pairedLabel;
        this.rulesTitle = rulesTitle == null ? "Portal Link" : rulesTitle;
        this.inventory = Bukkit.createInventory(this, SIZE, "Portal Link");
        fillSlots();
    }

    private void fillSlots() {
        TransferNodePortalRepository portalRepo = new TransferNodePortalRepository(plugin.getDatabase());
        TransferNodeRepository nodeRepo = new TransferNodeRepository(plugin.getDatabase());
        StationRepository stationRepo = new StationRepository(plugin.getDatabase());
        int nodeDim = nodeRepo.findById(nodeId).flatMap(n -> stationRepo.findById(n.getStationId())).map(Station::getDimension).orElse(DimensionHelper.DIMENSION_OVERWORLD);

        boolean hasOverworld = !portalRepo.getBlocks(nodeId, overworldSideFor(nodeDim)).isEmpty();
        boolean hasNether = !portalRepo.getBlocks(nodeId, netherSideFor(nodeDim)).isEmpty();
        boolean bothSidesThisNode = hasOverworld && hasNether;

        boolean bothNodesConfigured = bothSidesThisNode && isPairedNodeFullyConfigured(portalRepo, nodeRepo, stationRepo);

        List<String> backLore = new ArrayList<>();
        backLore.add("Return to unpair menu.");
        if (bothNodesConfigured)
            backLore.add("§aBoth nodes have portal signs. Routing uses this link.");

        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", backLore));
        String overworldLore = hasOverworld ? "Set." : "Click then right-click the overworld portal.";
        if (bothSidesThisNode && !bothNodesConfigured && !pairedNodeId.isEmpty())
            overworldLore += " Set both sides on the PAIRED node too for routing.";
        inventory.setItem(SLOT_OVERWORLD_SIDE, newSideItem("Overworld side", overworldLore, hasOverworld));
        String netherLore = hasNether ? "Set." : "Click then right-click the nether portal.";
        if (bothSidesThisNode && !bothNodesConfigured && !pairedNodeId.isEmpty())
            netherLore += " Set both sides on the PAIRED node too for routing.";
        inventory.setItem(SLOT_NETHER_SIDE, newSideItem("Nether side", netherLore, hasNether));
        inventory.setItem(SLOT_CLEAR, newItem(Material.BARRIER, "Clear portal link", List.of("Remove both overworld and nether links for this node.")));
    }

    /** True if the paired node has both overworld and nether portal sides set (so routing can use portal cost). */
    private boolean isPairedNodeFullyConfigured(TransferNodePortalRepository portalRepo, TransferNodeRepository nodeRepo, StationRepository stationRepo) {
        if (pairedNodeId == null || pairedNodeId.isEmpty()) return false;
        int pairedDim = nodeRepo.findById(pairedNodeId).flatMap(n -> stationRepo.findById(n.getStationId())).map(Station::getDimension).orElse(DimensionHelper.DIMENSION_OVERWORLD);
        int owSide = pairedDim == DimensionHelper.DIMENSION_OVERWORLD ? TransferNodePortalRepository.SIDE_SAME_DIMENSION : TransferNodePortalRepository.SIDE_OTHER_DIMENSION;
        int netherSide = pairedDim == DimensionHelper.DIMENSION_OVERWORLD ? TransferNodePortalRepository.SIDE_OTHER_DIMENSION : TransferNodePortalRepository.SIDE_SAME_DIMENSION;
        return !portalRepo.getBlocks(pairedNodeId, owSide).isEmpty() && !portalRepo.getBlocks(pairedNodeId, netherSide).isEmpty();
    }

    /** Side index for "Overworld" portal: same dimension if node is OW, other if node is Nether. */
    private int overworldSideFor(int nodeDimension) {
        return nodeDimension == DimensionHelper.DIMENSION_OVERWORLD
            ? TransferNodePortalRepository.SIDE_SAME_DIMENSION
            : TransferNodePortalRepository.SIDE_OTHER_DIMENSION;
    }

    /** Side index for "Nether" portal: other dimension if node is OW, same if node is Nether. */
    private int netherSideFor(int nodeDimension) {
        return nodeDimension == DimensionHelper.DIMENSION_OVERWORLD
            ? TransferNodePortalRepository.SIDE_OTHER_DIMENSION
            : TransferNodePortalRepository.SIDE_SAME_DIMENSION;
    }

    private ItemStack newItem(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) meta.setLore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack newSideItem(String name, String loreLine, boolean enchanted) {
        ItemStack stack = newItem(Material.OBSIDIAN, name, List.of(loreLine, "Type /netro cancel to cancel."));
        if (enchanted) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    public NetroPlugin getPlugin() { return plugin; }
    public String getNodeId() { return nodeId; }
    public String getPairedNodeId() { return pairedNodeId; }
    public String getPairedLabel() { return pairedLabel; }
    public String getRulesTitle() { return rulesTitle; }

    private int nodeDimension() {
        TransferNodeRepository nodeRepo = new TransferNodeRepository(plugin.getDatabase());
        StationRepository stationRepo = new StationRepository(plugin.getDatabase());
        return nodeRepo.findById(nodeId).flatMap(n -> stationRepo.findById(n.getStationId())).map(Station::getDimension).orElse(DimensionHelper.DIMENSION_OVERWORLD);
    }

    /** Side index for Overworld button (same dim if node is OW, other if node is Nether). */
    public int getOverworldSide() { return overworldSideFor(nodeDimension()); }
    /** Side index for Nether button (other dim if node is OW, same if node is Nether). */
    public int getNetherSide() { return netherSideFor(nodeDimension()); }

    /** Refresh items (e.g. after clear so enchanted state is up to date). */
    public void refresh() {
        fillSlots();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
