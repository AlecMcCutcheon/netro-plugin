package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.database.TransferNodePortalRepository;
import dev.netro.util.DimensionHelper;
import dev.netro.util.PortalTracer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Repeating task that highlights the block a player is looking at when they are in
 * "select block to relocate" mode or "select rail to set state" mode.
 */
public final class BlockHighlightTask implements Runnable {

    private static final int RAY_TRACE_RANGE = 6;
    private static final int PARTICLES_PER_EDGE = 4;
    private static final float DUST_SIZE = 0.4f;
    private static final Color RELOCATE_HIGHLIGHT = Color.AQUA;
    private static final Color RAIL_HIGHLIGHT = Color.fromRGB(255, 220, 100);
    private static final Color PORTAL_LINK_HIGHLIGHT = Color.fromRGB(180, 100, 255);

    private final NetroPlugin plugin;

    public BlockHighlightTask(NetroPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        List<Player> withPending = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.isOnline() || !player.getWorld().isChunkLoaded(player.getLocation().getBlockX() >> 4, player.getLocation().getBlockZ() >> 4)) {
                continue;
            }
            if (plugin.getPendingRelocate(player.getUniqueId()) != null
                || plugin.getPendingSetRailState(player.getUniqueId()) != null
                || plugin.getPendingPortalLink(player.getUniqueId()) != null) {
                withPending.add(player);
            }
        }
        for (Player player : withPending) {
            Block target = getTargetBlock(player);
            if (target == null) continue;

            boolean inRelocate = plugin.getPendingRelocate(player.getUniqueId()) != null;
            boolean inSetRail = plugin.getPendingSetRailState(player.getUniqueId()) != null;
            boolean inPortalLink = plugin.getPendingPortalLink(player.getUniqueId()) != null;

            if (inPortalLink) {
                PendingPortalLink pending = plugin.getPendingPortalLink(player.getUniqueId());
                if (pending != null) {
                    int expectedDim = PortalLinkHelper.getExpectedDimensionForSide(pending.nodeId(), pending.side(), plugin.getDatabase());
                    int targetDim = DimensionHelper.dimensionFromEnvironment(target.getWorld().getEnvironment());
                    if (targetDim == expectedDim) {
                        List<TransferNodePortalRepository.BlockPos> portalBlocks = PortalTracer.tracePortal(
                            target.getWorld(), target.getX(), target.getY(), target.getZ());
                        if (!portalBlocks.isEmpty()) {
                            for (TransferNodePortalRepository.BlockPos p : portalBlocks) {
                                if (!player.getWorld().getName().equals(p.world())) continue;
                                Block b = target.getWorld().getBlockAt(p.x(), p.y(), p.z());
                                drawBlockOutline(player, b, PORTAL_LINK_HIGHLIGHT);
                            }
                        }
                    }
                }
            } else if (inRelocate) {
                if (!target.getType().isAir()) {
                    Block placeAt = target.getRelative(BlockFace.UP);
                    drawBlockOutline(player, placeAt, RELOCATE_HIGHLIGHT);
                }
            } else if (inSetRail) {
                if (target.getType() == Material.RAIL) {
                    drawSlabOutline(player, target, RAIL_HIGHLIGHT);
                }
            }
        }
    }

    private Block getTargetBlock(Player player) {
        RayTraceResult result = player.rayTraceBlocks(RAY_TRACE_RANGE);
        if (result == null) return null;
        return result.getHitBlock();
    }

    private void drawBlockOutline(Player player, Block block, Color color) {
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        Particle.DustOptions dust = new Particle.DustOptions(color, DUST_SIZE);

        World world = block.getWorld();
        // 12 edges of the block: 4 bottom, 4 top, 4 vertical
        // Bottom: (x,y,z)->(x+1,y,z), (x+1,y,z)->(x+1,y,z+1), (x+1,y,z+1)->(x,y,z+1), (x,y,z+1)->(x,y,z)
        drawEdge(player, world, x, y, z, 1, 0, 0, dust);
        drawEdge(player, world, x + 1, y, z, 0, 0, 1, dust);
        drawEdge(player, world, x + 1, y, z + 1, -1, 0, 0, dust);
        drawEdge(player, world, x, y, z + 1, 0, 0, -1, dust);
        // Top
        drawEdge(player, world, x, y + 1, z, 1, 0, 0, dust);
        drawEdge(player, world, x + 1, y + 1, z, 0, 0, 1, dust);
        drawEdge(player, world, x + 1, y + 1, z + 1, -1, 0, 0, dust);
        drawEdge(player, world, x, y + 1, z + 1, 0, 0, -1, dust);
        // Vertical
        drawEdge(player, world, x, y, z, 0, 1, 0, dust);
        drawEdge(player, world, x + 1, y, z, 0, 1, 0, dust);
        drawEdge(player, world, x + 1, y, z + 1, 0, 1, 0, dust);
        drawEdge(player, world, x, y, z + 1, 0, 1, 0, dust);
    }

    /** Slab-sized outline (bottom half of block) for rail highlight – closer to actual rail size. */
    private void drawSlabOutline(Player player, Block block, Color color) {
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        double bottom = y;
        double top = y + 0.5;
        World world = block.getWorld();
        Particle.DustOptions dust = new Particle.DustOptions(color, DUST_SIZE);
        // Bottom face of slab (at y)
        drawEdge(player, world, x, bottom, z, 1, 0, 0, dust);
        drawEdge(player, world, x + 1, bottom, z, 0, 0, 1, dust);
        drawEdge(player, world, x + 1, bottom, z + 1, -1, 0, 0, dust);
        drawEdge(player, world, x, bottom, z + 1, 0, 0, -1, dust);
        // Top face of slab (at y+0.5)
        drawEdge(player, world, x, top, z, 1, 0, 0, dust);
        drawEdge(player, world, x + 1, top, z, 0, 0, 1, dust);
        drawEdge(player, world, x + 1, top, z + 1, -1, 0, 0, dust);
        drawEdge(player, world, x, top, z + 1, 0, 0, -1, dust);
        // Vertical edges (y to y+0.5)
        drawEdge(player, world, x, bottom, z, 0, 0.5, 0, dust);
        drawEdge(player, world, x + 1, bottom, z, 0, 0.5, 0, dust);
        drawEdge(player, world, x + 1, bottom, z + 1, 0, 0.5, 0, dust);
        drawEdge(player, world, x, bottom, z + 1, 0, 0.5, 0, dust);
    }

    /** Draw particles along one block edge from (ox,oy,oz) in direction (dx,dy,dz). */
    private void drawEdge(Player player, World world, int ox, int oy, int oz, int dx, int dy, int dz, Particle.DustOptions dust) {
        drawEdge(player, world, (double) ox, (double) oy, (double) oz, (double) dx, (double) dy, (double) dz, dust);
    }

    private void drawEdge(Player player, World world, double ox, double oy, double oz, double dx, double dy, double dz, Particle.DustOptions dust) {
        for (int i = 0; i <= PARTICLES_PER_EDGE; i++) {
            double t = (double) i / PARTICLES_PER_EDGE;
            double px = ox + t * dx;
            double py = oy + t * dy;
            double pz = oz + t * dz;
            Location at = new Location(world, px, py, pz);
            player.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust);
        }
    }
}
