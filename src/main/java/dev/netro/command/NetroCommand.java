package dev.netro.command;

import dev.netro.NetroPlugin;
import dev.netro.util.NetroBook;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class NetroCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "debug", "guide",
        "station", "setdestination", "dns", "cartcontroller", "railroadcontroller"
    );

    private final NetroPlugin plugin;
    private final StationCommand stationCommand;
    private final SetDestinationCommand setDestinationCommand;
    private final DnsCommand dnsCommand;
    private final CartControllerCommand cartControllerCommand;
    private final RailroadControllerCommand railroadControllerCommand;

    public NetroCommand(NetroPlugin plugin, StationCommand stationCommand,
                        SetDestinationCommand setDestinationCommand, DnsCommand dnsCommand,
                        CartControllerCommand cartControllerCommand,
                        RailroadControllerCommand railroadControllerCommand) {
        this.plugin = plugin;
        this.stationCommand = stationCommand;
        this.setDestinationCommand = setDestinationCommand;
        this.dnsCommand = dnsCommand;
        this.cartControllerCommand = cartControllerCommand;
        this.railroadControllerCommand = railroadControllerCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /netro <subcommand> [args]. Subcommands: debug, guide, station, setdestination, dns, cartcontroller, railroadcontroller");
            return true;
        }
        String sub = args[0].toLowerCase();
        String[] subArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];

        if ("debug".equals(sub)) {
            boolean now = !plugin.isDebugEnabled();
            plugin.setDebug(now);
            sender.sendMessage("Debug is now " + (now ? "on" : "off") + ". Detector and routing logs go to the server console.");
            return true;
        }
        if ("guide".equals(sub)) {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage("Only players can receive the guide book.");
                return true;
            }
            player.getInventory().addItem(NetroBook.createGuideBook());
            sender.sendMessage("You received the Netro Guide. Use the table of contents to jump to sections.");
            return true;
        }
        String subLabel = "netro " + sub;
        if ("station".equals(sub)) return stationCommand.onCommand(sender, command, subLabel, subArgs);
        if ("setdestination".equals(sub)) return setDestinationCommand.onCommand(sender, command, subLabel, subArgs);
        if ("dns".equals(sub)) return dnsCommand.onCommand(sender, command, subLabel, subArgs);
        if ("cartcontroller".equals(sub)) return cartControllerCommand.onCommand(sender, command, subLabel, subArgs);
        if ("railroadcontroller".equals(sub)) return railroadControllerCommand.onCommand(sender, command, subLabel, subArgs);

        sender.sendMessage("Unknown subcommand. Use: /netro debug | guide | station | setdestination | dns | cartcontroller | railroadcontroller");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) return new ArrayList<>(SUBCOMMANDS);
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, out);
            Collections.sort(out);
            return out;
        }
        String sub = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        String subLabel = "netro " + sub;
        if ("station".equals(sub)) return stationCommand.onTabComplete(sender, command, subLabel, subArgs);
        if ("dns".equals(sub)) return dnsCommand.onTabComplete(sender, command, subLabel, subArgs);
        return List.of();
    }
}
