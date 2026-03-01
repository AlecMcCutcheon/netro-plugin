package dev.netro.detector;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Powerable;

import java.util.Set;

/**
 * Sets or reads the output state of copper bulbs. Plugin never touches rails;
 * it only toggles copper bulb state so the player can wire with comparators.
 * Uses the <b>lit</b> state so the bulb fully lights up and emits redstone
 * (comparator signal 15). The "powered" state only shows the red dot and does
 * not make comparators output; lit is what actually transmits signal.
 */
public final class CopperBulbHelper {

    private static final Set<Material> COPPER_BULBS = Set.of(
        Material.COPPER_BULB,
        Material.EXPOSED_COPPER_BULB,
        Material.WEATHERED_COPPER_BULB,
        Material.OXIDIZED_COPPER_BULB,
        Material.WAXED_COPPER_BULB,
        Material.WAXED_EXPOSED_COPPER_BULB,
        Material.WAXED_WEATHERED_COPPER_BULB,
        Material.WAXED_OXIDIZED_COPPER_BULB
    );

    public static boolean isCopperBulb(Material material) {
        return material != null && COPPER_BULBS.contains(material);
    }

    public static boolean isCopperBulb(Block block) {
        return block != null && isCopperBulb(block.getType());
    }

    /**
     * Set the output state of the copper bulb at (x,y,z). Prefers the <b>lit</b>
     * state (Lightable) so the bulb fully lights and comparators get signal 15;
     * falls back to powered (Powerable) if the block has no Lightable.
     * Call on main thread.
     */
    public static void setPowered(World world, int x, int y, int z, boolean on) {
        if (world == null) return;
        Block block = world.getBlockAt(x, y, z);
        if (!isCopperBulb(block)) return;
        BlockData data = block.getBlockData();
        if (data instanceof Lightable lit) {
            if (lit.isLit() == on) return;
            lit.setLit(on);
            block.setBlockData(data);
        } else if (data instanceof Powerable p) {
            if (p.isPowered() == on) return;
            p.setPowered(on);
            block.setBlockData(data);
        }
    }

    public static boolean isPowered(World world, int x, int y, int z) {
        if (world == null) return false;
        Block block = world.getBlockAt(x, y, z);
        if (!isCopperBulb(block)) return false;
        BlockData data = block.getBlockData();
        if (data instanceof Lightable lit) return lit.isLit();
        return data instanceof Powerable p && p.isPowered();
    }
}
