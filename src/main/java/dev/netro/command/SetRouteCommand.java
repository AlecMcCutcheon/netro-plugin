package dev.netro.command;

import dev.netro.NetroPlugin;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Station;
import dev.netro.model.TransferNode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Optional;
import java.util.UUID;

public class SetRouteCommand implements CommandExecutor {

    private final NetroPlugin plugin;
    private final StationRepository stationRepo;
    private final TransferNodeRepository nodeRepo;

    public SetRouteCommand(NetroPlugin plugin) {
        this.plugin = plugin;
        this.stationRepo = new StationRepository(plugin.getDatabase());
        this.nodeRepo = new TransferNodeRepository(plugin.getDatabase());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("Usage: /setroute <station> <dest_prefix> <next_hop_node>  (Names or addresses.)");
            return true;
        }

        String stationArg = args[0];
        String destPrefix = args[1];
        String nextHopArg = args[2];

        Optional<Station> station = resolveStation(stationArg);
        if (station.isEmpty()) {
            sender.sendMessage("Station not found: " + stationArg);
            return true;
        }

        Optional<TransferNode> nextHop = nodeRepo.findByName(nextHopArg).or(() -> nodeRepo.findById(nextHopArg));
        if (nextHop.isEmpty()) {
            sender.sendMessage("Transfer node not found: " + nextHopArg);
            return true;
        }

        nodeRepo.upsertRoutingEntry(UUID.randomUUID().toString(),
            station.get().getId(), destPrefix, nextHop.get().getId(), 1);
        sender.sendMessage("Route set: " + station.get().getName() + " -> " + destPrefix + " via " + nextHop.get().getName());
        return true;
    }

    private Optional<Station> resolveStation(String arg) {
        return stationRepo.findByName(arg).or(() -> stationRepo.findByAddress(arg)).or(() -> stationRepo.findById(arg));
    }
}
