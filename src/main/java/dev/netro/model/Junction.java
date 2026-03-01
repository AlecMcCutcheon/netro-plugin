package dev.netro.model;

/**
 * A junction on a segment between two transfer nodes. Same physical idea as a
 * hold siding but not tied to a station; used to allow opposing traffic to pass.
 */
public class Junction {

    private final String id;
    private final String name;
    private final String world;
    private final String nodeAId;
    private final String nodeBId;
    private final Integer refX;
    private final Integer refY;
    private final Integer refZ;
    private final String setupState;
    private final boolean releaseReversed;

    public Junction(String id, String name, String world, String nodeAId, String nodeBId,
                    Integer refX, Integer refY, Integer refZ, String setupState, boolean releaseReversed) {
        this.id = id;
        this.name = name;
        this.world = world;
        this.nodeAId = nodeAId;
        this.nodeBId = nodeBId;
        this.refX = refX;
        this.refY = refY;
        this.refZ = refZ;
        this.setupState = setupState;
        this.releaseReversed = releaseReversed;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getWorld() { return world; }
    public String getNodeAId() { return nodeAId; }
    public String getNodeBId() { return nodeBId; }
    public Integer getRefX() { return refX; }
    public Integer getRefY() { return refY; }
    public Integer getRefZ() { return refZ; }
    public String getSetupState() { return setupState; }
    public boolean isReleaseReversed() { return releaseReversed; }
}
