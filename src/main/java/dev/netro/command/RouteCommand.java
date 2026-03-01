package dev.netro.command;

import dev.netro.NetroPlugin;
import dev.netro.database.RouteRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.RouteRepository.RouteRow;
import dev.netro.model.Station;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RouteCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "list", "create", "info", "delete"
    );

    private final NetroPlugin plugin;
    private final RouteRepository routeRepo;
    private final StationRepository stationRepo;

    public RouteCommand(NetroPlugin plugin) {
        this.plugin = plugin;
        this.routeRepo = new RouteRepository(plugin.getDatabase());
        this.stationRepo = new StationRepository(plugin.getDatabase());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /route list | create <name> [color] <station1> [station2 ...] | info <name> | delete <name>");
            return true;
        }

        String sub = args[0].toLowerCase();

        if ("list".equals(sub)) {
            List<RouteRow> list = routeRepo.findAll();
            if (list.isEmpty()) {
                sender.sendMessage("No named routes.");
                return true;
            }
            sender.sendMessage("Routes: " + String.join(", ", list.stream().map(r -> r.name).toList()));
            return true;
        }

        if ("create".equals(sub)) {
            if (args.length < 3) {
                sender.sendMessage("Usage: /route create <name> [color] <station1> [station2 ...]");
                return true;
            }
            String name = args[1];
            if (routeRepo.findByName(name) != null) {
                sender.sendMessage("A route with that name already exists.");
                return true;
            }
            String color = "blue";
            int stationStart = 2;
            if (args.length > 3 && isColor(args[2])) {
                color = args[2].toLowerCase();
                stationStart = 3;
            }
            List<String> stationIds = new ArrayList<>();
            for (int i = stationStart; i < args.length; i++) {
                String stationArg = args[i];
                Optional<Station> s = stationRepo.findByName(stationArg).or(() -> stationRepo.findByAddress(stationArg));
                if (s.isEmpty()) {
                    sender.sendMessage("Station not found: " + stationArg);
                    return true;
                }
                stationIds.add(s.get().getId());
            }
            String stationIdsJson = "[\"" + String.join("\",\"", stationIds) + "\"]";
            routeRepo.insert(UUID.randomUUID().toString(), name, color, stationIdsJson);
            sender.sendMessage("Route \"" + name + "\" created with " + stationIds.size() + " station(s).");
            return true;
        }

        if ("info".equals(sub)) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /route info <name>");
                return true;
            }
            RouteRow r = routeRepo.findByName(args[1]);
            if (r == null) {
                sender.sendMessage("Route not found: " + args[1]);
                return true;
            }
            sender.sendMessage("Route: " + r.name + " | color: " + r.color + " | stations: " + r.stationIds);
            return true;
        }

        if ("delete".equals(sub)) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /route delete <name>");
                return true;
            }
            String name = args[1];
            if (routeRepo.findByName(name) == null) {
                sender.sendMessage("Route not found: " + name);
                return true;
            }
            routeRepo.deleteByName(name);
            sender.sendMessage("Route \"" + name + "\" deleted.");
            return true;
        }

        sender.sendMessage("Usage: /route list | create <name> [color] <station1> ... | info <name> | delete <name>");
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

    private static boolean isColor(String s) {
        switch (s.toLowerCase()) {
            case "red": case "green": case "blue": case "yellow": case "purple": case "white": case "gray": case "orange":
                return true;
            default:
                return false;
        }
    }
}
