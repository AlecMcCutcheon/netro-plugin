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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
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
    /** When non-null, player is selecting a nether portal for this transfer node; right-click portal to save. */
    private final Map<UUID, dev.netro.gui.PendingPortalLink> pendingPortalLinkByPlayer = new ConcurrentHashMap<>();
    /** After saving a portal link, reopen the Portal Link GUI for this node (keyed by player). */
    private final Map<UUID, dev.netro.gui.ReopenPortalLinkGui> reopenPortalLinkAfterSaveByPlayer = new ConcurrentHashMap<>();
    /** Player UUIDs who have disabled rail title messages (arriving, departing, leaving, occupied). Default is enabled. */
    private final Set<UUID> titleMessagesDisabled = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        debug = getConfig().getBoolean("debug", false);

        database = new Database(this);
        database.initialize();
        new dev.netro.database.SchemaMigration(this, database).run();

        routingEngine = new RoutingEngine(this);
        getServer().getScheduler().runTaskTimer(this, () -> routingEngine.processOneRouteRefresh(), 10L, 10L);
        getServer().getScheduler().runTaskTimer(this, () -> routingEngine.scheduleRefreshForAllReachablePairs(), 20L * 60 * 30, 20L * 60 * 30);
        api = new NetroAPI(this);

        detectorRailHandler = new DetectorRailHandler(this);
        getServer().getPluginManager().registerEvents(new StationListener(this), this);
        getServer().getPluginManager().registerEvents(new CartListener(this), this);
        DetectorControllerSignListener detectorSignListener = new DetectorControllerSignListener(this);
        getServer().getPluginManager().registerEvents(detectorSignListener, this);

        StationCommand stationCommand = new StationCommand(this);
        SetDestinationCommand setDestinationCommand = new SetDestinationCommand(this);
        DnsCommand dnsCommand = new DnsCommand(this);
        WhereAmICommand whereAmICommand = new WhereAmICommand(this);
        CartControllerCommand cartControllerCommand = new CartControllerCommand(this);
        RailroadControllerCommand railroadControllerCommand = new RailroadControllerCommand(this);
        NetroCommand netroCommand = new NetroCommand(this, stationCommand, setDestinationCommand, dnsCommand, whereAmICommand, cartControllerCommand, railroadControllerCommand);
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
        getServer().getPluginManager().registerEvents(new dev.netro.listener.PortalLinkBlockBreakListener(this), this);
        getServer().getScheduler().runTaskTimer(this, new dev.netro.gui.BlockHighlightTask(this), 10L, 10L);
        getServer().getScheduler().runTaskTimer(this, new dev.netro.gui.RegionBoundaryHighlightTask(this), 20L, 20L);
        new dev.netro.gui.RailroadControllerBossBarUpdater(this).start();

        scheduleStaleCartCleanup();
        scheduleReadyRailSync();
        loadTitleMessagesDisabled();

        chunkLoadService = new ChunkLoadService(this,
            database,
            new StationRepository(database),
            new DetectorRepository(database),
            new CartRepository(database),
            new dev.netro.database.TransferNodePortalRepository(database));
        chunkLoadService.loadAllFromDatabase();
        chunkLoadService.ensureCartTaskRunning();

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
                    java.util.Set<String> existing = new java.util.HashSet<>();
                    for (org.bukkit.World world : getServer().getWorlds()) {
                        for (org.bukkit.entity.Minecart cart : world.getEntitiesByClass(org.bukkit.entity.Minecart.class)) {
                            existing.add(cart.getUniqueId().toString());
                        }
                    }
                    java.util.List<String> toRemove = new java.util.ArrayList<>();
                    for (String uuidStr : dbList) {
                        try {
                            java.util.UUID.fromString(uuidStr);
                        } catch (IllegalArgumentException e) {
                            toRemove.add(uuidStr);
                            continue;
                        }
                        if (!existing.contains(uuidStr)) toRemove.add(uuidStr);
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

    /** Runs every 2 seconds: sync carts physically on READY rails into held state so routing sees those terminals as occupied. */
    private void scheduleReadyRailSync() {
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (detectorRailHandler != null) detectorRailHandler.syncCartsOnReadyRailsToHeld();
        }, 40L, 40L);
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

    public dev.netro.gui.PendingPortalLink getPendingPortalLink(java.util.UUID playerUuid) {
        return pendingPortalLinkByPlayer.get(playerUuid);
    }

    public void setPendingPortalLink(java.util.UUID playerUuid, dev.netro.gui.PendingPortalLink pending) {
        if (pending == null) pendingPortalLinkByPlayer.remove(playerUuid);
        else pendingPortalLinkByPlayer.put(playerUuid, pending);
    }

    public dev.netro.gui.ReopenPortalLinkGui getReopenPortalLinkAfterSave(java.util.UUID playerUuid) {
        return reopenPortalLinkAfterSaveByPlayer.get(playerUuid);
    }

    public void setReopenPortalLinkAfterSave(java.util.UUID playerUuid, dev.netro.gui.ReopenPortalLinkGui data) {
        if (data == null) reopenPortalLinkAfterSaveByPlayer.remove(playerUuid);
        else reopenPortalLinkAfterSaveByPlayer.put(playerUuid, data);
    }

    private static final String TITLES_DISABLED_FILE = "titles-disabled.txt";

    private void loadTitleMessagesDisabled() {
        java.io.File file = new java.io.File(getDataFolder(), TITLES_DISABLED_FILE);
        if (!file.exists()) return;
        try (BufferedReader r = Files.newBufferedReader(file.toPath())) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    titleMessagesDisabled.add(UUID.fromString(line));
                } catch (IllegalArgumentException ignored) { }
            }
        } catch (Exception e) {
            getLogger().warning("Could not load title-messages preferences: " + e.getMessage());
        }
    }

    private void saveTitleMessagesDisabled() {
        java.io.File file = new java.io.File(getDataFolder(), TITLES_DISABLED_FILE);
        getDataFolder().mkdirs();
        try (BufferedWriter w = Files.newBufferedWriter(file.toPath())) {
            for (UUID uuid : titleMessagesDisabled) {
                w.write(uuid.toString());
                w.newLine();
            }
        } catch (Exception e) {
            getLogger().warning("Could not save title-messages preferences: " + e.getMessage());
        }
    }

    /** True if this player should see rail titles (arriving, departing, leaving, occupied). Default true. */
    public boolean isTitleMessagesEnabled(org.bukkit.entity.Player player) {
        return player != null && !titleMessagesDisabled.contains(player.getUniqueId());
    }

    /** Toggle title messages for the player. Returns true if titles are now enabled, false if disabled. */
    public boolean toggleTitleMessages(org.bukkit.entity.Player player) {
        if (player == null) return true;
        UUID uuid = player.getUniqueId();
        if (titleMessagesDisabled.contains(uuid)) {
            titleMessagesDisabled.remove(uuid);
            saveTitleMessagesDisabled();
            return true;
        } else {
            titleMessagesDisabled.add(uuid);
            saveTitleMessagesDisabled();
            return false;
        }
    }
}
