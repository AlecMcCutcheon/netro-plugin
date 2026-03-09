package dev.netro.listener;

import dev.netro.NetroPlugin;
import dev.netro.detector.DetectorRailHandler;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles minecart movement: when the cart is on a detector rail, runs detector/controller flow
 * (ENTRY/READY/CLEAR, copper bulbs). Also enforces READY-hold (center cart for 1s) in VehicleMoveEvent
 * so it works when a player is in the cart.
 * Detector checks are throttled to every 2–3 ticks so we do less work on the main thread while still
 * catching detectors (we check both current and previous block when we run).
 */
public class CartListener implements Listener {

    /** Throttle detector logic to this interval (5 ticks at 20 TPS). Reduces main-thread work per moving cart. */
    private static final long DETECTOR_THROTTLE_MS = 250;
    /** When cart is in READY hold, apply velocity correction at most this often (5 ticks) to reduce main-thread work. */
    private static final long READY_HOLD_VELOCITY_THROTTLE_MS = 250;

    private final NetroPlugin plugin;
    private final DetectorRailHandler detectorHandler;
    /** cartUuid -> last time we ran detector logic (millis). Cleared on cart destroy. */
    private final Map<String, Long> lastDetectorRunByCart = new ConcurrentHashMap<>();
    /** cartUuid -> last time we applied READY-hold velocity correction (millis). Cleared when hold is removed. */
    private final Map<String, Long> lastReadyHoldVelocityByCart = new ConcurrentHashMap<>();

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
            long now = System.currentTimeMillis();
            Long lastVel = lastReadyHoldVelocityByCart.get(cartUuid);
            if (lastVel == null || (now - lastVel) >= READY_HOLD_VELOCITY_THROTTLE_MS) {
                lastReadyHoldVelocityByCart.put(cartUuid, now);
                detectorHandler.applyReadyHoldVelocityCorrection(cart, hold.cx(), hold.cy(), hold.cz());
            }
            return;
        }
        lastReadyHoldVelocityByCart.remove(cartUuid);

        Block from = event.getFrom().getBlock();
        Block to = event.getTo().getBlock();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;

        long now = System.currentTimeMillis();
        Long last = lastDetectorRunByCart.get(cartUuid);
        if (last != null && (now - last) < DETECTOR_THROTTLE_MS) return;
        lastDetectorRunByCart.put(cartUuid, now);

        org.bukkit.World world = cart.getWorld();
        Set<String> seen = new HashSet<>(4);
        Block toAt = to;
        Block toBelow = toAt.getRelative(BlockFace.DOWN);
        Block fromAt = from;
        Block fromBelow = fromAt.getRelative(BlockFace.DOWN);

        for (Block b : new Block[] { toAt, toBelow, fromAt, fromBelow }) {
            if (!isRailBlock(b)) continue;
            String key = b.getX() + "," + b.getY() + "," + b.getZ();
            if (!seen.add(key)) continue;
            detectorHandler.onCartOnDetectorRail(world, b.getX(), b.getY(), b.getZ(), cart);
        }
    }

    private static boolean isRailBlock(Block block) {
        return block != null && block.getType().toString().contains("RAIL");
    }

    @EventHandler
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart)) return;
        String cartUuid = cart.getUniqueId().toString();
        lastDetectorRunByCart.remove(cartUuid);
        lastReadyHoldVelocityByCart.remove(cartUuid);
        plugin.getRoutingEngine().onCartRemoved(cartUuid);
    }
}
