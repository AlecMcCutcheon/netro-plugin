package dev.netro.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.World;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Double-chest (54-slot) UI for setting rail shape from player's perspective.
 * Layout: 6 rows x 9 columns. Top=slot 4, Bottom=slot 49, Left=18, Right=26, Center=22.
 * Pick two directions (e.g. N and E) to choose the rail shape (e.g. NORTH_EAST).
 * When opened for a pending rule, center = Cancel; selecting two directions confirms the shape for the rule.
 */
public class RailroadControllerHolder implements InventoryHolder {

    public static final int SIZE = 54;
    /** Top of cross (forward direction when opened). */
    public static final int SLOT_TOP = 4;
    /** Bottom of cross (back). */
    public static final int SLOT_BOTTOM = 49;
    /** Left of cross. */
    public static final int SLOT_LEFT = 18;
    /** Right of cross. */
    public static final int SLOT_RIGHT = 26;
    /** Center: clear/reset or cancel (when in rule mode). */
    public static final int SLOT_CENTER = 22;

    private final World world;
    private final int blockX;
    private final int blockY;
    private final int blockZ;
    /** Player facing when UI was opened (N/S/E/W only). */
    private final BlockFace facing;
    /** When present, we're choosing shape for a rule; don't apply to block on confirm. */
    private final Optional<PendingSetRailStateRule> pendingRule;
    private final Inventory inventory;
    /** Selected cardinal directions (0, 1, or 2). */
    private final Set<BlockFace> selection = EnumSet.noneOf(BlockFace.class);

    public RailroadControllerHolder(World world, int blockX, int blockY, int blockZ, BlockFace facing) {
        this(world, blockX, blockY, blockZ, facing, null);
    }

    public RailroadControllerHolder(World world, int blockX, int blockY, int blockZ, BlockFace facing, PendingSetRailStateRule pendingRule) {
        this.world = world;
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        this.facing = normalizeFacing(facing);
        this.pendingRule = Optional.ofNullable(pendingRule);
        this.inventory = Bukkit.createInventory(this, SIZE, this.pendingRule.isPresent() ? "Rule: Rail shape" : "Rail direction");
        fillLayout();
    }

    public Optional<PendingSetRailStateRule> getPendingRule() { return pendingRule; }

    private static BlockFace normalizeFacing(BlockFace f) {
        return switch (f) {
            case NORTH, NORTH_NORTH_EAST, NORTH_NORTH_WEST -> BlockFace.NORTH;
            case SOUTH, SOUTH_SOUTH_EAST, SOUTH_SOUTH_WEST -> BlockFace.SOUTH;
            case EAST, EAST_NORTH_EAST, EAST_SOUTH_EAST -> BlockFace.EAST;
            case WEST, WEST_NORTH_WEST, WEST_SOUTH_WEST -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    public World getWorld() { return world; }
    public int getBlockX() { return blockX; }
    public int getBlockY() { return blockY; }
    public int getBlockZ() { return blockZ; }
    public BlockFace getFacing() { return facing; }
    public Set<BlockFace> getSelection() { return selection; }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Which cardinal direction is at the given slot for this holder's facing. */
    public BlockFace getFaceAtSlot(int slot) {
        return switch (slot) {
            case SLOT_TOP -> topFace();
            case SLOT_BOTTOM -> bottomFace();
            case SLOT_LEFT -> leftFace();
            case SLOT_RIGHT -> rightFace();
            default -> null;
        };
    }

    private BlockFace topFace() {
        return facing;
    }

    private BlockFace bottomFace() {
        return facing.getOppositeFace();
    }

    private BlockFace leftFace() {
        return switch (facing) {
            case NORTH -> BlockFace.WEST;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            case WEST -> BlockFace.SOUTH;
            default -> BlockFace.WEST;
        };
    }

    private BlockFace rightFace() {
        return switch (facing) {
            case NORTH -> BlockFace.EAST;
            case SOUTH -> BlockFace.WEST;
            case EAST -> BlockFace.SOUTH;
            case WEST -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    /** True if slot is one of the four direction slots. */
    public boolean isDirectionSlot(int slot) {
        return slot == SLOT_TOP || slot == SLOT_BOTTOM || slot == SLOT_LEFT || slot == SLOT_RIGHT;
    }

    public boolean isCenterSlot(int slot) {
        return slot == SLOT_CENTER;
    }

    /** Add direction to selection; returns true if now have 2 (ready to apply). */
    public boolean select(BlockFace face) {
        if (selection.size() >= 2) return false;
        selection.add(face);
        setDirectionItemEnchanted(slotForFace(face), true);
        return selection.size() == 2;
    }

    public void clearSelection() {
        for (BlockFace f : List.copyOf(selection)) {
            setDirectionItemEnchanted(slotForFace(f), false);
        }
        selection.clear();
    }

    private int slotForFace(BlockFace face) {
        if (face.equals(topFace())) return SLOT_TOP;
        if (face.equals(bottomFace())) return SLOT_BOTTOM;
        if (face.equals(leftFace())) return SLOT_LEFT;
        if (face.equals(rightFace())) return SLOT_RIGHT;
        return -1;
    }

    private void setDirectionItemEnchanted(int slot, boolean enchanted) {
        ItemStack item = inventory.getItem(slot);
        if (item == null || item.getType().isAir()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (enchanted) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        } else {
            meta.removeEnchant(Enchantment.UNBREAKING);
        }
        item.setItemMeta(meta);
    }

    private void fillLayout() {
        inventory.setItem(SLOT_TOP, directionItem(labelFor(topFace())));
        inventory.setItem(SLOT_BOTTOM, directionItem(labelFor(bottomFace())));
        inventory.setItem(SLOT_LEFT, directionItem(labelFor(leftFace())));
        inventory.setItem(SLOT_RIGHT, directionItem(labelFor(rightFace())));
        inventory.setItem(SLOT_CENTER, centerItem());
    }

    private static String labelFor(BlockFace f) {
        return switch (f) {
            case NORTH -> "North";
            case SOUTH -> "South";
            case EAST -> "East";
            case WEST -> "West";
            default -> f.name();
        };
    }

    private static ItemStack directionItem(String label) {
        ItemStack item = new ItemStack(Material.OAK_SIGN, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(label);
            meta.setLore(List.of("Click to select this direction."));
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack centerItem() {
        ItemStack item = new ItemStack(Material.BARRIER, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (pendingRule.isPresent()) {
                meta.setDisplayName("Back to action menu");
                meta.setLore(List.of("Cancel rail shape choice and return to action menu."));
            } else {
                meta.setDisplayName("Clear / Reset");
                meta.setLore(List.of("Clear selection without changing the rail."));
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    /** Convert two cardinals to Rail.Shape. Order-independent. */
    public static Rail.Shape shapeFromTwoFaces(BlockFace a, BlockFace b) {
        if (a == b) return null;
        Set<BlockFace> set = EnumSet.of(a, b);
        if (set.contains(BlockFace.NORTH) && set.contains(BlockFace.SOUTH)) return Rail.Shape.NORTH_SOUTH;
        if (set.contains(BlockFace.EAST) && set.contains(BlockFace.WEST)) return Rail.Shape.EAST_WEST;
        if (set.contains(BlockFace.NORTH) && set.contains(BlockFace.EAST)) return Rail.Shape.NORTH_EAST;
        if (set.contains(BlockFace.NORTH) && set.contains(BlockFace.WEST)) return Rail.Shape.NORTH_WEST;
        if (set.contains(BlockFace.SOUTH) && set.contains(BlockFace.EAST)) return Rail.Shape.SOUTH_EAST;
        if (set.contains(BlockFace.SOUTH) && set.contains(BlockFace.WEST)) return Rail.Shape.SOUTH_WEST;
        return null;
    }

    /** Apply current selection to the block and clear selection. No-op if selection size != 2. */
    public boolean applyToBlockAndClear() {
        if (selection.size() != 2) return false;
        BlockFace[] arr = selection.toArray(new BlockFace[0]);
        Rail.Shape shape = shapeFromTwoFaces(arr[0], arr[1]);
        if (shape == null) {
            clearSelection();
            return false;
        }
        var block = world.getBlockAt(blockX, blockY, blockZ);
        if (!(block.getBlockData() instanceof Rail railData)) {
            clearSelection();
            return false;
        }
        railData.setShape(shape);
        block.setBlockData(railData);
        clearSelection();
        return true;
    }
}
