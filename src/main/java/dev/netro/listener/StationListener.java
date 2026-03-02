package dev.netro.listener;

import dev.netro.NetroPlugin;
import dev.netro.database.StationRepository;
import dev.netro.model.Station;
import dev.netro.util.AddressHelper;
import dev.netro.util.SignColors;
import dev.netro.util.SignTextHelper;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * Handles station sign creation: [Station] on line 1 and name on line 2;
 * creates or finds station at block and sets line 3 to address.
 */
public class StationListener implements Listener {

    private final NetroPlugin plugin;
    private final StationRepository stationRepo;

    public StationListener(NetroPlugin plugin) {
        this.plugin = plugin;
        this.stationRepo = new StationRepository(plugin.getDatabase());
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        String line0 = SignTextHelper.readSignLine(event.getLine(0));
        if (!line0.equalsIgnoreCase("[Station]")) return;

        String name = SignTextHelper.readSignLine(event.getLine(1));
        if (name.isEmpty()) return;

        Block block = event.getBlock();
        String worldName = block.getWorld().getName();
        int x = block.getX(), y = block.getY(), z = block.getZ();

        Optional<Station> existing = stationRepo.findAtBlock(worldName, x, y, z);
        if (existing.isPresent()) {
            String address = existing.get().getAddress();
            SignColors.applyStationSign(event, name, address);
            event.getPlayer().sendMessage("Station already at this sign; address shown on sign.");
            return;
        }
        if (stationRepo.findByName(name).isPresent()) {
            event.getPlayer().sendMessage("A station with that name already exists.");
            return;
        }

        int mainnet = AddressHelper.mainnetFromX(x);
        int cluster = AddressHelper.clusterFromX(x);
        int localnet = AddressHelper.localnetFromX(x);
        int nextIndex = stationRepo.countStationsInLocalnet(worldName, mainnet, cluster, localnet) + 1;
        String address = AddressHelper.stationAddress(x, nextIndex);

        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Station station = new Station(id, name, address, worldName, x, y, z, now);
        stationRepo.insert(station);
        if (plugin.getChunkLoadService() != null)
            plugin.getChunkLoadService().addChunksForBlock(station.getWorld(), station.getSignX(), station.getSignZ());
        SignColors.applyStationSign(event, name, address);
        event.getPlayer().sendMessage("Station \"" + name + "\" created with address " + address + ".");
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockState state = block.getState();
        if (!(state instanceof Sign)) return;

        String worldName = block.getWorld().getName();
        int x = block.getX(), y = block.getY(), z = block.getZ();
        stationRepo.findAtBlock(worldName, x, y, z).ifPresent(station -> {
            if (plugin.getChunkLoadService() != null)
                plugin.getChunkLoadService().removeChunksForBlock(station.getWorld(), station.getSignX(), station.getSignZ());
            stationRepo.deleteById(station.getId());
            event.getPlayer().sendMessage("Station removed (sign broken). Set up again with a new sign or /station create.");
        });
    }
}
