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
        "detectordebug", "routingdebug", "routinglog", "pairingwand", "segmentwand", "book", "guide"
    );

    private final NetroPlugin plugin;

    public NetroCommand(NetroPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && "detectordebug".equalsIgnoreCase(args[0])) {
            boolean now = !plugin.isDetectorDebugEnabled();
            plugin.setDetectorDebug(now);
            sender.sendMessage("Detector debug is now " + (now ? "on" : "off") + ". Logs go to the server console.");
            return true;
        }
        if (args.length > 0 && "routingdebug".equalsIgnoreCase(args[0])) {
            boolean now = !plugin.isRoutingDebugEnabled();
            plugin.setRoutingDebug(now);
            sender.sendMessage("Routing debug is now " + (now ? "on" : "off") + ". Next-hop decisions and block reasons go to the server console.");
            return true;
        }
        if (args.length >= 2 && "routinglog".equalsIgnoreCase(args[0])) {
            String cartUuid = args[1];
            java.util.List<java.util.Map<String, Object>> list = plugin.getAPI().getLastRoutingDecisions(cartUuid, 5);
            if (list.isEmpty()) {
                sender.sendMessage("No routing decisions found for cart " + cartUuid);
            } else {
                for (int i = 0; i < list.size(); i++) {
                    java.util.Map<String, Object> row = list.get(i);
                    String line = String.format("[%d] station=%s preferred=%s chosen=%s canDispatch=%s dest=%s",
                        i + 1,
                        row.get("station_id"),
                        row.get("preferred_node_id"),
                        row.get("chosen_node_id"),
                        row.get("can_dispatch"),
                        row.get("destination_address"));
                    if (row.get("block_reason") != null && !((String) row.get("block_reason")).isEmpty()) {
                        line += " reason=" + row.get("block_reason");
                    }
                    sender.sendMessage(line);
                }
            }
            return true;
        }
        if (args.length > 0 && "pairingwand".equalsIgnoreCase(args[0])) {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage("Only players can receive the pairing wand.");
                return true;
            }
            player.getInventory().addItem(plugin.getLinkWandListener().createPairingWand());
            sender.sendMessage("You received the Pairing Wand. Click a transfer detector, then the other station's transfer detector to pair.");
            return true;
        }
        if (args.length > 0 && "segmentwand".equalsIgnoreCase(args[0])) {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage("Only players can receive the segment wand.");
                return true;
            }
            player.getInventory().addItem(plugin.getLinkWandListener().createSegmentWand());
            sender.sendMessage("You received the Segment Wand. Click transfer A → junction detector(s) → transfer B to register segment.");
            return true;
        }
        if (args.length > 0 && ("book".equalsIgnoreCase(args[0]) || "guide".equalsIgnoreCase(args[0]))) {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage("Only players can receive the guide book.");
                return true;
            }
            player.getInventory().addItem(NetroBook.createGuideBook());
            sender.sendMessage("You received the Netro Guide. Use the table of contents to jump to sections.");
            return true;
        }
        sender.sendMessage("Usage: /netro detectordebug | routingdebug | routinglog <cart_uuid> | pairingwand | segmentwand | book");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) {
            return new ArrayList<>(SUBCOMMANDS);
        }
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, out);
            Collections.sort(out);
            return out;
        }
        return Collections.emptyList();
    }
}
