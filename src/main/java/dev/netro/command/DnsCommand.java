package dev.netro.command;

import dev.netro.NetroPlugin;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Station;
import dev.netro.model.TransferNode;
import dev.netro.util.AddressHelper;
import dev.netro.util.DimensionHelper;
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
            sender.sendMessage("Usage: /" + label + " list | list cluster | list main <n> | <address|name>");
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
            int z = p.getLocation().getBlockZ();
            int dimension = DimensionHelper.dimensionFromEnvironment(p.getWorld().getEnvironment());
            String prefix = AddressHelper.clusterPrefix2D(dimension, x, z);
            List<Station> stations = stationRepo.findByAddressPrefix(prefix);
            stations = stations.stream().filter(s -> isStationAddress(s.getAddress())).toList();
            sendDnsHeader(sender, "Cluster " + prefix);
            sendStationList(sender, stations, true);
            return true;
        }

        if (args.length >= 3 && "list".equals(args[0].toLowerCase()) && "main".equals(args[1].toLowerCase())) {
            String mainnetArg = args[2].strip();
            int dimension = sender instanceof Player p
                ? DimensionHelper.dimensionFromEnvironment(p.getWorld().getEnvironment())
                : DimensionHelper.DIMENSION_OVERWORLD;
            Integer mX = null;
            Integer mZ = null;
            if (mainnetArg.contains(":") && mainnetArg.split(":", -1).length >= 2) {
                String[] mxz = mainnetArg.split(":", -1);
                mX = AddressHelper.parseMainnetCardinal(mxz[0].strip());
                mZ = AddressHelper.parseMainnetCardinal(mxz[1].strip());
            } else {
                int[] parsed = AddressHelper.parseMainnetToken(mainnetArg);
                if (parsed != null) { mX = parsed[0]; mZ = parsed[1]; }
            }
            if (mX == null || mZ == null) {
                sender.sendMessage("Usage: /" + label + " list main <mainnetLabel> (e.g. E2N3 or E2:N3)");
                return true;
            }
            String prefix = AddressHelper.mainnetPrefix2D(dimension, mX, mZ);
            List<Station> stations = stationRepo.findByAddressPrefix(prefix);
            stations = stations.stream().filter(s -> isStationAddress(s.getAddress())).toList();
            sendDnsHeader(sender, "MainNet " + AddressHelper.mainnetLabel(mX, mZ));
            sendStationList(sender, stations, true);
            return true;
        }

        if ("list".equals(sub)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Use /" + label + " list as a player to see your localnet, or /" + label + " list main <E2N3> or /" + label + " <address|name>");
                return true;
            }
            Player p = (Player) sender;
            int x = p.getLocation().getBlockX();
            int z = p.getLocation().getBlockZ();
            int dimension = DimensionHelper.dimensionFromEnvironment(p.getWorld().getEnvironment());
            String prefix = AddressHelper.regionPrefix2D(dimension, x, z);
            List<Station> stations = stationRepo.findByAddressPrefix(prefix);
            stations = stations.stream().filter(s -> isStationAddress(s.getAddress())).toList();
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
                    .filter(s -> address.equals(s.getAddress()) || address.startsWith(s.getAddress() + ".") || address.startsWith(s.getAddress() + ":"))
                    .findFirst());
            if (stOpt.isEmpty()) {
                sender.sendMessage("Resolved to " + address + " (station not in DB).");
                return true;
            }
            Station st = stOpt.get();
            String displayLabel = st.getName();
            if ((address.startsWith(st.getAddress() + ".") || address.startsWith(st.getAddress() + ":")) && address.length() > st.getAddress().length() + 1) {
                String suffix = address.substring(st.getAddress().length() + 1);
                if (suffix.matches("[0-9]+")) {
                    displayLabel = st.getName() + " terminal #" + suffix;
                }
            }
            String setDestCmd = "/netro setdestination " + address;
            plugin.sendMessage(sender, Component.text(displayLabel + " ", NamedTextColor.GREEN)
                .append(Component.text(address).clickEvent(ClickEvent.runCommand(setDestCmd)))
                .append(Component.text("  " + st.getWorld() + " " + st.getSignX() + "," + st.getSignY() + "," + st.getSignZ()).color(NamedTextColor.GRAY))
                .append(Component.text(" [Set Destination]").color(NamedTextColor.DARK_GRAY).clickEvent(ClickEvent.runCommand(setDestCmd))));
            return true;
        }

        sender.sendMessage("Usage: /" + label + " list | list cluster | list main <n> | <address|name>");
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
            String setDestStation = "/netro setdestination " + st.getAddress();
            Component line = Component.text("  ► " + st.getName() + "  ").color(NamedTextColor.GREEN)
                .append(Component.text(st.getAddress()).color(NamedTextColor.WHITE))
                .append(Component.text("  ").append(
                    Component.text("[Any Terminal]").color(NamedTextColor.GRAY).clickEvent(ClickEvent.runCommand(setDestStation))));
            plugin.sendMessage(sender, line);
            if (withTerminals) {
                List<TransferNode> terms = nodeRepo.findTerminals(st.getId());
                for (int i = 0; i < terms.size(); i++) {
                    TransferNode tn = terms.get(i);
                    if (tn.getTerminalIndex() != null) {
                        String termAddr = dev.netro.util.AddressHelper.terminalAddress(st.getAddress(), tn.getTerminalIndex());
                        String setDestTerm = "/netro setdestination " + termAddr;
                        plugin.sendMessage(sender, Component.text("        └ " + tn.getName() + "  ").color(NamedTextColor.DARK_GRAY)
                            .append(Component.text(termAddr).clickEvent(ClickEvent.runCommand(setDestTerm)))
                            .append(Component.text("  [Set Destination]").color(NamedTextColor.GRAY).clickEvent(ClickEvent.runCommand(setDestTerm))));
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
        return List.of();
    }

    /** True if address is a station address (6-part), not a terminal (7-part). */
    private static boolean isStationAddress(String addr) {
        if (addr == null) return false;
        return AddressHelper.isNewFormatStationAddress(addr);
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
