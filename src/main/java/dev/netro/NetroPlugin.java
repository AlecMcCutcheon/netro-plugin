package dev.netro;

import dev.netro.api.NetroAPI;
import dev.netro.command.*;
import dev.netro.database.CartRepository;
import dev.netro.database.Database;

import java.util.Collections;
import dev.netro.listener.AbsorbWizardListener;
import dev.netro.listener.CartListener;
import dev.netro.listener.JunctionWizardListener;
import dev.netro.listener.StationListener;
import dev.netro.listener.SignalRegisterListener;
import dev.netro.listener.TerminalWizardListener;
import dev.netro.detector.DetectorControllerSignListener;
import dev.netro.detector.DetectorRailHandler;
import dev.netro.listener.TransferNodeBlockListener;
import dev.netro.listener.TransferWizardListener;
import dev.netro.routing.RoutingEngine;
import dev.netro.signal.SignalEngine;
import dev.netro.junction.JunctionSetupWizard;
import dev.netro.terminal.TerminalSetupWizard;
import dev.netro.transfer.TransferSetupWizard;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

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
    private SignalEngine signalEngine;
    private NetroAPI api;
    private final Map<UUID, TransferSetupWizard> transferWizards = new ConcurrentHashMap<>();
    private final Map<UUID, TerminalSetupWizard> terminalWizards = new ConcurrentHashMap<>();
    private final Map<UUID, SignalRegisterState> signalRegisterPending = new ConcurrentHashMap<>();
    private final Map<UUID, JunctionSetupWizard> junctionWizards = new ConcurrentHashMap<>();
    private final Set<UUID> absorbWizards = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private AbsorbCommand absorbCommand;
    private dev.netro.listener.LinkWandListener linkWandListener;
    private DetectorRailHandler detectorRailHandler;
    private boolean detectorDebug;
    private boolean routingDebug;
    private final Map<String, ReadyHoldInfo> readyHoldCarts = new ConcurrentHashMap<>();
    /** Time (ms) when a detector/READY last applied velocity to this cart (for cruise yield / priority). */
    private final Map<String, Long> lastDetectorControlTimeByCart = new ConcurrentHashMap<>();
    private dev.netro.gui.CartControllerGuiListener cartControllerGuiListener;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        detectorDebug = getConfig().getBoolean("detector-debug", false);
        routingDebug = getConfig().getBoolean("routing-debug", false);

        database = new Database(this);
        database.initialize();

        routingEngine = new RoutingEngine(this);
        signalEngine = new SignalEngine(this);
        api = new NetroAPI(this);

        detectorRailHandler = new DetectorRailHandler(this);
        getServer().getPluginManager().registerEvents(new StationListener(this), this);
        getServer().getPluginManager().registerEvents(new CartListener(this), this);
        getServer().getPluginManager().registerEvents(new TransferWizardListener(this), this);
        getServer().getPluginManager().registerEvents(new TerminalWizardListener(this), this);
        getServer().getPluginManager().registerEvents(new SignalRegisterListener(this), this);
        getServer().getPluginManager().registerEvents(new JunctionWizardListener(this), this);
        getServer().getPluginManager().registerEvents(new TransferNodeBlockListener(this), this);
        DetectorControllerSignListener detectorSignListener = new DetectorControllerSignListener(this);
        getServer().getPluginManager().registerEvents(detectorSignListener, this);
        absorbCommand = new AbsorbCommand(this, detectorSignListener);
        getServer().getPluginManager().registerEvents(new AbsorbWizardListener(this), this);
        linkWandListener = new dev.netro.listener.LinkWandListener(this);
        getServer().getPluginManager().registerEvents(linkWandListener, this);

        StationCommand stationCommand = new StationCommand(this);
        getCommand("station").setExecutor(stationCommand);
        getCommand("station").setTabCompleter(stationCommand);
        getCommand("setroute").setExecutor(new SetRouteCommand(this));
        SetGatewayCommand transferCommand = new SetGatewayCommand(this);
        getCommand("transfer").setExecutor(transferCommand);
        getCommand("transfer").setTabCompleter(transferCommand);
        TerminalCommand terminalCommand = new TerminalCommand(this);
        getCommand("terminal").setExecutor(terminalCommand);
        getCommand("terminal").setTabCompleter(terminalCommand);
        getCommand("setdestination").setExecutor(new SetDestinationCommand(this));
        DnsCommand dnsCommand = new DnsCommand(this);
        getCommand("dns").setExecutor(dnsCommand);
        getCommand("dns").setTabCompleter(dnsCommand);
        JunctionCommand junctionCommand = new JunctionCommand(this);
        getCommand("junction").setExecutor(junctionCommand);
        getCommand("junction").setTabCompleter(junctionCommand);
        SignalCommand signalCommand = new SignalCommand(this);
        getCommand("signal").setExecutor(signalCommand);
        getCommand("signal").setTabCompleter(signalCommand);
        RouteCommand routeCommand = new RouteCommand(this);
        getCommand("route").setExecutor(routeCommand);
        getCommand("route").setTabCompleter(routeCommand);
        NetroCommand netroCommand = new NetroCommand(this);
        getCommand("netro").setExecutor(netroCommand);
        getCommand("netro").setTabCompleter(netroCommand);
        getCommand("absorb").setExecutor(absorbCommand);
        getCommand("absorb").setTabCompleter(absorbCommand);
        getCommand("clearcarts").setExecutor(new ClearCartsCommand(this));

        dev.netro.gui.CartControllerListener cartControllerListener = new dev.netro.gui.CartControllerListener(this);
        getServer().getPluginManager().registerEvents(cartControllerListener, this);
        dev.netro.gui.CartControllerGuiListener guiListener = new dev.netro.gui.CartControllerGuiListener(this);
        this.cartControllerGuiListener = guiListener;
        getServer().getPluginManager().registerEvents(guiListener, this);
        guiListener.startRefreshTask();
        getCommand("cartcontroller").setExecutor(new dev.netro.command.CartControllerCommand(this));

        scheduleStaleCartCleanup();

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
    public SignalEngine getSignalEngine() { return signalEngine; }
    public NetroAPI getAPI() { return api; }
    public Map<UUID, TransferSetupWizard> getTransferWizards() { return transferWizards; }
    public Map<UUID, TerminalSetupWizard> getTerminalWizards() { return terminalWizards; }
    public Map<UUID, SignalRegisterState> getSignalRegisterPending() { return signalRegisterPending; }
    public Map<UUID, JunctionSetupWizard> getJunctionWizards() { return junctionWizards; }
    public Set<UUID> getAbsorbWizards() { return absorbWizards; }
    public AbsorbCommand getAbsorbCommand() { return absorbCommand; }
    public dev.netro.listener.LinkWandListener getLinkWandListener() { return linkWandListener; }
    public DetectorRailHandler getDetectorRailHandler() { return detectorRailHandler; }

    public boolean isDetectorDebugEnabled() { return detectorDebug; }
    public void setDetectorDebug(boolean on) {
        this.detectorDebug = on;
        getConfig().set("detector-debug", on);
        saveConfig();
    }

    public boolean isRoutingDebugEnabled() { return routingDebug; }
    public void setRoutingDebug(boolean on) {
        this.routingDebug = on;
        getConfig().set("routing-debug", on);
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

    /** Call when READY hold applied (no magnitude to sync). Yields cruise so detectors/rails keep control. */
    public void notifyCartVelocityControlledByDetector(String cartUuid) {
        lastDetectorControlTimeByCart.put(cartUuid, System.currentTimeMillis());
        if (cartControllerGuiListener != null) cartControllerGuiListener.yieldCart(cartUuid);
    }

    /** Call when MINV/MAXV applied velocity: yields cruise and updates stored cruise speed to {@code appliedSpeedMagnitude} so the GUI and future Start use it. */
    public void notifyCartVelocityControlledByDetector(String cartUuid, double appliedSpeedMagnitude) {
        lastDetectorControlTimeByCart.put(cartUuid, System.currentTimeMillis());
        if (cartControllerGuiListener != null) {
            cartControllerGuiListener.yieldCart(cartUuid);
            cartControllerGuiListener.updateStoredSpeedFromMagnitude(cartUuid, appliedSpeedMagnitude);
        }
    }

    /** True if detector/READY last controlled this cart within the last {@code withinTicks} ticks (~50 ms per tick). */
    public boolean isRecentlyControlledByDetector(String cartUuid, int withinTicks) {
        Long t = lastDetectorControlTimeByCart.get(cartUuid);
        if (t == null) return false;
        return (System.currentTimeMillis() - t) < (withinTicks * 50L);
    }

    public static final class SignalRegisterState {
        public enum Step { AWAITING_LECTERN, AWAITING_CHAT }
        public final Step step;
        public final String stationId;
        public final String world;
        public final int x, y, z;

        public SignalRegisterState(Step step, String stationId, String world, int x, int y, int z) {
            this.step = step;
            this.stationId = stationId;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
