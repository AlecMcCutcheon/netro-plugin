package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.database.RuleRepository;
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
 * 54-slot UI for managing rules in a context (transfer node or terminal).
 * Slots 0–44: rule items. Slot 45: default blocked policy. Slot 46: Pair (transfer only). Slot 49: Create rule. Slot 52: Relocate. Slot 53: Close.
 */
public class RulesMainHolder implements InventoryHolder {

    public static final int SIZE = 54;
    /** First slot used for rule list (up to 45 rules). */
    public static final int RULES_START = 0;
    public static final int RULES_END = 44;
    /** Special slot showing default blocked policy; not a real rule, cannot be deleted. */
    public static final int SLOT_DEFAULT_POLICY = 45;
    /** Pair transfer node (transfer context only). */
    public static final int SLOT_ACTION = 46;
    public static final int SLOT_CREATE = 49;
    /** Relocate a detector or controller for this node (next to Close for symmetry). */
    public static final int SLOT_RELOCATE = 52;
    public static final int SLOT_CLOSE = 53;

    private final NetroPlugin plugin;
    private final String contextType;
    private final String contextId;
    private final String contextSide;
    private final String title;
    private final Inventory inventory;
    /** Ordered by rule_index. Slot index = rule_index (so slot 0 = rule index 0). */
    private final List<Rule> rules = new ArrayList<>();

    public RulesMainHolder(NetroPlugin plugin, String contextType, String contextId, String contextSide, String title) {
        this.plugin = plugin;
        this.contextType = contextType;
        this.contextId = contextId;
        this.contextSide = contextSide;
        this.title = title == null || title.isEmpty() ? "Rules" : title;
        this.inventory = Bukkit.createInventory(this, SIZE, this.title);
        loadRules();
        fillLayout();
    }

    private void loadRules() {
        rules.clear();
        RuleRepository repo = new RuleRepository(plugin.getDatabase());
        rules.addAll(repo.findByContext(contextType, contextId, contextSide));
    }

    private void fillLayout() {
        inventory.clear();
        StationRepository stationRepo = new StationRepository(plugin.getDatabase());
        TransferNodeRepository nodeRepo = new TransferNodeRepository(plugin.getDatabase());
        for (int i = RULES_START; i <= RULES_END; i++) {
            if (i < rules.size()) {
                Rule r = rules.get(i);
                inventory.setItem(i, ruleItem(r, stationRepo, nodeRepo));
            }
        }
        inventory.setItem(SLOT_DEFAULT_POLICY, defaultPolicyItem());
        if ("transfer".equals(contextType) && contextId != null) {
            inventory.setItem(SLOT_ACTION, pairButtonItem());
        }
        inventory.setItem(SLOT_CREATE, newItem(Material.WRITABLE_BOOK, "Create rule", List.of("Add rule.")));
        if (contextId != null) {
            inventory.setItem(SLOT_RELOCATE, newItem(Material.ENDER_PEARL, "Relocate", List.of("Move a detector or controller to a new spot.")));
        }
        inventory.setItem(SLOT_CLOSE, newItem(Material.ARROW, "Close", List.of("Close.")));
    }

    private ItemStack pairButtonItem() {
        TransferNodeRepository nodeRepo = new TransferNodeRepository(plugin.getDatabase());
        StationRepository stationRepo = new StationRepository(plugin.getDatabase());
        List<String> lore = new ArrayList<>();
        var nodeOpt = nodeRepo.findById(contextId);
        if (nodeOpt.isEmpty()) {
            lore.add("Not paired");
        } else {
            String pairedId = nodeOpt.get().getPairedNodeId();
            if (pairedId == null || pairedId.isEmpty()) {
                lore.add("Not paired");
            } else {
                String label = formatStationNode(pairedId, stationRepo, nodeRepo);
                lore.add("Paired to " + label);
            }
        }
        return newItem(Material.ENDER_EYE, "Pair transfer node…", lore);
    }

    public static String formatStationNode(String nodeId, StationRepository stationRepo, TransferNodeRepository nodeRepo) {
        var nodeOpt = nodeRepo.findById(nodeId);
        if (nodeOpt.isEmpty()) return nodeId;
        TransferNode n = nodeOpt.get();
        return stationRepo.findById(n.getStationId()).map(st -> st.getName() + ":" + n.getName()).orElse(n.getName());
    }

    /** Resolve destination id to a display name (StationName:NodeName). Terminals are stored as address.terminalIndex; resolve to names. */
    public static String formatDestinationId(String destId, StationRepository stationRepo, TransferNodeRepository nodeRepo) {
        if (destId == null || destId.isEmpty()) return "?";
        int lastDot = destId.lastIndexOf('.');
        if (lastDot > 0 && lastDot < destId.length() - 1) {
            String address = destId.substring(0, lastDot);
            String indexStr = destId.substring(lastDot + 1);
            try {
                int terminalIndex = Integer.parseInt(indexStr);
                Optional<Station> stOpt = stationRepo.findByAddress(address);
                if (stOpt.isPresent()) {
                    Station st = stOpt.get();
                    for (TransferNode node : nodeRepo.findTerminals(st.getId())) {
                        if (node.getTerminalIndex() != null && node.getTerminalIndex() == terminalIndex) {
                            return st.getName() + ":" + node.getName();
                        }
                    }
                }
            } catch (NumberFormatException ignored) { }
        }
        return destId;
    }

    private static ItemStack defaultPolicyItem() {
        List<String> lore = new ArrayList<>();
        lore.add("When any local destination is blocked and no rule matches:");
        lore.add("1. Send to available terminal at this station");
        lore.add("2. Else available terminal at another station");
        lore.add("3. Else another unoccupied transfer node");
        lore.add("");
        lore.add("(Default policy — cannot be deleted)");
        return newItem(Material.BOOK, "Default when blocked", lore);
    }

    private static ItemStack ruleItem(Rule r, StationRepository stationRepo, TransferNodeRepository nodeRepo) {
        List<String> lore = new ArrayList<>();
        lore.add("Trigger: " + r.getTriggerType());
        if (Rule.TRIGGER_BLOCKED.equals(r.getTriggerType())) {
            String destDisplay = formatDestinationId(r.getDestinationId(), stationRepo, nodeRepo);
            lore.add("When hop to " + destDisplay + " is blocked");
            String actionDestDisplay = formatDestinationId(r.getActionData(), stationRepo, nodeRepo);
            lore.add("Action: set destination to " + actionDestDisplay);
        } else {
            String dest = r.getDestinationId() == null || r.getDestinationId().isEmpty() ? "any" : formatDestinationId(r.getDestinationId(), stationRepo, nodeRepo);
            String cond = r.isDestinationPositive() ? "to " + dest : "not to " + dest;
            lore.add("When cart " + cond);
            if (Rule.ACTION_SET_CRUISE_SPEED.equals(r.getActionType())) {
                lore.add("Action: set cruise speed to " + (r.getActionData() != null ? r.getActionData() : "?"));
            } else {
                lore.add("Action: " + r.getActionType());
                if (r.getActionData() != null && !r.getActionData().isEmpty()) lore.add(r.getActionData());
            }
        }
        lore.add("Index: " + r.getRuleIndex());
        return newItem(Material.PAPER, "Rule " + r.getRuleIndex(), lore);
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
    public String getTitle() { return title; }
    public List<Rule> getRules() { return rules; }

    /** Rule at slot if slot is in [RULES_START, RULES_END] and that index has a rule. */
    public Rule getRuleAtSlot(int slot) {
        if (slot < RULES_START || slot > RULES_END) return null;
        if (slot >= rules.size()) return null;
        return rules.get(slot);
    }

    public boolean isRuleSlot(int slot) {
        return slot >= RULES_START && slot <= RULES_END && slot < rules.size();
    }

    /** Default blocked policy indicator; not a rule, click does nothing. */
    public boolean isDefaultPolicySlot(int slot) { return slot == SLOT_DEFAULT_POLICY; }

    /** Pair button (transfer context only). */
    public boolean isPairSlot(int slot) { return slot == SLOT_ACTION && "transfer".equals(contextType); }
    public boolean isRelocateSlot(int slot) { return slot == SLOT_RELOCATE; }
    public boolean isSegmentSlot(int slot) { return false; }

    public boolean isCreateSlot(int slot) { return slot == SLOT_CREATE; }
    public boolean isCloseSlot(int slot) { return slot == SLOT_CLOSE; }

    /** Refresh rules from DB and redraw (e.g. after create/delete). */
    public void refresh() {
        loadRules();
        fillLayout();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
