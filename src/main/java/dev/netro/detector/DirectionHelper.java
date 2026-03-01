package dev.netro.detector;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.bukkit.util.Vector;

/**
 * Converts sign facing and cart velocity (and optional rail shape) to LEFT/RIGHT for detector rules.
 * Sign facing NORTH → LEFT=WEST, RIGHT=EAST; cart direction from velocity, constrained by rail shape when available.
 */
public final class DirectionHelper {

    public static BlockFace rotateClockwise(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> f;
        };
    }

    public static BlockFace rotateCounterClockwise(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> f;
        };
    }

    /** Parse stored sign facing string to BlockFace. */
    public static BlockFace signFacingFromString(String s) {
        if (s == null || s.isEmpty()) return BlockFace.NORTH;
        try {
            return BlockFace.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BlockFace.NORTH;
        }
    }

    /** Snap velocity to nearest cardinal (NORTH, SOUTH, EAST, WEST). */
    public static BlockFace velocityToCardinal(Vector velocity) {
        if (velocity == null) return BlockFace.NORTH;
        double x = velocity.getX();
        double z = velocity.getZ();
        if (Math.abs(x) >= Math.abs(z)) return x >= 0 ? BlockFace.EAST : BlockFace.WEST;
        return z >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /**
     * For a detector's sign facing and the cart's velocity, return "LEFT", "RIGHT", or null if no match.
     */
    public static String directionLabel(String signFacingStr, Vector velocity) {
        return cardinalToDirectionLabel(signFacingStr, velocityToCardinal(velocity));
    }

    /**
     * Direction of travel using rail shape when available (more accurate on curves and straight rails).
     * Straight rails constrain to one axis; curved rails constrain to the two directions of the curve.
     */
    public static BlockFace velocityAndRailToCardinal(Vector velocity, Block railBlock) {
        if (velocity == null) return BlockFace.NORTH;
        if (railBlock == null || !railBlock.getType().toString().contains("RAIL")) return velocityToCardinal(velocity);
        if (!(railBlock.getBlockData() instanceof Rail rail)) return velocityToCardinal(velocity);
        return switch (rail.getShape()) {
            case NORTH_SOUTH -> velocity.getZ() >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
            case EAST_WEST -> velocity.getX() >= 0 ? BlockFace.EAST : BlockFace.WEST;
            case NORTH_EAST -> (-velocity.getZ() >= velocity.getX()) ? BlockFace.NORTH : BlockFace.EAST;
            case NORTH_WEST -> (-velocity.getZ() >= -velocity.getX()) ? BlockFace.NORTH : BlockFace.WEST;
            case SOUTH_EAST -> (velocity.getZ() >= velocity.getX()) ? BlockFace.SOUTH : BlockFace.EAST;
            case SOUTH_WEST -> (velocity.getZ() >= -velocity.getX()) ? BlockFace.SOUTH : BlockFace.WEST;
            case ASCENDING_NORTH -> velocity.getZ() >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
            case ASCENDING_SOUTH -> velocity.getZ() >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
            case ASCENDING_EAST -> velocity.getX() >= 0 ? BlockFace.EAST : BlockFace.WEST;
            case ASCENDING_WEST -> velocity.getX() >= 0 ? BlockFace.EAST : BlockFace.WEST;
        };
    }

    /** Convert cardinal direction of travel to LEFT/RIGHT relative to sign facing, or null if cart is going "ahead" or "back". */
    public static String cardinalToDirectionLabel(String signFacingStr, BlockFace cartCardinal) {
        if (cartCardinal == null) return null;
        BlockFace signFacing = signFacingFromString(signFacingStr);
        BlockFace left = rotateCounterClockwise(signFacing);
        BlockFace right = rotateClockwise(signFacing);
        if (cartCardinal == left) return "LEFT";
        if (cartCardinal == right) return "RIGHT";
        return null;
    }
}
