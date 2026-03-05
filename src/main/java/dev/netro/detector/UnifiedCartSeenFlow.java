package dev.netro.detector;

import dev.netro.NetroPlugin;
import dev.netro.database.CartRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Detector;
import dev.netro.model.TransferNode;
import dev.netro.routing.RoutingEngine;
import org.bukkit.World;
import org.bukkit.entity.Minecart;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single place for the unified flow when a detector sees a cart:
 * (1) If cart is not in DB yet, add it as a "managed" cart (chunk loading, destination/rules). Prefer the station
 *     the cart is heading toward (when CLEAR at a transfer node = leaving toward paired station); if that station
 *     has an available terminal use it. Otherwise use closest station with a terminal by distance, or null.
 * (2) No-dest at a station → apply no-destination rule (set dest or remove cart if no terminals).
 * No segment occupancy (no collision detection). Stale carts in DB are trimmed by NetroPlugin's scheduled cleanup.
 *
 * Repos and routing are injected so there is a single source of truth (e.g. DetectorRailHandler's instances).
 */
public class UnifiedCartSeenFlow {

    public record Result(boolean ranNoDestAtStart) {}

    private final NetroPlugin plugin;
    private final CartRepository cartRepo;
    private final StationRepository stationRepo;
    private final TransferNodeRepository nodeRepo;
    private final RoutingEngine routing;

    /** @param plugin for scheduler and world lookup (despawn callback). Repos and routing are shared with the caller (single source of truth). */
    public UnifiedCartSeenFlow(NetroPlugin plugin, CartRepository cartRepo, StationRepository stationRepo, TransferNodeRepository nodeRepo, RoutingEngine routing) {
        this.plugin = plugin;
        this.cartRepo = cartRepo;
        this.stationRepo = stationRepo;
        this.nodeRepo = nodeRepo;
        this.routing = routing;
    }

    /**
     * Run the unified flow. Call from the detector async block before the node loop.
     *
     * @return result with ranNoDestAtStart so the caller can skip re-running the no-dest rule.
     */
    public Result run(
        String cartUuid,
        World world,
        Minecart cart,
        List<Detector> detectors,
        int cartBlockX,
        int cartBlockY,
        int cartBlockZ,
        Optional<String> directionHintStationId
    ) {
        if (world == null || cart == null) {
            return new Result(false);
        }
        // 1) If cart not yet managed, add to DB and set destination. Prefer station the cart is heading toward (CLEAR at transfer = leaving toward paired station).
        if (cartRepo.find(cartUuid).isEmpty()) {
            String worldName = world.getName();
            Optional<String> terminalAddress = directionHintStationId
                .filter(stId -> nodeRepo.findAvailableTerminal(stId).isPresent())
                .flatMap(stId -> stationRepo.findById(stId)
                    .flatMap(st -> nodeRepo.findAvailableTerminal(stId)
                        .flatMap(t -> Optional.ofNullable(t.terminalAddress(st.getAddress())))));
            if (terminalAddress.isEmpty()) {
                terminalAddress = stationRepo.findAll().stream()
                    .filter(s -> worldName.equals(s.getWorld()))
                    .filter(s -> nodeRepo.findAvailableTerminal(s.getId()).isPresent())
                    .min((a, b) -> Long.compare(
                        sq(cartBlockX - a.getSignX()) + sq(cartBlockY - a.getSignY()) + sq(cartBlockZ - a.getSignZ()),
                        sq(cartBlockX - b.getSignX()) + sq(cartBlockY - b.getSignY()) + sq(cartBlockZ - b.getSignZ())))
                    .flatMap(st -> nodeRepo.findAvailableTerminal(st.getId())
                        .flatMap(t -> Optional.ofNullable(t.terminalAddress(st.getAddress()))));
            }
            if (terminalAddress.isPresent()) {
                cartRepo.setDestination(cartUuid, terminalAddress.get(), null);
            } else {
                cartRepo.setDestination(cartUuid, null, null);
            }
            if (plugin.getChunkLoadService() != null) {
                plugin.getChunkLoadService().ensureCartTaskRunning();
            }
            plugin.getDetectorRailHandler().recheckTerminalReleaseForCart(cartUuid);
        }
        // 2) If at a station with no destination, apply no-destination rule (set dest or remove if no terminals).
        String currentStationId = resolveCurrentStationId(detectors);
        boolean ranNoDestAtStart = false;
        if (currentStationId != null) {
            Optional<Map<String, Object>> cartDataForNoDest = cartRepo.find(cartUuid);
            boolean noDest = cartDataForNoDest.isEmpty()
                || cartDataForNoDest.get().get("destination_address") == null
                || "".equals(cartDataForNoDest.get().get("destination_address"));
            if (noDest) {
                ranNoDestAtStart = true;
                final Minecart cartToRemove = cart;
                routing.applyNoDestinationRule(cartUuid, currentStationId, () ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (cartToRemove != null && cartToRemove.isValid()) cartToRemove.remove();
                    }));
            }
        }
        return new Result(ranNoDestAtStart);
    }

    private String resolveCurrentStationId(List<Detector> detectors) {
        if (detectors == null) return null;
        for (Detector d : detectors) {
            if (d.getNodeId() != null) {
                Optional<TransferNode> node = nodeRepo.findById(d.getNodeId());
                if (node.isPresent()) return node.get().getStationId();
            }
        }
        return null;
    }

    private static long sq(int n) { return (long) n * n; }
}
