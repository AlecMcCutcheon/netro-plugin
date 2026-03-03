package dev.netro.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Locale;

/**
 * Main cart controller menu. Symmetric 3-row layout:
 * Row 0: [ ] [ ] [ ] [ ] [ ] [ ] [ ] [ ] [ ]
 * Row 1: [ ] [Stop] [Lower] [Disable Cruise] [Increase] [Start] [ ] [ ] [ ]
 * Row 2: [ ] [ ] [ ] [Direction] [ ] [Destination] [ ] [ ] [ ]
 */
public class CartMenuHolder implements InventoryHolder {

    public static final int SIZE = 27;
    public static final int SLOT_STOP = 11;
    public static final int SLOT_LOWER = 12;
    public static final int SLOT_DISABLE_CRUISE = 13;
    public static final int SLOT_INCREASE = 14;
    public static final int SLOT_START = 15;
    public static final int SLOT_DIR = 21;
    public static final int SLOT_DEST = 23;

    private final String cartUuid;
    private final Inventory inventory;

    public CartMenuHolder(String cartUuid) {
        this.cartUuid = cartUuid;
        this.inventory = Bukkit.createInventory(this, SIZE, "Cart Controller");
        fillLayout();
    }

    public String getCartUuid() {
        return cartUuid;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void fillLayout() {
        inventory.setItem(SLOT_INCREASE, newItem(Material.FIREWORK_ROCKET, "Increase Speed by 1", List.of("Current speed: —")));
        inventory.setItem(SLOT_LOWER, newItem(Material.LEVER, "Lower Speed by 1", List.of("Current speed: —")));
        inventory.setItem(SLOT_STOP, newItem(Material.BARRIER, "Stop", List.of("Stops the cart and saves speed.")));
        inventory.setItem(SLOT_START, newItem(Material.LIME_WOOL, "Start", List.of("Resume with saved speed.")));
        inventory.setItem(SLOT_DISABLE_CRUISE, newItem(Material.BLAZE_POWDER, "Disable Cruise", List.of("Turn cruise off; cart keeps moving.")));
        inventory.setItem(SLOT_DIR, newItem(Material.OAK_SIGN, "Change Direction", List.of("Current: —", "Click to reverse.")));
        inventory.setItem(SLOT_DEST, newItem(Material.MAP, "Destination", List.of("Set cart destination.")));
    }

    /** Update item lores with live cart state (current speed, heading, cruise/stopped, current destination). Call every ~20 ticks. */
    public void updateLore(Minecart cart, CartControllerState state, String currentDestination) {
        if (cart == null || !cart.isValid()) return;
        Vector vel = cart.getVelocity();
        double speed = vel.length();
        String heading = headingFromVector(vel);
        String opposite = oppositeHeading(heading);
        String mode = state != null && state.isCruiseActive() ? "Cruise" : "Stopped";
        String destLine = (currentDestination != null && !currentDestination.isEmpty()) ? currentDestination : "(none)";
        String setSpeedLine = state != null ? "Set speed: " + state.getSpeedLevel() + "/10" : "Set speed: —";

        updateItemLore(SLOT_INCREASE, List.of("Current speed: " + String.format("%.2f", speed), setSpeedLine));
        updateItemLore(SLOT_LOWER, List.of("Current speed: " + String.format("%.2f", speed), setSpeedLine));
        updateItemLore(SLOT_STOP, List.of("Stops the cart and saves speed.", "Mode: " + mode));
        updateItemLore(SLOT_START, List.of("Resume with saved speed.", "Mode: " + mode));
        updateItemLore(SLOT_DISABLE_CRUISE, List.of("Turn cruise off; cart keeps moving.", "Mode: " + mode));
        updateItemLore(SLOT_DIR, List.of("Current: " + heading, "Click to head " + opposite));
        updateItemLore(SLOT_DEST, List.of("Set cart destination.", "Current: " + destLine));
    }

    private static String headingFromVector(Vector v) {
        if (v.lengthSquared() < 1e-6) return "Stopped";
        double x = v.getX(), z = v.getZ();
        if (Math.abs(z) >= Math.abs(x)) return z > 0 ? "South" : "North";
        return x > 0 ? "East" : "West";
    }

    private static String oppositeHeading(String h) {
        return switch (h.toUpperCase(Locale.ROOT)) {
            case "NORTH" -> "South";
            case "SOUTH" -> "North";
            case "EAST" -> "West";
            case "WEST" -> "East";
            default -> "reverse";
        };
    }

    private void updateItemLore(int slot, List<String> lore) {
        ItemStack item = inventory.getItem(slot);
        if (item == null || item.getType().isAir()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private static ItemStack newItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
