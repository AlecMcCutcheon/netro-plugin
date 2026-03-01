package dev.netro.listener;

import dev.netro.NetroPlugin;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.TransferNode;
import dev.netro.transfer.TransferSetupWizard;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

public class TransferWizardListener implements Listener {

    private final NetroPlugin plugin;
    private final StationRepository stationRepo;
    private final TransferNodeRepository nodeRepo;

    public TransferWizardListener(NetroPlugin plugin) {
        this.plugin = plugin;
        this.stationRepo = new StationRepository(plugin.getDatabase());
        this.nodeRepo = new TransferNodeRepository(plugin.getDatabase());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        TransferSetupWizard wizard = plugin.getTransferWizards().get(event.getPlayer().getUniqueId());
        if (wizard == null) return;

        event.setCancelled(true);

        Player player = event.getPlayer();
        String type = block.getType().name();

        if (isSign(type)) {
            handleSignClick(player, wizard, block);
            return;
        }
        if (isRail(type)) {
            player.sendMessage("Click the station sign to create the transfer node. Place [Detector] and [Controller] signs on copper bulbs after creation.");
            return;
        }
    }

    private void handleSignClick(Player player, TransferSetupWizard wizard, Block block) {
        var station = stationRepo.findAll().stream()
            .filter(s -> s.getWorld().equals(block.getWorld().getName())
                && s.getSignX() == block.getX() && s.getSignY() == block.getY() && s.getSignZ() == block.getZ())
            .findFirst();

        if (station.isEmpty()) {
            player.sendMessage("That sign is not a registered station. Click a station sign.");
            return;
        }

        int step = wizard.getStep();
        if (step == TransferSetupWizard.STEP_CLICK_OWN_STATION) {
            if (nodeRepo.findByNameAtStation(station.get().getId(), wizard.getNodeName()).isPresent()) {
                player.sendMessage("A transfer node with that name already exists at this station (names are case-insensitive). Use /transfer create <differentName>.");
                return;
            }
            TransferNode node = new TransferNode(java.util.UUID.randomUUID().toString(), wizard.getNodeName());
            node.setStationId(station.get().getId());
            nodeRepo.insert(node);
            nodeRepo.setSetupComplete(node.getId());
            String stationName = station.get().getName();
            plugin.getTransferWizards().remove(player.getUniqueId());
            player.sendMessage("Transfer node \"" + wizard.getNodeName() + "\" created at " + stationName + ". Pair with /transfer pair " + stationName + ":" + wizard.getNodeName() + " <OtherStation:OtherNode>. Place [Detector] and [Controller] signs on copper bulbs for physical setup.");
            return;
        }

        player.sendMessage("Not a sign step. Run /transfer create <name> then click the station sign.");
    }

    private static boolean isSign(String type) {
        return type.contains("SIGN");
    }

    private static boolean isRail(String type) {
        return type.contains("RAIL");
    }
}
