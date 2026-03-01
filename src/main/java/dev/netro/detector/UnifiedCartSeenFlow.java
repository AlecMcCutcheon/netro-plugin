package dev.netro.detector;

import dev.netro.NetroPlugin;
import dev.netro.database.CartRepository;
import dev.netro.database.JunctionRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Detector;
import dev.netro.model.Junction;
import dev.netro.model.StationDetector;
import dev.netro.model.TransferNode;
import dev.netro.routing.RoutingEngine;
import org.bukkit.World;
import org.bukkit.entity.Minecart;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Single place for the unified flow when a detector sees a cart:
 * (1) No-dest/not in DB → add cart, set destination if needed (or remove if no terminals).
 * (2) Reconcile this cart's segment → clear from all segments (one segment per cart).
 * (3) Trim ghosts → remove from DB any carts in segment_occupancy that no longer exist in the world.
 * After this, the caller runs collision prevention and detector logic (station loop, node/junction loop).
 *
 * Repos and routing are injected so there is a single source of truth (e.g. DetectorRailHandler's instances).
 */
public class UnifiedCartSeenFlow {

    public record Result(boolean ranNoDestAtStart) {}

    private final NetroPlugin plugin;
    private final CartRepository cartRepo;
    private final TransferNodeRepository nodeRepo;
    private final JunctionRepository junctionRepo;
    private final RoutingEngine routing;

    /** @param plugin for scheduler and world lookup (despawn callback). Repos and routing are shared with the caller (single source of truth). */
    public UnifiedCartSeenFlow(NetroPlugin plugin, CartRepository cartRepo, TransferNodeRepository nodeRepo,
                              JunctionRepository junctionRepo, RoutingEngine routing) {
        this.plugin = plugin;
        this.cartRepo = cartRepo;
        this.nodeRepo = nodeRepo;
        this.junctionRepo = junctionRepo;
        this.routing = routing;
    }

    /**
     * Run the unified flow. Call from the detector async block before the station and node/junction loops.
     *
     * @param existingCartUuids set of cart UUIDs that exist as entities in the world (collected on main thread).
     * @return result with ranNoDestAtStart so the caller can skip re-running the no-dest rule in the station ROUTE loop.
     */
    public Result run(
        String cartUuid,
        World world,
        Minecart cart,
        List<StationDetector> stationDetectors,
        List<Detector> detectors,
        Set<String> existingCartUuids
    ) {
        if (world == null || cart == null) {
            return new Result(false);
        }
        // 1) Ensure cart in DB; if no destination (or not in DB), apply no-destination rule from any detector with station context.
        if (cartRepo.find(cartUuid).isEmpty()) {
            cartRepo.setDestination(cartUuid, null, null);
        }
        String currentStationId = resolveCurrentStationId(stationDetectors, detectors);
        boolean ranNoDestAtStart = false;
        if (currentStationId != null) {
            Optional<Map<String, Object>> cartDataForNoDest = cartRepo.find(cartUuid);
            boolean noDest = cartDataForNoDest.isEmpty()
                || cartDataForNoDest.get().get("destination_address") == null
                || "".equals(cartDataForNoDest.get().get("destination_address"));
            if (noDest) {
                ranNoDestAtStart = true;
                String worldNameForDespawn = world.getName();
                java.util.UUID cartUuidObj = cart.getUniqueId();
                routing.applyNoDestinationRule(cartUuid, currentStationId, () ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        World w = plugin.getServer().getWorld(worldNameForDespawn);
                        if (w != null) {
                            for (org.bukkit.entity.Entity e : w.getEntities()) {
                                if (e.getUniqueId().equals(cartUuidObj)) {
                                    e.remove();
                                    break;
                                }
                            }
                        }
                    }));
            }
        }
        // 2) Reconcile this cart's segment: a cart can only be on one segment at a time.
        cartRepo.clearSegmentOccupancyForCart(cartUuid);
        // 3) Trim carts in segment_occupancy that no longer exist in the world.
        for (String uuid : cartRepo.listCartUuidsInSegmentOccupancy()) {
            if (!existingCartUuids.contains(uuid)) {
                routing.onCartRemoved(uuid);
                cartRepo.deleteCart(uuid);
            }
        }
        return new Result(ranNoDestAtStart);
    }

    private String resolveCurrentStationId(List<StationDetector> stationDetectors, List<Detector> detectors) {
        if (stationDetectors != null && !stationDetectors.isEmpty()) {
            return stationDetectors.get(0).getStationId();
        }
        if (detectors == null) return null;
        for (Detector d : detectors) {
            if (d.getNodeId() != null) {
                Optional<TransferNode> node = nodeRepo.findById(d.getNodeId());
                if (node.isPresent()) return node.get().getStationId();
            }
            if (d.getJunctionId() != null) {
                Optional<Junction> j = junctionRepo.findById(d.getJunctionId());
                if (j.isPresent()) {
                    Optional<TransferNode> nodeA = nodeRepo.findById(j.get().getNodeAId());
                    if (nodeA.isPresent()) return nodeA.get().getStationId();
                }
            }
        }
        return null;
    }
}
