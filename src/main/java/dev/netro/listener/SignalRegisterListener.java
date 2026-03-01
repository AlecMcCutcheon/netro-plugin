package dev.netro.listener;

import dev.netro.NetroPlugin;
import dev.netro.database.LecternRepository;
import dev.netro.database.StationRepository;
import dev.netro.model.Station;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * Handles /signal register workflow: right-click lectern then type label in chat.
 */
public class SignalRegisterListener implements Listener {

    private final NetroPlugin plugin;
    private final StationRepository stationRepo;
    private final LecternRepository lecternRepo;

    public SignalRegisterListener(NetroPlugin plugin) {
        this.plugin = plugin;
        this.stationRepo = new StationRepository(plugin.getDatabase());
        this.lecternRepo = new LecternRepository(plugin.getDatabase());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.LECTERN) return;

        UUID id = event.getPlayer().getUniqueId();
        NetroPlugin.SignalRegisterState state = plugin.getSignalRegisterPending().get(id);
        if (state == null || state.step != NetroPlugin.SignalRegisterState.Step.AWAITING_LECTERN) return;

        event.setCancelled(true);
        String worldName = block.getWorld().getName();
        int bx = block.getX(), by = block.getY(), bz = block.getZ();
        Optional<Station> nearest = stationRepo.findAll().stream()
            .filter(s -> s.getWorld().equals(worldName))
            .min((a, b) -> Long.compare(
                sq(bx - a.getSignX()) + sq(by - a.getSignY()) + sq(bz - a.getSignZ()),
                sq(bx - b.getSignX()) + sq(by - b.getSignY()) + sq(bz - b.getSignZ())));
        if (nearest.isEmpty()) {
            event.getPlayer().sendMessage("No station in this world. Create a station first.");
            plugin.getSignalRegisterPending().remove(id);
            return;
        }
        plugin.getSignalRegisterPending().put(id,
            new NetroPlugin.SignalRegisterState(
                NetroPlugin.SignalRegisterState.Step.AWAITING_CHAT,
                nearest.get().getId(), worldName, bx, by, bz));
        event.getPlayer().sendMessage("Lectern at " + bx + "," + by + "," + bz + " → station \"" + nearest.get().getName() + "\". Type the label in chat.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        NetroPlugin.SignalRegisterState state = plugin.getSignalRegisterPending().get(id);
        if (state == null || state.step != NetroPlugin.SignalRegisterState.Step.AWAITING_CHAT) return;

        event.setCancelled(true);
        String label = event.getMessage().strip();
        if (label.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> player.sendMessage("Label cannot be empty. Type the lectern label in chat."));
            return;
        }
        plugin.getSignalRegisterPending().remove(id);
        String stationId = state.stationId;
        String world = state.world;
        int x = state.x, y = state.y, z = state.z;
        if (lecternRepo.isBlockUsedByLectern(world, x, y, z)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> player.sendMessage("That block is already registered as a lectern."));
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            lecternRepo.insertLectern(UUID.randomUUID().toString(), stationId, label, x, y, z);
            player.sendMessage("Lectern \"" + label + "\" registered at " + x + "," + y + "," + z + ".");
        });
    }

    private static long sq(int n) { return (long) n * n; }
}
