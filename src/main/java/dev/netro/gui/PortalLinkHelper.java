package dev.netro.gui;

import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.database.TransferNodePortalRepository;
import dev.netro.util.DimensionHelper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Helpers for portal link flow: expected dimension for a side, overlap checks.
 */
public final class PortalLinkHelper {

    private PortalLinkHelper() {}

    /**
     * Dimension (0 = Overworld, 1 = Nether) that the portal must be in for the given node and side.
     * Side 0 = same dimension as node's station; side 1 = other dimension.
     */
    public static int getExpectedDimensionForSide(String nodeId, int side, dev.netro.database.Database database) {
        TransferNodeRepository nodeRepo = new TransferNodeRepository(database);
        StationRepository stationRepo = new StationRepository(database);
        int nodeDim = nodeRepo.findById(nodeId)
            .flatMap(n -> stationRepo.findById(n.getStationId()))
            .map(s -> s.getDimension())
            .orElse(DimensionHelper.DIMENSION_OVERWORLD);
        return side == TransferNodePortalRepository.SIDE_SAME_DIMENSION
            ? nodeDim
            : (nodeDim == DimensionHelper.DIMENSION_OVERWORLD ? DimensionHelper.DIMENSION_NETHER : DimensionHelper.DIMENSION_OVERWORLD);
    }

    /**
     * True if any block in {@code newBlocks} has the same (world, x, y, z) as any block in {@code existingBlocks}.
     */
    public static boolean anyBlockOverlaps(
        List<TransferNodePortalRepository.BlockPos> newBlocks,
        List<TransferNodePortalRepository.BlockPos> existingBlocks
    ) {
        if (existingBlocks == null || existingBlocks.isEmpty()) return false;
        Set<String> existing = new HashSet<>();
        for (TransferNodePortalRepository.BlockPos p : existingBlocks) {
            existing.add(p.world() + "|" + p.x() + "|" + p.y() + "|" + p.z());
        }
        for (TransferNodePortalRepository.BlockPos p : newBlocks) {
            if (existing.contains(p.world() + "|" + p.x() + "|" + p.y() + "|" + p.z())) return true;
        }
        return false;
    }
}
