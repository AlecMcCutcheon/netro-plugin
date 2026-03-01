package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.database.CartRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Station;
import dev.netro.model.TransferNode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Handles clicks in cart controller GUIs and runs the 20-tick lore refresh for open main menus. */
public class CartControllerGuiListener implements Listener {

    private static final long REFRESH_TICKS = 20L;

    private final NetroPlugin plugin;
    private final CartRepository cartRepo;
    private final StationRepository stationRepo;
    private final TransferNodeRepository nodeRepo;
    private final Map<String, CartControllerState> stateByCart = new ConcurrentHashMap<>();
    private BukkitTask refreshTask;
    private BukkitTask cruiseTask;

    public CartControllerGuiListener(NetroPlugin plugin) {
        this.plugin = plugin;
        var db = plugin.getDatabase();
        this.cartRepo = new CartRepository(db);
        this.stationRepo = new StationRepository(db);
        this.nodeRepo = new TransferNodeRepository(db);
    }

    public void startRefreshTask() {
        if (refreshTask != null) return;
        refreshTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshOpenMenus, REFRESH_TICKS, REFRESH_TICKS);
        cruiseTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::runCruiseReapply, 1L, 1L);
    }

    public void stopRefreshTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        if (cruiseTask != null) {
            cruiseTask.cancel();
            cruiseTask = null;
        }
    }

    private CartControllerState stateFor(String cartUuid) {
        return stateByCart.computeIfAbsent(cartUuid, k -> new CartControllerState());
    }

    private void refreshOpenMenus() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof CartMenuHolder holder) {
                Minecart cart = findMinecartByUuid(holder.getCartUuid());
                if (cart != null && cart.isValid()) {
                    String currentDest = cartRepo.getDestinationAddress(holder.getCartUuid()).orElse(null);
                    holder.updateLore(cart, stateFor(holder.getCartUuid()), currentDest);
                }
            }
        }
    }

    /** Re-apply set speed for carts in cruise mode when not under READY hold. No time-based skip: MINV/MAXV update stored speed and yield; only READY skip applies. */
    private void runCruiseReapply() {
        for (Map.Entry<String, CartControllerState> e : stateByCart.entrySet()) {
            String cartUuid = e.getKey();
            CartControllerState state = e.getValue();
            if (!state.isCruiseActive()) continue;
            if (plugin.getReadyHoldInfo(cartUuid) != null) continue;
            Minecart cart = findMinecartByUuid(cartUuid);
            if (cart == null || !cart.isValid()) continue;
            applyCruiseSpeed(cart, state);
        }
    }

    /** Update stored cruise speed level to match a velocity magnitude (e.g. from MINV/MAXV). User sees updated speed in the GUI. */
    public void updateStoredSpeedFromMagnitude(String cartUuid, double magnitude) {
        CartControllerState s = stateByCart.computeIfAbsent(cartUuid, k -> new CartControllerState());
        s.setSpeedLevel(CartControllerState.speedLevelFromMagnitude(magnitude));
    }

    private void applyCruiseSpeed(Minecart cart, CartControllerState state) {
        Vector v = cart.getVelocity();
        if (v.lengthSquared() < 1e-12) {
            v = state.getCachedVelocity();
            if (v.lengthSquared() < 1e-12) v = new Vector(1, 0, 0);
            else v = v.clone().normalize();
        } else {
            v = v.normalize().clone();
        }
        double speed = state.getTargetSpeedMagnitude();
        cart.setVelocity(v.multiply(speed));
    }

    /** Called when a detector/READY applied velocity so cruise yields (no zero; manual/detectors keep control). */
    public void yieldCart(String cartUuid) {
        CartControllerState s = stateByCart.get(cartUuid);
        if (s != null) s.setCruiseActive(false);
    }

    private static Minecart findMinecartByUuid(String uuidStr) {
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (Minecart cart : w.getEntitiesByClass(Minecart.class)) {
                if (cart.getUniqueId().equals(uuid)) return cart;
            }
        }
        return null;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof CartMenuHolder holder) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handleMainMenuClick((Player) event.getWhoClicked(), holder, event.getSlot());
            return;
        }
        if (top.getHolder() instanceof CartDestinationHolder destHolder) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handleDestinationClick((Player) event.getWhoClicked(), destHolder, event.getSlot());
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CartMenuHolder ||
            event.getView().getTopInventory().getHolder() instanceof CartDestinationHolder) {
            event.setCancelled(true);
        }
    }

    private void handleMainMenuClick(Player player, CartMenuHolder holder, int slot) {
        String cartUuid = holder.getCartUuid();
        Minecart cart = findMinecartByUuid(cartUuid);
        if (cart == null || !cart.isValid()) {
            plugin.sendMessage(player, Component.text("Cart no longer valid.", NamedTextColor.RED));
            player.closeInventory();
            return;
        }
        CartControllerState state = stateFor(cartUuid);

        switch (slot) {
            case CartMenuHolder.SLOT_INCREASE -> {
                state.setSpeedLevel(state.getSpeedLevel() + 1);
                if (state.isCruiseActive()) applySpeedMultiplier(cart, state);
            }
            case CartMenuHolder.SLOT_LOWER -> {
                state.setSpeedLevel(state.getSpeedLevel() - 1);
                if (state.isCruiseActive()) applySpeedMultiplier(cart, state);
            }
            case CartMenuHolder.SLOT_STOP -> {
                state.setCachedVelocity(cart.getVelocity());
                state.setCruiseActive(false);
                cart.setVelocity(new Vector(0, 0, 0));
            }
            case CartMenuHolder.SLOT_START -> {
                state.setCruiseActive(true);
                Vector v = state.getCachedVelocity();
                if (v.lengthSquared() < 1e-12) {
                    v = cart.getVelocity();
                    if (v.lengthSquared() >= 1e-12) v = v.normalize();
                    else v = new Vector(1, 0, 0);
                } else {
                    v = v.clone().normalize();
                }
                cart.setVelocity(v.multiply(state.getTargetSpeedMagnitude()));
            }
            case CartMenuHolder.SLOT_DIR -> {
                Vector dir = cart.getVelocity();
                if (dir.lengthSquared() < 1e-12) dir = state.getCachedVelocity();
                if (dir.lengthSquared() < 1e-12) dir = new Vector(1, 0, 0);
                else dir = dir.clone().normalize();
                Vector reversed = dir.multiply(-1);
                state.setCachedVelocity(reversed);
                if (state.isCruiseActive())
                    cart.setVelocity(reversed.clone().multiply(state.getTargetSpeedMagnitude()));
            }
            case CartMenuHolder.SLOT_DEST -> openStationsMenu(player, cartUuid);
            default -> {}
        }
    }

    private void applySpeedMultiplier(Minecart cart, CartControllerState state) {
        Vector v = cart.getVelocity();
        if (v.lengthSquared() < 1e-12) return;
        double speed = state.getTargetSpeedMagnitude();
        cart.setVelocity(v.normalize().multiply(speed));
    }

    private void openStationsMenu(Player player, String cartUuid) {
        List<Station> stations = stationRepo.findAll();
        CartDestinationHolder holder = new CartDestinationHolder(cartUuid, "Set destination");
        holder.setBackButton();
        int slot = 1;
        for (Station s : stations) {
            if (slot >= CartDestinationHolder.SIZE) break;
            holder.setStationButton(slot, s);
            if (nodeRepo.findTerminals(s.getId()).size() <= 1) {
                holder.putStationAddress(slot, s.getAddress());
            }
            slot++;
        }
        player.openInventory(holder.getInventory());
    }

    private void handleDestinationClick(Player player, CartDestinationHolder destHolder, int slot) {
        if (slot == CartDestinationHolder.SLOT_BACK) {
            if (destHolder.getPage() == CartDestinationHolder.Page.TERMINALS) {
                openStationsMenu(player, destHolder.getCartUuid());
            } else {
                Minecart cart = findMinecartByUuid(destHolder.getCartUuid());
                if (cart != null) {
                    player.closeInventory();
                    player.openInventory(new CartMenuHolder(destHolder.getCartUuid()).getInventory());
                } else {
                    player.closeInventory();
                }
            }
            return;
        }

        String address = destHolder.getAddressForSlot(slot);
        if (address != null) {
            setDestinationAndReturnToMain(player, destHolder.getCartUuid(), address);
            return;
        }

        if (destHolder.getPage() == CartDestinationHolder.Page.STATIONS) {
            Station station = destHolder.getStationForSlot(slot);
            if (station == null) return;
            List<TransferNode> terminals = nodeRepo.findTerminals(station.getId());
            if (terminals.size() <= 1) {
                setDestinationAndReturnToMain(player, destHolder.getCartUuid(), station.getAddress());
                return;
            }
            CartDestinationHolder termHolder = new CartDestinationHolder(destHolder.getCartUuid(), station.getId(), station.getName());
            termHolder.setBackButton();
            termHolder.setStationAnyButton(1, station.getName(), station.getAddress());
            String addr = station.getAddress();
            int s = 2;
            for (TransferNode t : terminals) {
                if (s >= CartDestinationHolder.SIZE) break;
                termHolder.setTerminalButton(s++, t, addr);
            }
            player.openInventory(termHolder.getInventory());
        }
    }

    /** Display name for station lookup: strip legacy color codes so "§fStationName" matches "StationName". */
    private String getItemDisplayNameForStationLookup(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String raw = item.getItemMeta().getDisplayName();
        if (raw == null) return null;
        return org.bukkit.ChatColor.stripColor(raw).trim();
    }

    private String getItemDisplayName(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getDisplayName();
    }

    /** Set destination and return to main cart menu (do not close inventory). */
    private void setDestinationAndReturnToMain(Player player, String cartUuid, String address) {
        String resolved = resolveToTerminalIfStationHasTerminals(address);
        cartRepo.setDestination(cartUuid, resolved, null);
        plugin.getDetectorRailHandler().recheckTerminalReleaseForCart(cartUuid);
        plugin.sendMessage(player, Component.text("Destination set to " + resolved, NamedTextColor.GREEN));
        player.openInventory(new CartMenuHolder(cartUuid).getInventory());
    }

    /** If address is exactly a station address and that station has terminals, return first terminal's address; else return address. */
    private String resolveToTerminalIfStationHasTerminals(String address) {
        if (address == null) return address;
        Optional<Station> st = stationRepo.findByAddress(address);
        if (st.isEmpty() || !st.get().getAddress().equals(address)) return address;
        List<TransferNode> terms = nodeRepo.findTerminals(st.get().getId());
        if (terms.isEmpty()) return address;
        TransferNode first = terms.get(0);
        if (first.getTerminalIndex() == null) return address;
        return st.get().getAddress() + "." + first.getTerminalIndex();
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // Optional: clear state for cart when last menu closes to avoid unbounded growth. For now keep state.
    }
}
