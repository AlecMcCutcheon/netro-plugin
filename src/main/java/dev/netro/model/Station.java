package dev.netro.model;

/**
 * A named station with a unique 2D hierarchical address (e.g. OV:E2:N3:01:02:05 for Overworld, NE:W1:S2:02:03:01 for Nether).
 */
public class Station {

    private final String id;
    private final String name;
    private final String address;
    private final String world;
    private final int dimension;
    private final int signX;
    private final int signY;
    private final int signZ;
    private final long createdAt;

    public Station(String id, String name, String address, String world,
                   int dimension, int signX, int signY, int signZ, long createdAt) {
        this.id = id;
        this.name = name;
        this.address = address;
        this.world = world;
        this.dimension = dimension;
        this.signX = signX;
        this.signY = signY;
        this.signZ = signZ;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getAddress() { return address; }
    public String getWorld() { return world; }
    /** 0 = Overworld, 1 = Nether. */
    public int getDimension() { return dimension; }
    public int getSignX() { return signX; }
    public int getSignY() { return signY; }
    public int getSignZ() { return signZ; }
    public long getCreatedAt() { return createdAt; }
}
