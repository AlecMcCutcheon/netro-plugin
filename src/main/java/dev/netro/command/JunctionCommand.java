package dev.netro.command;

import dev.netro.junction.JunctionSetupWizard;
import dev.netro.NetroPlugin;
import dev.netro.database.JunctionRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Junction;
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
import java.util.UUID;

public class JunctionCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "create", "setup", "done", "list", "info", "release-order", "segment"
    );

    private final NetroPlugin plugin;
    private final JunctionRepository junctionRepo;
    private final TransferNodeRepository nodeRepo;

    public JunctionCommand(NetroPlugin plugin) {
        this.plugin = plugin;
        this.junctionRepo = new JunctionRepository(plugin.getDatabase());
        this.nodeRepo = new TransferNodeRepository(plugin.getDatabase());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /junction create <name> | setup <name> | done | list | info <name> | release-order <name> fifo|filo | segment <junction> <nodeA> <nodeB>");
            return true;
        }

        String sub = args[0].toLowerCase();

        if ("create".equals(sub)) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /junction create <name>");
                return true;
            }
            if (!(sender instanceof Player)) {
                sender.sendMessage("You must be a player to create a junction (location is used).");
                return true;
            }
            String name = joinArgs(args, 1);
            if (junctionRepo.findByName(name).isPresent()) {
                sender.sendMessage("A junction with that name already exists.");
                return true;
            }
            Player p = (Player) sender;
            junctionRepo.insert(UUID.randomUUID().toString(), name, p.getWorld().getName(),
                p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ());
            sender.sendMessage("Junction \"" + name + "\" created. Run /junction setup " + name + " to add Side A/B switches and gate slots.");
            return true;
        }

        if ("setup".equals(sub)) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /junction setup <name>");
                return true;
            }
            if (!(sender instanceof Player)) {
                sender.sendMessage("You must be a player.");
                return true;
            }
            String name = joinArgs(args, 1);
            Optional<Junction> j = junctionRepo.findByName(name);
            if (j.isEmpty()) {
                sender.sendMessage("Junction not found: " + name);
                return true;
            }
            Player p = (Player) sender;
            plugin.getJunctionWizards().put(p.getUniqueId(), new JunctionSetupWizard(j.get().getId(), j.get().getName()));
            sender.sendMessage("Junction setup: click the Side A switch (rail block).");
            return true;
        }

        if ("done".equals(sub)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("You must be a player.");
                return true;
            }
            Player p = (Player) sender;
            JunctionSetupWizard wizard = plugin.getJunctionWizards().remove(p.getUniqueId());
            if (wizard == null) {
                sender.sendMessage("You are not in a junction setup. Use /junction setup <name> first.");
                return true;
            }
            String jId = wizard.getJunctionId();
            junctionRepo.updateSetupState(jId, "ready");
            sender.sendMessage("Junction \"" + wizard.getJunctionName() + "\" ready. Use /junction segment to attach to a segment; place [Detector] and [Controller] signs on copper bulbs for physical setup.");
            return true;
        }

        if ("list".equals(sub)) {
            List<Junction> list = junctionRepo.findAll();
            if (list.isEmpty()) {
                sender.sendMessage("No junctions.");
                return true;
            }
            sender.sendMessage("Junctions: " + String.join(", ", list.stream().map(Junction::getName).toList()));
            return true;
        }

        if ("info".equals(sub)) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /junction info <name>");
                return true;
            }
            String name = joinArgs(args, 1);
            Optional<Junction> j = junctionRepo.findByName(name);
            if (j.isEmpty()) {
                sender.sendMessage("Junction not found: " + name);
                return true;
            }
            Junction junction = j.get();
            sender.sendMessage("Junction: " + junction.getName() + " | " + junction.getWorld() +
                " ref " + junction.getRefX() + "," + junction.getRefY() + "," + junction.getRefZ() +
                " | Release order: " + (junction.isReleaseReversed() ? "filo" : "fifo") +
                " | nodes: " + junction.getNodeAId() + " / " + junction.getNodeBId());
            return true;
        }

        if ("release-order".equals(sub)) {
            if (args.length < 3) {
                sender.sendMessage("Usage: /junction release-order <name> fifo|filo");
                return true;
            }
            String order = args[args.length - 1].toLowerCase();
            String name = args.length > 3 ? joinArgs(args, 1, args.length - 1) : args[1];
            Optional<Junction> j = junctionRepo.findByName(name);
            if (j.isEmpty()) {
                sender.sendMessage("Junction not found: " + name);
                return true;
            }
            if (!order.equals("fifo") && !order.equals("filo") && !order.equals("lifo")) {
                sender.sendMessage("Use fifo (first-in first-out) or filo (first-in last-out, e.g. straight siding with newest at front).");
                return true;
            }
            boolean reversed = order.equals("filo") || order.equals("lifo");
            junctionRepo.setReleaseReversed(j.get().getId(), reversed);
            sender.sendMessage("Junction \"" + name + "\" release order set to " + order + ".");
            return true;
        }

        if ("segment".equals(sub)) {
            if (args.length < 4) {
                sender.sendMessage("Usage: /junction segment <junction> <nodeA> <nodeB>");
                return true;
            }
            String jName = args[1];
            String nodeAName = args[2];
            String nodeBName = args[3];
            Optional<Junction> j = junctionRepo.findByName(jName);
            var a = nodeRepo.findByName(nodeAName).or(() -> nodeRepo.findById(nodeAName));
            var b = nodeRepo.findByName(nodeBName).or(() -> nodeRepo.findById(nodeBName));
            if (j.isEmpty()) {
                sender.sendMessage("Junction not found: " + jName);
                return true;
            }
            if (a.isEmpty() || b.isEmpty()) {
                sender.sendMessage("Node not found. Use names or IDs for both nodeA and nodeB.");
                return true;
            }
            junctionRepo.updateSegment(j.get().getId(), a.get().getId(), b.get().getId());
            sender.sendMessage("Junction \"" + jName + "\" segment set to " + a.get().getName() + " — " + b.get().getName());
            return true;
        }

        sender.sendMessage("Usage: /junction create <name> | setup <name> | done | list | info <name> | segment <junction> <nodeA> <nodeB>");
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

    private static String joinArgs(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString().trim();
    }

    private static String joinArgs(String[] args, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString().trim();
    }
}
