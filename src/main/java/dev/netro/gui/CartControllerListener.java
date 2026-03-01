package dev.netro.gui;

import dev.netro.NetroPlugin;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Opens the cart controller GUI when the player right-clicks a cart or uses the controller while in a cart. */
public class CartControllerListener implements Listener {

    private final NetroPlugin plugin;

    public CartControllerListener(NetroPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        if (!CartControllerItem.isCartController(plugin, item)) return;
        if (!(event.getRightClicked() instanceof Minecart cart)) return;
        event.setCancelled(true);
        openMainMenu(event.getPlayer(), cart);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        if (!CartControllerItem.isCartController(plugin, item)) return;
        if (!(event.getPlayer().getVehicle() instanceof Minecart cart)) return;
        event.setCancelled(true);
        openMainMenu(event.getPlayer(), cart);
    }

    private void openMainMenu(Player player, Minecart cart) {
        String cartUuid = cart.getUniqueId().toString();
        CartMenuHolder holder = new CartMenuHolder(cartUuid);
        player.openInventory(holder.getInventory());
    }
}
