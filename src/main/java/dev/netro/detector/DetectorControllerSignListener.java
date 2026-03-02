package dev.netro.detector;

import dev.netro.NetroPlugin;
import dev.netro.database.ControllerRepository;
import dev.netro.database.DetectorRepository;
import dev.netro.database.JunctionRepository;
import dev.netro.database.StationControllerRepository;
import dev.netro.database.StationDetectorRepository;
import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Controller;
import dev.netro.model.Detector;
import dev.netro.model.Station;
import dev.netro.model.StationController;
import dev.netro.model.StationDetector;
import dev.netro.model.Junction;
import dev.netro.model.TransferNode;
import dev.netro.util.DestinationResolver;
import dev.netro.util.SignTextHelper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.block.Sign;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Auto-registers [Detector], [Transfer], [Terminal], [Junction] (detectors) and [Controller] when a sign is placed on a copper bulb.
 * Unregisters when the sign or copper bulb is broken.
 * [Transfer] = boundary detector for a transfer node (Line 2 = Station:Node, non-terminal).
 * [Terminal] = boundary detector for a terminal (Line 2 = Station:Node, terminal).
 * [Junction] = detector for one side of a junction (Line 2 = junction name, no colon).
 */
public class DetectorControllerSignListener implements Listener {

    private static final Set<String> DETECTOR_LINE1 = Set.of("[Detector]", "[Transfer]", "[Terminal]", "[Junction]");
    /** Sentinel for parseRule: allow any role of the form MAXV_<number> (e.g. MAXV_0.5, MAXV_0.25:L). */
    private static final String MAXV_PATTERN = "MAXV_*";
    /** Sentinel for parseRule: allow any role of the form MINV_<number> (e.g. MINV_0.2, MINV_0.2:L). */
    private static final String MINV_PATTERN = "MINV_*";
    private static final Set<String> DETECTOR_ROLES = Set.of("ENTRY", "READY", "CLEAR", MAXV_PATTERN, MINV_PATTERN);
    private static final Set<String> STATION_DETECTOR_ROLES = Set.of("ROUTE", "SET_DEST");
    private static final Set<String> CONTROLLER_ROLES = Set.of("DIVERT", "DIVERT+", "RELEASE", "NOT_DIVERT", "NOT_DIVERT+");
    private static final Set<String> STATION_CONTROLLER_ROLES = Set.of("TRANSFER", "NOT_TRANSFER", "TRANSFER+", "NOT_TRANSFER+");
    private static final Set<String> DIRECTIONS = Set.of("LEFT", "RIGHT", "L", "R");

    /** Sign line colors: line 1 = type (bold), line 2 = target, lines 3–4 = rules. */
    private static final String BOLD = "§l";
    private static final String COLOR_DETECTOR = "§b";   // aqua – generic detector
    private static final String COLOR_TRANSFER = "§a";   // green – transfer node
    private static final String COLOR_TERMINAL = "§e";   // yellow – terminal
    private static final String COLOR_JUNCTION = "§d";   // light purple – junction
    private static final String COLOR_CONTROLLER = "§6"; // gold – controller
    private static final String COLOR_TARGET = "§f";     // white – target (Station:Node / junction / station name)
    private static final String COLOR_RULES = "§7";     // gray – signal rules

    /** 3-letter (and DIV+, NOD+) shorthand to canonical role. No first-letter collisions. */
    private static final Map<String, String> ROLE_SHORTHAND = Map.ofEntries(
        Map.entry("ENT", "ENTRY"), Map.entry("REA", "READY"), Map.entry("CLE", "CLEAR"),
        Map.entry("ROU", "ROUTE"),
        Map.entry("SET_DST", "SET_DEST"),
        Map.entry("DIV", "DIVERT"), Map.entry("DIV+", "DIVERT+"), Map.entry("REL", "RELEASE"),
        Map.entry("NOD", "NOT_DIVERT"), Map.entry("NOD+", "NOT_DIVERT+"),
        Map.entry("TRA", "TRANSFER"), Map.entry("NOT_TRA", "NOT_TRANSFER"),
        Map.entry("TRA+", "TRANSFER+"), Map.entry("NOT_TRA+", "NOT_TRANSFER+")
    );

    private final DetectorRepository detectorRepo;
    private final ControllerRepository controllerRepo;
    private final StationDetectorRepository stationDetectorRepo;
    private final StationControllerRepository stationControllerRepo;
    private final StationRepository stationRepo;
    private final TransferNodeRepository nodeRepo;
    private final JunctionRepository junctionRepo;
    private final NetroPlugin plugin;

    public DetectorControllerSignListener(NetroPlugin plugin) {
        this.plugin = plugin;
        var db = plugin.getDatabase();
        this.detectorRepo = new DetectorRepository(db);
        this.controllerRepo = new ControllerRepository(db);
        this.stationDetectorRepo = new StationDetectorRepository(db);
        this.stationControllerRepo = new StationControllerRepository(db);
        this.stationRepo = new StationRepository(db);
        this.nodeRepo = new TransferNodeRepository(db);
        this.junctionRepo = new JunctionRepository(db);
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
        boolean hadStationDetector = stationDetectorRepo.findByBlock(world, bx, by, bz)
            .map(d -> { if (plugin.getChunkLoadService() != null) plugin.getChunkLoadService().removeChunksForBlock(d.getWorld(), d.getRailX(), d.getRailZ()); stationDetectorRepo.deleteById(d.getId()); return true; }).orElse(false);
        boolean hadStationController = stationControllerRepo.findByBlock(world, bx, by, bz)
            .map(c -> { stationControllerRepo.deleteById(c.getId()); return true; }).orElse(false);

        String line0 = SignTextHelper.readSignLine(event.getLine(0));
        boolean willBeDetector = DETECTOR_LINE1.stream().anyMatch(s -> s.equalsIgnoreCase(line0));
        boolean willBeController = line0.equalsIgnoreCase("[Controller]");

        if (hadDetector && !willBeDetector) player.sendMessage("Detector removed.");
        if (hadController && !willBeController) player.sendMessage("Controller removed.");
        if (hadStationDetector && !willBeDetector) player.sendMessage("Station detector removed.");
        if (hadStationController && !willBeController) player.sendMessage("Station controller removed.");

        if (!willBeDetector && !willBeController) return;

        String name = SignTextHelper.readSignLine(event.getLine(1));
        if (name.isEmpty()) {
            player.sendMessage("Line 2 is empty. Use Station:Node, junction name, or station name (for [Detector] with ROUTE).");
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

        Set<String> detectorAndRouteRoles = Set.of("ENTRY", "READY", "CLEAR", "ROUTE", "SET_DEST", MAXV_PATTERN, MINV_PATTERN);
        List<String[]> rules = parseRuleLine(SignTextHelper.readSignLine(event.getLine(2)), detectorAndRouteRoles);
        rules.addAll(parseRuleLine(SignTextHelper.readSignLine(event.getLine(3)), detectorAndRouteRoles));

        if (name.indexOf(':') < 0) {
            Optional<Station> stationOpt = stationRepo.findByNameIgnoreCase(name);
            if (stationOpt.isPresent()) {
                if (!"[Detector]".equalsIgnoreCase(line0)) {
                    player.sendMessage(line0 + " needs Line 2 = Station:Node or junction name. For station-only use [Detector].");
                    return;
                }
                if (rules.isEmpty() || !rules.stream().allMatch(r -> isAllowedStationDetectorRole(r[0]))) {
                    player.sendMessage("Station detector needs ROUTE, SET_DEST (with :L/:R), MAXV_/MINV_<value> (e.g. MAXV_0.5, MINV_0.2:L), etc. on lines 3–4.");
                    return;
                }
                boolean hasSetDest = rules.stream().anyMatch(r -> "SET_DEST".equals(r[0]));
                String setDestValue = null;
                if (hasSetDest) {
                    String line4 = SignTextHelper.readSignLine(event.getLine(3));
                    if (line4.isEmpty()) {
                        player.sendMessage("SET_DEST needs destination on line 4 (e.g. Snowy2 or Snowy2:0).");
                        return;
                    }
                    if (DestinationResolver.resolveToAddress(stationRepo, nodeRepo, line4).isEmpty()) {
                        player.sendMessage("Line 4 \"" + line4 + "\" is not a valid station name or Name:TerminalIndex (e.g. Snowy2 or Snowy2:0).");
                        return;
                    }
                    setDestValue = line4;
                }
                String[] rule1 = rules.get(0);
                String[] rule2 = rules.size() > 1 ? rules.get(1) : null;
                String[] rule3 = rules.size() > 2 ? rules.get(2) : null;
                String[] rule4 = rules.size() > 3 ? rules.get(3) : null;
                BlockData data = event.getBlock().getBlockData();
                BlockFace signFacing = data instanceof Directional d ? d.getFacing() : BlockFace.NORTH;
                StationDetector sd = new StationDetector(
                    UUID.randomUUID().toString(),
                    stationOpt.get().getId(),
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
                    rule4 != null ? rule4[1] : null,
                    setDestValue
                );
                stationDetectorRepo.insert(sd);
                if (plugin.getChunkLoadService() != null) plugin.getChunkLoadService().addChunksForBlock(sd.getWorld(), sd.getRailX(), sd.getRailZ());
                applyDetectorSignColors(event, "[Detector]", name, SignTextHelper.readSignLine(event.getLine(2)), SignTextHelper.readSignLine(event.getLine(3)));
                player.sendMessage("Station detector registered for " + name + ".");
                return;
            }
        }

        ResolvedTarget target = resolveTarget(name);
        if (target.error != null) {
            if ("[Junction]".equalsIgnoreCase(line0)) {
                target = ensureJunctionForDetector(name, bulb.getWorld().getName(), bulb.getX(), bulb.getY(), bulb.getZ());
                if (target.error != null) {
                    player.sendMessage(target.error);
                    return;
                }
                player.sendMessage("Junction \"" + junctionNameFromLine2(name) + "\" created. Register the other side when ready; use /junction segment to attach to a segment.");
            } else if ("[Transfer]".equalsIgnoreCase(line0) || "[Terminal]".equalsIgnoreCase(line0)) {
                target = ensureNodeForDetector(name, line0);
                if (target.error != null) {
                    player.sendMessage(target.error);
                    return;
                }
                String kind = "[Terminal]".equalsIgnoreCase(line0) ? "Terminal" : "Transfer node";
                player.sendMessage(kind + " \"" + name + "\" created. Pair with /transfer pair or /terminal done as needed; detector registered.");
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

        List<String[]> nodeRules = parseRuleLine(SignTextHelper.readSignLine(event.getLine(2)), DETECTOR_ROLES);
        nodeRules.addAll(parseRuleLine(SignTextHelper.readSignLine(event.getLine(3)), DETECTOR_ROLES));
        if (nodeRules.isEmpty()) {
            player.sendMessage("Detector needs at least one rule on lines 3–4: ENT/REA/CLE or ROLE:L/R (e.g. ENT:L CLE:R).");
            return;
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
            target.junctionId,
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
        if ("[Junction]".equalsIgnoreCase(n)) return COLOR_JUNCTION;
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

    /** For "JunctionName" or "JunctionName:LEFT", returns "JunctionName" for display. */
    private static String junctionNameFromLine2(String name) {
        int colon = name.indexOf(':');
        return (colon > 0 && colon < name.length() - 1) ? name.substring(0, colon).strip() : name.strip();
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
            t.error = "Station \"" + stationName + "\" not found. Create the station first (place a [Station] sign or /station create).";
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
            return t;
        }
        TransferNode node = new TransferNode(UUID.randomUUID().toString(), nodeName);
        node.setStationId(stationId);
        nodeRepo.insert(node);
        nodeRepo.setSetupComplete(node.getId());
        t.nodeId = node.getId();
        return t;
    }

    /**
     * For [Junction] detector when the junction doesn't exist yet: find by name (ignore case) or create one.
     * Uses junction name from line 2 (part before colon if present). Returns ResolvedTarget with junctionId set.
     */
    private ResolvedTarget ensureJunctionForDetector(String name, String world, int refX, int refY, int refZ) {
        String junctionName = junctionNameFromLine2(name);
        if (junctionName.isEmpty()) {
            ResolvedTarget t = new ResolvedTarget();
            t.error = "Junction name (line 2) is empty or invalid.";
            return t;
        }
        Optional<Junction> existing = junctionRepo.findByNameIgnoreCase(junctionName);
        if (existing.isPresent()) {
            ResolvedTarget t = new ResolvedTarget();
            t.junctionId = existing.get().getId();
            return t;
        }
        String id = UUID.randomUUID().toString();
        junctionRepo.insert(id, junctionName, world, refX, refY, refZ);
        ResolvedTarget t = new ResolvedTarget();
        t.junctionId = id;
        return t;
    }

    /**
     * Validates that line 1 detector type matches the resolved target.
     * [Transfer] → must be a transfer node (node, not terminal). [Terminal] → must be a terminal. [Junction] → must be a junction.
     */
    private String validateDetectorType(String line0, ResolvedTarget target) {
        if (line0 == null) return null;
        if ("[Transfer]".equalsIgnoreCase(line0)) {
            if (target.junctionId != null) return "[Transfer] needs Station:Node (transfer node), not a junction name.";
            if (target.nodeId != null && nodeRepo.findById(target.nodeId).map(TransferNode::isTerminal).orElse(false))
                return "[Transfer] needs a transfer node, not a terminal. Use [Terminal] for terminals.";
            return null;
        }
        if ("[Terminal]".equalsIgnoreCase(line0)) {
            if (target.junctionId != null) return "[Terminal] needs Station:Node (terminal), not a junction name.";
            if (target.nodeId != null && !nodeRepo.findById(target.nodeId).map(TransferNode::isTerminal).orElse(false))
                return "[Terminal] needs a terminal. Use [Transfer] for transfer nodes.";
            return null;
        }
        if ("[Junction]".equalsIgnoreCase(line0)) {
            if (target.nodeId != null) return "[Junction] needs junction name (no colon), not Station:Node.";
            return null;
        }
        return null;
    }

    private void handleControllerSign(SignChangeEvent event, Block bulb, String name) {
        Player player = event.getPlayer();
        Set<String> allControllerRoles = Set.of("DIVERT", "DIVERT+", "RELEASE", "NOT_DIVERT", "NOT_DIVERT+", "TRANSFER", "NOT_TRANSFER", "TRANSFER+", "NOT_TRANSFER+");
        List<String[]> rules = parseRuleLine(SignTextHelper.readSignLine(event.getLine(2)), allControllerRoles);
        rules.addAll(parseRuleLine(SignTextHelper.readSignLine(event.getLine(3)), allControllerRoles));
        if (rules.isEmpty()) {
            player.sendMessage("Controller needs at least one rule on lines 3–4: DIV/REL/NOD or TRA/NOT_TRA, with :L/:R ok.");
            return;
        }

        boolean allStationControllerRules = rules.stream().allMatch(r -> STATION_CONTROLLER_ROLES.contains(r[0]));
        if (allStationControllerRules) {
            if (name.indexOf(':') < 0) {
                player.sendMessage("Station controller needs Line 2 = Station:Node or Station:Terminal (e.g. Snowy1:Main). Each controller is for one node/terminal.");
                return;
            }
            ResolvedTarget target = resolveTarget(name);
            if (target.error != null) {
                player.sendMessage(target.error);
                return;
            }
            if (target.nodeId == null) {
                player.sendMessage("Station controller needs Line 2 = Station:Node (not a junction).");
                return;
            }
            TransferNode node = nodeRepo.findById(target.nodeId).orElse(null);
            if (node == null) {
                player.sendMessage("Node not found. Create the transfer node or terminal first.");
                return;
            }
            String stationId = node.getStationId();
            if (stationId == null) {
                player.sendMessage("Node must belong to a station.");
                return;
            }
            String nodeId = target.nodeId;
            String[] rule1 = rules.get(0);
            String[] rule2 = rules.size() > 1 ? rules.get(1) : null;
            String[] rule3 = rules.size() > 2 ? rules.get(2) : null;
            String[] rule4 = rules.size() > 3 ? rules.get(3) : null;
            BlockData data = event.getBlock().getBlockData();
            BlockFace signFacing = data instanceof Directional d ? d.getFacing() : BlockFace.NORTH;
            StationController sc = new StationController(
                UUID.randomUUID().toString(),
                stationId,
                nodeId,
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
            stationControllerRepo.insert(sc);
            applyControllerSignColors(event, name, SignTextHelper.readSignLine(event.getLine(2)), SignTextHelper.readSignLine(event.getLine(3)));
            player.sendMessage("Station controller registered for " + name + ".");
            return;
        }

        ResolvedTarget target = resolveTarget(name);
        if (target.error != null) {
            player.sendMessage(target.error);
            return;
        }

        List<String[]> nodeRules = parseRuleLine(SignTextHelper.readSignLine(event.getLine(2)), CONTROLLER_ROLES);
        nodeRules.addAll(parseRuleLine(SignTextHelper.readSignLine(event.getLine(3)), CONTROLLER_ROLES));
        if (nodeRules.isEmpty()) {
            player.sendMessage("Controller needs at least one rule on lines 3–4: DIV/REL/NOD or ROLE:L/R.");
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
            target.junctionId,
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
            if (plugin.getChunkLoadService() != null) plugin.getChunkLoadService().removeChunksForBlock(d.getWorld(), d.getRailX(), d.getRailZ());
            detectorRepo.deleteById(d.getId());
            player.sendMessage("Detector removed.");
        });
        controllerRepo.findByBlock(world, x, y, z).ifPresent(c -> {
            controllerRepo.deleteById(c.getId());
            player.sendMessage("Controller removed.");
        });
        stationDetectorRepo.findByBlock(world, x, y, z).ifPresent(d -> {
            if (plugin.getChunkLoadService() != null) plugin.getChunkLoadService().removeChunksForBlock(d.getWorld(), d.getRailX(), d.getRailZ());
            stationDetectorRepo.deleteById(d.getId());
            player.sendMessage("Station detector removed.");
        });
        stationControllerRepo.findByBlock(world, x, y, z).ifPresent(c -> {
            stationControllerRepo.deleteById(c.getId());
            player.sendMessage("Station controller removed.");
        });
    }

    /**
     * Re-register a detector or controller from an existing sign (e.g. after node/station was deleted and recreated).
     * Sends messages to the player (including clickable create-node suggestion when the node does not exist).
     * Does not create stations; use absorb on a [Station] sign to recreate a station.
     */
    public void absorbSign(Player player, Block signBlock) {
        Block attached = getAttachedBlock(signBlock);
        if (attached == null || !CopperBulbHelper.isCopperBulb(attached)) {
            player.sendMessage("That sign is not on a copper bulb.");
            return;
        }
        if (!(signBlock.getState() instanceof Sign sign)) {
            player.sendMessage("Not a sign.");
            return;
        }
        String world = attached.getWorld().getName();
        int bx = attached.getX(), by = attached.getY(), bz = attached.getZ();

        String line0 = SignTextHelper.readSignLine(sign.getLine(0));
        String name = SignTextHelper.readSignLine(sign.getLine(1));
        String line2 = SignTextHelper.readSignLine(sign.getLine(2));
        String line3 = SignTextHelper.readSignLine(sign.getLine(3));

        boolean isDetector = DETECTOR_LINE1.stream().anyMatch(s -> s.equalsIgnoreCase(line0));
        boolean isController = line0.equalsIgnoreCase("[Controller]");
        if (!isDetector && !isController) {
            player.sendMessage("Not a detector or controller sign. Line 1 must be [Detector], [Transfer], [Terminal], [Junction], or [Controller].");
            return;
        }
        if (name.isEmpty()) {
            player.sendMessage("Line 2 (Station:Node or junction or station name) is empty.");
            return;
        }

        BlockData data = signBlock.getBlockData();
        BlockFace signFacing = data instanceof Directional d ? d.getFacing() : BlockFace.NORTH;

        if (isDetector) {
            Block rail = findAdjacentRail(attached);
            if (rail == null) {
                player.sendMessage("No rail adjacent to this copper bulb.");
                return;
            }
            Set<String> detectorAndRouteRoles = Set.of("ENTRY", "READY", "CLEAR", "ROUTE", "SET_DEST", MAXV_PATTERN, MINV_PATTERN);
            List<String[]> rules = parseRuleLine(line2, detectorAndRouteRoles);
            rules.addAll(parseRuleLine(line3, detectorAndRouteRoles));

            if (name.indexOf(':') < 0) {
                Optional<Station> stationOpt = stationRepo.findByNameIgnoreCase(name);
                if (stationOpt.isPresent() && !rules.isEmpty() && rules.stream().allMatch(r -> isAllowedStationDetectorRole(r[0]))) {
                    boolean hasSetDestAbsorb = rules.stream().anyMatch(r -> "SET_DEST".equals(r[0]));
                    String setDestValueAbsorb = null;
                    if (hasSetDestAbsorb) {
                        String line4 = line3.strip();
                        if (line4.isEmpty()) {
                            player.sendMessage("SET_DEST needs destination on line 4 (e.g. Snowy2 or Snowy2:0).");
                            return;
                        }
                        if (DestinationResolver.resolveToAddress(stationRepo, nodeRepo, line4).isEmpty()) {
                            player.sendMessage("Line 4 \"" + line4 + "\" is not a valid station name or Name:TerminalIndex.");
                            return;
                        }
                        setDestValueAbsorb = line4;
                    }
                    java.util.Optional<StationDetector> existingSd = stationDetectorRepo.findByBlock(world, bx, by, bz);
                    existingSd.ifPresent(old -> {
                        if (plugin.getChunkLoadService() != null) plugin.getChunkLoadService().removeChunksForBlock(old.getWorld(), old.getRailX(), old.getRailZ());
                        stationDetectorRepo.deleteById(old.getId());
                    });
                    String existingId = existingSd.map(StationDetector::getId).orElse(null);
                    String[] rule1 = rules.get(0);
                    String[] rule2 = rules.size() > 1 ? rules.get(1) : null;
                    String[] rule3 = rules.size() > 2 ? rules.get(2) : null;
                    String[] rule4 = rules.size() > 3 ? rules.get(3) : null;
                    StationDetector sd = new StationDetector(
                        existingId != null ? existingId : UUID.randomUUID().toString(),
                        stationOpt.get().getId(),
                        world, bx, by, bz,
                        rail.getX(), rail.getY(), rail.getZ(),
                        signFacing.name(),
                        rule1[0], rule1[1],
                        rule2 != null ? rule2[0] : null, rule2 != null ? rule2[1] : null,
                        rule3 != null ? rule3[0] : null, rule3 != null ? rule3[1] : null,
                        rule4 != null ? rule4[0] : null, rule4 != null ? rule4[1] : null,
                        setDestValueAbsorb
                    );
                    stationDetectorRepo.insert(sd);
                    if (plugin.getChunkLoadService() != null) plugin.getChunkLoadService().addChunksForBlock(sd.getWorld(), sd.getRailX(), sd.getRailZ());
                    player.sendMessage(existingId != null ? "Station detector re-registered for " + name + "." : "Station detector absorbed for " + name + ".");
                    return;
                }
            }

            ResolvedTarget target = resolveTarget(name);
            if (target.error != null) {
                if ("[Junction]".equalsIgnoreCase(line0)) {
                    target = ensureJunctionForDetector(name, world, bx, by, bz);
                    if (target.error != null) {
                        player.sendMessage(target.error);
                        return;
                    }
                    player.sendMessage("Junction \"" + junctionNameFromLine2(name) + "\" created. Register the other side when ready; use /junction segment to attach to a segment.");
                } else if ("[Transfer]".equalsIgnoreCase(line0) || "[Terminal]".equalsIgnoreCase(line0)) {
                    target = ensureNodeForDetector(name, line0);
                    if (target.error != null) {
                        player.sendMessage(target.error);
                        return;
                    }
                    String kind = "[Terminal]".equalsIgnoreCase(line0) ? "Terminal" : "Transfer node";
                    player.sendMessage(kind + " \"" + name + "\" created. Detector absorbed.");
                } else {
                    sendNodeMissingMessage(player, name);
                    return;
                }
            }
            String detectorTypeError = validateDetectorType(line0, target);
            if (detectorTypeError != null) {
                player.sendMessage(detectorTypeError);
                return;
            }
            List<String[]> nodeRules = parseRuleLine(line2, DETECTOR_ROLES);
            nodeRules.addAll(parseRuleLine(line3, DETECTOR_ROLES));
            if (nodeRules.isEmpty()) {
                player.sendMessage("Detector requires at least one rule (ENTRY, READY, CLEAR or ROUTE) on lines 3–4.");
                return;
            }
            java.util.Optional<Detector> existingDet = detectorRepo.findByBlock(world, bx, by, bz);
            existingDet.ifPresent(old -> {
                if (plugin.getChunkLoadService() != null) plugin.getChunkLoadService().removeChunksForBlock(old.getWorld(), old.getRailX(), old.getRailZ());
                detectorRepo.deleteById(old.getId());
            });
            String existingId = existingDet.map(Detector::getId).orElse(null);
            String[] rule1 = nodeRules.get(0);
            String[] rule2 = nodeRules.size() > 1 ? nodeRules.get(1) : null;
            String[] rule3 = nodeRules.size() > 2 ? nodeRules.get(2) : null;
            String[] rule4 = nodeRules.size() > 3 ? nodeRules.get(3) : null;
            Detector d = new Detector(
                existingId != null ? existingId : UUID.randomUUID().toString(),
                target.nodeId, target.junctionId,
                world, bx, by, bz,
                rail.getX(), rail.getY(), rail.getZ(),
                signFacing.name(),
                rule1[0], rule1[1],
                rule2 != null ? rule2[0] : null, rule2 != null ? rule2[1] : null,
                rule3 != null ? rule3[0] : null, rule3 != null ? rule3[1] : null,
                rule4 != null ? rule4[0] : null, rule4 != null ? rule4[1] : null
            );
            detectorRepo.insert(d);
            if (plugin.getChunkLoadService() != null) plugin.getChunkLoadService().addChunksForBlock(d.getWorld(), d.getRailX(), d.getRailZ());
            String absorbMsg = existingId != null ? "Detector re-registered for " + name + "." : "Detector absorbed for " + name + ".";
            if (!"[Detector]".equalsIgnoreCase(line0)) {
                absorbMsg = existingId != null ? line0 + " detector re-registered for " + name + "." : line0 + " detector absorbed for " + name + ".";
            }
            player.sendMessage(absorbMsg);
            return;
        }

        // Controller
        Set<String> allControllerRoles = Set.of("DIVERT", "DIVERT+", "RELEASE", "NOT_DIVERT", "NOT_DIVERT+", "TRANSFER", "NOT_TRANSFER", "TRANSFER+", "NOT_TRANSFER+");
        List<String[]> rules = parseRuleLine(line2, allControllerRoles);
        rules.addAll(parseRuleLine(line3, allControllerRoles));
        if (rules.isEmpty()) {
            player.sendMessage("Controller requires at least one rule on lines 3–4.");
            return;
        }

        boolean allStationControllerRules = rules.stream().allMatch(r -> STATION_CONTROLLER_ROLES.contains(r[0]));
        if (allStationControllerRules) {
            if (name.indexOf(':') < 0) {
                player.sendMessage("Station controller needs Line 2 = Station:Node or Station:Terminal (e.g. Snowy1:Main). Each controller is for one node/terminal.");
                return;
            }
            ResolvedTarget target = resolveTarget(name);
            if (target.error != null) {
                sendNodeMissingMessage(player, name);
                return;
            }
            if (target.nodeId == null) {
                player.sendMessage("Station controller needs Station:Node on line 2.");
                return;
            }
            TransferNode node = nodeRepo.findById(target.nodeId).orElse(null);
            if (node == null) {
                sendNodeMissingMessage(player, name);
                return;
            }
            String stationId = node.getStationId();
            if (stationId == null) {
                player.sendMessage("Node must belong to a station.");
                return;
            }
            Optional<Station> st = stationRepo.findById(stationId);
            if (st.isEmpty()) {
                String stationName = name.substring(0, name.indexOf(':')).strip();
                player.sendMessage("Station \"" + stationName + "\" does not exist. Absorb a station sign for that station first (or create with /station create).");
                return;
            }
            String nodeId = target.nodeId;
            String existingId = stationControllerRepo.findByBlock(world, bx, by, bz).map(StationController::getId).orElse(null);
            if (existingId != null) stationControllerRepo.deleteById(existingId);
            String[] rule1 = rules.get(0);
            String[] rule2 = rules.size() > 1 ? rules.get(1) : null;
            String[] rule3 = rules.size() > 2 ? rules.get(2) : null;
            String[] rule4 = rules.size() > 3 ? rules.get(3) : null;
            StationController sc = new StationController(
                existingId != null ? existingId : UUID.randomUUID().toString(),
                stationId, nodeId,
                world, bx, by, bz, signFacing.name(),
                rule1[0], rule1[1],
                rule2 != null ? rule2[0] : null, rule2 != null ? rule2[1] : null,
                rule3 != null ? rule3[0] : null, rule3 != null ? rule3[1] : null,
                rule4 != null ? rule4[0] : null, rule4 != null ? rule4[1] : null
            );
            stationControllerRepo.insert(sc);
            player.sendMessage(existingId != null ? "Station controller re-registered for " + name + "." : "Station controller absorbed for " + name + ".");
            return;
        }

        ResolvedTarget target = resolveTarget(name);
        if (target.error != null) {
            sendNodeMissingMessage(player, name);
            return;
        }
        List<String[]> nodeRules = parseRuleLine(line2, CONTROLLER_ROLES);
        nodeRules.addAll(parseRuleLine(line3, CONTROLLER_ROLES));
        if (nodeRules.isEmpty()) {
            player.sendMessage("Controller requires at least one rule (DIVERT, RELEASE, NOT_DIVERT, etc.) on lines 3–4.");
            return;
        }
        String existingId = controllerRepo.findByBlock(world, bx, by, bz).map(Controller::getId).orElse(null);
        if (existingId != null) controllerRepo.deleteById(existingId);
        String[] rule1 = nodeRules.get(0);
        String[] rule2 = nodeRules.size() > 1 ? nodeRules.get(1) : null;
        String[] rule3 = nodeRules.size() > 2 ? nodeRules.get(2) : null;
        String[] rule4 = nodeRules.size() > 3 ? nodeRules.get(3) : null;
        Controller c = new Controller(
            existingId != null ? existingId : UUID.randomUUID().toString(),
            target.nodeId, target.junctionId,
            world, bx, by, bz, signFacing.name(),
            rule1[0], rule1[1],
            rule2 != null ? rule2[0] : null, rule2 != null ? rule2[1] : null,
            rule3 != null ? rule3[0] : null, rule3 != null ? rule3[1] : null,
            rule4 != null ? rule4[0] : null, rule4 != null ? rule4[1] : null
        );
        controllerRepo.insert(c);
        player.sendMessage(existingId != null ? "Controller re-registered for " + name + "." : "Controller absorbed for " + name + ".");
    }

    private void sendNodeMissingMessage(Player player, String name) {
        player.sendMessage("No transfer node or terminal exists for \"" + name + "\". Create it, then run /absorb again.");
        int colon = name.indexOf(':');
        if (colon > 0 && colon < name.length() - 1) {
            String stationPart = name.substring(0, colon).strip();
            String nodePart = name.substring(colon + 1).strip();
            if (!stationPart.isEmpty() && !nodePart.isEmpty()) {
                Component createTransfer = Component.text("[Create transfer node]").color(NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/transfer create " + name));
                Component createTerminal = Component.text(" [Create terminal]").color(NamedTextColor.GRAY)
                    .clickEvent(ClickEvent.runCommand("/terminal create " + name));
                plugin.sendMessage(player, createTransfer.append(createTerminal));
            }
        }
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
        String junctionId;
        String error;
    }

    /**
     * Resolve line 2 to a node or junction.
     * - "StationName:NodeName" → transfer node or terminal at that station (unambiguous).
     * - "JunctionName" or "JunctionName:LEFT" / "JunctionName:RIGHT" → junction (case-insensitive).
     * - Bare "NodeName" → junction by name first, then node by name across stations if unique.
     */
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
                Optional<Junction> junction = junctionRepo.findByNameIgnoreCase(stationName);
                if (junction.isPresent()) {
                    t.junctionId = junction.get().getId();
                    return t;
                }
                t.error = "Unknown Station:Node or junction name";
                return t;
            }
        }
        Optional<Junction> junction = junctionRepo.findByNameIgnoreCase(name);
        if (junction.isPresent()) {
            t.junctionId = junction.get().getId();
            return t;
        }
        Optional<TransferNode> node = nodeRepo.findByNameAcrossStations(name);
        if (node.isPresent()) {
            t.nodeId = node.get().getId();
            return t;
        }
        t.error = "Unknown. Use Station:Node or junction name";
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

    /** Returns { role, direction } or null if invalid. direction may be null. Accepts 3-letter shorthand (ENT, REA, CLE, DIV, DIV+, REL, NOD, NOD+). */
    private static String[] parseRule(String token, Set<String> allowedRoles) {
        token = token.strip();
        if (token.isEmpty()) return null;
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
        if (allowedRoles.contains(MAXV_PATTERN) && role != null && role.startsWith("MAXV_") && parseMaxVValue(role) != null) return new String[] { role, direction };
        if (allowedRoles.contains(MINV_PATTERN) && role != null && role.startsWith("MINV_") && parseMinVValue(role) != null) return new String[] { role, direction };
        return null;
    }

    /** True for ROUTE, SET_DEST, MAXV_<number>, or MINV_<number>. */
    private static boolean isAllowedStationDetectorRole(String role) {
        if (role == null) return false;
        if (STATION_DETECTOR_ROLES.contains(role)) return true;
        if (role.startsWith("MAXV_") && parseMaxVValue(role) != null) return true;
        if (role.startsWith("MINV_") && parseMinVValue(role) != null) return true;
        return false;
    }

    /** Returns the max velocity value if role is MAXV_<number>, else null. */
    private static Double parseMaxVValue(String role) {
        if (role == null || !role.startsWith("MAXV_") || role.length() <= 5) return null;
        try {
            double v = Double.parseDouble(role.substring(5).trim());
            return v >= 0 && v <= 10 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Returns the min velocity value if role is MINV_<number>, else null. */
    private static Double parseMinVValue(String role) {
        if (role == null || !role.startsWith("MINV_") || role.length() <= 5) return null;
        try {
            double v = Double.parseDouble(role.substring(5).trim());
            return v >= 0 && v <= 10 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isSign(Material m) {
        return m != null && m.name().contains("SIGN");
    }
}
