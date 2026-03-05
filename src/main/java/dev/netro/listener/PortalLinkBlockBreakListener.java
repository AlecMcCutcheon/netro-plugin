package dev.netro.listener;

import dev.netro.NetroPlugin;
import dev.netro.database.TransferNodePortalRepository;
import dev.netro.database.TransferNodeRepository;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * When a block that is part of a transfer node's portal link is broken, remove the portal link
 * for that node and its paired node (both sides cleared).
 */
public final class PortalLinkBlockBreakListener implements Listener {

    private final NetroPlugin plugin;

    public PortalLinkBlockBreakListener(NetroPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;
        TransferNodePortalRepository portalRepo = new TransferNodePortalRepository(plugin.getDatabase());
        String nodeId = portalRepo.findNodeIdByBlock(
            event.getBlock().getWorld().getName(),
            event.getBlock().getX(),
            event.getBlock().getY(),
            event.getBlock().getZ());
        if (nodeId == null) return;

        TransferNodeRepository nodeRepo = new TransferNodeRepository(plugin.getDatabase());
        String pairedId = nodeRepo.findById(nodeId).map(n -> n.getPairedNodeId()).orElse(null);
        portalRepo.deleteByNode(nodeId);
        if (pairedId != null && !pairedId.isEmpty()) {
            portalRepo.deleteByNode(pairedId);
        }
        if (event.getPlayer() != null) {
            event.getPlayer().sendMessage("Netro: Portal link removed (a portal block was broken).");
        }
    }
}
