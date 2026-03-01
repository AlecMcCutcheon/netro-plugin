package dev.netro.command;

import dev.netro.NetroPlugin;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Station;
import dev.netro.model.TransferNode;
import dev.netro.util.AddressHelper;
import dev.netro.util.DestinationResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class DnsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "list", "lookup", "resolve"
    );

    private final NetroPlugin plugin;
    private final StationRepository stationRepo;
    private final TransferNodeRepository nodeRepo;

    public DnsCommand(NetroPlugin plugin) {
        this.plugin = plugin;
        this.stationRepo = new StationRepository(plugin.getDatabase());
        this.nodeRepo = new TransferNodeRepository(plugin.getDatabase());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /dns list | list cluster | list main <n> | <address|name>");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (args.length >= 2 && "list".equals(args[0].toLowerCase()) && "cluster".equals(args[1].toLowerCase())) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Use as a player to see your cluster.");
                return true;
            }
            Player p = (Player) sender;
            int x = p.getLocation().getBlockX();
            String prefix = AddressHelper.mainnetFromX(x) + "." + AddressHelper.clusterFromX(x);
            List<Station> stations = stationRepo.findByAddressPrefix(prefix);
            stations = stations.stream().filter(s -> isFourPartAddress(s.getAddress())).toList();
            sendDnsHeader(sender, "Cluster " + prefix);
            sendStationList(sender, stations, true);
            return true;
        }

        if (args.length >= 3 && "list".equals(args[0].toLowerCase()) && "main".equals(args[1].toLowerCase())) {
            String mainArg = args[2];
            int mainnet;
            try {
                mainnet = Integer.parseInt(mainArg);
            } catch (NumberFormatException e) {
                sender.sendMessage("Usage: /dns list main <number>");
                return true;
            }
            List<Station> stations = stationRepo.findByAddressPrefix(String.valueOf(mainnet));
            stations = stations.stream().filter(s -> isFourPartAddress(s.getAddress())).toList();
            sendDnsHeader(sender, "MainNet " + mainnet);
            sendStationList(sender, stations, true);
            return true;
        }

        if ("list".equals(sub)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Use /dns list as a player to see your localnet, or /dns list main <n> or /dns <address|name>");
                return true;
            }
            Player p = (Player) sender;
            int x = p.getLocation().getBlockX();
            String prefix = AddressHelper.prefixFromX(x);
            List<Station> stations = stationRepo.findByAddressPrefix(prefix);
            stations = stations.stream().filter(s -> isFourPartAddress(s.getAddress())).toList();
            sendDnsHeader(sender, "LocalNet " + prefix);
            sendStationList(sender, stations, true);
            return true;
        }

        String query;
        if (args.length >= 2 && ("lookup".equals(sub) || "resolve".equals(sub))) {
            query = joinArgs(args, 1);
        } else if (args.length == 1 && !"list".equals(sub)) {
            query = args[0];
        } else {
            query = null;
        }
        if (query != null) {
            Optional<String> addressOpt = DestinationResolver.resolveToAddress(stationRepo, nodeRepo, query);
            if (addressOpt.isEmpty()) {
                sender.sendMessage("No station or terminal found for \"" + query + "\". Try address, station name, or Name:TerminalIndex (e.g. Snowy2:0).");
                return true;
            }
            String address = addressOpt.get();
            Optional<Station> stOpt = stationRepo.findByAddress(address)
                .or(() -> stationRepo.findAll().stream()
                    .filter(s -> address.equals(s.getAddress()) || address.startsWith(s.getAddress() + "."))
                    .findFirst());
            if (stOpt.isEmpty()) {
                sender.sendMessage("Resolved to " + address + " (station not in DB).");
                return true;
            }
            Station st = stOpt.get();
            String displayLabel = st.getName();
            if (address.startsWith(st.getAddress() + ".") && address.substring(st.getAddress().length() + 1).matches("[0-9]+")) {
                displayLabel = st.getName() + " terminal #" + address.substring(st.getAddress().length() + 1);
            }
            plugin.sendMessage(sender, Component.text(displayLabel + " ", NamedTextColor.GREEN)
                .append(Component.text(address).clickEvent(ClickEvent.runCommand("/setdestination " + address)))
                .append(Component.text("  " + st.getWorld() + " " + st.getSignX() + "," + st.getSignY() + "," + st.getSignZ()).color(NamedTextColor.GRAY))
                .append(Component.text(" [Set Destination]").color(NamedTextColor.DARK_GRAY).clickEvent(ClickEvent.runCommand("/setdestination " + address))));
            return true;
        }

        sender.sendMessage("Usage: /dns list | list cluster | list main <n> | <address|name>");
        return true;
    }

    private void sendDnsHeader(CommandSender sender, String title) {
        plugin.sendMessage(sender, Component.text("──── Netro DNS — " + title + " ────").color(NamedTextColor.GOLD));
    }

    private void sendStationList(CommandSender sender, List<Station> stations, boolean withTerminals) {
        if (stations.isEmpty()) {
            plugin.sendMessage(sender, Component.text("No stations.").color(NamedTextColor.GRAY));
            return;
        }
        for (Station st : stations) {
            Component line = Component.text("  ► " + st.getName() + "  ").color(NamedTextColor.GREEN)
                .append(Component.text(st.getAddress()).color(NamedTextColor.WHITE))
                .append(Component.text("  ").append(
                    Component.text("[Any Terminal]").color(NamedTextColor.GRAY).clickEvent(ClickEvent.runCommand("/setdestination " + st.getAddress()))));
            plugin.sendMessage(sender, line);
            if (withTerminals) {
                List<TransferNode> terms = nodeRepo.findTerminals(st.getId());
                for (int i = 0; i < terms.size(); i++) {
                    TransferNode tn = terms.get(i);
                    if (tn.getTerminalIndex() != null) {
                        String termAddr = st.getAddress() + "." + tn.getTerminalIndex();
                        plugin.sendMessage(sender, Component.text("        └ " + tn.getName() + "  ").color(NamedTextColor.DARK_GRAY)
                            .append(Component.text(termAddr).clickEvent(ClickEvent.runCommand("/setdestination " + termAddr)))
                            .append(Component.text("  [Set Destination]").color(NamedTextColor.GRAY).clickEvent(ClickEvent.runCommand("/setdestination " + termAddr))));
                    }
                }
            }
        }
        plugin.sendMessage(sender, Component.text("────────────────────────────────────").color(NamedTextColor.GOLD));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, out);
            Collections.sort(out);
            return out;
        }
        if (args.length == 2 && "list".equalsIgnoreCase(args[0])) {
            List<String> out = new ArrayList<>();
            StringUtil.copyPartialMatches(args[1], Arrays.asList("cluster", "main"), out);
            Collections.sort(out);
            return out;
        }
        return Collections.emptyList();
    }

    private static boolean isFourPartAddress(String addr) {
        if (addr == null) return false;
        String[] parts = addr.split("\\.");
        return parts.length == 4;
    }

    private static String joinArgs(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString().trim();
    }
}
