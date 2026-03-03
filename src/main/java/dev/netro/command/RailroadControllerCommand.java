package dev.netro.command;

import dev.netro.NetroPlugin;
import dev.netro.gui.RailroadControllerItem;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class RailroadControllerCommand implements CommandExecutor {

    private final NetroPlugin plugin;

    public RailroadControllerCommand(NetroPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can run this command.");
            return true;
        }
        if (args.length > 0 && "cancel".equalsIgnoreCase(args[0])) {
            boolean hadPending = plugin.getPendingSetRailState(player.getUniqueId()) != null;
            if (hadPending) {
                plugin.setPendingSetRailState(player.getUniqueId(), null);
                player.sendMessage("Pending rail-state rule cancelled. You can create a new rule from the Rules UI.");
            }
            boolean hadOpenUI = player.getOpenInventory().getTopInventory().getHolder() instanceof dev.netro.gui.RulesMainHolder
                || player.getOpenInventory().getTopInventory().getHolder() instanceof dev.netro.gui.RulesCreateStep1Holder
                || player.getOpenInventory().getTopInventory().getHolder() instanceof dev.netro.gui.RulesCreateStep2Holder
                || player.getOpenInventory().getTopInventory().getHolder() instanceof dev.netro.gui.RulesCreateStep3Holder
                || player.getOpenInventory().getTopInventory().getHolder() instanceof dev.netro.gui.RulesConfirmDeleteHolder
                || player.getOpenInventory().getTopInventory().getHolder() instanceof dev.netro.gui.RulesDestinationPickerHolder
                || player.getOpenInventory().getTopInventory().getHolder() instanceof dev.netro.gui.RulesSetRailStateHolder
                || player.getOpenInventory().getTopInventory().getHolder() instanceof dev.netro.gui.RailroadControllerHolder;
            if (hadOpenUI) {
                player.closeInventory();
                player.sendMessage("Rules UI closed.");
            } else if (!hadPending) {
                player.sendMessage("No Rules UI open. Sneak+right-click a detector or controller sign (or right-click the copper bulb) to open it.");
            }
            return true;
        }
        player.getInventory().addItem(RailroadControllerItem.create(plugin));
        player.sendMessage("You received the Railroad Controller. Right-click a rail to set direction. Sneak+right-click a detector/controller sign to open Rules.");
        return true;
    }
}
