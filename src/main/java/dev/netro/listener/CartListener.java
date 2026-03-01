package dev.netro.listener;

import dev.netro.NetroPlugin;
import dev.netro.detector.DetectorRailHandler;
import org.bukkit.block.Block;
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

/**
 * Handles minecart movement: when the cart is on a detector rail, runs detector/controller flow
 * (ENTRY/READY/CLEAR, copper bulbs). Also enforces READY-hold (center cart for 1s) in VehicleMoveEvent
 * so it works when a player is in the cart.
 */
public class CartListener implements Listener {

    private final NetroPlugin plugin;
    private final DetectorRailHandler detectorHandler;

    public CartListener(NetroPlugin plugin) {
        this.plugin = plugin;
        this.detectorHandler = plugin.getDetectorRailHandler();
    }

    @EventHandler
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart)) return;
        String cartUuid = cart.getUniqueId().toString();

        NetroPlugin.ReadyHoldInfo hold = plugin.getReadyHoldInfo(cartUuid);
        if (hold != null) {
            detectorHandler.applyReadyHoldVelocityCorrection(cart, hold.cx(), hold.cy(), hold.cz());
            return;
        }

        Block from = event.getFrom().getBlock();
        Block to = event.getTo().getBlock();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;
        org.bukkit.World world = cart.getWorld();
        Block at = event.getTo().getBlock();
        Block below = at.getRelative(org.bukkit.block.BlockFace.DOWN);

        detectorHandler.onCartOnDetectorRail(world, at.getX(), at.getY(), at.getZ(), cart);
        detectorHandler.onCartOnDetectorRail(world, below.getX(), below.getY(), below.getZ(), cart);
    }

    @EventHandler
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart)) return;
        String cartUuid = cart.getUniqueId().toString();
        plugin.getRoutingEngine().onCartRemoved(cartUuid);
    }
}
