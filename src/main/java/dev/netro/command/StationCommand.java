package dev.netro.command;

import dev.netro.NetroPlugin;
import dev.netro.database.LecternRepository;
import dev.netro.database.StationRepository;
import dev.netro.model.Station;
import dev.netro.util.AddressHelper;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
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

public class StationCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "create", "list", "info", "setaddress"
    );

    private final NetroPlugin plugin;
    private final StationRepository stationRepo;
    private final LecternRepository lecternRepo;

    public StationCommand(NetroPlugin plugin) {
        this.plugin = plugin;
        this.stationRepo = new StationRepository(plugin.getDatabase());
        this.lecternRepo = new LecternRepository(plugin.getDatabase());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        if ("create".equals(sub)) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /station create <name>  (Look at the sign you want to use for this station.)");
                return true;
            }
            if (!(sender instanceof Player)) {
                sender.sendMessage("You must be a player. Look at the sign you want to use for this station.");
                return true;
            }
            Player player = (Player) sender;
            Block target = player.getTargetBlockExact(6);
            if (target == null || !(target.getState() instanceof Sign)) {
                sender.sendMessage("Look at the sign you want to use for this station (within 6 blocks).");
                return true;
            }
            String name = trimQuotes(joinArgs(args, 1));
            if (name.isEmpty()) {
                sender.sendMessage("Station name cannot be empty.");
                return true;
            }
            int x = target.getX();
            int y = target.getY();
            int z = target.getZ();
            String worldName = target.getWorld().getName();

            if (stationRepo.findByName(name).isPresent()) {
                sender.sendMessage("A station with that name already exists.");
                return true;
            }
            if (stationRepo.findAtBlock(worldName, x, y, z).isPresent()) {
                sender.sendMessage("A station already exists at that sign.");
                return true;
            }
            if (lecternRepo.isBlockUsedByLectern(worldName, x, y, z)) {
                sender.sendMessage("That block is already registered as a lectern.");
                return true;
            }

            int mainnet = AddressHelper.mainnetFromX(x);
            int cluster = AddressHelper.clusterFromX(x);
            int localnet = AddressHelper.localnetFromX(x);
            int nextIndex = stationRepo.countStationsInLocalnet(worldName, mainnet, cluster, localnet) + 1;
            String address = AddressHelper.stationAddress(x, nextIndex);

            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            Station station = new Station(id, name, address, worldName, x, y, z, now);
            stationRepo.insert(station);

            BlockState state = target.getState();
            if (state instanceof Sign sign) {
                dev.netro.util.SignColors.applyStationSign(sign, name, address);
                sign.update();
            }
            sender.sendMessage("Station \"" + name + "\" created and linked to that sign with address " + address + ".");
            return true;
        }

        if ("list".equals(sub)) {
            var list = stationRepo.findAll();
            if (list.isEmpty()) {
                sender.sendMessage("No stations.");
                return true;
            }
            sender.sendMessage("Stations (" + list.size() + "): " + String.join(", ", list.stream().map(Station::getName).toList()));
            return true;
        }

        if ("info".equals(sub)) {
            if (args.length >= 2) {
                String nameOrAddress = joinArgs(args, 1);
                stationRepo.findByName(nameOrAddress).or(() -> stationRepo.findByAddress(nameOrAddress))
                    .ifPresentOrElse(
                        s -> sender.sendMessage("Station: " + s.getName() + " | Address: " + s.getAddress() + " | " + s.getWorld() + " " + s.getSignX() + "," + s.getSignY() + "," + s.getSignZ()),
                        () -> sender.sendMessage("Station not found: " + nameOrAddress));
                return true;
            }
            if (!(sender instanceof Player)) {
                sender.sendMessage("Usage: /station info <name|address>  (no-arg nearest requires a player)");
                return true;
            }
            Player player = (Player) sender;
            int px = player.getLocation().getBlockX(), py = player.getLocation().getBlockY(), pz = player.getLocation().getBlockZ();
            String worldName = player.getWorld().getName();
            Optional<Station> nearest = stationRepo.findAll().stream()
                .filter(s -> worldName.equals(s.getWorld()))
                .min((a, b) -> {
                    long da = sq(px - a.getSignX()) + sq(py - a.getSignY()) + sq(pz - a.getSignZ());
                    long db = sq(px - b.getSignX()) + sq(py - b.getSignY()) + sq(pz - b.getSignZ());
                    return Long.compare(da, db);
                });
            nearest.ifPresentOrElse(
                s -> sender.sendMessage("Nearest station: " + s.getName() + " | Address: " + s.getAddress() + " | " + s.getWorld() + " " + s.getSignX() + "," + s.getSignY() + "," + s.getSignZ()),
                () -> sender.sendMessage("No station in this world."));
            return true;
        }

        if ("setaddress".equals(sub)) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /station setaddress <address>  (updates nearest station; run as player)");
                return true;
            }
            if (!(sender instanceof Player)) {
                sender.sendMessage("You must be a player (nearest station is used).");
                return true;
            }
            Player player = (Player) sender;
            int px = player.getLocation().getBlockX(), py = player.getLocation().getBlockY(), pz = player.getLocation().getBlockZ();
            String worldName = player.getWorld().getName();
            Optional<Station> nearest = stationRepo.findAll().stream()
                .filter(s -> worldName.equals(s.getWorld()))
                .min((a, b) -> {
                    long da = sq(px - a.getSignX()) + sq(py - a.getSignY()) + sq(pz - a.getSignZ());
                    long db = sq(px - b.getSignX()) + sq(py - b.getSignY()) + sq(pz - b.getSignZ());
                    return Long.compare(da, db);
                });
            if (nearest.isEmpty()) {
                sender.sendMessage("No station in this world.");
                return true;
            }
            String newAddress = args[1].trim();
            stationRepo.updateAddress(nearest.get().getId(), newAddress);
            sender.sendMessage("Station \"" + nearest.get().getName() + "\" address set to " + newAddress);
            return true;
        }

        sendUsage(sender);
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

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("Netro /station: create <name> | list | info [name] | setaddress <address>");
    }

    private static long sq(int n) { return (long) n * n; }

    private static String joinArgs(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString().trim();
    }

    private static String trimQuotes(String s) {
        if (s == null || s.length() < 2) return s;
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))
            return s.substring(1, s.length() - 1);
        return s;
    }
}
