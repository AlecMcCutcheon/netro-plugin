package dev.netro.command;

import dev.netro.NetroPlugin;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Station;
import dev.netro.model.TransferNode;
import dev.netro.terminal.TerminalSetupWizard;
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

/**
 * Create and manage terminals (station sidings with transfer switch(es) and gate slots only, no hold switches).
 */
public class TerminalCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "create", "done", "delete", "list", "release-order"
    );

    private final NetroPlugin plugin;
    private final StationRepository stationRepo;
    private final TransferNodeRepository nodeRepo;

    public TerminalCommand(NetroPlugin plugin) {
        this.plugin = plugin;
        this.stationRepo = new StationRepository(plugin.getDatabase());
        this.nodeRepo = new TransferNodeRepository(plugin.getDatabase());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /terminal create <name|station:node> | done | delete <station:node> | list [station] | release-order <station:node> fifo|filo");
            return true;
        }

        String sub = args[0].toLowerCase();

        if ("create".equals(sub)) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /terminal create <name>  or  /terminal create <station:node>");
                return true;
            }
            if (!(sender instanceof Player)) {
                sender.sendMessage("You must be a player to create a terminal.");
                return true;
            }
            Player player = (Player) sender;
            String arg = args[1].strip();

            if (arg.contains(":")) {
                int colon = arg.indexOf(':');
                if (colon <= 0 || colon == arg.length() - 1) {
                    sender.sendMessage("Use format StationName:NodeName (e.g. /terminal create MyStation:Main).");
                    return true;
                }
                String stationName = arg.substring(0, colon).strip();
                String nodeName = arg.substring(colon + 1).strip();
                if (stationName.isEmpty() || nodeName.isEmpty()) {
                    sender.sendMessage("Station name and node name cannot be empty.");
                    return true;
                }
                Optional<Station> station = stationRepo.findByNameIgnoreCase(stationName);
                if (station.isEmpty()) {
                    sender.sendMessage("Station not found: \"" + stationName + "\".");
                    return true;
                }
                if (nodeRepo.findByNameAtStation(station.get().getId(), nodeName).isPresent()) {
                    sender.sendMessage("A node with that name already exists at this station. Use a different name or /terminal delete " + arg + " first.");
                    return true;
                }
                TransferNode node = new TransferNode(java.util.UUID.randomUUID().toString(), nodeName);
                node.setStationId(station.get().getId());
                node.setTerminal(true);
                nodeRepo.insert(node);
                int terminalIndex = nodeRepo.countTerminalsAtStation(station.get().getId());
                nodeRepo.setTerminal(node.getId(), terminalIndex);
                nodeRepo.setSetupComplete(node.getId());
                sender.sendMessage("Terminal \"" + nodeName + "\" created at " + station.get().getName() + " (index " + terminalIndex + "). Place [Detector] and [Controller] signs on copper bulbs.");
                return true;
            }

            if (plugin.getTerminalWizards().containsKey(player.getUniqueId())) {
                sender.sendMessage("Finish your current setup with /terminal done or cancel by relogging.");
                return true;
            }
            if (plugin.getTransferWizards().containsKey(player.getUniqueId())) {
                sender.sendMessage("Finish your transfer setup first (/transfer done).");
                return true;
            }
            TerminalSetupWizard wizard = new TerminalSetupWizard(player.getUniqueId(), arg);
            plugin.getTerminalWizards().put(player.getUniqueId(), wizard);
            sender.sendMessage("Step 1: Click the station sign this terminal belongs to.");
            return true;
        }

        if ("done".equals(sub)) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;
            TerminalSetupWizard wizard = plugin.getTerminalWizards().get(player.getUniqueId());
            if (wizard == null) {
                sender.sendMessage("You are not in terminal setup. Use /terminal create <name> first.");
                return true;
            }
            if (wizard.getNodeId() == null) {
                sender.sendMessage("Click the station sign first (Step 1).");
                return true;
            }
            sender.sendMessage("Terminal already created. Place [Detector] and [Controller] signs on copper bulbs.");
            plugin.getTerminalWizards().remove(player.getUniqueId());
            return true;
        }

        if ("delete".equals(sub)) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /terminal delete <station:node>  (e.g. /terminal delete MyStation:Main)");
                return true;
            }
            Optional<TransferNode> node = resolveStationNode(args[1]);
            if (node.isEmpty()) {
                sender.sendMessage("Terminal not found: \"" + args[1] + "\". Use format StationName:NodeName.");
                return true;
            }
            if (!node.get().isTerminal()) {
                sender.sendMessage("That node is not a terminal. Use /transfer delete for transfer nodes.");
                return true;
            }
            String nodeId = node.get().getId();
            String name = node.get().getName();
            String stName = stationRepo.findById(node.get().getStationId()).map(Station::getName).orElse("?");
            nodeRepo.deleteNodeAndAllBlockData(nodeId);
            sender.sendMessage("Deleted terminal " + stName + ":" + name + " (detectors/controllers for this node were removed).");
            return true;
        }

        if ("list".equals(sub)) {
            Optional<Station> station;
            if (args.length >= 2) {
                station = stationRepo.findByNameIgnoreCase(args[1]).or(() -> stationRepo.findByAddress(args[1]));
            } else if (sender instanceof Player) {
                station = stationAt((Player) sender);
            } else {
                sender.sendMessage("Usage: /terminal list <station>");
                return true;
            }
            if (station.isEmpty()) {
                sender.sendMessage("Station not found.");
                return true;
            }
            List<TransferNode> terminals = nodeRepo.findTerminals(station.get().getId());
            if (terminals.isEmpty()) {
                sender.sendMessage("No terminals at " + station.get().getName() + ".");
                return true;
            }
            sender.sendMessage("Terminals at " + station.get().getName() + ": " +
                String.join(", ", terminals.stream().map(n -> n.getName() + (n.getTerminalIndex() != null ? " (#" + n.getTerminalIndex() + ")" : "")).toList()));
            return true;
        }

        if ("release-order".equals(sub)) {
            if (args.length < 3) {
                sender.sendMessage("Usage: /terminal release-order <station:node> fifo|filo");
                return true;
            }
            Optional<TransferNode> n = resolveStationNode(args[1]);
            if (n.isEmpty()) {
                sender.sendMessage("Terminal not found: \"" + args[1] + "\". Use format StationName:NodeName.");
                return true;
            }
            if (!n.get().isTerminal()) {
                sender.sendMessage("That node is not a terminal. Use /transfer release-order for transfer nodes.");
                return true;
            }
            String order = args[2].toLowerCase();
            if (!order.equals("fifo") && !order.equals("filo") && !order.equals("lifo")) {
                sender.sendMessage("Use fifo (first-in first-out) or filo (first-in last-out, e.g. straight siding with newest at front).");
                return true;
            }
            boolean reversed = order.equals("filo") || order.equals("lifo");
            nodeRepo.setReleaseReversed(n.get().getId(), reversed);
            sender.sendMessage("Terminal " + args[1] + " release order set to " + order + ".");
            return true;
        }

        sender.sendMessage("Usage: /terminal create <name|station:node> | done | delete <station:node> | list [station] | release-order <station:node> fifo|filo");
        return true;
    }

    private Optional<TransferNode> resolveStationNode(String spec) {
        int colon = spec.indexOf(':');
        if (colon <= 0 || colon == spec.length() - 1) return Optional.empty();
        String stationName = spec.substring(0, colon).strip();
        String nodeName = spec.substring(colon + 1).strip();
        if (stationName.isEmpty() || nodeName.isEmpty()) return Optional.empty();
        return stationRepo.findByNameIgnoreCase(stationName)
            .flatMap(st -> nodeRepo.findByNameAtStation(st.getId(), nodeName));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, out);
            Collections.sort(out);
            return out;
        }
        return Collections.emptyList();
    }

    private Optional<Station> stationAt(Player player) {
        int px = player.getLocation().getBlockX();
        int py = player.getLocation().getBlockY();
        int pz = player.getLocation().getBlockZ();
        String world = player.getWorld().getName();
        return stationRepo.findAll().stream()
            .filter(s -> s.getWorld().equals(world) && s.getSignX() == px && s.getSignY() == py && s.getSignZ() == pz)
            .findFirst();
    }
}
