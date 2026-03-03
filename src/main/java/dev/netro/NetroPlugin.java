package dev.netro;

import dev.netro.api.NetroAPI;
import dev.netro.chunk.ChunkLoadService;
import dev.netro.command.*;
import dev.netro.database.CartRepository;
import dev.netro.database.Database;
import dev.netro.database.DetectorRepository;
import dev.netro.database.StationRepository;

import dev.netro.listener.CartListener;
import dev.netro.listener.StationListener;
import dev.netro.detector.DetectorControllerSignListener;
import dev.netro.detector.DetectorRailHandler;
import dev.netro.routing.RoutingEngine;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NetroPlugin extends JavaPlugin {

    /** Center position for READY-hold: cart is kept at this location for ~1 second (enforced in VehicleMoveEvent). */
    public record ReadyHoldInfo(String worldName, double cx, double cy, double cz) {}

    private static NetroPlugin instance;
    private Database database;
    private RoutingEngine routingEngine;
    private NetroAPI api;
    private DetectorRailHandler detectorRailHandler;
    private boolean debug;
    private final Map<String, ReadyHoldInfo> readyHoldCarts = new ConcurrentHashMap<>();
    /** Time (ms) when a detector/READY last applied velocity to this cart (for cruise yield / priority). */
    private final Map<String, Long> lastDetectorControlTimeByCart = new ConcurrentHashMap<>();
    private dev.netro.gui.CartControllerGuiListener cartControllerGuiListener;
    private ChunkLoadService chunkLoadService;
    /** When non-null, next rail-controller click on a rail opens the direction UI to choose shape for this rule. */
    private final Map<UUID, dev.netro.gui.PendingSetRailStateRule> pendingSetRailStateByPlayer = new ConcurrentHashMap<>();
    /** When non-null, player is in relocate flow: click source block then target block (placed above). */
    private final Map<UUID, dev.netro.gui.PendingRelocate> pendingRelocateByPlayer = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        debug = getConfig().getBoolean("debug", false);

        database = new Database(this);
        database.initialize();

        routingEngine = new RoutingEngine(this);
        api = new NetroAPI(this);

        detectorRailHandler = new DetectorRailHandler(this);
        getServer().getPluginManager().registerEvents(new StationListener(this), this);
        getServer().getPluginManager().registerEvents(new CartListener(this), this);
        DetectorControllerSignListener detectorSignListener = new DetectorControllerSignListener(this);
        getServer().getPluginManager().registerEvents(detectorSignListener, this);

        StationCommand stationCommand = new StationCommand(this);
        SetDestinationCommand setDestinationCommand = new SetDestinationCommand(this);
        DnsCommand dnsCommand = new DnsCommand(this);
        CartControllerCommand cartControllerCommand = new CartControllerCommand(this);
        RailroadControllerCommand railroadControllerCommand = new RailroadControllerCommand(this);
        NetroCommand netroCommand = new NetroCommand(this, stationCommand, setDestinationCommand, dnsCommand, cartControllerCommand, railroadControllerCommand);
        getCommand("netro").setExecutor(netroCommand);
        getCommand("netro").setTabCompleter(netroCommand);

        dev.netro.gui.CartControllerListener cartControllerListener = new dev.netro.gui.CartControllerListener(this);
        getServer().getPluginManager().registerEvents(cartControllerListener, this);
        dev.netro.gui.CartControllerGuiListener guiListener = new dev.netro.gui.CartControllerGuiListener(this);
        this.cartControllerGuiListener = guiListener;
        getServer().getPluginManager().registerEvents(guiListener, this);
        guiListener.startRefreshTask();
        new dev.netro.gui.CartControllerBossBarUpdater(this).start();
        getServer().getPluginManager().registerEvents(new dev.netro.gui.RailroadControllerOpenListener(this), this);
        getServer().getPluginManager().registerEvents(new dev.netro.gui.RelocateBlockListener(this), this);
        getServer().getPluginManager().registerEvents(new dev.netro.gui.RelocateBlockListener(this), this);
        getServer().getScheduler().runTaskTimer(this, new dev.netro.gui.BlockHighlightTask(this), 5L, 5L);

        scheduleStaleCartCleanup();

        chunkLoadService = new ChunkLoadService(this,
            new StationRepository(database),
            new DetectorRepository(database),
            new CartRepository(database));
        chunkLoadService.loadAllFromDatabase();
        chunkLoadService.startCartChunkTask();

        getLogger().info("Netro enabled.");
    }

    /** Runs every 2 minutes: for each cart in the DB, check if that entity still exists in any world; if not, remove it from the DB. */
    private void scheduleStaleCartCleanup() {
        long intervalTicks = 20L * 60 * 2;
        getServer().getScheduler().runTaskTimer(this, () -> {
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                CartRepository cartRepo = new CartRepository(database);
                java.util.List<String> inDb = cartRepo.listAllCartUuids();
                if (inDb.isEmpty()) return;
                java.util.List<String> dbList = inDb;
                getServer().getScheduler().runTask(this, () -> {
                    java.util.List<String> toRemove = new java.util.ArrayList<>();
                    for (String uuidStr : dbList) {
                        java.util.UUID uuid;
                        try {
                            uuid = java.util.UUID.fromString(uuidStr);
                        } catch (IllegalArgumentException e) {
                            toRemove.add(uuidStr);
                            continue;
                        }
                        boolean found = false;
                        for (org.bukkit.World world : getServer().getWorlds()) {
                            for (org.bukkit.entity.Entity entity : world.getEntities()) {
                                if (entity.getUniqueId().equals(uuid)) {
                                    found = true;
                                    break;
                                }
                            }
                            if (found) break;
                        }
                        if (!found) toRemove.add(uuidStr);
                    }
                    if (toRemove.isEmpty()) return;
                    java.util.List<String> toRemoveFinal = toRemove;
                    getServer().getScheduler().runTaskAsynchronously(NetroPlugin.this, () -> {
                        for (String uuid : toRemoveFinal) {
                            routingEngine.onCartRemoved(uuid);
                            cartRepo.deleteCart(uuid);
                        }
                        getLogger().info("[Netro] Stale cart cleanup: removed " + toRemoveFinal.size() + " cart(s) that no longer exist as entities.");
                    });
                });
            });
        }, intervalTicks, intervalTicks);
    }

    @Override
    public void onDisable() {
        if (chunkLoadService != null) {
            chunkLoadService.unloadAll();
            chunkLoadService = null;
        }
        if (database != null) database.close();
        getLogger().info("Netro disabled.");
    }

    /** Sends an Adventure Component to a CommandSender. On Paper, CommandSender is an Audience; on Spigot we fall back to plain text. */
    public void sendMessage(CommandSender sender, Component message) {
        if (sender instanceof Audience audience) {
            audience.sendMessage(message);
        } else {
            sender.sendMessage(PlainTextComponentSerializer.plainText().serialize(message));
        }
    }

    public static NetroPlugin getInstance() { return instance; }
    public Database getDatabase() { return database; }
    public RoutingEngine getRoutingEngine() { return routingEngine; }
    public NetroAPI getAPI() { return api; }
    public DetectorRailHandler getDetectorRailHandler() { return detectorRailHandler; }
    public dev.netro.gui.CartControllerGuiListener getCartControllerGuiListener() { return cartControllerGuiListener; }

    public boolean isDebugEnabled() { return debug; }
    public void setDebug(boolean on) {
        this.debug = on;
        getConfig().set("debug", on);
        saveConfig();
    }

    /** Register a cart to be held at (cx,cy,cz) for ~1 second. Enforced in VehicleMoveEvent so it works with a player in the cart. Call from main thread. */
    public void registerReadyHold(String cartUuid, String worldName, double cx, double cy, double cz) {
        readyHoldCarts.put(cartUuid, new ReadyHoldInfo(worldName, cx, cy, cz));
        notifyCartVelocityControlledByDetector(cartUuid);
        getServer().getScheduler().runTaskLater(this, () -> readyHoldCarts.remove(cartUuid), 20);
    }

    public void removeReadyHold(String cartUuid) { readyHoldCarts.remove(cartUuid); }
    public ReadyHoldInfo getReadyHoldInfo(String cartUuid) { return readyHoldCarts.get(cartUuid); }

    /** Chunk loading for detectors/stations/carts; null before onEnable or after onDisable. */
    public ChunkLoadService getChunkLoadService() { return chunkLoadService; }

    /** Call when READY hold applied (no magnitude to sync). Yields cruise so detectors/rails keep control. */
    public void notifyCartVelocityControlledByDetector(String cartUuid) {
        lastDetectorControlTimeByCart.put(cartUuid, System.currentTimeMillis());
        if (cartControllerGuiListener != null) cartControllerGuiListener.yieldCart(cartUuid);
    }

    /** True if detector/READY last controlled this cart within the last {@code withinTicks} ticks (~50 ms per tick). */
    public boolean isRecentlyControlledByDetector(String cartUuid, int withinTicks) {
        Long t = lastDetectorControlTimeByCart.get(cartUuid);
        if (t == null) return false;
        return (System.currentTimeMillis() - t) < (withinTicks * 50L);
    }

    public dev.netro.gui.PendingSetRailStateRule getPendingSetRailState(java.util.UUID playerUuid) {
        return pendingSetRailStateByPlayer.get(playerUuid);
    }

    public void setPendingSetRailState(java.util.UUID playerUuid, dev.netro.gui.PendingSetRailStateRule pending) {
        if (pending == null) pendingSetRailStateByPlayer.remove(playerUuid);
        else pendingSetRailStateByPlayer.put(playerUuid, pending);
    }

    public dev.netro.gui.PendingRelocate getPendingRelocate(java.util.UUID playerUuid) {
        return pendingRelocateByPlayer.get(playerUuid);
    }

    public void setPendingRelocate(java.util.UUID playerUuid, dev.netro.gui.PendingRelocate pending) {
        if (pending == null) pendingRelocateByPlayer.remove(playerUuid);
        else pendingRelocateByPlayer.put(playerUuid, pending);
    }
}
