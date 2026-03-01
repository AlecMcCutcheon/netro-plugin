package dev.netro.model;

/**
 * Station-level detector (ROUTE): at the decision point before the junction.
 * Line 2 = station name; rules ROUTE/ROU. Fires routing and sets station controllers (TRANSFER/NOT_TRANSFER).
 */
public class StationDetector {

    private final String id;
    private final String stationId;
    private final String world;
    private final int x, y, z;
    private final int railX, railY, railZ;
    private final String signFacing;
    private final String rule1Role;
    private final String rule1Direction;
    private final String rule2Role;
    private final String rule2Direction;
    private final String rule3Role;
    private final String rule3Direction;
    private final String rule4Role;
    private final String rule4Direction;
    private final String setDestValue;

    public StationDetector(String id, String stationId, String world, int x, int y, int z,
                           int railX, int railY, int railZ, String signFacing,
                           String rule1Role, String rule1Direction,
                           String rule2Role, String rule2Direction,
                           String rule3Role, String rule3Direction,
                           String rule4Role, String rule4Direction,
                           String setDestValue) {
        this.id = id;
        this.stationId = stationId;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.railX = railX;
        this.railY = railY;
        this.railZ = railZ;
        this.signFacing = signFacing;
        this.rule1Role = rule1Role;
        this.rule1Direction = rule1Direction;
        this.rule2Role = rule2Role;
        this.rule2Direction = rule2Direction;
        this.rule3Role = rule3Role;
        this.rule3Direction = rule3Direction;
        this.rule4Role = rule4Role;
        this.rule4Direction = rule4Direction;
        this.setDestValue = setDestValue;
    }

    public String getId() { return id; }
    public String getStationId() { return stationId; }
    public String getWorld() { return world; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public int getRailX() { return railX; }
    public int getRailY() { return railY; }
    public int getRailZ() { return railZ; }
    public String getSignFacing() { return signFacing; }
    public String getRule1Role() { return rule1Role; }
    public String getRule1Direction() { return rule1Direction; }
    public String getRule2Role() { return rule2Role; }
    public String getRule2Direction() { return rule2Direction; }
    public String getRule3Role() { return rule3Role; }
    public String getRule3Direction() { return rule3Direction; }
    public String getRule4Role() { return rule4Role; }
    public String getRule4Direction() { return rule4Direction; }
    /** Destination string for SET_DEST rule (e.g. Snowy2 or Snowy2:0). Null when no SET_DEST. */
    public String getSetDestValue() { return setDestValue; }
}
