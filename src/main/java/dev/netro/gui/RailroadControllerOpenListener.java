package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.database.ControllerRepository;
import dev.netro.database.DetectorRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.detector.CopperBulbHelper;
import dev.netro.util.SignTextHelper;
import dev.netro.model.Controller;
import dev.netro.model.Detector;
import dev.netro.model.Station;
import dev.netro.model.TransferNode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Opens UI when the player:
 * - Right-clicks a [Station] sign with the Railroad Controller → station menu (transfers & terminals).
 * - Sneak+right-clicks a detector or controller sign (sign on a copper bulb) → Rules UI for that context.
 * - Right-clicks a rail with the Railroad Controller → rail direction UI.
 */
public class RailroadControllerOpenListener implements Listener {

    private final NetroPlugin plugin;

    public RailroadControllerOpenListener(NetroPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        if (isSign(block.getType())) {
            Player player = event.getPlayer();
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (RailroadControllerItem.isRailroadController(plugin, hand)) {
                org.bukkit.block.Sign sign = (org.bukkit.block.Sign) block.getState();
                String line0 = SignTextHelper.readSignLine(sign.getLine(0));
                if (line0 != null && line0.equalsIgnoreCase("[Station]")) {
                    StationRepository stationRepo = new StationRepository(plugin.getDatabase());
                    Optional<Station> stationOpt = stationRepo.findAtBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
                    if (stationOpt.isPresent()) {
                        event.setCancelled(true);
                        Station st = stationOpt.get();
                        player.openInventory(new StationNodeListHolder(plugin, st.getId(), st.getName()).getInventory());
                    }
                    return;
                }
            }
            if (player.isSneaking() && RailroadControllerItem.isRailroadController(plugin, hand)) {
                org.bukkit.block.BlockState state = block.getState();
                if (!(state instanceof org.bukkit.block.Sign sign)) {
                    return;
                }
                String header = SignTextHelper.readSignLine(sign.getLine(0));
                if (header == null || header.isEmpty()) {
                    return;
                }
                boolean isRulesSign =
                    header.equalsIgnoreCase("[Transfer]") ||
                    header.equalsIgnoreCase("[Terminal]");
                if (!isRulesSign) {
                    return;
                }

                Block attached = getAttachedBlock(block);
                if (attached != null && CopperBulbHelper.isCopperBulb(attached)) {
                    Optional<RulesContext> ctx = resolveRulesContext(attached);
                    if (ctx.isPresent()) {
                        event.setCancelled(true);
                        event.getPlayer().openInventory(new RulesMainHolder(
                            plugin,
                            ctx.get().contextType,
                            ctx.get().contextId,
                            ctx.get().contextSide,
                            ctx.get().title
                        ).getInventory());
                    }
                }
                return;
            }
            return;
        }

        if (block.getType() != Material.RAIL) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!RailroadControllerItem.isRailroadController(plugin, item)) return;
        event.setCancelled(true);
        BlockFace facing = player.getFacing();
        PendingSetRailStateRule pending = plugin.getPendingSetRailState(player.getUniqueId());
        RailroadControllerHolder holder = new RailroadControllerHolder(
            block.getWorld(),
            block.getX(), block.getY(), block.getZ(),
            facing,
            pending
        );
        player.openInventory(holder.getInventory());
    }

    /** Resolve context and title from a detector or controller at this copper bulb block. */
    private Optional<RulesContext> resolveRulesContext(Block bulb) {
        String world = bulb.getWorld().getName();
        int x = bulb.getX(), y = bulb.getY(), z = bulb.getZ();
        var db = plugin.getDatabase();
        DetectorRepository detectorRepo = new DetectorRepository(db);
        ControllerRepository controllerRepo = new ControllerRepository(db);
        Optional<Detector> detOpt = detectorRepo.findByBlock(world, x, y, z);
        if (detOpt.isPresent()) {
            return contextFromDetector(detOpt.get(), db);
        }
        Optional<Controller> ctrlOpt = controllerRepo.findByBlock(world, x, y, z);
        if (ctrlOpt.isPresent()) {
            return contextFromController(ctrlOpt.get(), db);
        }
        return Optional.empty();
    }

    private Optional<RulesContext> contextFromDetector(Detector d, dev.netro.database.Database db) {
        StationRepository stationRepo = new StationRepository(db);
        TransferNodeRepository nodeRepo = new TransferNodeRepository(db);
        if (d.getNodeId() == null) return Optional.empty();
        Optional<TransferNode> node = nodeRepo.findById(d.getNodeId());
        if (node.isEmpty()) return Optional.empty();
        String contextType = node.get().isTerminal() ? "terminal" : "transfer";
        String title = stationRepo.findById(node.get().getStationId())
            .map(st -> st.getName() + ":" + node.get().getName())
            .orElse("node:" + d.getNodeId());
        return Optional.of(new RulesContext(contextType, d.getNodeId(), null, "Rules — " + title));
    }

    private Optional<RulesContext> contextFromController(Controller c, dev.netro.database.Database db) {
        StationRepository stationRepo = new StationRepository(db);
        TransferNodeRepository nodeRepo = new TransferNodeRepository(db);
        if (c.getNodeId() == null) return Optional.empty();
        Optional<TransferNode> node = nodeRepo.findById(c.getNodeId());
        if (node.isEmpty()) return Optional.empty();
        String title = stationRepo.findById(node.get().getStationId())
            .map(st -> st.getName() + ":" + node.get().getName())
            .orElse("node:" + c.getNodeId());
        String contextType = node.get().isTerminal() ? "terminal" : "transfer";
        return Optional.of(new RulesContext(contextType, c.getNodeId(), null, "Rules — " + title));
    }

    private static final class RulesContext {
        final String contextType;
        final String contextId;
        final String contextSide;
        final String title;

        RulesContext(String contextType, String contextId, String contextSide, String title) {
            this.contextType = contextType;
            this.contextId = contextId;
            this.contextSide = contextSide;
            this.title = title;
        }
    }

    private static boolean isSign(Material m) {
        return m != null && m.name().contains("SIGN");
    }

    private static Block getAttachedBlock(Block signBlock) {
        BlockData data = signBlock.getBlockData();
        if (!(data instanceof Directional d)) return null;
        return signBlock.getRelative(d.getFacing().getOppositeFace());
    }
}
