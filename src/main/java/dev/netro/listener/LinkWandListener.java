package dev.netro.listener;

import dev.netro.NetroPlugin;
import dev.netro.database.DetectorRepository;
import dev.netro.database.JunctionRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.detector.CopperBulbHelper;
import dev.netro.model.Detector;
import dev.netro.model.TransferNode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pairing wand: click transfer detector A, then transfer detector B → pair A–B.
 * Segment wand: click transfer A → junction detector(s) in order → transfer B → register segment with junction(s).
 */
public class LinkWandListener implements Listener {

    public static final String PAIRING_WAND_NAME = "Netro Pairing Wand";
    public static final String SEGMENT_WAND_NAME = "Netro Segment Wand";

    private final NetroPlugin plugin;
    private final NamespacedKey pairingWandKey;
    private final NamespacedKey segmentWandKey;
    private final DetectorRepository detectorRepo;
    private final TransferNodeRepository nodeRepo;
    private final JunctionRepository junctionRepo;

    private final Map<UUID, String> pendingPairNodeId = new ConcurrentHashMap<>();
    private final Map<UUID, SegmentState> pendingSegment = new ConcurrentHashMap<>();

    public LinkWandListener(NetroPlugin plugin) {
        this.plugin = plugin;
        this.pairingWandKey = new NamespacedKey(plugin, "pairing_wand");
        this.segmentWandKey = new NamespacedKey(plugin, "segment_wand");
        var db = plugin.getDatabase();
        this.detectorRepo = new DetectorRepository(db);
        this.nodeRepo = new TransferNodeRepository(db);
        this.junctionRepo = new JunctionRepository(db);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        if (isPairingWand(hand)) {
            event.setCancelled(true);
            handlePairingWandClick(event.getPlayer(), block);
            return;
        }
        if (isSegmentWand(hand)) {
            event.setCancelled(true);
            handleSegmentWandClick(event.getPlayer(), block);
            return;
        }
    }

    private void handlePairingWandClick(Player player, Block clicked) {
        Block bulb = resolveBulb(clicked);
        if (bulb == null) {
            player.sendMessage("Click on a detector sign or copper bulb (transfer node).");
            return;
        }
        Optional<Detector> det = detectorRepo.findByBlock(bulb.getWorld().getName(), bulb.getX(), bulb.getY(), bulb.getZ());
        if (det.isEmpty()) {
            player.sendMessage("No detector here. Click a transfer node's detector.");
            return;
        }
        Detector d = det.get();
        if (d.getJunctionId() != null) {
            player.sendMessage("This is a junction detector. Use the Segment wand to register junctions on a segment.");
            return;
        }
        String nodeId = d.getNodeId();
        if (nodeId == null) return;
        Optional<TransferNode> node = nodeRepo.findById(nodeId);
        if (node.isEmpty()) return;
        if (node.get().isTerminal()) {
            player.sendMessage("Cannot pair a terminal. Click a transfer node detector.");
            return;
        }

        String pending = pendingPairNodeId.get(player.getUniqueId());
        if (pending == null) {
            pendingPairNodeId.put(player.getUniqueId(), nodeId);
            String name = node.get().getName();
            player.sendMessage("First node: " + name + ". Now click the other station's transfer detector to pair.");
            return;
        }
        if (pending.equals(nodeId)) {
            player.sendMessage("Same node. Click the other station's transfer detector.");
            return;
        }
        Optional<TransferNode> other = nodeRepo.findById(pending);
        if (other.isEmpty()) {
            pendingPairNodeId.remove(player.getUniqueId());
            player.sendMessage("First node no longer found. Start over.");
            return;
        }
        if (other.get().getStationId().equals(node.get().getStationId())) {
            player.sendMessage("Pair with a node at a different station.");
            return;
        }
        pendingPairNodeId.remove(player.getUniqueId());
        nodeRepo.setPaired(pending, nodeId);
        nodeRepo.setPaired(nodeId, pending);
        plugin.getRoutingEngine().onNodePaired(pending, nodeId);
        player.sendMessage("Paired " + other.get().getName() + " with " + node.get().getName() + ".");
    }

    private void handleSegmentWandClick(Player player, Block clicked) {
        Block bulb = resolveBulb(clicked);
        if (bulb == null) {
            player.sendMessage("Click on a detector sign or copper bulb (transfer or junction).");
            return;
        }
        Optional<Detector> det = detectorRepo.findByBlock(bulb.getWorld().getName(), bulb.getX(), bulb.getY(), bulb.getZ());
        if (det.isEmpty()) {
            player.sendMessage("No detector here.");
            return;
        }
        Detector d = det.get();
        SegmentState state = pendingSegment.get(player.getUniqueId());

        if (d.getNodeId() != null) {
            Optional<TransferNode> node = nodeRepo.findById(d.getNodeId());
            if (node.isEmpty()) return;
            if (node.get().isTerminal()) {
                player.sendMessage("Use a transfer node detector (not a terminal) for segment endpoints.");
                return;
            }
            if (state == null) {
                pendingSegment.put(player.getUniqueId(), new SegmentState(d.getNodeId(), new ArrayList<>()));
                player.sendMessage("Segment start: " + node.get().getName() + ". Click junction detector(s) then the other transfer detector.");
                return;
            }
            String nodeBId = d.getNodeId();
            if (state.nodeAId.equals(nodeBId)) {
                player.sendMessage("Same node. Click junction(s) then the other transfer detector.");
                return;
            }
            if (state.junctionIds.isEmpty()) {
                pendingSegment.remove(player.getUniqueId());
                player.sendMessage("Segment has no junctions. Paired nodes already define the segment. Cleared.");
                return;
            }
            for (String jId : state.junctionIds) {
                junctionRepo.updateSegment(jId, state.nodeAId, nodeBId);
            }
            pendingSegment.remove(player.getUniqueId());
            player.sendMessage("Segment registered: " + state.junctionIds.size() + " junction(s) between the two nodes.");
            return;
        }
        if (d.getJunctionId() != null) {
            if (state == null) {
                player.sendMessage("Click a transfer detector first to start the segment.");
                return;
            }
            state.junctionIds.add(d.getJunctionId());
            player.sendMessage("Added junction. Click more junction detectors or the other transfer detector to finish.");
        }
    }

    private static Block resolveBulb(Block clicked) {
        if (CopperBulbHelper.isCopperBulb(clicked)) return clicked;
        BlockState state = clicked.getState();
        if (state instanceof org.bukkit.block.Sign) {
            BlockData data = clicked.getBlockData();
            if (data instanceof Directional dir) {
                Block attached = clicked.getRelative(dir.getFacing().getOppositeFace());
                return CopperBulbHelper.isCopperBulb(attached) ? attached : null;
            }
        }
        return null;
    }

    public boolean isPairingWand(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(pairingWandKey, PersistentDataType.BYTE);
    }

    public boolean isSegmentWand(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(segmentWandKey, PersistentDataType.BYTE);
    }

    public ItemStack createPairingWand() {
        ItemStack stack = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(PAIRING_WAND_NAME);
            meta.getPersistentDataContainer().set(pairingWandKey, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public ItemStack createSegmentWand() {
        ItemStack stack = new ItemStack(Material.END_ROD);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(SEGMENT_WAND_NAME);
            meta.getPersistentDataContainer().set(segmentWandKey, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static class SegmentState {
        final String nodeAId;
        final List<String> junctionIds;

        SegmentState(String nodeAId, List<String> junctionIds) {
            this.nodeAId = nodeAId;
            this.junctionIds = junctionIds;
        }
    }
}
