package dev.netro.gui;

import dev.netro.model.Station;
import dev.netro.model.TransferNode;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Destination sub-menu: list of stations, or list of terminals for one station (with "Go to station" option). */
public class CartDestinationHolder implements InventoryHolder {

    public static final int SIZE = 54;
    public static final int SLOT_BACK = 0;

    public enum Page { STATIONS, TERMINALS }

    private final String cartUuid;
    private final Page page;
    private final String stationId; // for TERMINALS page
    private final Inventory inventory;
    /** Slot index -> destination address (for stations list and terminal list). */
    private final java.util.Map<Integer, String> slotToAddress = new java.util.HashMap<>();
    /** Slot index -> station (STATIONS page only; avoids display-name lookup for terminal sub-menu). */
    private final java.util.Map<Integer, Station> slotToStation = new java.util.HashMap<>();

    /** Stations list (page = STATIONS, stationId = null). */
    public CartDestinationHolder(String cartUuid, String title) {
        this.cartUuid = cartUuid;
        this.page = Page.STATIONS;
        this.stationId = null;
        this.inventory = Bukkit.createInventory(this, SIZE, title);
    }

    /** Terminals for one station (page = TERMINALS). */
    public CartDestinationHolder(String cartUuid, String stationId, String stationName) {
        this.cartUuid = cartUuid;
        this.page = Page.TERMINALS;
        this.stationId = stationId;
        this.inventory = Bukkit.createInventory(this, SIZE, stationName + " — Terminal");
    }

    public String getCartUuid() { return cartUuid; }
    public Page getPage() { return page; }
    public String getStationId() { return stationId; }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setBackButton() {
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", List.of("Return to main menu.")));
    }

    /** Set station button at slot. Call putStationAddress(slot, address) when this station has 0 or 1 terminal. */
    public void setStationButton(int slot, Station station) {
        slotToStation.put(slot, station);
        inventory.setItem(slot, newItem(Material.MINECART, station.getName(),
            List.of("Address: " + station.getAddress(), "Click to set destination.")));
    }

    /** Station at slot on STATIONS page (for opening terminal sub-menu without display-name lookup). */
    public Station getStationForSlot(int slot) {
        return slotToStation.get(slot);
    }

    /** Store address for a station slot (call after setStationButton when station has 0 or 1 terminal). */
    public void putStationAddress(int slot, String address) {
        slotToAddress.put(slot, address);
    }

    /** Set "Go to station (any terminal)" at slot. */
    public void setStationAnyButton(int slot, String stationName, String address) {
        slotToAddress.put(slot, address);
        inventory.setItem(slot, newItem(Material.RAIL, stationName + " (any)",
            List.of("Address: " + address, "Click to set destination.")));
    }

    /** Set specific terminal button. */
    public void setTerminalButton(int slot, TransferNode terminal, String stationAddress) {
        String addr = stationAddress + "." + terminal.getTerminalIndex();
        slotToAddress.put(slot, addr);
        inventory.setItem(slot, newItem(Material.CHEST, terminal.getName(),
            List.of("Terminal " + terminal.getTerminalIndex(), "Address: " + addr)));
    }

    public String getAddressForSlot(int slot) {
        return slotToAddress.get(slot);
    }

    private static ItemStack newItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
