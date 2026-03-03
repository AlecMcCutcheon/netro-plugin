package dev.netro.detector;

import dev.netro.NetroPlugin;
import dev.netro.database.ControllerRepository;
import dev.netro.database.DetectorRepository;
import dev.netro.database.RuleRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Controller;
import dev.netro.model.Detector;
import dev.netro.model.Station;
import dev.netro.model.TransferNode;
import dev.netro.util.SignTextHelper;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Auto-registers [Detector], [Transfer], [Terminal] (detectors) and [Controller] when a sign is placed on a copper bulb.
 * Unregisters when the sign or copper bulb is broken.
 * [Transfer] = boundary detector for a transfer node (Line 2 = Station:Node, non-terminal).
 * [Terminal] = boundary detector for a terminal (Line 2 = Station:Node, terminal).
 */
public class DetectorControllerSignListener implements Listener {

    private static final Set<String> DETECTOR_LINE1 = Set.of("[Detector]", "[Transfer]", "[Terminal]");
    private static final Set<String> DETECTOR_ROLES = Set.of("ENTRY", "READY", "CLEAR");
    /** [Transfer] signs only allow ENTRY and CLEAR. */
    private static final Set<String> TRANSFER_ROLES = Set.of("ENTRY", "CLEAR");
    /** [Terminal] allows ENTRY, CLEAR, and at most one READY detector per terminal (single slot). */
    private static final Set<String> TERMINAL_ROLES = Set.of("ENTRY", "CLEAR", "READY");
    /** Sentinel for parseRule: allow any role of the form RULE:<number> (e.g. RULE:0, RULE:15). */
    private static final String RULE_PATTERN = "RULE_*";
    private static final Set<String> CONTROLLER_ROLES = Set.of("RELEASE", RULE_PATTERN);
    private static final Set<String> DIRECTIONS = Set.of("LEFT", "RIGHT", "L", "R");

    /** Sign line colors: line 1 = type (bold), line 2 = target, lines 3–4 = rules. */
    private static final String BOLD = "§l";
    private static final String COLOR_DETECTOR = "§b";   // aqua – generic detector
    private static final String COLOR_TRANSFER = "§a";   // green – transfer node
    private static final String COLOR_TERMINAL = "§e";   // yellow – terminal
    private static final String COLOR_CONTROLLER = "§6"; // gold – controller
    private static final String COLOR_TARGET = "§f";     // white – target (Station:Node / station name)
    private static final String COLOR_RULES = "§7";     // gray – signal rules

    /** 3-letter shorthand to canonical role. No first-letter collisions. */
    private static final Map<String, String> ROLE_SHORTHAND = Map.ofEntries(
        Map.entry("ENT", "ENTRY"), Map.entry("REA", "READY"), Map.entry("CLE", "CLEAR"),
        Map.entry("REL", "RELEASE")
    );

    private final DetectorRepository detectorRepo;
    private final ControllerRepository controllerRepo;
    private final StationRepository stationRepo;
    private final TransferNodeRepository nodeRepo;
    private final RuleRepository ruleRepo;
    private final NetroPlugin plugin;

    public DetectorControllerSignListener(NetroPlugin plugin) {
        this.plugin = plugin;
        var db = plugin.getDatabase();
        this.detectorRepo = new DetectorRepository(db);
        this.controllerRepo = new ControllerRepository(db);
        this.stationRepo = new StationRepository(db);
        this.nodeRepo = new TransferNodeRepository(db);
        this.ruleRepo = new RuleRepository(db);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Block signBlock = event.getBlock();
        Block attached = getAttachedBlock(signBlock);
        if (attached == null || !CopperBulbHelper.isCopperBulb(attached)) return;

        String world = attached.getWorld().getName();
        int bx = attached.getX(), by = attached.getY(), bz = attached.getZ();
        Player player = event.getPlayer();

        // On any sign edit on a copper bulb, clear existing detector/controller so edits (or type change) don't leave stale registrations
        boolean hadDetector = detectorRepo.findByBlock(world, bx, by, bz)
            .map(d -> { if (plugin.getChunkLoadService() != null) plugin.getChunkLoadService().removeChunksForBlock(d.getWorld(), d.getRailX(), d.getRailZ()); detectorRepo.deleteById(d.getId()); return true; }).orElse(false);
        boolean hadController = controllerRepo.findByBlock(world, bx, by, bz)
            .map(c -> { controllerRepo.deleteById(c.getId()); return true; }).orElse(false);

        String line0 = SignTextHelper.readSignLine(event.getLine(0));
        boolean willBeDetector = DETECTOR_LINE1.stream().anyMatch(s -> s.equalsIgnoreCase(line0));
        boolean willBeController = line0.equalsIgnoreCase("[Controller]");

        if (hadDetector && !willBeDetector) player.sendMessage("Detector removed.");
        if (hadController && !willBeController) player.sendMessage("Controller removed.");

        if (!willBeDetector && !willBeController) return;

        String name = SignTextHelper.readSignLine(event.getLine(1));
        if (name.isEmpty()) {
            player.sendMessage("Line 2 is empty. Use Station:Node or station name (for [Detector] with ROUTE).");
            return;
        }

        if (willBeDetector) {
            handleDetectorSign(event, attached, name);
        } else {
            handleControllerSign(event, attached, name);
        }
    }

    private void handleDetectorSign(SignChangeEvent event, Block bulb, String name) {
        Player player = event.getPlayer();
        String line0 = SignTextHelper.readSignLine(event.getLine(0));
        Block rail = findAdjacentRail(bulb);
        if (rail == null) {
            player.sendMessage("No rail adjacent to this copper bulb. Place the bulb next to a rail.");
            return;
        }

        ResolvedTarget target = resolveTarget(name);
        if (target.error != null) {
            if ("[Transfer]".equalsIgnoreCase(line0) || "[Terminal]".equalsIgnoreCase(line0)) {
                target = ensureNodeForDetector(name, line0);
                if (target.error != null) {
                    player.sendMessage(target.error);
                    return;
                }
                String kind = "[Terminal]".equalsIgnoreCase(line0) ? "Terminal" : "Transfer node";
                player.sendMessage(kind + " \"" + name + "\" created. Pair from the Rules UI or complete terminal setup as needed; detector registered.");
            } else {
                player.sendMessage(target.error);
                return;
            }
        }
        String detectorTypeError = validateDetectorType(line0, target);
        if (detectorTypeError != null) {
            player.sendMessage(detectorTypeError);
            return;
        }

        Set<String> allowedRoles = "[Transfer]".equalsIgnoreCase(line0) ? TRANSFER_ROLES
            : "[Terminal]".equalsIgnoreCase(line0) ? TERMINAL_ROLES : DETECTOR_ROLES;
        List<String[]> nodeRules = parseRuleLine(SignTextHelper.readSignLine(event.getLine(2)), allowedRoles);
        nodeRules.addAll(parseRuleLine(SignTextHelper.readSignLine(event.getLine(3)), allowedRoles));
        if (nodeRules.isEmpty()) {
            if (isTransferTerminal(line0)) {
                player.sendMessage("[Transfer]: use ENTRY and/or CLEAR. [Terminal]: ENTRY, CLEAR, and optionally READY (one per terminal).");
            } else {
                player.sendMessage("Detector needs at least one rule on lines 3–4: ENT/REA/CLE or ROLE:L/R (e.g. ENT:L CLE:R).");
            }
            return;
        }
        if ("[Terminal]".equalsIgnoreCase(line0) && nodeRulesContainRole(nodeRules, "READY")) {
            boolean alreadyHasReady = detectorRepo.findByNodeId(target.nodeId).stream().anyMatch(d -> d.hasRole("READY"));
            if (alreadyHasReady) {
                player.sendMessage("This terminal already has a detector with READY. Only one READY detector is allowed per terminal (single slot).");
                return;
            }
        }

        String[] rule1 = nodeRules.get(0);
        String[] rule2 = nodeRules.size() > 1 ? nodeRules.get(1) : null;
        String[] rule3 = nodeRules.size() > 2 ? nodeRules.get(2) : null;
        String[] rule4 = nodeRules.size() > 3 ? nodeRules.get(3) : null;

        BlockData data = event.getBlock().getBlockData();
        BlockFace signFacing = data instanceof Directional d ? d.getFacing() : BlockFace.NORTH;

        Detector d = new Detector(
            UUID.randomUUID().toString(),
            target.nodeId,
            bulb.getWorld().getName(),
            bulb.getX(), bulb.getY(), bulb.getZ(),
            rail.getX(), rail.getY(), rail.getZ(),
            signFacing.name(),
            rule1[0], rule1[1],
            rule2 != null ? rule2[0] : null,
            rule2 != null ? rule2[1] : null,
            rule3 != null ? rule3[0] : null,
            rule3 != null ? rule3[1] : null,
            rule4 != null ? rule4[0] : null,
            rule4 != null ? rule4[1] : null
        );
        detectorRepo.insert(d);
        if (plugin.getChunkLoadService() != null) plugin.getChunkLoadService().addChunksForBlock(d.getWorld(), d.getRailX(), d.getRailZ());
        applyDetectorSignColors(event, line0, name, SignTextHelper.readSignLine(event.getLine(2)), SignTextHelper.readSignLine(event.getLine(3)));
        String msg = "Detector registered for " + name + ".";
        if (!"[Detector]".equalsIgnoreCase(line0)) {
            msg = line0 + " detector registered for " + name + ".";
        }
        player.sendMessage(msg);
    }

    private static String colorForLine1(String line1) {
        if (line1 == null) return COLOR_DETECTOR;
        String n = line1.strip();
        if ("[Transfer]".equalsIgnoreCase(n)) return COLOR_TRANSFER;
        if ("[Terminal]".equalsIgnoreCase(n)) return COLOR_TERMINAL;
        if ("[Controller]".equalsIgnoreCase(n)) return COLOR_CONTROLLER;
        return COLOR_DETECTOR;
    }

    private void applyDetectorSignColors(SignChangeEvent event, String line1, String target, String rawLine2, String rawLine3) {
        String c1 = colorForLine1(line1);
        event.setLine(0, c1 + BOLD + SignTextHelper.readSignLine(event.getLine(0)));
        event.setLine(1, COLOR_TARGET + SignTextHelper.readSignLine(event.getLine(1)));
        event.setLine(2, COLOR_RULES + SignTextHelper.readSignLine(rawLine2));
        event.setLine(3, COLOR_RULES + SignTextHelper.readSignLine(rawLine3));
    }

    private void applyControllerSignColors(SignChangeEvent event, String target, String rawLine2, String rawLine3) {
        event.setLine(0, COLOR_CONTROLLER + BOLD + SignTextHelper.readSignLine(event.getLine(0)));
        event.setLine(1, COLOR_TARGET + SignTextHelper.readSignLine(event.getLine(1)));
        event.setLine(2, COLOR_RULES + SignTextHelper.readSignLine(rawLine2));
        event.setLine(3, COLOR_RULES + SignTextHelper.readSignLine(rawLine3));
    }

    private static boolean isTransferTerminal(String line0) {
        return line0 != null && ("[Transfer]".equalsIgnoreCase(line0) || "[Terminal]".equalsIgnoreCase(line0));
    }

    private static boolean nodeRulesContainRole(List<String[]> nodeRules, String role) {
        if (nodeRules == null || role == null) return false;
        for (String[] r : nodeRules) {
            if (r != null && r.length > 0 && role.equals(r[0])) return true;
        }
        return false;
    }

    /**
     * For [Transfer] or [Terminal] detector when the node doesn't exist yet: create transfer node or terminal at the station.
     * Name must be "StationName:NodeName". Station must exist. Returns ResolvedTarget with nodeId set or error.
     */
    private ResolvedTarget ensureNodeForDetector(String name, String line0) {
        ResolvedTarget t = new ResolvedTarget();
        int colon = name.indexOf(':');
        if (colon <= 0 || colon >= name.length() - 1) {
            t.error = "Use Station:Node on line 2 (e.g. Snowy1:Main).";
            return t;
        }
        String stationName = name.substring(0, colon).strip();
        String nodeName = name.substring(colon + 1).strip();
        if (stationName.isEmpty() || nodeName.isEmpty()) {
            t.error = "Station name and node name must both be non-empty (Station:Node).";
            return t;
        }
        Optional<Station> stationOpt = stationRepo.findByNameIgnoreCase(stationName);
        if (stationOpt.isEmpty()) {
            t.error = "Station \"" + stationName + "\" not found. Create the station first by placing a [Station] sign with the station name on line 2.";
            return t;
        }
        String stationId = stationOpt.get().getId();
        if (nodeRepo.findByNameAtStation(stationId, nodeName).isPresent()) {
            t.error = "A node named \"" + nodeName + "\" already exists at " + stationName + ". Use a different name or delete it first.";
            return t;
        }
        if ("[Terminal]".equalsIgnoreCase(line0)) {
            TransferNode node = new TransferNode(UUID.randomUUID().toString(), nodeName);
            node.setStationId(stationId);
            node.setTerminal(true);
            nodeRepo.insert(node);
            int terminalIndex = nodeRepo.countTerminalsAtStation(stationId);
            nodeRepo.setTerminal(node.getId(), terminalIndex);
            nodeRepo.setSetupComplete(node.getId());
            t.nodeId = node.getId();
            ruleRepo.reassignRulesToNode(node.getId(), stationId, nodeName);
            return t;
        }
        TransferNode node = new TransferNode(UUID.randomUUID().toString(), nodeName);
        node.setStationId(stationId);
        nodeRepo.insert(node);
        nodeRepo.setSetupComplete(node.getId());
        t.nodeId = node.getId();
        ruleRepo.reassignRulesToNode(node.getId(), stationId, nodeName);
        return t;
    }

    /** Validates that line 1 detector type matches the resolved target. [Transfer] → transfer node; [Terminal] → terminal. */
    private String validateDetectorType(String line0, ResolvedTarget target) {
        if (line0 == null) return null;
        if ("[Transfer]".equalsIgnoreCase(line0)) {
            if (target.nodeId != null && nodeRepo.findById(target.nodeId).map(TransferNode::isTerminal).orElse(false))
                return "[Transfer] needs a transfer node, not a terminal. Use [Terminal] for terminals.";
            return null;
        }
        if ("[Terminal]".equalsIgnoreCase(line0)) {
            if (target.nodeId != null && !nodeRepo.findById(target.nodeId).map(TransferNode::isTerminal).orElse(false))
                return "[Terminal] needs a terminal. Use [Transfer] for transfer nodes.";
            return null;
        }
        return null;
    }

    private void handleControllerSign(SignChangeEvent event, Block bulb, String name) {
        Player player = event.getPlayer();
        ResolvedTarget target = resolveTarget(name);
        if (target.error != null) {
            player.sendMessage(target.error);
            return;
        }

        List<String[]> nodeRules = parseRuleLine(SignTextHelper.readSignLine(event.getLine(2)), CONTROLLER_ROLES);
        nodeRules.addAll(parseRuleLine(SignTextHelper.readSignLine(event.getLine(3)), CONTROLLER_ROLES));
        if (nodeRules.isEmpty()) {
            player.sendMessage("Controller needs at least one rule on lines 3–4: REL or RULE:N with optional :L/:R.");
            return;
        }

        String[] rule1 = nodeRules.get(0);
        String[] rule2 = nodeRules.size() > 1 ? nodeRules.get(1) : null;
        String[] rule3 = nodeRules.size() > 2 ? nodeRules.get(2) : null;
        String[] rule4 = nodeRules.size() > 3 ? nodeRules.get(3) : null;

        BlockData data = event.getBlock().getBlockData();
        BlockFace signFacing = data instanceof Directional d ? d.getFacing() : BlockFace.NORTH;

        Controller c = new Controller(
            UUID.randomUUID().toString(),
            target.nodeId,
            bulb.getWorld().getName(),
            bulb.getX(), bulb.getY(), bulb.getZ(),
            signFacing.name(),
            rule1[0], rule1[1],
            rule2 != null ? rule2[0] : null,
            rule2 != null ? rule2[1] : null,
            rule3 != null ? rule3[0] : null,
            rule3 != null ? rule3[1] : null,
            rule4 != null ? rule4[0] : null,
            rule4 != null ? rule4[1] : null
        );
        controllerRepo.insert(c);
        applyControllerSignColors(event, name, SignTextHelper.readSignLine(event.getLine(2)), SignTextHelper.readSignLine(event.getLine(3)));
        player.sendMessage("Controller registered for " + name + ".");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String world = block.getWorld().getName();
        int x, y, z;
        if (CopperBulbHelper.isCopperBulb(block)) {
            x = block.getX(); y = block.getY(); z = block.getZ();
        } else if (isSign(block.getType())) {
            Block attached = getAttachedBlock(block);
            if (attached == null || !CopperBulbHelper.isCopperBulb(attached)) return;
            x = attached.getX(); y = attached.getY(); z = attached.getZ();
        } else {
            return;
        }
        Player player = event.getPlayer();
        detectorRepo.findByBlock(world, x, y, z).ifPresent(d -> {
            String nodeId = d.getNodeId();
            if (nodeId != null) {
                for (Detector det : detectorRepo.findByNodeId(nodeId)) {
                    if (plugin.getChunkLoadService() != null)
                        plugin.getChunkLoadService().removeChunksForBlock(det.getWorld(), det.getRailX(), det.getRailZ());
                }
                nodeRepo.deleteNodeAndAllBlockData(nodeId);
                player.sendMessage("Detector removed. Transfer node removed from system; rules kept.");
            } else {
                if (plugin.getChunkLoadService() != null)
                    plugin.getChunkLoadService().removeChunksForBlock(d.getWorld(), d.getRailX(), d.getRailZ());
                detectorRepo.deleteById(d.getId());
                player.sendMessage("Detector removed.");
            }
        });
        controllerRepo.findByBlock(world, x, y, z).ifPresent(c -> {
            controllerRepo.deleteById(c.getId());
            player.sendMessage("Controller removed.");
        });
    }

    private Block getAttachedBlock(Block signBlock) {
        BlockData data = signBlock.getBlockData();
        if (!(data instanceof Directional d)) return null;
        return signBlock.getRelative(d.getFacing().getOppositeFace());
    }

    private static final Set<Material> RAILS = Set.of(
        Material.RAIL, Material.POWERED_RAIL, Material.ACTIVATOR_RAIL,
        Material.DETECTOR_RAIL
    );

    private Block findAdjacentRail(Block bulb) {
        for (BlockFace face : new BlockFace[] { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST }) {
            Block b = bulb.getRelative(face);
            if (RAILS.contains(b.getType())) return b;
        }
        return null;
    }

    private static class ResolvedTarget {
        String nodeId;
        String error;
    }

    /** Resolve line 2 to a transfer node or terminal: "StationName:NodeName" or bare node name across stations. */
    private ResolvedTarget resolveTarget(String name) {
        ResolvedTarget t = new ResolvedTarget();
        int colon = name.indexOf(':');
        if (colon > 0 && colon < name.length() - 1) {
            String stationName = name.substring(0, colon).strip();
            String nodeName = name.substring(colon + 1).strip();
            if (!stationName.isEmpty() && !nodeName.isEmpty()) {
                Optional<TransferNode> node = stationRepo.findByNameIgnoreCase(stationName)
                    .flatMap(st -> nodeRepo.findByNameAtStation(st.getId(), nodeName));
                if (node.isPresent()) {
                    t.nodeId = node.get().getId();
                    return t;
                }
                t.error = "Unknown Station:Node";
                return t;
            }
        }
        Optional<TransferNode> node = nodeRepo.findByNameAcrossStations(name);
        if (node.isPresent()) {
            t.nodeId = node.get().getId();
            return t;
        }
        t.error = "Unknown. Use Station:Node or node name";
        return t;
    }

    /** Parse a line into multiple rules (space-separated). Caller takes first 2 for DB. */
    private static List<String[]> parseRuleLine(String line, Set<String> allowedRoles) {
        List<String[]> out = new ArrayList<>();
        for (String token : line.split("\\s+")) {
            if (token.isBlank()) continue;
            String[] rule = parseRule(token.strip(), allowedRoles);
            if (rule != null) out.add(rule);
        }
        return out;
    }

    /** Returns { role, direction } or null if invalid. direction may be null. Accepts 3-letter shorthand (ENT, REA, CLE, REL). RULE:N and RULE:N:L/R accepted when RULE_PATTERN is in allowedRoles. */
    private static String[] parseRule(String token, Set<String> allowedRoles) {
        token = token.strip();
        if (token.isEmpty()) return null;
        String upper = token.toUpperCase();
        if (allowedRoles.contains(RULE_PATTERN) && upper.startsWith("RULE:")) {
            String after = upper.substring(5).strip();
            String dir = null;
            if (after.endsWith(":L") || after.endsWith(":R")) {
                int lastColon = after.lastIndexOf(':');
                String roleNum = after.substring(0, lastColon).strip();
                String d = after.substring(lastColon + 1);
                if (roleNum.matches("\\d+")) {
                    dir = "L".equals(d) ? "LEFT" : "RIGHT";
                    after = roleNum;
                }
            }
            if (after.matches("\\d+")) return new String[] { "RULE:" + after, dir };
        }
        int colon = token.indexOf(':');
        String rolePart;
        String direction = null;
        if (colon >= 0) {
            rolePart = token.substring(0, colon).strip().toUpperCase();
            String dir = token.substring(colon + 1).strip().toUpperCase();
            if (!DIRECTIONS.contains(dir)) return null;
            direction = "L".equals(dir) ? "LEFT" : "R".equals(dir) ? "RIGHT" : dir;
        } else {
            rolePart = token.toUpperCase();
        }
        String role = ROLE_SHORTHAND.getOrDefault(rolePart, rolePart);
        if (allowedRoles.contains(role)) return new String[] { role, direction };
        if (allowedRoles.contains(RULE_PATTERN) && role != null && role.matches("RULE:\\d+")) return new String[] { role, direction };
        return null;
    }

    private static boolean isSign(Material m) {
        return m != null && m.name().contains("SIGN");
    }
}
