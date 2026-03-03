package dev.netro.gui;

/**
 * Pending relocate: player clicked Relocate; next block click is the target (detector/controller
 * for the node is moved above that block). Source position is resolved from DB by node id.
 */
public sealed interface PendingRelocate {

    /** Waiting for player to click the target block; detector/controller for this node will be moved above it. */
    record Source(String nodeId) implements PendingRelocate {}

    /** Internal: resolved source data used when performing the move. */
    record Dest(
        boolean isDetector,
        String id,
        String nodeId,
        String world,
        int oldBx, int oldBy, int oldBz,
        String signFacing,
        int railX, int railY, int railZ  // for detector only
    ) implements PendingRelocate {}
}
