package dev.netro.command;

import dev.netro.NetroPlugin;
import dev.netro.gui.CartControllerItem;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CartControllerCommand implements CommandExecutor {

    private final NetroPlugin plugin;

    public CartControllerCommand(NetroPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can run this command.");
            return true;
        }
        player.getInventory().addItem(CartControllerItem.create(plugin));
        player.sendMessage("You received the Cart Controller. Right-click a cart or use while in a cart to open the menu.");
        return true;
    }
}
