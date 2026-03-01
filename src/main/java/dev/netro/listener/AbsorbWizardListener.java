package dev.netro.listener;

import dev.netro.NetroPlugin;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Handles: (1) Absorb wand — right-click with wand on a sign runs absorb; on a non-sign, tells player to click a sign (no wizard).
 * (2) Absorb wizard — when in wizard mode (ran /absorb without looking at a sign), right-clicking a sign runs absorb.
 */
public class AbsorbWizardListener implements Listener {

    private final NetroPlugin plugin;

    public AbsorbWizardListener(NetroPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        Player player = event.getPlayer();

        if (plugin.getAbsorbCommand().isAbsorbWand(player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            if (isSign(block.getType())) {
                plugin.getAbsorbCommand().processTargetSign(player, block);
            } else {
                player.sendMessage("Click on a station, detector, or controller sign.");
            }
            return;
        }

        if (!plugin.getAbsorbWizards().remove(player.getUniqueId())) return;

        event.setCancelled(true);

        if (!isSign(block.getType())) {
            player.sendMessage("That was not a sign. Run /absorb and right-click a station, detector, or controller sign.");
            return;
        }

        plugin.getAbsorbCommand().processTargetSign(player, block);
    }

    private static boolean isSign(org.bukkit.Material m) {
        return m != null && m.name().contains("SIGN");
    }
}
