package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.util.AddressHelper;
import dev.netro.util.DimensionHelper;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.KeyedBossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shows a boss bar when the player holds the Railroad Controller: R = 2D region (colon format
 * OV:E2:N3:01:02), Local = offset within 100×100 quadrant cell (0–99), World = block XYZ.
 */
public class RailroadControllerBossBarUpdater implements Listener {

    private static final long TICK_INTERVAL = 10L;

    private final NetroPlugin plugin;
    private final Map<UUID, KeyedBossBar> barByPlayer = new HashMap<>();

    public RailroadControllerBossBarUpdater(NetroPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeBarForPlayer(event.getPlayer());
    }

    public void removeBarForPlayer(Player player) {
        KeyedBossBar bar = barByPlayer.remove(player.getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!RailroadControllerItem.isRailroadController(plugin, player.getInventory().getItemInMainHand())) {
                hideBar(player);
                continue;
            }

            String title = buildTitle(player);
            showBar(player, title);
        }
    }

    private String buildTitle(Player player) {
        int blockX = player.getLocation().getBlockX();
        int blockY = player.getLocation().getBlockY();
        int blockZ = player.getLocation().getBlockZ();

        int dimension = DimensionHelper.dimensionFromEnvironment(player.getWorld().getEnvironment());
        String region = AddressHelper.regionPrefix2D(dimension, blockX, blockZ);

        int localX = AddressHelper.localOffsetInCellX(blockX, blockZ);
        int localZ = AddressHelper.localOffsetInCellZ(blockX, blockZ);

        return "R " + region
            + " | Local " + localX + "," + blockY + "," + localZ
            + " | World " + blockX + "," + blockY + "," + blockZ;
    }

    private void showBar(Player player, String title) {
        KeyedBossBar bar = barByPlayer.get(player.getUniqueId());
        if (bar == null) {
            NamespacedKey key = new NamespacedKey(plugin, "railroad_controller_bar_" + player.getUniqueId());
            bar = Bukkit.getBossBar(key);
            if (bar == null) {
                bar = Bukkit.createBossBar(key, title, BarColor.WHITE, BarStyle.SOLID);
            }
            barByPlayer.put(player.getUniqueId(), bar);
        }
        bar.setTitle(title);
        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }
    }

    private void hideBar(Player player) {
        KeyedBossBar bar = barByPlayer.get(player.getUniqueId());
        if (bar != null) {
            bar.removePlayer(player);
        }
    }
}
