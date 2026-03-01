package dev.netro.command;

import dev.netro.NetroPlugin;
import dev.netro.database.ControllerRepository;
import dev.netro.database.DetectorRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Station;
import dev.netro.model.TransferNode;
import dev.netro.transfer.TransferSetupWizard;
import net.kyori.adventure.text.Component;
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

public class SetGatewayCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "create", "done", "delete", "list", "pair", "terminal", "release-order", "status", "info"
    );

    private final NetroPlugin plugin;
    private final StationRepository stationRepo;
    private final TransferNodeRepository nodeRepo;
    private final DetectorRepository detectorRepo;
    private final ControllerRepository controllerRepo;

    public SetGatewayCommand(NetroPlugin plugin) {
        this.plugin = plugin;
        this.stationRepo = new StationRepository(plugin.getDatabase());
        this.nodeRepo = new TransferNodeRepository(plugin.getDatabase());
        this.detectorRepo = new DetectorRepository(plugin.getDatabase());
        this.controllerRepo = new ControllerRepository(plugin.getDatabase());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /transfer create <name|station:node> | done | delete <station:node> | list [station] | pair <station:node> <station:node> | terminal <station:node> | release-order <station:node> fifo|filo | status | info <station:node>");
            return true;
        }

        String sub = args[0].toLowerCase();

        if ("create".equals(sub)) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /transfer create <name>  or  /transfer create <station:node>");
                return true;
            }
            if (!(sender instanceof Player)) {
                sender.sendMessage("You must be a player to create a transfer node.");
                return true;
            }
            Player player = (Player) sender;
            String arg = args[1].strip();

            if (arg.contains(":")) {
                int colon = arg.indexOf(':');
                if (colon <= 0 || colon == arg.length() - 1) {
                    sender.sendMessage("Use format StationName:NodeName (e.g. /transfer create MyStation:Main).");
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
                    sender.sendMessage("A transfer node with that name already exists at this station. Use a different name or /transfer delete " + arg + " first.");
                    return true;
                }
                TransferNode node = new TransferNode(java.util.UUID.randomUUID().toString(), nodeName);
                node.setStationId(station.get().getId());
                nodeRepo.insert(node);
                nodeRepo.setSetupComplete(node.getId());
                sender.sendMessage("Transfer node \"" + nodeName + "\" created at " + station.get().getName() + ". Pair with /transfer pair " + arg + " <OtherStation:OtherNode>. Place [Detector] and [Controller] signs on copper bulbs.");
                return true;
            }

            if (plugin.getTransferWizards().containsKey(player.getUniqueId())) {
                sender.sendMessage("Finish your current setup with /transfer done or cancel by relogging.");
                return true;
            }
            TransferSetupWizard wizard = new TransferSetupWizard(player.getUniqueId(), arg);
            plugin.getTransferWizards().put(player.getUniqueId(), wizard);
            sender.sendMessage("Step 1: Click the station sign this node belongs to.");
            return true;
        }

        if ("done".equals(sub)) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;
            TransferSetupWizard wizard = plugin.getTransferWizards().get(player.getUniqueId());
            if (wizard == null) {
                sender.sendMessage("You are not in transfer setup. Use /transfer create <name> first.");
                return true;
            }
            if (wizard.getNodeId() == null) {
                sender.sendMessage("Click the station sign first (Step 1).");
                return true;
            }
            sender.sendMessage("Node already created. Place [Detector] and [Controller] signs on copper bulbs; use /transfer pair to link.");
            plugin.getTransferWizards().remove(player.getUniqueId());
            return true;
        }

        if ("delete".equals(sub)) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /transfer delete <station:node>  (e.g. /transfer delete MyStation:Main)");
                return true;
            }
            Optional<TransferNode> node = resolveStationNode(args[1]);
            if (node.isEmpty()) {
                sender.sendMessage("Transfer node not found: \"" + args[1] + "\". Use format StationName:NodeName.");
                return true;
            }
            String nodeId = node.get().getId();
            String name = node.get().getName();
            String stName = stationRepo.findById(node.get().getStationId()).map(Station::getName).orElse("?");
            nodeRepo.deleteNodeAndAllBlockData(nodeId);
            sender.sendMessage("Deleted transfer node " + stName + ":" + name + " (detectors/controllers for this node were removed).");
            return true;
        }

        if ("list".equals(sub)) {
            Optional<Station> station;
            if (args.length >= 2) {
                station = stationRepo.findByNameIgnoreCase(args[1]).or(() -> stationRepo.findByAddress(args[1]));
            } else if (sender instanceof Player) {
                station = stationAt((Player) sender);
            } else {
                sender.sendMessage("Usage: /transfer list <station>  (station name is case-insensitive)");
                return true;
            }
            if (station.isEmpty()) {
                sender.sendMessage("Station not found.");
                return true;
            }
            List<TransferNode> nodes = nodeRepo.findByStation(station.get().getId());
            if (nodes.isEmpty()) {
                sender.sendMessage("No gateways at " + station.get().getName() + ".");
                return true;
            }
            sender.sendMessage("Transfer nodes at " + station.get().getName() + ": " +
                String.join(", ", nodes.stream().map(n -> n.getName() + (n.isTerminal() ? " (terminal)" : n.isPaired() ? " (paired)" : " (unlinked)")).toList()));
            return true;
        }

        if ("pair".equals(sub) || "link".equals(sub)) {
            if (args.length < 3) {
                sender.sendMessage("Usage: /transfer pair <station:node> <station:node> — e.g. /transfer pair StationA:Main StationB:Main (station and node names are case-insensitive).");
                return true;
            }
            Optional<TransferNode> a = resolveStationNode(args[1]);
            Optional<TransferNode> b = resolveStationNode(args[2]);
            if (a.isEmpty()) {
                sender.sendMessage("First node not found: \"" + args[1] + "\". Use format StationName:NodeName; run /transfer list <station> to see nodes.");
                return true;
            }
            if (b.isEmpty()) {
                sender.sendMessage("Second node not found: \"" + args[2] + "\". Use format StationName:NodeName.");
                return true;
            }
            if (a.get().getId().equals(b.get().getId())) {
                sender.sendMessage("Cannot pair a node with itself.");
                return true;
            }
            if (a.get().getStationId().equals(b.get().getStationId())) {
                sender.sendMessage("Both nodes are at the same station. Pair nodes at different stations (e.g. StationA:Main StationB:Main).");
                return true;
            }
            if (a.get().isTerminal() || b.get().isTerminal()) {
                sender.sendMessage("Cannot pair a terminal. Terminals are sidings at one station only.");
                return true;
            }
            nodeRepo.setPaired(a.get().getId(), b.get().getId());
            nodeRepo.setPaired(b.get().getId(), a.get().getId());
            plugin.getRoutingEngine().onNodePaired(a.get().getId(), b.get().getId());
            String stA = stationRepo.findById(a.get().getStationId()).map(Station::getName).orElse("?");
            String stB = stationRepo.findById(b.get().getStationId()).map(Station::getName).orElse("?");
            sender.sendMessage("Paired " + stA + ":" + a.get().getName() + " with " + stB + ":" + b.get().getName() + ". Carts can now run between these two nodes.");
            return true;
        }

        if ("terminal".equals(sub)) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /transfer terminal <station:node> — e.g. /transfer terminal MyStation:Main (mark as siding at this station only; case-insensitive).");
                return true;
            }
            Optional<TransferNode> node = resolveStationNode(args[1]);
            if (node.isEmpty()) {
                sender.sendMessage("Transfer node not found: \"" + args[1] + "\". Use format StationName:NodeName.");
                return true;
            }
            if (!node.get().isReady()) {
                sender.sendMessage("Node must be set up first (run /transfer create and finish with /transfer done).");
                return true;
            }
            if (node.get().isPaired()) {
                sender.sendMessage("Node is already paired. Unpair it first or use a different node for a terminal.");
                return true;
            }
            if (node.get().isTerminal()) {
                sender.sendMessage("Node is already a terminal.");
                return true;
            }
            int termIdx = nodeRepo.countTerminalsAtStation(node.get().getStationId());
            nodeRepo.setTerminal(node.get().getId(), termIdx);
            String st = stationRepo.findById(node.get().getStationId()).map(Station::getName).orElse("?");
            sender.sendMessage("Transfer node " + st + ":" + node.get().getName() + " is now a terminal (siding at this station only, index " + termIdx + ").");
            return true;
        }

        if ("status".equals(sub)) {
            List<TransferNode> all = stationRepo.findAll().stream()
                .flatMap(s -> nodeRepo.findByStation(s.getId()).stream())
                .distinct()
                .toList();
            if (all.isEmpty()) {
                sender.sendMessage("No transfer nodes.");
                return true;
            }
            for (TransferNode n : all) {
                boolean ready = n.isReady();
                boolean linked = n.isPaired();
                NamedTextColor c = !ready ? NamedTextColor.RED : (linked ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
                String state = !ready ? "incomplete" : (linked ? "linked" : "unlinked");
                String spec = stationRepo.findById(n.getStationId()).map(Station::getName).orElse("?") + ":" + n.getName();
                plugin.sendMessage(sender, Component.text("  " + spec).color(c).append(Component.text(" — " + state).color(NamedTextColor.GRAY)));
            }
            return true;
        }

        if ("info".equals(sub)) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /transfer info <station:node>  (e.g. /transfer info MyStation:Main)");
                return true;
            }
            Optional<TransferNode> n = resolveStationNode(args[1]);
            if (n.isEmpty()) {
                sender.sendMessage("Transfer node not found: \"" + args[1] + "\". Use format StationName:NodeName.");
                return true;
            }
            TransferNode node = n.get();
            Optional<Station> st = stationRepo.findById(node.getStationId());
            sender.sendMessage("Node: " + node.getName() + " | Station: " + st.map(Station::getName).orElse("?") +
                " | Paired: " + (node.getPairedNodeId() != null ? nodeRepo.findById(node.getPairedNodeId()).map(TransferNode::getName).orElse(node.getPairedNodeId()) : "no") +
                " | Terminal: " + node.isTerminal() + (node.getTerminalIndex() != null ? " index " + node.getTerminalIndex() : "") +
                " | Release order: " + (node.isReleaseReversed() ? "filo" : "fifo"));
            int detectors = detectorRepo.findByNodeId(node.getId()).size();
            int controllers = controllerRepo.findByNodeId(node.getId()).size();
            sender.sendMessage("  Detectors: " + detectors + " | Controllers: " + controllers);
            return true;
        }

        if ("release-order".equals(sub)) {
            if (args.length < 3) {
                sender.sendMessage("Usage: /transfer release-order <station:node> fifo|filo");
                return true;
            }
            Optional<TransferNode> n = resolveStationNode(args[1]);
            if (n.isEmpty()) {
                sender.sendMessage("Transfer node not found: \"" + args[1] + "\". Use format StationName:NodeName.");
                return true;
            }
            String order = args[2].toLowerCase();
            if (!order.equals("fifo") && !order.equals("filo") && !order.equals("lifo")) {
                sender.sendMessage("Use fifo (first-in first-out) or filo (first-in last-out, e.g. straight siding with newest at front).");
                return true;
            }
            boolean reversed = order.equals("filo") || order.equals("lifo");
            nodeRepo.setReleaseReversed(n.get().getId(), reversed);
            sender.sendMessage("Transfer node " + args[1] + " release order set to " + order + ".");
            return true;
        }

        sender.sendMessage("Usage: /transfer create <name> | done | list | pair <station:node> <station:node> | terminal <station:node> | release-order <station:node> fifo|filo | status | info <station:node>");
        return true;
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

    /** Parse "StationName:NodeName" (case-insensitive) to a transfer node. */
    private Optional<TransferNode> resolveStationNode(String spec) {
        int colon = spec.indexOf(':');
        if (colon <= 0 || colon == spec.length() - 1) return Optional.empty();
        String stationName = spec.substring(0, colon).strip();
        String nodeName = spec.substring(colon + 1).strip();
        if (stationName.isEmpty() || nodeName.isEmpty()) return Optional.empty();
        return stationRepo.findByNameIgnoreCase(stationName)
            .flatMap(st -> nodeRepo.findByNameAtStation(st.getId(), nodeName));
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
