package dev.netro.api;

import dev.netro.NetroPlugin;
import dev.netro.database.CartRepository;
import dev.netro.database.ControllerRepository;
import dev.netro.database.DetectorRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Station;
import dev.netro.model.TransferNode;
import dev.netro.routing.RoutingEngine;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Public API for other plugins or internal use. Exposes read-only and safe operations.
 */
public class NetroAPI {

    private final NetroPlugin plugin;
    private final StationRepository stationRepo;
    private final TransferNodeRepository nodeRepo;
    private final CartRepository cartRepo;
    private final DetectorRepository detectorRepo;
    private final ControllerRepository controllerRepo;

    public NetroAPI(NetroPlugin plugin) {
        this.plugin = plugin;
        this.stationRepo = new StationRepository(plugin.getDatabase());
        this.nodeRepo = new TransferNodeRepository(plugin.getDatabase());
        this.cartRepo = new CartRepository(plugin.getDatabase());
        this.detectorRepo = new DetectorRepository(plugin.getDatabase());
        this.controllerRepo = new ControllerRepository(plugin.getDatabase());
    }

    public Optional<Station> getStationById(String id) {
        return stationRepo.findById(id);
    }

    public Optional<Station> getStationByAddress(String address) {
        return stationRepo.findByAddress(address);
    }

    /** Resolve by name or address. */
    public Optional<Station> getStation(String nameOrAddress) {
        return stationRepo.findByName(nameOrAddress).or(() -> stationRepo.findByAddress(nameOrAddress));
    }

    public List<Station> getAllStations() {
        return stationRepo.findAll();
    }

    /** Stations whose address starts with mainnetIndex (e.g. 2 for mainnet 2). */
    public List<Station> getStationsInMainnet(int mainnetIndex) {
        String prefix = mainnetIndex + ".";
        return stationRepo.findAll().stream().filter(s -> s.getAddress().startsWith(prefix)).toList();
    }

    public List<TransferNode> getAllTransferNodes() {
        return stationRepo.findAll().stream()
            .flatMap(s -> nodeRepo.findByStation(s.getId()).stream())
            .distinct()
            .toList();
    }

    public List<TransferNode> getTransferNodesForStation(String stationId) {
        return nodeRepo.findByStation(stationId);
    }

    /** Number of detectors registered for this transfer node. */
    public int getDetectorCount(String nodeId) {
        return detectorRepo.findByNodeId(nodeId).size();
    }

    /** Number of controllers registered for this transfer node. */
    public int getControllerCount(String nodeId) {
        return controllerRepo.findByNodeId(nodeId).size();
    }

    public int getFreeSlotCount(String nodeId) {
        return nodeRepo.countFreeSlots(nodeId);
    }

    public List<TransferNode> getTerminals(String stationId) {
        return nodeRepo.findByStation(stationId).stream().filter(TransferNode::isTerminal).toList();
    }

    public int getAvailableTerminalSlots(String stationId) {
        return nodeRepo.findByStation(stationId).stream()
            .filter(TransferNode::isTerminal)
            .mapToInt(n -> nodeRepo.countFreeSlots(n.getId()))
            .sum();
    }

    public Optional<Map<String, Object>> getCartData(String cartUuid) {
        return cartRepo.find(cartUuid);
    }

    public RoutingEngine getRoutingEngine() {
        return plugin.getRoutingEngine();
    }
}
