package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.database.CartRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.KeyedBossBar;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows a boss bar to players holding the Cart Controller while in a minecart.
 * Displays: cart heading, player facing, velocity, destination (if set), next hop (if set), cruise speed and on/off.
 */
public class CartControllerBossBarUpdater implements Listener {

    private static final long TICK_INTERVAL = 20L;
    /** TTL for destination/next-node cache (ms). Reduces DB calls when same cart is shown every tick. */
    private static final long CART_DISPLAY_CACHE_TTL_MS = 1500L;
    /** Max cache size; evict expired when exceeding to avoid unbounded growth. */
    private static final int CART_DISPLAY_CACHE_MAX_SIZE = 64;

    private final NetroPlugin plugin;
    private final CartRepository cartRepo;
    private final StationRepository stationRepo;
    private final TransferNodeRepository nodeRepo;
    private final Map<UUID, KeyedBossBar> barByPlayer = new HashMap<>();
    /** Cache: cartUuid -> (destinationAddress, nextNodeId, expiryTimeMs). */
    private final Map<String, CartDisplayCacheEntry> cartDisplayCache = new ConcurrentHashMap<>();

    private static final class CartDisplayCacheEntry {
        final String destinationAddress;
        final String nextNodeId;
        final long expiryTimeMs;

        CartDisplayCacheEntry(String destinationAddress, String nextNodeId, long expiryTimeMs) {
            this.destinationAddress = destinationAddress;
            this.nextNodeId = nextNodeId;
            this.expiryTimeMs = expiryTimeMs;
        }
    }

    public CartControllerBossBarUpdater(NetroPlugin plugin) {
        this.plugin = plugin;
        var db = plugin.getDatabase();
        this.cartRepo = new CartRepository(db);
        this.stationRepo = new StationRepository(db);
        this.nodeRepo = new TransferNodeRepository(db);
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeBarForPlayer(event.getPlayer());
    }

    public void removeBarForPlayer(Player player) {
        KeyedBossBar bar = barByPlayer.remove(player.getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
    }

    private void tick() {
        var guiListener = plugin.getCartControllerGuiListener();
        if (guiListener == null) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isHoldingCartController(player)) {
                hideBar(player);
                continue;
            }
            if (!(player.getVehicle() instanceof Minecart cart)) {
                hideBar(player);
                continue;
            }

            String cartUuid = cart.getUniqueId().toString();
            CartControllerState state = guiListener.getStateFor(cartUuid);

            String title = buildTitle(cart, player, cartUuid, state);
            showBar(player, title);
        }
    }

    private boolean isHoldingCartController(Player player) {
        return CartControllerItem.isCartController(plugin, player.getInventory().getItemInMainHand())
            || CartControllerItem.isCartController(plugin, player.getInventory().getItemInOffHand());
    }

    private String buildTitle(Minecart cart, Player player, String cartUuid, CartControllerState state) {
        List<String> parts = new ArrayList<>();

        String heading = headingFromVector(cart.getVelocity());
        parts.add("Heading: " + heading);

        String facing = yawToDirection(player.getLocation().getYaw());
        parts.add("Facing: " + facing);

        double speed = cart.getVelocity().length();
        parts.add("Speed: " + String.format("%.2f", speed));

        long now = System.currentTimeMillis();
        CartDisplayCacheEntry cached = cartDisplayCache.get(cartUuid);
        if (cached == null || now >= cached.expiryTimeMs) {
            if (cartDisplayCache.size() >= CART_DISPLAY_CACHE_MAX_SIZE) {
                cartDisplayCache.entrySet().removeIf(e -> e.getValue().expiryTimeMs < now);
            }
            Optional<String> destOpt = cartRepo.getDestinationAddress(cartUuid);
            String dest = (destOpt.isPresent() && destOpt.get() != null && !destOpt.get().isEmpty()) ? destOpt.get() : null;
            String nextNodeId = null;
            Optional<Map<String, Object>> cartData = cartRepo.find(cartUuid);
            if (cartData.isPresent()) nextNodeId = (String) cartData.get().get("next_node_id");
            cached = new CartDisplayCacheEntry(dest, nextNodeId, now + CART_DISPLAY_CACHE_TTL_MS);
            cartDisplayCache.put(cartUuid, cached);
        }
        if (cached.destinationAddress != null && !cached.destinationAddress.isEmpty()) {
            String destDisplay = RulesMainHolder.formatDestinationId(cached.destinationAddress, stationRepo, nodeRepo);
            parts.add("Dest: " + destDisplay);
        }
        if (cached.nextNodeId != null && !cached.nextNodeId.isEmpty()) {
            String nextDisplay = RulesMainHolder.formatStationNode(cached.nextNodeId, stationRepo, nodeRepo);
            parts.add("Next: " + nextDisplay);
        }

        String cruiseSpeedStr = formatCruiseSpeed(state);
        String cruiseOnOff = state.isCruiseActive() ? "ON" : "OFF";
        parts.add("Cruise: " + cruiseSpeedStr + " " + cruiseOnOff);

        return String.join(" | ", parts);
    }

    private static String headingFromVector(Vector v) {
        if (v.lengthSquared() < 1e-6) return "Stopped";
        double x = v.getX(), z = v.getZ();
        if (Math.abs(z) >= Math.abs(x)) return z > 0 ? "S" : "N";
        return x > 0 ? "E" : "W";
    }

    private static String yawToDirection(float yaw) {
        double a = ((yaw + 360) % 360);
        if (a >= 315 || a < 45) return "S";
        if (a >= 45 && a < 135) return "W";
        if (a >= 135 && a < 225) return "N";
        return "E";
    }

    private static String formatCruiseSpeed(CartControllerState state) {
        return String.valueOf(state.getSpeedLevel());
    }

    private void showBar(Player player, String title) {
        KeyedBossBar bar = barByPlayer.get(player.getUniqueId());
        if (bar == null) {
            NamespacedKey key = new NamespacedKey(plugin, "cart_controller_bar_" + player.getUniqueId());
            bar = Bukkit.getBossBar(key);
            if (bar == null) {
                bar = Bukkit.createBossBar(key, title, BarColor.GREEN, BarStyle.SOLID);
            }
            barByPlayer.put(player.getUniqueId(), bar);
        }
        bar.setTitle(title);
        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }
    }

    private void hideBar(Player player) {
        KeyedBossBar bar = barByPlayer.get(player.getUniqueId());
        if (bar != null) {
            bar.removePlayer(player);
        }
    }
}
