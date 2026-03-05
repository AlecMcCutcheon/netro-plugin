package dev.netro.chunk;

import dev.netro.database.CartRepository;
import dev.netro.database.DetectorRepository;
import dev.netro.database.StationRepository;
import dev.netro.model.BlockPos;
import dev.netro.model.Station;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.entity.Minecart;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps chunks loaded for detectors (transfer, terminal) and for "managed" carts (carts in cart_segments).
 * A cart becomes managed the first time any detector sees it (UnifiedCartSeenFlow adds it to the DB),
 * so chunk loading applies as soon as a cart is detected. Uses Paper's addPluginChunkTicket/removePluginChunkTicket
 * when available; no-op on Spigot without Paper. On enable: loads all stations and detectors from DB.
 * Every 20 ticks, carts in cart_segments get current chunk + chunk ahead loaded; when removed from DB we stop.
 */
public class ChunkLoadService {

    private static final int CART_CHUNK_INTERVAL_TICKS = 20;
    private static final double VELOCITY_EPSILON = 1e-6;

    private final Plugin plugin;
    private final StationRepository stationRepo;
    private final DetectorRepository detectorRepo;
    private final CartRepository cartRepo;

    private final Map<ChunkKey, Integer> refCount = new HashMap<>();
    /** Chunks we added for each cart (so we can remove when cart moves or is removed from DB). */
    private final Map<String, Set<ChunkKey>> cartChunks = new HashMap<>();
    private BukkitTask cartTask;
    private Method addTicketMethod;
    private Method removeTicketMethod;

    public ChunkLoadService(Plugin plugin,
                            StationRepository stationRepo,
                            DetectorRepository detectorRepo,
                            CartRepository cartRepo) {
        this.plugin = plugin;
        this.stationRepo = stationRepo;
        this.detectorRepo = detectorRepo;
        this.cartRepo = cartRepo;
        try {
            addTicketMethod = Chunk.class.getMethod("addPluginChunkTicket", Plugin.class);
            removeTicketMethod = Chunk.class.getMethod("removePluginChunkTicket", Plugin.class);
        } catch (NoSuchMethodException e) {
            plugin.getLogger().warning("Chunk loading disabled: Paper addPluginChunkTicket not found (use Paper for chunk loading).");
        }
    }

    private record ChunkKey(String world, int cx, int cz) {}

    public void loadAllFromDatabase() {
        if (addTicketMethod == null) return;
        for (Station s : stationRepo.findAll())
            addChunksForBlock(s.getWorld(), s.getSignX(), s.getSignZ());
        for (BlockPos p : detectorRepo.listAllRailPositions())
            addChunksForBlock(p.world(), p.x(), p.z());
        plugin.getLogger().info("[Netro] Chunk loading: applied to all stations and transfer/terminal detectors from DB.");
    }

    public void unloadAll() {
        if (cartTask != null) {
            cartTask.cancel();
            cartTask = null;
        }
        for (String cartUuid : List.copyOf(cartChunks.keySet()))
            removeChunksForCart(cartUuid);
        Set<ChunkKey> toRemove = new HashSet<>(refCount.keySet());
        for (ChunkKey key : toRemove) {
            while (refCount.getOrDefault(key, 0) > 0)
                removeChunk(key.world(), key.cx(), key.cz());
        }
    }

    /** Call when a new detector/station is registered (optional; loadAllFromDatabase already covers existing). */
    public void addChunksForBlock(String worldName, int blockX, int blockZ) {
        int cx = blockX >> 4;
        int cz = blockZ >> 4;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                addChunk(worldName, cx + dx, cz + dz);
    }

    /** Call when a detector/station is removed (optional). */
    public void removeChunksForBlock(String worldName, int blockX, int blockZ) {
        int cx = blockX >> 4;
        int cz = blockZ >> 4;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                removeChunk(worldName, cx + dx, cz + dz);
    }

    /** Starts the cart chunk task only if there are carts in the DB. Call on plugin enable and when a cart is first added. */
    public void ensureCartTaskRunning() {
        if (addTicketMethod == null || cartTask != null) return;
        if (cartRepo.listAllCartUuids().isEmpty()) return;
        cartTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickCartChunks, CART_CHUNK_INTERVAL_TICKS, CART_CHUNK_INTERVAL_TICKS);
    }

    private void tickCartChunks() {
        List<String> cartUuids = cartRepo.listAllCartUuids();
        if (cartUuids.isEmpty()) {
            if (cartTask != null) {
                cartTask.cancel();
                cartTask = null;
            }
            return;
        }
        Set<String> current = new HashSet<>(cartUuids);
        for (String key : List.copyOf(cartChunks.keySet())) {
            if (!current.contains(key))
                removeChunksForCart(key);
        }
        Map<String, Minecart> uuidToCart = collectAllMinecartsByUuid();
        for (String cartUuid : cartUuids) {
            Minecart cart = uuidToCart.get(cartUuid);
            if (cart == null || !cart.isValid()) {
                removeChunksForCart(cartUuid);
                continue;
            }
            Location loc = cart.getLocation();
            Vector vel = cart.getVelocity();
            int cx = loc.getBlockX() >> 4;
            int cz = loc.getBlockZ() >> 4;
            int aheadCx = cx;
            int aheadCz = cz;
            double vx = vel.getX();
            double vz = vel.getZ();
            if (Math.abs(vx) > VELOCITY_EPSILON || Math.abs(vz) > VELOCITY_EPSILON) {
                if (Math.abs(vx) >= Math.abs(vz))
                    aheadCx += (int) Math.signum(vx);
                else
                    aheadCz += (int) Math.signum(vz);
            }
            String worldName = loc.getWorld() != null ? loc.getWorld().getName() : null;
            if (worldName == null) continue;
            Set<ChunkKey> newChunks = new HashSet<>();
            newChunks.add(new ChunkKey(worldName, cx, cz));
            newChunks.add(new ChunkKey(worldName, aheadCx, aheadCz));
            Set<ChunkKey> old = cartChunks.put(cartUuid, newChunks);
            if (old != null)
                for (ChunkKey k : old)
                    if (!newChunks.contains(k)) removeChunk(k.world(), k.cx(), k.cz());
            for (ChunkKey k : newChunks)
                addChunk(k.world(), k.cx(), k.cz());
        }
    }

    private void removeChunksForCart(String cartUuid) {
        Set<ChunkKey> old = cartChunks.remove(cartUuid);
        if (old != null)
            for (ChunkKey k : old)
                removeChunk(k.world(), k.cx(), k.cz());
    }

    private void addChunk(String worldName, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(worldName, chunkX, chunkZ);
        refCount.merge(key, 1, Integer::sum);
        if (refCount.get(key) == 1)
            applyChunkTicket(worldName, chunkX, chunkZ, true);
    }

    private void removeChunk(String worldName, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(worldName, chunkX, chunkZ);
        int prev = refCount.getOrDefault(key, 0);
        if (prev <= 0) return;
        refCount.put(key, prev - 1);
        if (prev == 1) {
            refCount.remove(key);
            applyChunkTicket(worldName, chunkX, chunkZ, false);
        }
    }

    private void applyChunkTicket(String worldName, int chunkX, int chunkZ, boolean add) {
        if (addTicketMethod == null || removeTicketMethod == null) return;
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) return;
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        try {
            if (add)
                addTicketMethod.invoke(chunk, plugin);
            else
                removeTicketMethod.invoke(chunk, plugin);
        } catch (Exception e) {
            plugin.getLogger().warning("Chunk ticket " + (add ? "add" : "remove") + " failed: " + e.getMessage());
        }
    }

    /** One pass over all worlds and minecarts; use for multiple lookups in the same tick. */
    private static Map<String, Minecart> collectAllMinecartsByUuid() {
        Map<String, Minecart> out = new HashMap<>();
        for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds()) {
            for (Minecart cart : w.getEntitiesByClass(Minecart.class)) {
                out.put(cart.getUniqueId().toString(), cart);
            }
        }
        return out;
    }
}
