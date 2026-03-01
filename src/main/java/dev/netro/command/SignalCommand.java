package dev.netro.command;

import dev.netro.NetroPlugin;
import dev.netro.database.LecternRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.LecternRepository.LecternRow;
import dev.netro.model.Station;
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

public class SignalCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "register", "add", "bind", "list"
    );

    private final NetroPlugin plugin;
    private final StationRepository stationRepo;
    private final LecternRepository lecternRepo;

    public SignalCommand(NetroPlugin plugin) {
        this.plugin = plugin;
        this.stationRepo = new StationRepository(plugin.getDatabase());
        this.lecternRepo = new LecternRepository(plugin.getDatabase());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /signal register | add <station> <label> | bind <station> <label> <event> <level> | list [station]");
            return true;
        }

        String sub = args[0].toLowerCase();

        if ("register".equals(sub)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("You must be a player.");
                return true;
            }
            plugin.getSignalRegisterPending().put(((Player) sender).getUniqueId(),
                new dev.netro.NetroPlugin.SignalRegisterState(
                    dev.netro.NetroPlugin.SignalRegisterState.Step.AWAITING_LECTERN, null, null, 0, 0, 0));
            sender.sendMessage("Right-click a lectern, then type the label in chat.");
            return true;
        }

        if ("add".equals(sub)) {
            if (args.length < 3) {
                sender.sendMessage("Usage: /signal add <station> <label>  (Stand in front of the lectern.)");
                return true;
            }
            if (!(sender instanceof Player)) {
                sender.sendMessage("You must be a player.");
                return true;
            }
            Optional<Station> station = stationRepo.findByName(args[1]).or(() -> stationRepo.findByAddress(args[1]));
            if (station.isEmpty()) {
                sender.sendMessage("Station not found: " + args[1]);
                return true;
            }
            String lecternLabel = args[2];
            Player p = (Player) sender;
            var target = p.getTargetBlockExact(6);
            int x = target != null ? target.getX() : p.getLocation().getBlockX();
            int y = target != null ? target.getY() : p.getLocation().getBlockY();
            int z = target != null ? target.getZ() : p.getLocation().getBlockZ();
            String world = p.getWorld().getName();
            if (lecternRepo.isBlockUsedByLectern(world, x, y, z)) {
                sender.sendMessage("That block is already registered as a lectern.");
                return true;
            }
            lecternRepo.insertLectern(UUID.randomUUID().toString(), station.get().getId(), lecternLabel, x, y, z);
            sender.sendMessage("Lectern \"" + lecternLabel + "\" added to station " + station.get().getName() + ".");
            return true;
        }

        if ("bind".equals(sub)) {
            if (args.length < 5) {
                sender.sendMessage("Usage: /signal bind <station> <lectern_label> <event_type> <target_level>");
                return true;
            }
            Optional<Station> station = stationRepo.findByName(args[1]).or(() -> stationRepo.findByAddress(args[1]));
            if (station.isEmpty()) {
                sender.sendMessage("Station not found: " + args[1]);
                return true;
            }
            String lecternLabel = args[2];
            String eventType = args[3];
            int level;
            try {
                level = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                sender.sendMessage("Target level must be a number.");
                return true;
            }
            lecternRepo.insertBinding(UUID.randomUUID().toString(), station.get().getId(), lecternLabel, eventType, level);
            sender.sendMessage("Binding added: " + lecternLabel + " on " + eventType + " -> level " + level);
            return true;
        }

        if ("list".equals(sub)) {
            if (args.length >= 2) {
                Optional<Station> station = stationRepo.findByName(args[1]).or(() -> stationRepo.findByAddress(args[1]));
                if (station.isEmpty()) {
                    sender.sendMessage("Station not found: " + args[1]);
                    return true;
                }
                listLecterns(sender, station.get().getId(), station.get().getName());
                return true;
            }
            if (sender instanceof Player) {
                Player p = (Player) sender;
                int px = p.getLocation().getBlockX(), py = p.getLocation().getBlockY(), pz = p.getLocation().getBlockZ();
                String worldName = p.getWorld().getName();
                Optional<Station> nearest = stationRepo.findAll().stream()
                    .filter(s -> worldName.equals(s.getWorld()))
                    .min((a, b) -> Long.compare(
                        sq(px - a.getSignX()) + sq(py - a.getSignY()) + sq(pz - a.getSignZ()),
                        sq(px - b.getSignX()) + sq(py - b.getSignY()) + sq(pz - b.getSignZ())));
                if (nearest.isEmpty()) {
                    sender.sendMessage("No station in this world.");
                    return true;
                }
                listLecterns(sender, nearest.get().getId(), nearest.get().getName());
                return true;
            }
            sender.sendMessage("Usage: /signal list <station>  (or run as player for nearest station)");
            return true;
        }

        sender.sendMessage("Usage: /signal register | add <station> <label> | bind <station> <label> <event> <level> | list [station]");
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

    private void listLecterns(org.bukkit.command.CommandSender sender, String stationId, String stationName) {
        List<LecternRow> lecterns = lecternRepo.findByStation(stationId);
        if (lecterns.isEmpty()) {
            sender.sendMessage("No lecterns at " + stationName + ".");
            return;
        }
        sender.sendMessage("Lecterns: " + String.join(", ", lecterns.stream().map(l -> l.label + " @ " + l.x + "," + l.y + "," + l.z).toList()));
    }

    private static long sq(int n) { return (long) n * n; }
}
