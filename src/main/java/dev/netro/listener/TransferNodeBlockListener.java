package dev.netro.listener;

import dev.netro.NetroPlugin;
import dev.netro.database.TransferNodeRepository;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.Optional;

/**
 * When any block that is part of a transfer node (transfer switch, hold switch, or gate slot)
 * is broken, the entire transfer node is removed so the user must set it up again.
 */
public class TransferNodeBlockListener implements Listener {

    private final TransferNodeRepository nodeRepo;

    public TransferNodeBlockListener(NetroPlugin plugin) {
        this.nodeRepo = new TransferNodeRepository(plugin.getDatabase());
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isRail(block.getType())) return;

        String world = block.getWorld().getName();
        int x = block.getX(), y = block.getY(), z = block.getZ();
        Optional<String> nodeId = nodeRepo.findNodeIdAtBlock(world, x, y, z);
        if (nodeId.isEmpty()) return;

        nodeRepo.deleteNodeAndAllBlockData(nodeId.get());
        event.getPlayer().sendMessage("A transfer node block was broken. That transfer node has been removed; set it up again with /transfer create.");
    }

    private static boolean isRail(Material type) {
        return type == Material.RAIL || type == Material.POWERED_RAIL
            || type == Material.ACTIVATOR_RAIL || type == Material.DETECTOR_RAIL;
    }
}
