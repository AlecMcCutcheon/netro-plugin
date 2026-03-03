package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.detector.CopperBulbHelper;
import dev.netro.database.ControllerRepository;
import dev.netro.database.DetectorRepository;
import dev.netro.model.Controller;
import dev.netro.model.Detector;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Handles block clicks when the player is in the relocate flow: click the target block and the
 * detector/controller for the node (from DB) is moved to above that block.
 */
public class RelocateBlockListener implements Listener {

    private final NetroPlugin plugin;

    public RelocateBlockListener(NetroPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        PendingRelocate pending = plugin.getPendingRelocate(event.getPlayer().getUniqueId());
        if (pending == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        if (pending instanceof PendingRelocate.Source src) {
            resolveAndRelocateTo(player, clicked, src.nodeId());
            return;
        }
        if (pending instanceof PendingRelocate.Dest dest) {
            handleDestClick(player, clicked, dest);
        }
    }

    /** Resolve detector or controller for the node from DB and move it above the clicked block. */
    private void resolveAndRelocateTo(Player player, Block clicked, String nodeId) {
        var db = plugin.getDatabase();
        DetectorRepository detectorRepo = new DetectorRepository(db);
        ControllerRepository controllerRepo = new ControllerRepository(db);

        java.util.List<Detector> detectors = detectorRepo.findByNodeId(nodeId);
        if (!detectors.isEmpty()) {
            Detector d = detectors.get(0);
            PendingRelocate.Dest dest = new PendingRelocate.Dest(
                true, d.getId(), d.getNodeId(), d.getWorld(),
                d.getX(), d.getY(), d.getZ(), d.getSignFacing(),
                d.getRailX(), d.getRailY(), d.getRailZ());
            handleDestClick(player, clicked, dest);
            return;
        }
        java.util.List<Controller> controllers = controllerRepo.findByNodeId(nodeId);
        if (!controllers.isEmpty()) {
            Controller c = controllers.get(0);
            PendingRelocate.Dest dest = new PendingRelocate.Dest(
                false, c.getId(), c.getNodeId(), c.getWorld(),
                c.getX(), c.getY(), c.getZ(), c.getSignFacing(),
                0, 0, 0);
            handleDestClick(player, clicked, dest);
            return;
        }
        player.sendMessage("No detector or controller found for this node.");
    }

    private void handleDestClick(Player player, Block clicked, PendingRelocate.Dest dest) {
        World world = clicked.getWorld();
        if (world == null || !world.getName().equals(dest.world())) {
            player.sendMessage("Target must be in the same world.");
            return;
        }
        int nx = clicked.getX();
        int ny = clicked.getY() + 1;
        int nz = clicked.getZ();
        if (nx == dest.oldBx() && ny == dest.oldBy() && nz == dest.oldBz()) {
            player.sendMessage("Target is the same as current position.");
            return;
        }
        Block newBulbBlock = world.getBlockAt(nx, ny, nz);
        if (!newBulbBlock.getType().isAir()) {
            player.sendMessage("Target space above that block is not empty.");
            return;
        }

        Block oldBulb = world.getBlockAt(dest.oldBx(), dest.oldBy(), dest.oldBz());
        BlockFace signFace = BlockFace.valueOf(dest.signFacing());
        Block oldSign = oldBulb.getRelative(signFace);

        if (!CopperBulbHelper.isCopperBulb(oldBulb)) {
            player.sendMessage("Original bulb block is missing or changed. Relocate cancelled.");
            plugin.setPendingRelocate(player.getUniqueId(), null);
            return;
        }
        if (!isSign(oldSign.getType())) {
            player.sendMessage("Original sign is missing or changed. Relocate cancelled.");
            plugin.setPendingRelocate(player.getUniqueId(), null);
            return;
        }

        // Copy bulb
        BlockData bulbData = oldBulb.getBlockData().clone();
        newBulbBlock.setBlockData(bulbData);

        // Copy sign: type, lines, facing
        BlockState oldSignState = oldSign.getState();
        if (!(oldSignState instanceof org.bukkit.block.Sign oldSignBlock)) {
            plugin.setPendingRelocate(player.getUniqueId(), null);
            return;
        }
        Block newSignBlock = world.getBlockAt(nx + signFace.getModX(), ny + signFace.getModY(), nz + signFace.getModZ());
        newSignBlock.setType(oldSign.getType());
        BlockData newSignData = newSignBlock.getBlockData();
        if (newSignData instanceof Directional dir) {
            dir.setFacing(signFace);
            newSignBlock.setBlockData(newSignData);
        }
        BlockState newSignState = newSignBlock.getState();
        if (newSignState instanceof org.bukkit.block.Sign newSign) {
            for (int i = 0; i < 4; i++) {
                newSign.setLine(i, oldSignBlock.getLine(i));
            }
            newSign.update(true);
        }

        // Remove old blocks only if they are not the new positions (can overlap when relocating short distance)
        if (!oldSign.equals(newBulbBlock) && !oldSign.equals(newSignBlock)) {
            oldSign.setType(Material.AIR);
        }
        if (!oldBulb.equals(newBulbBlock) && !oldBulb.equals(newSignBlock)) {
            oldBulb.setType(Material.AIR);
        }

        // Update database
        int newRailX = dest.railX();
        int newRailY = dest.railY();
        int newRailZ = dest.railZ();
        if (dest.isDetector()) {
            Block adjRail = findAdjacentRail(newBulbBlock);
            if (adjRail != null) {
                newRailX = adjRail.getX();
                newRailY = adjRail.getY();
                newRailZ = adjRail.getZ();
            }
            DetectorRepository detectorRepo = new DetectorRepository(plugin.getDatabase());
            detectorRepo.updatePosition(dest.id(), world.getName(), nx, ny, nz, newRailX, newRailY, newRailZ);
            var chunkLoad = plugin.getChunkLoadService();
            if (chunkLoad != null) {
                chunkLoad.removeChunksForBlock(dest.world(), dest.railX(), dest.railZ());
                chunkLoad.addChunksForBlock(world.getName(), newRailX, newRailZ);
            }
        } else {
            ControllerRepository controllerRepo = new ControllerRepository(plugin.getDatabase());
            controllerRepo.updatePosition(dest.id(), world.getName(), nx, ny, nz);
        }

        plugin.setPendingRelocate(player.getUniqueId(), null);
        player.sendMessage("Relocated successfully. Detector/controller is now above the block you clicked.");
    }

    private static boolean isSign(Material m) {
        return m != null && m.name().contains("SIGN");
    }

    private static final java.util.Set<Material> RAILS = java.util.Set.of(
        Material.RAIL, Material.POWERED_RAIL, Material.ACTIVATOR_RAIL, Material.DETECTOR_RAIL);

    private static Block findAdjacentRail(Block bulb) {
        for (BlockFace face : new BlockFace[] { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST }) {
            Block b = bulb.getRelative(face);
            if (RAILS.contains(b.getType())) return b;
        }
        return null;
    }
}
