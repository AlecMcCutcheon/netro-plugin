package dev.netro.gui;

import dev.netro.NetroPlugin;
import dev.netro.database.CartRepository;
import dev.netro.database.DetectorRepository;
import dev.netro.database.RuleRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Detector;
import dev.netro.model.Rule;
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
import org.bukkit.block.BlockFace;
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

    /** Used by CartControllerBossBarUpdater to read cart state. */
    public CartControllerState getStateFor(String cartUuid) {
        return stateFor(cartUuid);
    }

    private void refreshOpenMenus() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof CartMenuHolder holder) {
                Minecart cart = findMinecartByUuid(holder.getCartUuid());
                if (cart != null && cart.isValid()) {
                    String currentDest = cartRepo.getDestinationAddress(holder.getCartUuid()).orElse(null);
                    String destDisplay = (currentDest != null && !currentDest.isEmpty())
                        ? RulesMainHolder.formatDestinationId(currentDest, stationRepo, nodeRepo)
                        : null;
                    holder.updateLore(cart, stateFor(holder.getCartUuid()), destDisplay != null ? destDisplay : currentDest);
                }
            }
        }
    }

    /** Re-apply set speed for carts in cruise mode when not under READY hold. Only READY hold skips reapply. */
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

    /** Update stored cruise speed level to match a velocity magnitude. User sees updated speed in the GUI.
     * If this cart had no state yet (player never opened GUI), set speed and turn cruise (Start) on so the cart keeps this speed.
     * If the cart already had state, leave speed and cruise alone so the existing setting is preserved. */
    public void updateStoredSpeedFromMagnitude(String cartUuid, double magnitude) {
        boolean hadState = stateByCart.containsKey(cartUuid);
        if (hadState) return;
        CartControllerState s = stateByCart.computeIfAbsent(cartUuid, k -> new CartControllerState());
        s.setSpeedLevel(CartControllerState.speedLevelFromMagnitude(magnitude));
        s.setCruiseActive(true);
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

    /** Called when a detector/READY applied velocity so cruise yields (stop mode; detectors/rails keep control). Ensures state exists so we can turn cruise off. */
    public void yieldCart(String cartUuid) {
        CartControllerState s = stateByCart.computeIfAbsent(cartUuid, k -> new CartControllerState());
        s.setCruiseActive(false);
    }

    /** Apply cruise speed from a rule (SET_CRUISE_SPEED). magnitude is 0–1 (e.g. 0.25 for rule "2.5"). Sets state and applies velocity on the cart. */
    public void applyRuleCruiseSpeed(String cartUuid, double magnitude) {
        CartControllerState s = stateByCart.computeIfAbsent(cartUuid, k -> new CartControllerState());
        s.setCustomSpeedMagnitude(magnitude);
        s.setCruiseActive(true);
        Minecart cart = findMinecartByUuid(cartUuid);
        if (cart != null && cart.isValid()) {
            applyCruiseSpeed(cart, s);
        }
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
            return;
        }
        if (top.getHolder() instanceof RailroadControllerHolder railHolder) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handleRailroadControllerClick((Player) event.getWhoClicked(), railHolder, event.getSlot());
            return;
        }
        if (top.getHolder() instanceof RulesMainHolder rulesHolder) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handleRulesMainClick((Player) event.getWhoClicked(), rulesHolder, event.getSlot());
            return;
        }
        if (top.getHolder() instanceof RulesCreateStep1Holder step1) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handleRulesCreateStep1Click((Player) event.getWhoClicked(), step1, event.getSlot());
            return;
        }
        if (top.getHolder() instanceof RulesCreateStep2Holder step2) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handleRulesCreateStep2Click((Player) event.getWhoClicked(), step2, event.getSlot());
            return;
        }
        if (top.getHolder() instanceof RulesCreateStep3Holder step3) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handleRulesCreateStep3Click((Player) event.getWhoClicked(), step3, event.getSlot());
            return;
        }
        if (top.getHolder() instanceof RulesEditOrDeleteHolder editOrDelete) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handleRulesEditOrDeleteClick((Player) event.getWhoClicked(), editOrDelete, event.getSlot());
            return;
        }
        if (top.getHolder() instanceof RulesConfirmDeleteHolder confirmDelete) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handleRulesConfirmDeleteClick((Player) event.getWhoClicked(), confirmDelete, event.getSlot());
            return;
        }
        if (top.getHolder() instanceof RulesDestinationPickerHolder picker) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handleRulesDestinationPickerClick((Player) event.getWhoClicked(), picker, event.getSlot());
            return;
        }
        if (top.getHolder() instanceof RulesSetRailStateHolder setRail) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handleRulesSetRailStateClick((Player) event.getWhoClicked(), setRail, event.getSlot());
            return;
        }
        if (top.getHolder() instanceof PairStationPickerHolder pairStation) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handlePairStationPickerClick((Player) event.getWhoClicked(), pairStation, event.getSlot());
            return;
        }
        if (top.getHolder() instanceof PairNodePickerHolder pairNode) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handlePairNodePickerClick((Player) event.getWhoClicked(), pairNode, event.getSlot());
            return;
        }
        if (top.getHolder() instanceof RulesConfirmUnpairHolder confirmUnpair) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handleRulesConfirmUnpairClick((Player) event.getWhoClicked(), confirmUnpair, event.getSlot());
            return;
        }
        if (top.getHolder() instanceof StationNodeListHolder stationList) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handleStationNodeListClick((Player) event.getWhoClicked(), stationList, event.getSlot());
            return;
        }
        if (top.getHolder() instanceof StationNodeOptionsHolder nodeOptions) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handleStationNodeOptionsClick((Player) event.getWhoClicked(), nodeOptions, event.getSlot());
            return;
        }
        if (top.getHolder() instanceof StationNodeConfirmDeleteHolder confirmDelete) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handleStationNodeConfirmDeleteClick((Player) event.getWhoClicked(), confirmDelete, event.getSlot());
            return;
        }
        if (top.getHolder() instanceof RulesCruiseSpeedHolder cruiseSpeed) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            handleRulesCruiseSpeedClick((Player) event.getWhoClicked(), cruiseSpeed, event.getSlot());
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CartMenuHolder ||
            event.getView().getTopInventory().getHolder() instanceof CartDestinationHolder) {
            event.setCancelled(true);
        }
        if (event.getView().getTopInventory().getHolder() instanceof RailroadControllerHolder) {
            event.setCancelled(true);
        }
        if (event.getView().getTopInventory().getHolder() instanceof RulesMainHolder ||
            event.getView().getTopInventory().getHolder() instanceof RulesCreateStep1Holder ||
            event.getView().getTopInventory().getHolder() instanceof RulesCreateStep2Holder ||
            event.getView().getTopInventory().getHolder() instanceof RulesCreateStep3Holder ||
            event.getView().getTopInventory().getHolder() instanceof RulesEditOrDeleteHolder ||
            event.getView().getTopInventory().getHolder() instanceof RulesConfirmDeleteHolder ||
            event.getView().getTopInventory().getHolder() instanceof RulesDestinationPickerHolder ||
            event.getView().getTopInventory().getHolder() instanceof RulesSetRailStateHolder ||
            event.getView().getTopInventory().getHolder() instanceof PairStationPickerHolder ||
            event.getView().getTopInventory().getHolder() instanceof PairNodePickerHolder ||
            event.getView().getTopInventory().getHolder() instanceof RulesConfirmUnpairHolder ||
            event.getView().getTopInventory().getHolder() instanceof StationNodeListHolder ||
            event.getView().getTopInventory().getHolder() instanceof StationNodeOptionsHolder ||
            event.getView().getTopInventory().getHolder() instanceof StationNodeConfirmDeleteHolder ||
            event.getView().getTopInventory().getHolder() instanceof RulesCruiseSpeedHolder) {
            event.setCancelled(true);
        }
    }

    private void handleRulesMainClick(Player player, RulesMainHolder holder, int slot) {
        if (holder.isCloseSlot(slot)) {
            player.closeInventory();
            return;
        }
        if (holder.isDefaultPolicySlot(slot)) {
            return;
        }
        if (holder.isCreateSlot(slot)) {
            RulesCreateStep1Holder step1 = new RulesCreateStep1Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getTitle());
            player.openInventory(step1.getInventory());
            return;
        }
        if (holder.isPairSlot(slot)) {
            var nodeOpt = nodeRepo.findById(holder.getContextId());
            if (nodeOpt.isPresent() && nodeOpt.get().getPairedNodeId() != null && !nodeOpt.get().getPairedNodeId().isEmpty()) {
                String pairedLabel = RulesMainHolder.formatStationNode(nodeOpt.get().getPairedNodeId(), stationRepo, nodeRepo);
                RulesConfirmUnpairHolder confirm = new RulesConfirmUnpairHolder(holder.getPlugin(), holder.getContextId(), pairedLabel, holder.getTitle());
                player.openInventory(confirm.getInventory());
            } else {
                PairStationPickerHolder picker = new PairStationPickerHolder(holder.getPlugin(), holder.getContextId(), holder.getTitle());
                player.openInventory(picker.getInventory());
            }
            return;
        }
        if (holder.isRelocateSlot(slot)) {
            plugin.setPendingRelocate(player.getUniqueId(), new dev.netro.gui.PendingRelocate.Source(holder.getContextId()));
            player.closeInventory();
            player.sendMessage("Click the block to move the detector/controller to (it will be placed above that block).");
            return;
        }
        if (holder.isRuleSlot(slot)) {
            dev.netro.model.Rule rule = holder.getRuleAtSlot(slot);
            if (rule != null) {
                RulesEditOrDeleteHolder editOrDelete = new RulesEditOrDeleteHolder(holder.getPlugin(), rule, holder.getTitle());
                player.openInventory(editOrDelete.getInventory());
            }
            return;
        }
    }

    private void handleRulesCreateStep1Click(Player player, RulesCreateStep1Holder holder, int slot) {
        if (slot == RulesCreateStep1Holder.SLOT_BACK) {
            if (holder.isEditMode()) {
                RulesMainHolder main = new RulesMainHolder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle());
                player.openInventory(main.getInventory());
            } else {
                RulesMainHolder main = new RulesMainHolder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle());
                player.openInventory(main.getInventory());
            }
            return;
        }
        if (holder.isEditMode() && slot == RulesCreateStep1Holder.SLOT_SAVE) {
            dev.netro.model.Rule r = holder.getEditRule();
            updateRule(r, holder.getSelectedTrigger(), r.isDestinationPositive(), r.getDestinationId(), r.getActionType(), r.getActionData());
            player.closeInventory();
            RulesMainHolder main = new RulesMainHolder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle());
            player.openInventory(main.getInventory());
            player.sendMessage("Rule " + r.getRuleIndex() + " updated (trigger saved).");
            return;
        }
        if (holder.isEditMode() && slot == RulesCreateStep1Holder.SLOT_NEXT) {
            RulesCreateStep2Holder step2 = new RulesCreateStep2Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), holder.getSelectedTrigger(), holder.getEditRule());
            player.openInventory(step2.getInventory());
            return;
        }
        if (slot == RulesCreateStep1Holder.SLOT_ENTERING) {
            if (holder.isEditMode()) {
                RulesCreateStep1Holder step1Select = new RulesCreateStep1Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), holder.getEditRule(), dev.netro.model.Rule.TRIGGER_ENTERING);
                player.openInventory(step1Select.getInventory());
            } else {
                RulesCreateStep2Holder step2 = new RulesCreateStep2Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), dev.netro.model.Rule.TRIGGER_ENTERING);
                player.openInventory(step2.getInventory());
            }
            return;
        }
        if (slot == RulesCreateStep1Holder.SLOT_CLEARING) {
            if (holder.isEditMode()) {
                RulesCreateStep1Holder step1Select = new RulesCreateStep1Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), holder.getEditRule(), dev.netro.model.Rule.TRIGGER_CLEARING);
                player.openInventory(step1Select.getInventory());
            } else {
                RulesCreateStep2Holder step2 = new RulesCreateStep2Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), dev.netro.model.Rule.TRIGGER_CLEARING);
                player.openInventory(step2.getInventory());
            }
            return;
        }
        if (slot == RulesCreateStep1Holder.SLOT_BLOCKED) {
            if (holder.isEditMode()) {
                RulesCreateStep1Holder step1Select = new RulesCreateStep1Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), holder.getEditRule(), dev.netro.model.Rule.TRIGGER_BLOCKED);
                player.openInventory(step1Select.getInventory());
            } else {
                RulesDestinationPickerHolder picker = new RulesDestinationPickerHolder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), dev.netro.model.Rule.TRIGGER_BLOCKED, true, RulesDestinationPickerHolder.PICKER_MODE_BLOCKED_HOP, null);
                player.openInventory(picker.getInventory());
            }
            return;
        }
        if (slot == RulesCreateStep1Holder.SLOT_DETECTED) {
            if (holder.isEditMode()) {
                RulesCreateStep1Holder step1Select = new RulesCreateStep1Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), holder.getEditRule(), dev.netro.model.Rule.TRIGGER_DETECTED);
                player.openInventory(step1Select.getInventory());
            } else {
                RulesCreateStep2Holder step2 = new RulesCreateStep2Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), dev.netro.model.Rule.TRIGGER_DETECTED);
                player.openInventory(step2.getInventory());
            }
        }
    }

    private void updateRule(dev.netro.model.Rule rule, String triggerType, boolean destinationPositive, String destinationId, String actionType, String actionData) {
        dev.netro.model.Rule updated = new dev.netro.model.Rule(
            rule.getId(),
            rule.getContextType(),
            rule.getContextId(),
            rule.getContextSide(),
            rule.getRuleIndex(),
            triggerType,
            destinationPositive,
            destinationId,
            actionType,
            actionData,
            rule.getCreatedAt()
        );
        RuleRepository repo = new RuleRepository(plugin.getDatabase());
        repo.update(updated);
    }

    private void handleRulesCreateStep2Click(Player player, RulesCreateStep2Holder holder, int slot) {
        if (slot == RulesCreateStep2Holder.SLOT_BACK) {
            if (holder.isEditMode()) {
                RulesCreateStep1Holder step1 = new RulesCreateStep1Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), holder.getEditRule());
                player.openInventory(step1.getInventory());
            } else {
                RulesCreateStep1Holder step1 = new RulesCreateStep1Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle());
                player.openInventory(step1.getInventory());
            }
            return;
        }
        if (holder.isEditMode() && slot == RulesCreateStep2Holder.SLOT_SAVE) {
            dev.netro.model.Rule r = holder.getEditRule();
            updateRule(r, holder.getTriggerType(), r.isDestinationPositive(), r.getDestinationId(), r.getActionType(), r.getActionData());
            player.closeInventory();
            RulesMainHolder main = new RulesMainHolder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle());
            player.openInventory(main.getInventory());
            player.sendMessage("Rule " + r.getRuleIndex() + " updated (trigger and destination saved).");
            return;
        }
        if (holder.isEditMode() && slot == RulesCreateStep2Holder.SLOT_NEXT) {
            dev.netro.model.Rule r = holder.getEditRule();
            String railStateData = dev.netro.model.Rule.ACTION_SET_RAIL_STATE.equals(r.getActionType()) ? r.getActionData() : null;
            RulesCreateStep3Holder step3 = new RulesCreateStep3Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), holder.getTriggerType(), r.isDestinationPositive(), r.getDestinationId(), r, railStateData);
            player.openInventory(step3.getInventory());
            return;
        }
        if (slot == RulesCreateStep2Holder.SLOT_GOING_TO) {
            RulesDestinationPickerHolder picker = new RulesDestinationPickerHolder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), holder.getTriggerType(), true, RulesDestinationPickerHolder.PICKER_MODE_NORMAL, null, holder.getEditRule());
            player.openInventory(picker.getInventory());
            return;
        }
        if (slot == RulesCreateStep2Holder.SLOT_NOT_GOING_TO) {
            RulesDestinationPickerHolder picker = new RulesDestinationPickerHolder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), holder.getTriggerType(), false, RulesDestinationPickerHolder.PICKER_MODE_NORMAL, null, holder.getEditRule());
            player.openInventory(picker.getInventory());
            return;
        }
        if (slot == RulesCreateStep2Holder.SLOT_ANY) {
            RulesCreateStep3Holder step3 = new RulesCreateStep3Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), holder.getTriggerType(), true, null, holder.getEditRule());
            player.openInventory(step3.getInventory());
            return;
        }
        if (slot == RulesCreateStep2Holder.SLOT_NOT_ANY) {
            RulesCreateStep3Holder step3 = new RulesCreateStep3Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), holder.getTriggerType(), false, null, holder.getEditRule());
            player.openInventory(step3.getInventory());
        }
    }

    private void handleRulesDestinationPickerClick(Player player, RulesDestinationPickerHolder picker, int slot) {
        if (picker.isBackSlot(slot)) {
            if (RulesDestinationPickerHolder.PICKER_MODE_SET_DESTINATION.equals(picker.getPickerMode())) {
                RulesDestinationPickerHolder backToBlocked = new RulesDestinationPickerHolder(picker.getPlugin(), picker.getContextType(), picker.getContextId(), picker.getContextSide(), picker.getRulesTitle(), dev.netro.model.Rule.TRIGGER_BLOCKED, true, RulesDestinationPickerHolder.PICKER_MODE_BLOCKED_HOP, null);
                player.openInventory(backToBlocked.getInventory());
            } else if (RulesDestinationPickerHolder.PICKER_MODE_BLOCKED_HOP.equals(picker.getPickerMode())) {
                RulesCreateStep1Holder step1 = new RulesCreateStep1Holder(picker.getPlugin(), picker.getContextType(), picker.getContextId(), picker.getContextSide(), picker.getRulesTitle());
                player.openInventory(step1.getInventory());
            } else {
                if (picker.getEditRule() != null) {
                    RulesCreateStep2Holder step2 = new RulesCreateStep2Holder(picker.getPlugin(), picker.getContextType(), picker.getContextId(), picker.getContextSide(), picker.getRulesTitle(), picker.getTriggerType(), picker.getEditRule());
                    player.openInventory(step2.getInventory());
                } else {
                    RulesCreateStep2Holder step2 = new RulesCreateStep2Holder(picker.getPlugin(), picker.getContextType(), picker.getContextId(), picker.getContextSide(), picker.getRulesTitle(), picker.getTriggerType());
                    player.openInventory(step2.getInventory());
                }
            }
            return;
        }
        String destId = picker.getDestinationIdAtSlot(slot);
        if (destId != null) {
            if (RulesDestinationPickerHolder.PICKER_MODE_BLOCKED_HOP.equals(picker.getPickerMode())) {
                RuleRepository ruleRepo = new RuleRepository(plugin.getDatabase(), nodeRepo);
                if (ruleRepo.findBlockedRuleForDestination(picker.getContextType(), picker.getContextId(), picker.getContextSide(), destId).isPresent()) {
                    player.sendMessage("A \"when blocked\" rule already exists for this hop. Delete it first to set a different redirect.");
                    return;
                }
                RulesDestinationPickerHolder setDestPicker = new RulesDestinationPickerHolder(picker.getPlugin(), picker.getContextType(), picker.getContextId(), picker.getContextSide(), picker.getRulesTitle(), dev.netro.model.Rule.TRIGGER_BLOCKED, true, RulesDestinationPickerHolder.PICKER_MODE_SET_DESTINATION, destId);
                player.openInventory(setDestPicker.getInventory());
                return;
            }
            if (RulesDestinationPickerHolder.PICKER_MODE_SET_DESTINATION.equals(picker.getPickerMode())) {
                RuleRepository ruleRepo = new RuleRepository(plugin.getDatabase(), nodeRepo);
                int ruleIndex = ruleRepo.nextRuleIndex(picker.getContextType(), picker.getContextId(), picker.getContextSide());
                dev.netro.model.Rule rule = new dev.netro.model.Rule(
                    UUID.randomUUID().toString(),
                    picker.getContextType(),
                    picker.getContextId(),
                    picker.getContextSide(),
                    ruleIndex,
                    dev.netro.model.Rule.TRIGGER_BLOCKED,
                    true,
                    picker.getBlockedHopId(),
                    dev.netro.model.Rule.ACTION_SET_DESTINATION,
                    destId,
                    System.currentTimeMillis()
                );
                ruleRepo.insert(rule);
                player.closeInventory();
                player.sendMessage("Rule " + ruleIndex + " created: when hop to " + picker.getBlockedHopId() + " is blocked, set destination to " + destId + ".");
                return;
            }
            dev.netro.model.Rule editRule = picker.getEditRule();
            String railStateData = editRule != null && dev.netro.model.Rule.ACTION_SET_RAIL_STATE.equals(editRule.getActionType()) ? editRule.getActionData() : null;
            RulesCreateStep3Holder step3 = new RulesCreateStep3Holder(picker.getPlugin(), picker.getContextType(), picker.getContextId(), picker.getContextSide(), picker.getRulesTitle(), picker.getTriggerType(), picker.isDestinationPositive(), destId, editRule, railStateData);
            player.openInventory(step3.getInventory());
        }
    }

    private void handleRulesCreateStep3Click(Player player, RulesCreateStep3Holder holder, int slot) {
        if (slot == RulesCreateStep3Holder.SLOT_BACK) {
            if (holder.isEditMode()) {
                RulesCreateStep2Holder step2 = new RulesCreateStep2Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), holder.getTriggerType(), holder.getEditRule());
                player.openInventory(step2.getInventory());
            } else {
                RulesCreateStep2Holder step2 = new RulesCreateStep2Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), holder.getTriggerType());
                player.openInventory(step2.getInventory());
            }
            return;
        }
        if (slot == RulesCreateStep3Holder.SLOT_SAVE) {
            if (holder.getSelectedRailStateActionData() != null) {
                if (holder.isEditMode()) {
                    dev.netro.model.Rule r = holder.getEditRule();
                    updateRule(r, holder.getTriggerType(), holder.isDestinationPositive(), holder.getDestinationId(), dev.netro.model.Rule.ACTION_SET_RAIL_STATE, holder.getSelectedRailStateActionData());
                    player.closeInventory();
                    RulesMainHolder main = new RulesMainHolder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle());
                    player.openInventory(main.getInventory());
                    player.sendMessage("Rule " + r.getRuleIndex() + " updated: rail state saved.");
                } else {
                    int idx = holder.createRule(dev.netro.model.Rule.ACTION_SET_RAIL_STATE, holder.getSelectedRailStateActionData());
                    player.closeInventory();
                    RulesMainHolder main = new RulesMainHolder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle());
                    player.openInventory(main.getInventory());
                    player.sendMessage("Rule " + idx + " created: when " + holder.getTriggerType() + " and destination match, set detector rail (saved).");
                }
                return;
            }
            if (holder.isEditMode()) {
                dev.netro.model.Rule r = holder.getEditRule();
                updateRule(r, holder.getTriggerType(), holder.isDestinationPositive(), holder.getDestinationId(), r.getActionType(), r.getActionData());
                player.closeInventory();
                RulesMainHolder main = new RulesMainHolder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle());
                player.openInventory(main.getInventory());
                player.sendMessage("Rule " + r.getRuleIndex() + " updated (saved).");
                return;
            }
        }
        if (slot == RulesCreateStep3Holder.SLOT_SEND_ON) {
            if (holder.isEditMode()) {
                dev.netro.model.Rule r = holder.getEditRule();
                updateRule(r, holder.getTriggerType(), holder.isDestinationPositive(), holder.getDestinationId(), dev.netro.model.Rule.ACTION_SEND_ON, null);
                player.closeInventory();
                RulesMainHolder main = new RulesMainHolder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle());
                player.openInventory(main.getInventory());
                player.sendMessage("Rule " + r.getRuleIndex() + " updated: turn bulbs ON (RULE:" + r.getRuleIndex() + ").");
            } else {
                int idx = holder.createRule(dev.netro.model.Rule.ACTION_SEND_ON);
                player.closeInventory();
                player.sendMessage("Rule " + idx + " created: when " + holder.getTriggerType() + " and destination match, turn controller bulbs ON (controllers with RULE:" + idx + " on their sign).");
            }
            return;
        }
        if (slot == RulesCreateStep3Holder.SLOT_SEND_OFF) {
            if (holder.isEditMode()) {
                dev.netro.model.Rule r = holder.getEditRule();
                updateRule(r, holder.getTriggerType(), holder.isDestinationPositive(), holder.getDestinationId(), dev.netro.model.Rule.ACTION_SEND_OFF, null);
                player.closeInventory();
                RulesMainHolder main = new RulesMainHolder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle());
                player.openInventory(main.getInventory());
                player.sendMessage("Rule " + r.getRuleIndex() + " updated: turn bulbs OFF (RULE:" + r.getRuleIndex() + ").");
            } else {
                int idx = holder.createRule(dev.netro.model.Rule.ACTION_SEND_OFF);
                player.closeInventory();
                player.sendMessage("Rule " + idx + " created: when " + holder.getTriggerType() + " and destination match, turn controller bulbs OFF (controllers with RULE:" + idx + " on their sign).");
            }
            return;
        }
        if (slot == RulesCreateStep3Holder.SLOT_SET_RAIL) {
            if (holder.getSelectedRailStateActionData() != null) {
                String editRuleId = holder.isEditMode() ? holder.getEditRule().getId() : null;
                PendingSetRailStateRule pending = new PendingSetRailStateRule(
                    holder.getContextType(),
                    holder.getContextId(),
                    holder.getContextSide(),
                    holder.getTriggerType(),
                    holder.isDestinationPositive(),
                    holder.getDestinationId(),
                    holder.getRulesTitle(),
                    editRuleId
                );
                plugin.setPendingSetRailState(player.getUniqueId(), pending);
                player.closeInventory();
                player.sendMessage("Right-click a rail with the Railroad Controller to choose a different shape.");
                return;
            }
            if (holder.isEditMode()) {
                dev.netro.model.Rule r = holder.getEditRule();
                PendingSetRailStateRule pending = new PendingSetRailStateRule(
                    holder.getContextType(),
                    holder.getContextId(),
                    holder.getContextSide(),
                    holder.getTriggerType(),
                    holder.isDestinationPositive(),
                    holder.getDestinationId(),
                    holder.getRulesTitle(),
                    r.getId()
                );
                plugin.setPendingSetRailState(player.getUniqueId(), pending);
                player.closeInventory();
                player.sendMessage("Right-click a rail with the Railroad Controller to choose the rail shape for this rule.");
            } else {
                PendingSetRailStateRule pending = new PendingSetRailStateRule(
                    holder.getContextType(),
                    holder.getContextId(),
                    holder.getContextSide(),
                    holder.getTriggerType(),
                    holder.isDestinationPositive(),
                    holder.getDestinationId(),
                    holder.getRulesTitle()
                );
                plugin.setPendingSetRailState(player.getUniqueId(), pending);
                player.closeInventory();
                player.sendMessage("Take your Railroad Controller and right-click a valid rail to choose the rail shape for this rule.");
            }
            return;
        }
        if (slot == RulesCreateStep3Holder.SLOT_SET_CRUISE_SPEED) {
            RulesCruiseSpeedHolder speedHolder = new RulesCruiseSpeedHolder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(),
                holder.getRulesTitle(), holder.getTriggerType(), holder.isDestinationPositive(), holder.getDestinationId(), holder.getEditRule());
            player.openInventory(speedHolder.getInventory());
        }
    }

    private void handleRulesSetRailStateClick(Player player, RulesSetRailStateHolder holder, int slot) {
        if (holder.isBackSlot(slot)) {
            RulesCreateStep3Holder step3 = new RulesCreateStep3Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle(), holder.getTriggerType(), holder.isDestinationPositive(), holder.getDestinationId());
            player.openInventory(step3.getInventory());
            return;
        }
        org.bukkit.block.data.Rail.Shape shape = holder.getShapeAtSlot(slot);
        if (shape != null) {
            int idx = holder.createRuleWithShape(shape);
            player.closeInventory();
            player.sendMessage("Rule " + idx + " created: when " + holder.getTriggerType() + " and destination match, set detector rail to " + shape.name().replace("_", " ") + ".");
        }
    }

    private void handleRulesEditOrDeleteClick(Player player, RulesEditOrDeleteHolder holder, int slot) {
        if (slot == RulesEditOrDeleteHolder.SLOT_BACK) {
            RulesMainHolder main = new RulesMainHolder(holder.getPlugin(), holder.getRule().getContextType(), holder.getRule().getContextId(), holder.getRule().getContextSide(), holder.getRulesTitle());
            player.openInventory(main.getInventory());
            return;
        }
        if (slot == RulesEditOrDeleteHolder.SLOT_EDIT) {
            dev.netro.model.Rule rule = holder.getRule();
            RulesCreateStep1Holder step1 = new RulesCreateStep1Holder(holder.getPlugin(), rule.getContextType(), rule.getContextId(), rule.getContextSide(), holder.getRulesTitle(), rule);
            player.openInventory(step1.getInventory());
            return;
        }
        if (slot == RulesEditOrDeleteHolder.SLOT_DELETE) {
            RulesConfirmDeleteHolder confirm = new RulesConfirmDeleteHolder(holder.getPlugin(), holder.getRule(), holder.getRulesTitle());
            player.openInventory(confirm.getInventory());
        }
    }

    private void handleRulesConfirmDeleteClick(Player player, RulesConfirmDeleteHolder holder, int slot) {
        if (slot == RulesConfirmDeleteHolder.SLOT_CANCEL) {
            RulesMainHolder main = new RulesMainHolder(holder.getPlugin(), holder.getRule().getContextType(), holder.getRule().getContextId(), holder.getRule().getContextSide(), holder.getRulesTitle());
            player.openInventory(main.getInventory());
            return;
        }
        if (slot == RulesConfirmDeleteHolder.SLOT_CONFIRM) {
            int idx = holder.getRule().getRuleIndex();
            holder.deleteRule();
            player.closeInventory();
            player.sendMessage("Rule " + idx + " deleted. Other rules renumbered.");
        }
    }

    private void handlePairStationPickerClick(Player player, PairStationPickerHolder holder, int slot) {
        if (holder.isBackSlot(slot)) {
            RulesMainHolder main = new RulesMainHolder(holder.getPlugin(), "transfer", holder.getCurrentNodeId(), null, holder.getRulesTitle());
            player.openInventory(main.getInventory());
            return;
        }
        String stationId = holder.getStationIdAtSlot(slot);
        if (stationId != null) {
            PairNodePickerHolder nodePicker = new PairNodePickerHolder(holder.getPlugin(), holder.getCurrentNodeId(), stationId, holder.getRulesTitle());
            player.openInventory(nodePicker.getInventory());
        }
    }

    private void handlePairNodePickerClick(Player player, PairNodePickerHolder holder, int slot) {
        if (holder.isBackSlot(slot)) {
            PairStationPickerHolder stationPicker = new PairStationPickerHolder(holder.getPlugin(), holder.getCurrentNodeId(), holder.getRulesTitle());
            player.openInventory(stationPicker.getInventory());
            return;
        }
        String chosenNodeId = holder.getNodeIdAtSlot(slot);
        if (chosenNodeId != null) {
            TransferNodeRepository nodeRepo = new TransferNodeRepository(holder.getPlugin().getDatabase());
            nodeRepo.setPaired(holder.getCurrentNodeId(), chosenNodeId);
            nodeRepo.setPaired(chosenNodeId, holder.getCurrentNodeId());
            RulesMainHolder main = new RulesMainHolder(holder.getPlugin(), "transfer", holder.getCurrentNodeId(), null, holder.getRulesTitle());
            player.openInventory(main.getInventory());
            String label = RulesMainHolder.formatStationNode(chosenNodeId, stationRepo, nodeRepo);
            plugin.sendMessage(player, Component.text("Paired to " + label + ".", NamedTextColor.GREEN));
        }
    }

    private void handleStationNodeListClick(Player player, StationNodeListHolder holder, int slot) {
        if (holder.isBackSlot(slot)) {
            player.closeInventory();
            return;
        }
        StationNodeListHolder.NodeEntry entry = holder.getEntryAtSlot(slot);
        if (entry != null) {
            StationNodeOptionsHolder options = new StationNodeOptionsHolder(
                holder.getPlugin(), holder.getStationId(), holder.getStationName(),
                entry.nodeId, entry.contextType, entry.displayName);
            player.openInventory(options.getInventory());
        }
    }

    private void handleStationNodeOptionsClick(Player player, StationNodeOptionsHolder holder, int slot) {
        if (holder.isBackSlot(slot)) {
            StationNodeListHolder list = new StationNodeListHolder(holder.getPlugin(), holder.getStationId(), holder.getStationName());
            player.openInventory(list.getInventory());
            return;
        }
        if (holder.isOpenRulesSlot(slot)) {
            String title = "Rules — " + holder.getStationName() + ":" + holder.getNodeDisplayName();
            RulesMainHolder rules = new RulesMainHolder(holder.getPlugin(), holder.getContextType(), holder.getNodeId(), null, title);
            player.openInventory(rules.getInventory());
            return;
        }
        if (holder.isRelocateSlot(slot)) {
            plugin.setPendingRelocate(player.getUniqueId(), new dev.netro.gui.PendingRelocate.Source(holder.getNodeId()));
            player.closeInventory();
            player.sendMessage("Click the block to move the detector/controller to (it will be placed above that block).");
            return;
        }
        if (holder.isDeleteSlot(slot)) {
            StationNodeConfirmDeleteHolder confirm = new StationNodeConfirmDeleteHolder(
                holder.getPlugin(), holder.getStationId(), holder.getStationName(),
                holder.getNodeId(), holder.getContextType(), holder.getNodeDisplayName());
            player.openInventory(confirm.getInventory());
        }
    }

    private void handleStationNodeConfirmDeleteClick(Player player, StationNodeConfirmDeleteHolder holder, int slot) {
        if (holder.isCancelSlot(slot)) {
            StationNodeOptionsHolder options = new StationNodeOptionsHolder(holder.getPlugin(), holder.getStationId(), holder.getStationName(),
                holder.getNodeId(), holder.getContextType(), holder.getNodeDisplayName());
            player.openInventory(options.getInventory());
            return;
        }
        if (holder.isConfirmSlot(slot)) {
            var db = holder.getPlugin().getDatabase();
            DetectorRepository detectorRepo = new DetectorRepository(db);
            RuleRepository ruleRepo = new RuleRepository(db);
            TransferNodeRepository nodeRepo = new TransferNodeRepository(db);
            var chunkLoad = holder.getPlugin().getChunkLoadService();
            for (Detector d : detectorRepo.findByNodeId(holder.getNodeId())) {
                if (chunkLoad != null) chunkLoad.removeChunksForBlock(d.getWorld(), d.getRailX(), d.getRailZ());
            }
            ruleRepo.deleteByContext(holder.getContextType(), holder.getNodeId());
            nodeRepo.deleteNodeAndAllBlockData(holder.getNodeId());
            player.closeInventory();
            player.sendMessage("Deleted " + holder.getNodeDisplayName() + ". Detectors, controllers, and rules for this node were removed.");
        }
    }

    private void handleRulesConfirmUnpairClick(Player player, RulesConfirmUnpairHolder holder, int slot) {
        if (slot == RulesConfirmUnpairHolder.SLOT_CANCEL) {
            RulesMainHolder main = new RulesMainHolder(holder.getPlugin(), "transfer", holder.getNodeId(), null, holder.getRulesTitle());
            player.openInventory(main.getInventory());
            return;
        }
        if (slot == RulesConfirmUnpairHolder.SLOT_CONFIRM) {
            TransferNodeRepository nodeRepo = new TransferNodeRepository(holder.getPlugin().getDatabase());
            var nodeOpt = nodeRepo.findById(holder.getNodeId());
            String pairedId = nodeOpt.map(TransferNode::getPairedNodeId).orElse(null);
            if (pairedId != null && !pairedId.isEmpty()) {
                nodeRepo.setPaired(holder.getNodeId(), null);
                nodeRepo.setPaired(pairedId, null);
            }
            RulesMainHolder main = new RulesMainHolder(holder.getPlugin(), "transfer", holder.getNodeId(), null, holder.getRulesTitle());
            player.openInventory(main.getInventory());
            plugin.sendMessage(player, Component.text("Unpaired. Both sides cleared.", NamedTextColor.GREEN));
        }
    }

    private void handleRulesCruiseSpeedClick(Player player, RulesCruiseSpeedHolder holder, int slot) {
        if (holder.isCancelSlot(slot)) {
            RulesCreateStep3Holder step3 = new RulesCreateStep3Holder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(),
                holder.getRulesTitle(), holder.getTriggerType(), holder.isDestinationPositive(), holder.getDestinationId(), holder.getEditRule());
            player.openInventory(step3.getInventory());
            return;
        }
        if (holder.isConfirmSlot(slot)) {
            String speedStr = holder.getSpeedString();
            if (holder.getEditRule() != null) {
                dev.netro.model.Rule r = holder.getEditRule();
                updateRule(r, holder.getTriggerType(), holder.isDestinationPositive(), holder.getDestinationId(), Rule.ACTION_SET_CRUISE_SPEED, speedStr);
                player.closeInventory();
                RulesMainHolder main = new RulesMainHolder(holder.getPlugin(), holder.getContextType(), holder.getContextId(), holder.getContextSide(), holder.getRulesTitle());
                player.openInventory(main.getInventory());
                player.sendMessage("Rule " + r.getRuleIndex() + " updated: cruise speed " + speedStr + ".");
            } else {
                RuleRepository ruleRepo = new RuleRepository(holder.getPlugin().getDatabase(), new TransferNodeRepository(holder.getPlugin().getDatabase()));
                int ruleIndex = ruleRepo.nextRuleIndex(holder.getContextType(), holder.getContextId(), holder.getContextSide());
                Rule rule = new Rule(
                    UUID.randomUUID().toString(),
                    holder.getContextType(),
                    holder.getContextId(),
                    holder.getContextSide(),
                    ruleIndex,
                    holder.getTriggerType(),
                    holder.isDestinationPositive(),
                    holder.getDestinationId(),
                    Rule.ACTION_SET_CRUISE_SPEED,
                    speedStr,
                    System.currentTimeMillis()
                );
                ruleRepo.insert(rule);
                player.closeInventory();
                player.sendMessage("Rule " + ruleIndex + " created: when " + holder.getTriggerType() + " and destination match, set cruise speed to " + speedStr + ".");
            }
            return;
        }
        if (holder.isFirstDigitSlot(slot)) {
            holder.setFirstDigit(slot);
            return;
        }
        if (holder.isSecondDigitSlot(slot)) {
            holder.setSecondDigit(slot);
        }
    }

    private void handleRailroadControllerClick(Player player, RailroadControllerHolder holder, int slot) {
        if (holder.isCenterSlot(slot)) {
            if (holder.getPendingRule().isPresent()) {
                PendingSetRailStateRule pending = holder.getPendingRule().get();
                plugin.setPendingSetRailState(player.getUniqueId(), null);
                player.closeInventory();
                RulesCreateStep3Holder step3;
                if (pending.editRuleId() != null) {
                    dev.netro.database.RuleRepository ruleRepo = new dev.netro.database.RuleRepository(plugin.getDatabase());
                    java.util.Optional<dev.netro.model.Rule> ruleOpt = ruleRepo.findById(pending.editRuleId());
                    if (ruleOpt.isPresent()) {
                        dev.netro.model.Rule rule = ruleOpt.get();
                        step3 = new RulesCreateStep3Holder(plugin, pending.contextType(), pending.contextId(), pending.contextSide(),
                            pending.rulesTitle(), pending.triggerType(), pending.destinationPositive(), pending.destinationId(), rule, null);
                    } else {
                        step3 = new RulesCreateStep3Holder(plugin, pending.contextType(), pending.contextId(), pending.contextSide(),
                            pending.rulesTitle(), pending.triggerType(), pending.destinationPositive(), pending.destinationId(), null, null);
                    }
                } else {
                    step3 = new RulesCreateStep3Holder(plugin, pending.contextType(), pending.contextId(), pending.contextSide(),
                        pending.rulesTitle(), pending.triggerType(), pending.destinationPositive(), pending.destinationId(), null, null);
                }
                player.sendMessage("Cancelled. Choose an action.");
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) player.openInventory(step3.getInventory());
                });
            } else {
                holder.clearSelection();
            }
            return;
        }
        if (!holder.isDirectionSlot(slot)) return;
        BlockFace face = holder.getFaceAtSlot(slot);
        if (face == null) return;
        if (holder.getSelection().size() >= 2) return;
        boolean nowTwo = holder.select(face);
        if (nowTwo) {
            if (holder.getPendingRule().isPresent()) {
                var sel = holder.getSelection();
                BlockFace[] arr = sel.toArray(new BlockFace[0]);
                org.bukkit.block.data.Rail.Shape shape = RailroadControllerHolder.shapeFromTwoFaces(arr[0], arr[1]);
                if (shape == null) {
                    holder.clearSelection();
                    return;
                }
                PendingSetRailStateRule pending = holder.getPendingRule().get();
                String worldName = holder.getWorld().getName();
                int rx = holder.getBlockX(), ry = holder.getBlockY(), rz = holder.getBlockZ();
                String actionData = worldName + "," + rx + "," + ry + "," + rz + "," + shape.name();
                plugin.setPendingSetRailState(player.getUniqueId(), null);
                if (pending.editRuleId() != null) {
                    dev.netro.database.RuleRepository ruleRepo = new dev.netro.database.RuleRepository(plugin.getDatabase());
                    java.util.Optional<dev.netro.model.Rule> ruleOpt = ruleRepo.findById(pending.editRuleId());
                    if (ruleOpt.isPresent()) {
                        dev.netro.model.Rule rule = ruleOpt.get();
                        updateRule(rule, rule.getTriggerType(), rule.isDestinationPositive(), rule.getDestinationId(), dev.netro.model.Rule.ACTION_SET_RAIL_STATE, actionData);
                        RulesMainHolder main = new RulesMainHolder(plugin, pending.contextType(), pending.contextId(), pending.contextSide(), pending.rulesTitle());
                        player.closeInventory();
                        player.sendMessage("Rule " + rule.getRuleIndex() + " updated: rail state saved.");
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (player.isOnline()) player.openInventory(main.getInventory());
                        });
                    } else {
                        player.sendMessage("Rule no longer exists.");
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (player.isOnline()) player.openInventory(holder.getInventory());
                        });
                    }
                } else {
                    RulesCreateStep3Holder step3 = new RulesCreateStep3Holder(plugin, pending.contextType(), pending.contextId(), pending.contextSide(),
                        pending.rulesTitle(), pending.triggerType(), pending.destinationPositive(), pending.destinationId(), null, actionData);
                    player.sendMessage("Rail shape chosen. Save to create the rule, or pick another action.");
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) player.openInventory(step3.getInventory());
                    });
                }
            } else {
                holder.applyToBlockAndClear();
            }
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
                if (state.isCruiseActive()) {
                    applySpeedMultiplier(cart, state);
                } else if (cart.getVelocity().lengthSquared() >= 1e-12) {
                    state.setCruiseActive(true);
                    applySpeedMultiplier(cart, state);
                }
            }
            case CartMenuHolder.SLOT_LOWER -> {
                state.setSpeedLevel(state.getSpeedLevel() - 1);
                if (state.isCruiseActive()) {
                    applySpeedMultiplier(cart, state);
                } else if (cart.getVelocity().lengthSquared() >= 1e-12) {
                    state.setCruiseActive(true);
                    applySpeedMultiplier(cart, state);
                }
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
            case CartMenuHolder.SLOT_DISABLE_CRUISE -> {
                state.setCruiseActive(false);
                state.setCachedVelocity(cart.getVelocity());
            }
            case CartMenuHolder.SLOT_DIR -> {
                Vector dir = cart.getVelocity();
                if (dir.lengthSquared() < 1e-12) dir = state.getCachedVelocity();
                if (dir.lengthSquared() < 1e-12) dir = new Vector(1, 0, 0);
                else dir = dir.clone().normalize();
                Vector reversed = dir.multiply(-1);
                state.setCachedVelocity(reversed);
                boolean cartWasMoving = cart.getVelocity().lengthSquared() >= 1e-12;
                if (state.isCruiseActive()) {
                    cart.setVelocity(reversed.clone().multiply(state.getTargetSpeedMagnitude()));
                } else if (cartWasMoving) {
                    state.setCruiseActive(true);
                    cart.setVelocity(reversed.clone().multiply(state.getTargetSpeedMagnitude()));
                }
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
            if (nodeRepo.findTerminals(s.getId()).isEmpty()) {
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
            if (terminals.isEmpty()) {
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
                termHolder.setTerminalButton(s++, t, addr, station.getName());
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
        String display = RulesMainHolder.formatDestinationId(resolved, stationRepo, nodeRepo);
        plugin.sendMessage(player, Component.text("Destination set to " + display, NamedTextColor.GREEN));
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
