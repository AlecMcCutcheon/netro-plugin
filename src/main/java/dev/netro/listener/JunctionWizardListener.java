package dev.netro.listener;

import dev.netro.NetroPlugin;
import dev.netro.junction.JunctionSetupWizard;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

public class JunctionWizardListener implements Listener {

    private final NetroPlugin plugin;

    public JunctionWizardListener(NetroPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        JunctionSetupWizard wizard = plugin.getJunctionWizards().get(event.getPlayer().getUniqueId());
        if (wizard == null) return;

        if (block.getType() != Material.POWERED_RAIL && block.getType() != Material.RAIL
            && block.getType() != Material.ACTIVATOR_RAIL && block.getType() != Material.DETECTOR_RAIL) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage("Run /junction done to finish setup, then /junction segment to attach. Place [Detector] and [Controller] signs on copper bulbs for physical control.");
    }
}
