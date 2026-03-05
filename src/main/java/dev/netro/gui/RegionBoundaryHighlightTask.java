package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.util.AddressHelper;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * When a player holds the Railroad Controller, draws the outline of 2D region boundaries
 * (mainnet 400 m, cluster 200 m, localnet 100 m) in both X and Z within a radius.
 * Red = mainnet, cyan = cluster, green = localnet. Uses AddressHelper.MAINNET_SIZE,
 * CLUSTER_SIZE, LOCALNET_SIZE. Each tier at a slightly different Y so the three colors stack.
 * Bright RGB values so all tiers read clearly (no separate glow; DUST particle brightness follows color).
 */
public final class RegionBoundaryHighlightTask implements Runnable {

    private static final int RADIUS_BLOCKS = 80;
    /** Vertical offsets from base Y for thickness: baseY-1, baseY, baseY+1. */
    private static final int[] VERTICAL_OFFSETS = { -1, 0, 1 };
    /** Small Y offset per tier so the three colors stack: localnet at base, cluster slightly up, mainnet a bit more. */
    private static final double LOCALNET_Y_OFFSET = 0.0;
    private static final double CLUSTER_Y_OFFSET = 0.25;
    private static final double MAINNET_Y_OFFSET = 0.5;
    private static final int PARTICLES_PER_LINE = 280;
    private static final float DUST_SIZE = 1.0f;
    private static final int PARTICLE_COUNT_PER_POINT = 5;

    /** Bright red (high R for visibility). */
    private static final Color MAINNET_COLOR = Color.fromRGB(255, 70, 70);
    /** Bright cyan / light blue (cluster). */
    private static final Color CLUSTER_COLOR = Color.fromRGB(100, 220, 255);
    /** Bright green (high G for visibility). */
    private static final Color LOCALNET_COLOR = Color.fromRGB(80, 255, 100);

    private final NetroPlugin plugin;

    public RegionBoundaryHighlightTask(NetroPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.isOnline() || !player.isValid()) continue;
            if (!RailroadControllerItem.isRailroadController(plugin, player.getInventory().getItemInMainHand())) continue;

            Location loc = player.getLocation();
            World world = loc.getWorld();
            if (world == null || !world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;

            double baseY = loc.getBlockY() + 0.2;

            int minX = loc.getBlockX() - RADIUS_BLOCKS;
            int maxX = loc.getBlockX() + RADIUS_BLOCKS;
            int minZ = loc.getBlockZ() - RADIUS_BLOCKS;
            int maxZ = loc.getBlockZ() + RADIUS_BLOCKS;

            Particle.DustOptions mainnetDust = new Particle.DustOptions(MAINNET_COLOR, DUST_SIZE);
            Particle.DustOptions clusterDust = new Particle.DustOptions(CLUSTER_COLOR, DUST_SIZE);
            Particle.DustOptions localnetDust = new Particle.DustOptions(LOCALNET_COLOR, DUST_SIZE);

            double mainnetY = baseY + MAINNET_Y_OFFSET;
            double clusterY = baseY + CLUSTER_Y_OFFSET;
            double localnetY = baseY + LOCALNET_Y_OFFSET;

            // Lines at fixed X (run along Z): each tier at its own Y so red/cyan/green stack at intersections
            for (int x = firstBoundaryInRange(minX, AddressHelper.MAINNET_SIZE); x <= maxX; x += AddressHelper.MAINNET_SIZE) {
                drawLineAtThreeHeights(player, world, x, mainnetY, minZ, x, mainnetY, maxZ, mainnetDust);
            }
            for (int x = firstBoundaryInRange(minX, AddressHelper.CLUSTER_SIZE); x <= maxX; x += AddressHelper.CLUSTER_SIZE) {
                drawLineAtThreeHeights(player, world, x, clusterY, minZ, x, clusterY, maxZ, clusterDust);
            }
            for (int x = firstBoundaryInRange(minX, AddressHelper.LOCALNET_SIZE); x <= maxX; x += AddressHelper.LOCALNET_SIZE) {
                drawLineAtThreeHeights(player, world, x, localnetY, minZ, x, localnetY, maxZ, localnetDust);
            }

            // Lines at fixed Z (run along X): same tier Y offsets
            for (int z = firstBoundaryInRange(minZ, AddressHelper.MAINNET_SIZE); z <= maxZ; z += AddressHelper.MAINNET_SIZE) {
                drawLineAtThreeHeights(player, world, minX, mainnetY, z, maxX, mainnetY, z, mainnetDust);
            }
            for (int z = firstBoundaryInRange(minZ, AddressHelper.CLUSTER_SIZE); z <= maxZ; z += AddressHelper.CLUSTER_SIZE) {
                drawLineAtThreeHeights(player, world, minX, clusterY, z, maxX, clusterY, z, clusterDust);
            }
            for (int z = firstBoundaryInRange(minZ, AddressHelper.LOCALNET_SIZE); z <= maxZ; z += AddressHelper.LOCALNET_SIZE) {
                drawLineAtThreeHeights(player, world, minX, localnetY, z, maxX, localnetY, z, localnetDust);
            }
        }
    }

    /** First boundary (multiple of step) that is >= min. */
    private static int firstBoundaryInRange(int min, int step) {
        int q = Math.floorDiv(min, step);
        int b = q * step;
        if (b < min) b += step;
        return b;
    }

    /** Draws the same line at three Y levels (baseY-1, baseY, baseY+1) for vertical thickness. */
    private void drawLineAtThreeHeights(Player player, World world, double x1, double baseY, double z1, double x2, double baseY2, double z2,
                                        Particle.DustOptions dust) {
        for (int dy : VERTICAL_OFFSETS) {
            double y1 = baseY + dy;
            double y2 = baseY2 + dy;
            drawLine(player, world, x1, y1, z1, x2, y2, z2, dust);
        }
    }

    private void drawLine(Player player, World world, double x1, double y1, double z1, double x2, double y2, double z2,
                          Particle.DustOptions dust) {
        for (int i = 0; i <= PARTICLES_PER_LINE; i++) {
            double t = (double) i / PARTICLES_PER_LINE;
            double x = x1 + t * (x2 - x1);
            double y = y1 + t * (y2 - y1);
            double z = z1 + t * (z2 - z1);
            Location at = new Location(world, x, y, z);
            player.spawnParticle(Particle.DUST, at, PARTICLE_COUNT_PER_POINT, 0.04, 0.04, 0.04, 0, dust);
        }
    }
}
