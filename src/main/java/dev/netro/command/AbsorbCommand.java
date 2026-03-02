package dev.netro.command;

import dev.netro.NetroPlugin;
import dev.netro.database.LecternRepository;
import dev.netro.database.StationRepository;
import dev.netro.detector.CopperBulbHelper;
import dev.netro.detector.DetectorControllerSignListener;
import dev.netro.model.Station;
import dev.netro.util.AddressHelper;
import dev.netro.util.SignTextHelper;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Re-register a detector, controller, or station sign into the DB (e.g. after node/station was deleted or DB was lost).
 * Look at the sign and run /absorb, or run /absorb and then right-click the sign (wizard).
 * Only [Station] signs can recreate a station; detector/controller signs never auto-create stations.
 */
public class AbsorbCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Collections.singletonList("wand");

    private static final int LOOK_AT_RANGE = 6;
    public static final String WAND_DISPLAY_NAME = "Netro Absorb Wand";

    private final NetroPlugin plugin;
    private final NamespacedKey absorbWandKey;
    private final DetectorControllerSignListener signListener;
    private final StationRepository stationRepo;
    private final LecternRepository lecternRepo;

    public AbsorbCommand(NetroPlugin plugin, DetectorControllerSignListener signListener) {
        this.plugin = plugin;
        this.signListener = signListener;
        this.absorbWandKey = new NamespacedKey(plugin, "absorb_wand");
        var db = plugin.getDatabase();
        this.stationRepo = new StationRepository(db);
        this.lecternRepo = new LecternRepository(db);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can run this command.");
            return true;
        }

        if (args.length >= 1 && "wand".equalsIgnoreCase(args[0].strip())) {
            ItemStack wand = createAbsorbWand();
            player.getInventory().addItem(wand);
            player.sendMessage("You received the Netro Absorb Wand. Right-click a station, detector, or controller sign to absorb it.");
            return true;
        }

        Block target = player.getTargetBlockExact(LOOK_AT_RANGE);
        if (target != null && target.getState() instanceof Sign sign) {
            String line0 = SignTextHelper.readSignLine(sign.getLine(0));
            if (line0.equalsIgnoreCase("[Station]")) {
                String msg = absorbStationSign(player, target);
                player.sendMessage(msg);
                return true;
            }
            Block attached = getAttachedBlock(target);
            if (attached != null && CopperBulbHelper.isCopperBulb(attached)) {
                signListener.absorbSign(player, target);
                return true;
            }
        }

        plugin.getAbsorbWizards().add(player.getUniqueId());
        player.sendMessage("Right-click a station, detector, or controller sign to absorb it.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, out);
            return out;
        }
        return Collections.emptyList();
    }

    /**
     * Recreate a station from a [Station] sign. Only runs when the sign has [Station] on line 1.
     * If a station already exists at this block, does nothing. Does not run for detector/controller signs.
     */
    public String absorbStationSign(Player player, Block signBlock) {
        if (!(signBlock.getState() instanceof Sign sign)) {
            return "Not a sign.";
        }
        String line0 = SignTextHelper.readSignLine(sign.getLine(0));
        if (!line0.equalsIgnoreCase("[Station]")) {
            return "Not a station sign. Line 1 must be [Station].";
        }
        String name = SignTextHelper.readSignLine(sign.getLine(1));
        if (name.isEmpty()) {
            return "Station name (line 2) is empty.";
        }
        String world = signBlock.getWorld().getName();
        int x = signBlock.getX(), y = signBlock.getY(), z = signBlock.getZ();

        Optional<Station> existingAtBlock = stationRepo.findAtBlock(world, x, y, z);
        if (existingAtBlock.isPresent()) {
            return "Station already exists at this sign.";
        }
        if (stationRepo.findByName(name).isPresent()) {
            return "A station with that name already exists. Use a different sign or name.";
        }
        if (lecternRepo.isBlockUsedByLectern(world, x, y, z)) {
            return "That block is already registered as a lectern.";
        }

        String addressLine = SignTextHelper.readSignLine(sign.getLine(2));
        String address;
        if (!addressLine.isEmpty() && addressLine.matches("[0-9.]+")) {
            address = addressLine;
        } else {
            int mainnet = AddressHelper.mainnetFromX(x);
            int cluster = AddressHelper.clusterFromX(x);
            int localnet = AddressHelper.localnetFromX(x);
            int nextIndex = stationRepo.countStationsInLocalnet(world, mainnet, cluster, localnet) + 1;
            address = AddressHelper.stationAddress(x, nextIndex);
        }
        Station station = new Station(
            UUID.randomUUID().toString(), name, address, world, x, y, z, System.currentTimeMillis());
        stationRepo.insert(station);
        if (plugin.getChunkLoadService() != null) plugin.getChunkLoadService().addChunksForBlock(station.getWorld(), station.getSignX(), station.getSignZ());
        if (signBlock.getState() instanceof Sign s) {
            dev.netro.util.SignColors.applyStationSign(s, name, address);
            s.update();
        }
        return "Station absorbed: " + name + " with address " + address + ".";
    }

    /** Same logic as DetectorControllerSignListener: sign's opposite face = attached block (copper bulb). */
    private static Block getAttachedBlock(Block signBlock) {
        BlockState state = signBlock.getState();
        if (!(state.getBlockData() instanceof Directional d)) return null;
        return signBlock.getRelative(d.getFacing().getOppositeFace());
    }

    /** Creates a single stick item marked as the Netro Absorb Wand (custom name + PDC). */
    public ItemStack createAbsorbWand() {
        ItemStack stack = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(WAND_DISPLAY_NAME);
            meta.getPersistentDataContainer().set(absorbWandKey, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** True if the item is the Netro Absorb Wand (has the plugin PDC key). */
    public boolean isAbsorbWand(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(absorbWandKey, PersistentDataType.BYTE);
    }

    /**
     * Called when player in wizard mode right-clicks a block. Dispatches to station absorb or detector/controller absorb.
     */
    public void processTargetSign(Player player, Block signBlock) {
        if (!(signBlock.getState() instanceof Sign sign)) {
            player.sendMessage("That was not a sign.");
            return;
        }
        String line0 = SignTextHelper.readSignLine(sign.getLine(0));
        if (line0.equalsIgnoreCase("[Station]")) {
            String msg = absorbStationSign(player, signBlock);
            player.sendMessage(msg);
            return;
        }
        Block attached = getAttachedBlock(signBlock);
        if (attached == null || !CopperBulbHelper.isCopperBulb(attached)) {
            player.sendMessage("That sign is not on a copper bulb. Detector/controller signs must be on a copper bulb.");
            return;
        }
        signListener.absorbSign(player, signBlock);
    }
}
