package dev.netro.util;

import dev.netro.database.TransferNodePortalRepository;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.*;

/**
 * Traces a nether portal from a seed block (portal block or obsidian with portal adjacent).
 * Returns only blocks necessary for the portal to exist: NETHER_PORTAL fill blocks, and
 * obsidian that has a NETHER_PORTAL neighbor (the frame). Excludes corner obsidian that
 * only touch other obsidian and decorative obsidian behind/in front of the portal.
 */
public final class PortalTracer {

    private static final BlockFace[] CARDINALS = { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN };

    private PortalTracer() {}

    /**
     * If the block at (x,y,z) is a portal block or obsidian with a portal neighbor, trace the full portal
     * and return only necessary blocks: portal fill + obsidian that has a portal neighbor.
     * Excludes obsidian with only obsidian neighbors and decorative layers (flat behind/in front).
     */
    public static List<TransferNodePortalRepository.BlockPos> tracePortal(World world, int x, int y, int z) {
        if (world == null) return List.of();
        Block start = world.getBlockAt(x, y, z);
        Material type = start.getType();
        Block seed = null;
        if (type == Material.NETHER_PORTAL) {
            seed = start;
        } else if (type == Material.OBSIDIAN) {
            for (BlockFace f : CARDINALS) {
                Block n = start.getRelative(f);
                if (n.getType() == Material.NETHER_PORTAL) {
                    seed = n;
                    break;
                }
            }
        }
        if (seed == null) return List.of();

        Set<String> portalKeys = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(seed);
        portalKeys.add(key(seed.getX(), seed.getY(), seed.getZ()));

        while (!queue.isEmpty()) {
            Block b = queue.poll();
            for (BlockFace f : CARDINALS) {
                Block n = b.getRelative(f);
                if (n.getType() != Material.NETHER_PORTAL) continue;
                String k = key(n.getX(), n.getY(), n.getZ());
                if (portalKeys.add(k)) queue.add(n);
            }
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (String pk : portalKeys) {
            int[] c = unkey(pk);
            minX = Math.min(minX, c[0]); maxX = Math.max(maxX, c[0]);
            minY = Math.min(minY, c[1]); maxY = Math.max(maxY, c[1]);
            minZ = Math.min(minZ, c[2]); maxZ = Math.max(maxZ, c[2]);
        }

        int spanX = maxX - minX, spanY = maxY - minY, spanZ = maxZ - minZ;
        int depthAxis; // 0=X, 1=Y, 2=Z - the axis with smallest span (portal "thickness")
        if (spanX <= spanY && spanX <= spanZ) depthAxis = 0;
        else if (spanY <= spanZ) depthAxis = 1;
        else depthAxis = 2;

        Set<String> frameKeys = new HashSet<>();
        for (String pk : portalKeys) {
            int[] c = unkey(pk);
            Block b = world.getBlockAt(c[0], c[1], c[2]);
            for (BlockFace f : CARDINALS) {
                Block n = b.getRelative(f);
                if (n.getType() != Material.OBSIDIAN) continue;
                int nx = n.getX(), ny = n.getY(), nz = n.getZ();
                if (!hasNeighborOfType(world, nx, ny, nz, Material.NETHER_PORTAL)) continue;
                int depthCoord = depthAxis == 0 ? nx : (depthAxis == 1 ? ny : nz);
                int portalMin = depthAxis == 0 ? minX : (depthAxis == 1 ? minY : minZ);
                int portalMax = depthAxis == 0 ? maxX : (depthAxis == 1 ? maxY : maxZ);
                if (depthCoord == portalMin - 1 || depthCoord == portalMax + 1) {
                    frameKeys.add(key(nx, ny, nz));
                }
            }
        }

        List<TransferNodePortalRepository.BlockPos> out = new ArrayList<>();
        String worldName = world.getName();
        for (String k : portalKeys) {
            int[] c = unkey(k);
            out.add(new TransferNodePortalRepository.BlockPos(worldName, c[0], c[1], c[2]));
        }
        for (String k : frameKeys) {
            int[] c = unkey(k);
            out.add(new TransferNodePortalRepository.BlockPos(worldName, c[0], c[1], c[2]));
        }
        return out;
    }

    private static boolean hasNeighborOfType(World world, int x, int y, int z, Material type) {
        for (BlockFace f : CARDINALS) {
            Block n = world.getBlockAt(x + f.getModX(), y + f.getModY(), z + f.getModZ());
            if (n.getType() == type) return true;
        }
        return false;
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static int[] unkey(String k) {
        String[] p = k.split(",");
        return new int[] { Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]) };
    }
}
