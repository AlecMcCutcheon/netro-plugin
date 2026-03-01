package dev.netro.terminal;

import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-player state for guided terminal setup: click station sign, then transfer switches, then gate slots.
 * Terminals have no hold switches; gate slots are ordered by distance from the closest transfer switch.
 */
public class TerminalSetupWizard {

    public static final int STEP_CLICK_STATION = 1;
    public static final int STEP_CLICK_TRANSFER_SWITCHES = 2;
    public static final int STEP_CLICK_GATE_SLOTS = 3;

    private final UUID playerId;
    private String nodeId;
    private final String nodeName;
    private int step = STEP_CLICK_STATION;

    private String stationId;
    private int signX, signY, signZ;

    private final List<BlockPos> transferSwitches = new ArrayList<>();
    private final List<BlockPos> gateSlots = new ArrayList<>();

    public TerminalSetupWizard(UUID playerId, String nodeName) {
        this.playerId = playerId;
        this.nodeName = nodeName;
    }

    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public UUID getPlayerId() { return playerId; }
    public String getNodeId() { return nodeId; }
    public String getNodeName() { return nodeName; }
    public int getStep() { return step; }
    public String getStationId() { return stationId; }
    public int getSignX() { return signX; }
    public int getSignY() { return signY; }
    public int getSignZ() { return signZ; }
    public List<BlockPos> getTransferSwitches() { return transferSwitches; }
    public List<BlockPos> getGateSlots() { return gateSlots; }

    public void setStation(String stationId, int signX, int signY, int signZ) {
        this.stationId = stationId;
        this.signX = signX;
        this.signY = signY;
        this.signZ = signZ;
    }

    public void advanceStep() { step++; }
    public void setStep(int step) { this.step = step; }

    public static final class BlockPos {
        public final int x, y, z;

        public BlockPos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public static BlockPos of(Block block) {
            return new BlockPos(block.getX(), block.getY(), block.getZ());
        }

        public int distSq(int ox, int oy, int oz) {
            int dx = x - ox, dy = y - oy, dz = z - oz;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
