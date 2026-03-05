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
        "cancel", "clearcache", "debug", "guide", "whereami",
        "station", "setdestination", "dns", "cartcontroller", "railroadcontroller"
    );

    private final NetroPlugin plugin;
    private final StationCommand stationCommand;
    private final SetDestinationCommand setDestinationCommand;
    private final DnsCommand dnsCommand;
    private final WhereAmICommand whereAmICommand;
    private final CartControllerCommand cartControllerCommand;
    private final RailroadControllerCommand railroadControllerCommand;

    public NetroCommand(NetroPlugin plugin, StationCommand stationCommand,
                        SetDestinationCommand setDestinationCommand, DnsCommand dnsCommand,
                        WhereAmICommand whereAmICommand,
                        CartControllerCommand cartControllerCommand,
                        RailroadControllerCommand railroadControllerCommand) {
        this.plugin = plugin;
        this.stationCommand = stationCommand;
        this.setDestinationCommand = setDestinationCommand;
        this.dnsCommand = dnsCommand;
        this.whereAmICommand = whereAmICommand;
        this.cartControllerCommand = cartControllerCommand;
        this.railroadControllerCommand = railroadControllerCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /netro <subcommand> [args]. Subcommands: cancel, clearcache, debug, guide, whereami, station, setdestination, dns, cartcontroller, railroadcontroller");
            return true;
        }
        String sub = args[0].toLowerCase();
        String[] subArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];

        if ("cancel".equals(sub)) {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage("Only players can cancel a pending action.");
                return true;
            }
            java.util.UUID uuid = player.getUniqueId();
            boolean hadRelocate = plugin.getPendingRelocate(uuid) != null;
            boolean hadPortal = plugin.getPendingPortalLink(uuid) != null;
            boolean hadRail = plugin.getPendingSetRailState(uuid) != null;
            plugin.setPendingRelocate(uuid, null);
            plugin.setPendingPortalLink(uuid, null);
            plugin.setReopenPortalLinkAfterSave(uuid, null);
            plugin.setPendingSetRailState(uuid, null);
            if (hadRelocate || hadPortal || hadRail) {
                sender.sendMessage("Cancelled pending action (relocate, portal link, or set rail state).");
            } else {
                sender.sendMessage("No pending action to cancel.");
            }
            return true;
        }
        if ("debug".equals(sub)) {
            boolean now = !plugin.isDebugEnabled();
            plugin.setDebug(now);
            sender.sendMessage("Debug is now " + (now ? "on" : "off") + ". Detector and routing logs go to the server console.");
            return true;
        }
        if ("clearcache".equals(sub)) {
            dev.netro.database.RouteCacheRepository routeCache = new dev.netro.database.RouteCacheRepository(plugin.getDatabase());
            routeCache.deleteAll();
            plugin.getRoutingEngine().clearRouteRefreshQueue();
            sender.sendMessage("All route caches cleared. Routing will recompute paths on demand.");
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
        if ("whereami".equals(sub)) return whereAmICommand.onCommand(sender, command, subLabel, subArgs);
        if ("cartcontroller".equals(sub)) return cartControllerCommand.onCommand(sender, command, subLabel, subArgs);
        if ("railroadcontroller".equals(sub)) return railroadControllerCommand.onCommand(sender, command, subLabel, subArgs);

        sender.sendMessage("Unknown subcommand. Use: /netro cancel | clearcache | debug | guide | whereami | station | setdestination | dns | cartcontroller | railroadcontroller");
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
