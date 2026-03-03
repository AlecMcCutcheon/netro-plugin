package dev.netro.gui;

import dev.netro.NetroPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** Item that opens the Railroad Controller (rail direction) UI and reminds about Rules UI. */
public final class RailroadControllerItem {

    public static final String DISPLAY_NAME = "Railroad Controller";

    private RailroadControllerItem() {}

    private static NamespacedKey key(NetroPlugin plugin) {
        return new NamespacedKey(plugin, "railroad_controller");
    }

    public static ItemStack create(NetroPlugin plugin) {
        ItemStack item = new ItemStack(Material.BRUSH, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(DISPLAY_NAME);
            meta.setLore(List.of(
                "Right-click a rail to set its direction.",
                "Sneak+right-click a detector/controller sign to open Rules."
            ));
            meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.BYTE, (byte) 1);
        }
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isRailroadController(NetroPlugin plugin, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key(plugin), PersistentDataType.BYTE);
    }
}
