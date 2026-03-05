package dev.netro.command;

import dev.netro.NetroPlugin;
import dev.netro.database.CartRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.gui.RulesMainHolder;
import dev.netro.model.Station;
import dev.netro.util.DestinationResolver;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Optional;

public class SetDestinationCommand implements CommandExecutor {

    private static final int LOOK_AT_RANGE = 6;

    private final NetroPlugin plugin;
    private final CartRepository cartRepo;
    private final StationRepository stationRepo;
    private final TransferNodeRepository nodeRepo;

    public SetDestinationCommand(NetroPlugin plugin) {
        this.plugin = plugin;
        var db = plugin.getDatabase();
        this.cartRepo = new CartRepository(db);
        this.stationRepo = new StationRepository(db);
        this.nodeRepo = new TransferNodeRepository(db);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("Usage: /" + label + " <address|name|StationName:TerminalIndex|StationName:TerminalName>  (e.g. Snowy2, Snowy2:0, or Snowy2:Platform A; or /" + label + " <dest> <player>)");
            return true;
        }

        String nameOrAddress = args[0].strip();
        Optional<String> addressOpt = DestinationResolver.resolveToAddress(stationRepo, nodeRepo, nameOrAddress);
        if (addressOpt.isEmpty()) {
            sender.sendMessage("No station or terminal found for \"" + nameOrAddress + "\". Use address (e.g. 2.4.7.3), station name (e.g. Snowy2), Name:TerminalIndex (e.g. Snowy2:0), or Name:TerminalName (e.g. Snowy2:Platform A).");
            return true;
        }
        String address = addressOpt.get();

        Minecart cart = null;

        if (args.length >= 2 && sender.hasPermission("netro.setdestination.other")) {
            Player target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("Player not found.");
                return true;
            }
            cart = getVehicleMinecart(target);
            if (cart == null) cart = getMinecartLookingAt(target);
        } else if (sender instanceof Player) {
            Player p = (Player) sender;
            cart = getVehicleMinecart(p);
            if (cart == null) cart = getMinecartLookingAt(p);
        }

        if (cart == null) {
            sender.sendMessage("No minecart found. Be in a minecart or look at one (e.g. chest minecart) within " + LOOK_AT_RANGE + " blocks.");
            return true;
        }

        String cartUuid = cart.getUniqueId().toString();
        String originStationId = null;
        if (sender instanceof Player) {
            Player p = (Player) sender;
            originStationId = nearestStationId(p.getWorld().getName(), p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ());
        } else if (args.length >= 2) {
            Player target = plugin.getServer().getPlayerExact(args[1]);
            if (target != null)
                originStationId = nearestStationId(target.getWorld().getName(), target.getLocation().getBlockX(), target.getLocation().getBlockY(), target.getLocation().getBlockZ());
        }
        cartRepo.setDestination(cartUuid, address, originStationId);
        plugin.getDetectorRailHandler().recheckTerminalReleaseForCart(cartUuid);
        String display = RulesMainHolder.formatDestinationId(address, stationRepo, nodeRepo);
        sender.sendMessage("Destination set to " + display + " for this cart.");
        return true;
    }

    private String nearestStationId(String worldName, int px, int py, int pz) {
        Optional<Station> nearest = stationRepo.findAll().stream()
            .filter(s -> worldName.equals(s.getWorld()))
            .min((a, b) -> Long.compare(
                sq(px - a.getSignX()) + sq(py - a.getSignY()) + sq(pz - a.getSignZ()),
                sq(px - b.getSignX()) + sq(py - b.getSignY()) + sq(pz - b.getSignZ())));
        return nearest.map(Station::getId).orElse(null);
    }

    private static long sq(int n) { return (long) n * n; }

    private Minecart getVehicleMinecart(Player player) {
        if (player.getVehicle() instanceof Minecart m) return m;
        return null;
    }

    /** Returns a minecart the player is looking at within LOOK_AT_RANGE, or null. */
    private Minecart getMinecartLookingAt(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        RayTraceResult result = player.getWorld().rayTraceEntities(eye, direction, LOOK_AT_RANGE, 0.5, e -> e instanceof Minecart);
        if (result != null && result.getHitEntity() instanceof Minecart m) return m;
        return null;
    }
}
