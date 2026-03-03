package dev.netro.command;

import dev.netro.NetroPlugin;
import dev.netro.database.StationRepository;
import dev.netro.model.Station;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class StationCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("list");

    private final StationRepository stationRepo;

    public StationCommand(NetroPlugin plugin) {
        this.stationRepo = new StationRepository(plugin.getDatabase());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "list".equals(args[0].toLowerCase())) {
            var list = stationRepo.findAll();
            if (list.isEmpty()) {
                sender.sendMessage("No stations.");
                return true;
            }
            sender.sendMessage("Stations (" + list.size() + "): " + String.join(", ", list.stream().map(Station::getName).toList()));
            return true;
        }
        sender.sendMessage("Usage: /" + label + " list  (Stations are created by placing a [Station] sign; removal by breaking the sign.)");
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
        return List.of();
    }
}
