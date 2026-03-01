package dev.netro.transfer;

import org.bukkit.Location;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-player state for guided transfer node setup: click station sign (own station),
 * then transfer switches, hold rails, gate slots. Linking is done separately with /transfer pair.
 */
public class TransferSetupWizard {

    public static final int STEP_CLICK_OWN_STATION = 1;
    public static final int STEP_CLICK_REMOTE_STATION = 2;
    public static final int STEP_CLICK_TRANSFER_SWITCHES = 3;
    public static final int STEP_CLICK_HOLD_RAILS = 4;
    public static final int STEP_CLICK_GATE_SLOTS = 5;

    private final UUID playerId;
    private String nodeId;  // set when node is created at step 1
    private final String nodeName;
    private int step = STEP_CLICK_OWN_STATION;

    private String stationId;
    private String remoteStationId;
    private int signX, signY, signZ;

    private final List<BlockPos> transferSwitches = new ArrayList<>();
    private final List<BlockPos> holdSwitches = new ArrayList<>();
    private final List<BlockPos> gateSlots = new ArrayList<>();

    public TransferSetupWizard(UUID playerId, String nodeName) {
        this.playerId = playerId;
        this.nodeName = nodeName;
    }

    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public UUID getPlayerId() { return playerId; }
    public String getNodeId() { return nodeId; }
    public String getNodeName() { return nodeName; }
    public int getStep() { return step; }
    public String getStationId() { return stationId; }
    public String getRemoteStationId() { return remoteStationId; }
    public int getSignX() { return signX; }
    public int getSignY() { return signY; }
    public int getSignZ() { return signZ; }
    public List<BlockPos> getTransferSwitches() { return transferSwitches; }
    public List<BlockPos> getHoldSwitches() { return holdSwitches; }
    public List<BlockPos> getGateSlots() { return gateSlots; }

    public void setStation(String stationId, int signX, int signY, int signZ) {
        this.stationId = stationId;
        this.signX = signX;
        this.signY = signY;
        this.signZ = signZ;
    }

    public void setRemoteStation(String remoteStationId) {
        this.remoteStationId = remoteStationId;
    }

    public void advanceStep() { step++; }
    public void setStep(int step) { this.step = step; }

    public boolean isTerminal() {
        return stationId != null && stationId.equals(remoteStationId);
    }

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

        public static BlockPos of(Location loc) {
            return new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }

        public int distSq(int ox, int oy, int oz) {
            int dx = x - ox, dy = y - oy, dz = z - oz;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
