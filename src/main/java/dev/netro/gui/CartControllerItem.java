package dev.netro.gui;

import dev.netro.NetroPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** Cart Controller item: right-click a cart or use while in a cart to open the control GUI. */
public final class CartControllerItem {

    public static final String DISPLAY_NAME = "Cart Controller";

    private CartControllerItem() {}

    private static NamespacedKey key(NetroPlugin plugin) {
        return new NamespacedKey(plugin, "cart_controller");
    }

    public static ItemStack create(NetroPlugin plugin) {
        ItemStack item = new ItemStack(Material.COMPASS, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(DISPLAY_NAME);
            meta.setLore(List.of(
                "Right-click a cart or use while in a cart",
                "to open the control menu."
            ));
            meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.BYTE, (byte) 1);
        }
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isCartController(NetroPlugin plugin, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key(plugin), PersistentDataType.BYTE);
    }
}
