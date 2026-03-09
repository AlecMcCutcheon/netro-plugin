package dev.netro.chunk;

import dev.netro.database.CartRepository;
import dev.netro.database.DetectorRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodePortalRepository;
import dev.netro.database.Database;
import dev.netro.model.BlockPos;
import dev.netro.model.Station;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.entity.Minecart;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.sql.Connection;
import java.sql.SQLException;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
 *
 * <p><b>How long are chunks loaded?</b> As long as we hold a plugin chunk ticket. There is no time limit:
 * we add a ticket when something needs the chunk and remove it when nothing does (refCount → 0).
 *
 * <p><b>When do we unload (remove ticket)?</b> We remove the ticket when our refCount for that chunk hits zero:
 * <ul>
 *   <li><b>Cart chunks:</b> Every CART_CHUNK_INTERVAL_TICKS we recompute "current + ahead" per cart.
 *       Chunks the cart no longer needs (old position or old ahead) get one ref removed; when no cart
 *       and no detector/station needs that chunk, we remove the ticket and the server can unload it
 *       (subject to its normal chunk GC). So cart chunks are unloaded shortly after the cart leaves.</li>
 *   <li><b>Cart removed from DB:</b> All chunks for that cart are released (tickets removed when refCount → 0).</li>
 *   <li><b>Detector/station removed:</b> The 3×3 around that block is released.</li>
 *   <li><b>Plugin disable:</b> unloadAll() removes every ticket.</li>
 * </ul>
 * We do not check for players; if we hold a ticket the chunk stays loaded. When we remove the ticket,
 * the server may unload the chunk after its usual delay if no players are nearby.
 *
 * <p>Every CART_CHUNK_INTERVAL_TICKS, carts get only current chunk + one chunk ahead in movement direction.
 * Portal (other-dimension) chunk lookup runs less often. We skip current/ahead tickets when a player is
 * in the cart (the player already keeps those chunks loaded).
 */
public class ChunkLoadService {

    /** Run cart chunk updates less often to reduce lag (only current + ahead chunk per cart). */
    private static final int CART_CHUNK_INTERVAL_TICKS = 40;
    private static final double VELOCITY_EPSILON = 1e-6;
    /** Run portal (other-dimension) chunk lookup every this many cart-tick runs to reduce DB/scan cost. */
    private static final int PORTAL_CHUNK_RUN_EVERY_N = 4;

    private int cartTickRunCount = 0;

    private final Plugin plugin;
    private final Database database;
    private final StationRepository stationRepo;
    private final DetectorRepository detectorRepo;
    private final CartRepository cartRepo;
    private final TransferNodePortalRepository portalRepo;

    private final Map<ChunkKey, Integer> refCount = new HashMap<>();
    /** Chunks we actually added a plugin ticket for (so we only remove when we had added; skip add when already loaded). */
    private final Set<ChunkKey> chunksWithTicket = new HashSet<>();
    /** Chunks we added for each cart (so we can remove when cart moves or is removed from DB). */
    private final Map<String, Set<ChunkKey>> cartChunks = new HashMap<>();
    private BukkitTask cartTask;
    private Method addTicketMethod;
    private Method removeTicketMethod;

    public ChunkLoadService(Plugin plugin,
                            Database database,
                            StationRepository stationRepo,
                            DetectorRepository detectorRepo,
                            CartRepository cartRepo,
                            TransferNodePortalRepository portalRepo) {
        this.plugin = plugin;
        this.database = database;
        this.stationRepo = stationRepo;
        this.detectorRepo = detectorRepo;
        this.cartRepo = cartRepo;
        this.portalRepo = portalRepo;
        try {
            addTicketMethod = Chunk.class.getMethod("addPluginChunkTicket", Plugin.class);
            removeTicketMethod = Chunk.class.getMethod("removePluginChunkTicket", Plugin.class);
        } catch (NoSuchMethodException e) {
            plugin.getLogger().warning("Chunk loading disabled: Paper addPluginChunkTicket not found (use Paper for chunk loading).");
        }
    }

    private record ChunkKey(String world, int cx, int cz) {}

    /** One cart's region for async portal chunk lookup (same order as cart list). */
    private record PortalRegion(String cartUuid, String worldName, int cx, int cz, int aheadCx, int aheadCz) {}

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

    /** Runs on main thread each interval; fetches cart list async then applies chunk updates on main. */
    private void tickCartChunks() {
        database.runAsyncRead(conn -> {
            try {
                return cartRepo.listAllCartUuids(conn);
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        }, this::applyCartChunkList);
    }

    /** Called on main thread with cart UUIDs from DB. Updates chunk tickets for current + ahead per cart. */
    private void applyCartChunkList(List<String> cartUuids) {
        if (cartUuids == null || cartUuids.isEmpty()) {
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
        Set<String> needed = new HashSet<>(cartUuids);
        Map<String, Minecart> uuidToCart = collectMinecartsByUuid(needed);
        boolean runPortalThisTick = (++cartTickRunCount % PORTAL_CHUNK_RUN_EVERY_N == 0);
        List<PortalRegion> portalRegions = new ArrayList<>();
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
            boolean hasPlayer = hasPlayerPassenger(cart);
            if (!hasPlayer) {
                newChunks.add(new ChunkKey(worldName, cx, cz));
                newChunks.add(new ChunkKey(worldName, aheadCx, aheadCz));
            }
            if (runPortalThisTick)
                portalRegions.add(new PortalRegion(cartUuid, worldName, cx, cz, aheadCx, aheadCz));
            Set<ChunkKey> old = cartChunks.put(cartUuid, newChunks);
            if (old != null)
                for (ChunkKey k : old)
                    if (!newChunks.contains(k)) removeChunk(k.world(), k.cx(), k.cz());
            for (ChunkKey k : newChunks)
                addChunk(k.world(), k.cx(), k.cz());
        }
        if (runPortalThisTick && !portalRegions.isEmpty()) {
            database.runAsyncRead(conn -> computePortalChunksAsync(conn, portalRegions), result -> applyPortalChunksOnMain(portalRegions, result));
        }
    }

    /** Runs on DB thread: for each region, compute other-dimension portal chunk keys (world-exists filter applied on main). */
    private List<Set<ChunkKey>> computePortalChunksAsync(Connection conn, List<PortalRegion> regions) throws SQLException {
        List<Set<ChunkKey>> out = new ArrayList<>(regions.size());
        for (PortalRegion r : regions) {
            Set<ChunkKey> set = new HashSet<>();
            int minCx = Math.min(r.cx(), r.aheadCx()) - 1;
            int maxCx = Math.max(r.cx(), r.aheadCx()) + 2;
            int minCz = Math.min(r.cz(), r.aheadCz()) - 1;
            int maxCz = Math.max(r.cz(), r.aheadCz()) + 2;
            int minX = minCx << 4;
            int maxX = (maxCx << 4) + 15;
            int minZ = minCz << 4;
            int maxZ = (maxCz << 4) + 15;
            Set<String> nodeIds = portalRepo.getNodeIdsWithBlocksInRegion(conn, r.worldName(), minX, maxX, 0, 255, minZ, maxZ);
            for (String nodeId : nodeIds) {
                List<TransferNodePortalRepository.BlockPos> side0 = portalRepo.getBlocks(conn, nodeId, TransferNodePortalRepository.SIDE_SAME_DIMENSION);
                List<TransferNodePortalRepository.BlockPos> side1 = portalRepo.getBlocks(conn, nodeId, TransferNodePortalRepository.SIDE_OTHER_DIMENSION);
                List<TransferNodePortalRepository.BlockPos> otherSide;
                if (!side0.isEmpty() && !side1.isEmpty()) {
                    boolean side0InCartWorld = side0.get(0).world().equals(r.worldName());
                    boolean side1InCartWorld = side1.get(0).world().equals(r.worldName());
                    if (side0InCartWorld && !side1InCartWorld) otherSide = side1;
                    else if (!side0InCartWorld && side1InCartWorld) otherSide = side0;
                    else continue;
                } else continue;
                for (TransferNodePortalRepository.BlockPos p : otherSide) {
                    int pcx = p.x() >> 4;
                    int pcz = p.z() >> 4;
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dz = -1; dz <= 1; dz++)
                            set.add(new ChunkKey(p.world(), pcx + dx, pcz + dz));
                }
            }
            out.add(set);
        }
        return out;
    }

    /** Called on main thread with async result: merge portal chunk sets into cart chunks and add tickets (skip worlds that are not loaded). */
    private void applyPortalChunksOnMain(List<PortalRegion> portalRegions, List<Set<ChunkKey>> result) {
        if (result == null || result.size() != portalRegions.size()) return;
        for (int i = 0; i < portalRegions.size(); i++) {
            String cartUuid = portalRegions.get(i).cartUuid();
            Set<ChunkKey> existing = cartChunks.get(cartUuid);
            if (existing == null) continue;
            for (ChunkKey k : result.get(i)) {
                if (plugin.getServer().getWorld(k.world()) == null) continue;
                if (existing.add(k))
                    addChunk(k.world(), k.cx(), k.cz());
            }
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
            applyChunkTicket(key, true);
    }

    private void removeChunk(String worldName, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(worldName, chunkX, chunkZ);
        int prev = refCount.getOrDefault(key, 0);
        if (prev <= 0) return;
        refCount.put(key, prev - 1);
        if (prev == 1) {
            refCount.remove(key);
            applyChunkTicket(key, false);
        }
    }

    private void applyChunkTicket(ChunkKey key, boolean add) {
        if (addTicketMethod == null || removeTicketMethod == null) return;
        World world = plugin.getServer().getWorld(key.world());
        if (world == null) return;
        int chunkX = key.cx();
        int chunkZ = key.cz();
        if (add) {
            if (world.isChunkLoaded(chunkX, chunkZ)) return;
            try {
                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                addTicketMethod.invoke(chunk, plugin);
                chunksWithTicket.add(key);
            } catch (Exception e) {
                plugin.getLogger().warning("Chunk ticket add failed: " + e.getMessage());
            }
        } else {
            if (!chunksWithTicket.remove(key)) return;
            try {
                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                removeTicketMethod.invoke(chunk, plugin);
            } catch (Exception e) {
                plugin.getLogger().warning("Chunk ticket remove failed: " + e.getMessage());
            }
        }
    }

    /** True if the cart has at least one player passenger (player keeps chunk loaded; we skip current/ahead tickets). */
    private static boolean hasPlayerPassenger(Minecart cart) {
        if (cart.getPassengers().isEmpty()) return false;
        for (org.bukkit.entity.Entity e : cart.getPassengers())
            if (e instanceof Player) return true;
        return false;
    }

    /** Collect only minecarts whose UUID is in needed; stop once all found. */
    private static Map<String, Minecart> collectMinecartsByUuid(Set<String> needed) {
        Map<String, Minecart> out = new HashMap<>();
        Set<String> remaining = new HashSet<>(needed);
        for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds()) {
            if (remaining.isEmpty()) break;
            for (Minecart cart : w.getEntitiesByClass(Minecart.class)) {
                String id = cart.getUniqueId().toString();
                if (remaining.remove(id)) {
                    out.put(id, cart);
                    if (remaining.isEmpty()) break;
                }
            }
        }
        return out;
    }
}
