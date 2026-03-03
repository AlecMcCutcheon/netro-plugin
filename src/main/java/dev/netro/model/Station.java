package dev.netro.model;

/**
 * A named station with a unique hierarchical address (e.g. 2.4.7.3).
 */
public class Station {

    private final String id;
    private final String name;
    private final String address;
    private final String world;
    private final int signX;
    private final int signY;
    private final int signZ;
    private final long createdAt;

    public Station(String id, String name, String address, String world,
                   int signX, int signY, int signZ, long createdAt) {
        this.id = id;
        this.name = name;
        this.address = address;
        this.world = world;
        this.signX = signX;
        this.signY = signY;
        this.signZ = signZ;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getAddress() { return address; }
    public String getWorld() { return world; }
    public int getSignX() { return signX; }
    public int getSignY() { return signY; }
    public int getSignZ() { return signZ; }
    public long getCreatedAt() { return createdAt; }
}
