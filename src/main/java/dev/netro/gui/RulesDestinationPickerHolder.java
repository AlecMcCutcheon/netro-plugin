package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Rule;
import dev.netro.model.Station;
import dev.netro.model.TransferNode;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Picker for "going to destination N" / "not going to destination N".
 * Shows destinations relevant to context: for transfer/terminal = all nodes (and terminals) at that station.
 */
public class RulesDestinationPickerHolder implements InventoryHolder {

    public static final int SIZE = 54;
    public static final int SLOT_BACK = 0;
    public static final int DEST_START = 9;

    /** Normal picker for "going to" / "not going to". */
    public static final String PICKER_MODE_NORMAL = null;
    /** Pick which hop triggers the blocked rule ("when this hop is blocked"). */
    public static final String PICKER_MODE_BLOCKED_HOP = "blocked_hop";
    /** Pick new destination for the blocked rule ("set destination to"). */
    public static final String PICKER_MODE_SET_DESTINATION = "set_destination";

    private final NetroPlugin plugin;
    private final String contextType;
    private final String contextId;
    private final String contextSide;
    private final String rulesTitle;
    private final String triggerType;
    private final boolean destinationPositive;
    /** When non-null, this picker is for blocked-rule flow. */
    private final String pickerMode;
    /** When pickerMode is SET_DESTINATION, the hop that when blocked triggers this rule. */
    private final String blockedHopId;
    /** When non-null, we are editing this rule; picking a destination opens Step 3 with editRule instead of creating. */
    private final Rule editRule;
    private final Inventory inventory;
    /** Slot index -> destination_id string (for routing/destination matching). */
    private final List<DestinationOption> options = new ArrayList<>();

    public RulesDestinationPickerHolder(NetroPlugin plugin, String contextType, String contextId, String contextSide,
                                       String rulesTitle, String triggerType, boolean destinationPositive) {
        this(plugin, contextType, contextId, contextSide, rulesTitle, triggerType, destinationPositive, PICKER_MODE_NORMAL, null);
    }

    public RulesDestinationPickerHolder(NetroPlugin plugin, String contextType, String contextId, String contextSide,
                                       String rulesTitle, String triggerType, boolean destinationPositive,
                                       String pickerMode, String blockedHopId) {
        this(plugin, contextType, contextId, contextSide, rulesTitle, triggerType, destinationPositive, pickerMode, blockedHopId, null);
    }

    public RulesDestinationPickerHolder(NetroPlugin plugin, String contextType, String contextId, String contextSide,
                                       String rulesTitle, String triggerType, boolean destinationPositive,
                                       String pickerMode, String blockedHopId, Rule editRule) {
        this.plugin = plugin;
        this.contextType = contextType;
        this.contextId = contextId;
        this.contextSide = contextSide;
        this.rulesTitle = rulesTitle;
        this.triggerType = triggerType;
        this.destinationPositive = destinationPositive;
        this.pickerMode = pickerMode;
        this.blockedHopId = blockedHopId;
        this.editRule = editRule;
        String title = titleForMode();
        this.inventory = Bukkit.createInventory(this, SIZE, title);
        fillDestinations();
    }

    private String titleForMode() {
        if (PICKER_MODE_BLOCKED_HOP.equals(pickerMode)) return "Rule: When which hop is blocked?";
        if (PICKER_MODE_SET_DESTINATION.equals(pickerMode)) return "Rule: Set destination to?";
        return destinationPositive ? "Rule: Going to which?" : "Rule: Not going to which?";
    }

    private void fillDestinations() {
        inventory.clear();
        inventory.setItem(SLOT_BACK, newItem(Material.ARROW, "Back", List.of("Back.")));

        StationRepository stationRepo = new StationRepository(plugin.getDatabase());
        TransferNodeRepository nodeRepo = new TransferNodeRepository(plugin.getDatabase());

        if (PICKER_MODE_BLOCKED_HOP.equals(pickerMode)) {
            int slot = DEST_START;
            for (Station station : stationRepo.findAll()) {
                String stationAddress = station.getAddress();
                for (TransferNode node : nodeRepo.findTerminals(station.getId())) {
                    if (slot >= SIZE) break;
                    if (node.getTerminalIndex() == null) continue;
                    String destId = stationAddress + "." + node.getTerminalIndex();
                    String display = station.getName() + ":" + node.getName();
                    options.add(new DestinationOption(slot, destId, display));
                    inventory.setItem(slot, newItem(Material.MINECART, display, List.of("Terminal blocked.")));
                    slot++;
                }
            }
            return;
        }

        if ("transfer".equals(contextType) || "terminal".equals(contextType)) {
            Optional<TransferNode> contextNode = nodeRepo.findById(contextId);
            if (contextNode.isEmpty()) return;
            String stationId = contextNode.get().getStationId();
            if (stationId == null) return;
            Optional<Station> stationOpt = stationRepo.findById(stationId);
            if (stationOpt.isEmpty()) return;
            Station station = stationOpt.get();
            String stationAddress = station.getAddress();
            List<TransferNode> nodes = nodeRepo.findByStation(stationId);
            int slot = DEST_START;
            for (TransferNode node : nodes) {
                if (slot >= SIZE) break;
                String destId;
                String display;
                if (node.isTerminal() && node.getTerminalIndex() != null) {
                    destId = stationAddress + "." + node.getTerminalIndex();
                    display = station.getName() + ":" + node.getName();
                } else {
                    destId = station.getName() + ":" + node.getName();
                    display = station.getName() + ":" + node.getName();
                }
                options.add(new DestinationOption(slot, destId, display));
                inventory.setItem(slot, newItem(Material.MINECART, display, List.of("Destination.")));
                slot++;
            }
            return;
        }
    }

    private static ItemStack newItem(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) meta.setLore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    public NetroPlugin getPlugin() { return plugin; }
    public String getContextType() { return contextType; }
    public String getContextId() { return contextId; }
    public String getContextSide() { return contextSide; }
    public String getRulesTitle() { return rulesTitle; }
    public String getTriggerType() { return triggerType; }
    public boolean isDestinationPositive() { return destinationPositive; }
    public String getPickerMode() { return pickerMode; }
    public String getBlockedHopId() { return blockedHopId; }
    public Rule getEditRule() { return editRule; }

    public String getDestinationIdAtSlot(int slot) {
        for (DestinationOption o : options) {
            if (o.slot == slot) return o.destinationId;
        }
        return null;
    }

    public boolean isBackSlot(int slot) { return slot == SLOT_BACK; }
    public boolean isDestinationSlot(int slot) { return getDestinationIdAtSlot(slot) != null; }

    @Override
    public Inventory getInventory() { return inventory; }

    private static final class DestinationOption {
        final int slot;
        final String destinationId;
        final String display;

        DestinationOption(int slot, String destinationId, String display) {
            this.slot = slot;
            this.destinationId = destinationId;
            this.display = display;
        }
    }
}
