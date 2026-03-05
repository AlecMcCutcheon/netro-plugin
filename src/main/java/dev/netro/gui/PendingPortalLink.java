package dev.netro.gui;

import dev.netro.database.TransferNodePortalRepository;

/**
 * Pending nether portal link: player chose "Portal - This dimension" or "Portal - Other dimension" for this node;
 * next valid portal they look at and right-click will be saved as this node's portal blocks for that side.
 */
public record PendingPortalLink(String nodeId, int side) {
    public static PendingPortalLink sameDimension(String nodeId) {
        return new PendingPortalLink(nodeId, TransferNodePortalRepository.SIDE_SAME_DIMENSION);
    }
    public static PendingPortalLink otherDimension(String nodeId) {
        return new PendingPortalLink(nodeId, TransferNodePortalRepository.SIDE_OTHER_DIMENSION);
    }
}
