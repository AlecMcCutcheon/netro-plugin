package dev.netro.model;

/**
 * A controller: copper bulb with sign [Controller] + node name + rules.
 * Plugin sets its powered state when RELEASE matches the routing decision (e.g. terminal release).
 */
public class Controller {

    private final String id;
    private final String nodeId;
    private final String world;
    private final int x, y, z;
    private final String signFacing;
    private final String rule1Role;
    private final String rule1Direction;
    private final String rule2Role;
    private final String rule2Direction;
    private final String rule3Role;
    private final String rule3Direction;
    private final String rule4Role;
    private final String rule4Direction;

    public Controller(String id, String nodeId, String world, int x, int y, int z,
                      String signFacing, String rule1Role, String rule1Direction,
                      String rule2Role, String rule2Direction,
                      String rule3Role, String rule3Direction,
                      String rule4Role, String rule4Direction) {
        this.id = id;
        this.nodeId = nodeId;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.signFacing = signFacing;
        this.rule1Role = rule1Role;
        this.rule1Direction = rule1Direction;
        this.rule2Role = rule2Role;
        this.rule2Direction = rule2Direction;
        this.rule3Role = rule3Role;
        this.rule3Direction = rule3Direction;
        this.rule4Role = rule4Role;
        this.rule4Direction = rule4Direction;
    }

    public String getId() { return id; }
    public String getNodeId() { return nodeId; }
    public String getWorld() { return world; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public String getSignFacing() { return signFacing; }
    public String getRule1Role() { return rule1Role; }
    public String getRule1Direction() { return rule1Direction; }
    public String getRule2Role() { return rule2Role; }
    public String getRule2Direction() { return rule2Direction; }
    public String getRule3Role() { return rule3Role; }
    public String getRule3Direction() { return rule3Direction; }
    public String getRule4Role() { return rule4Role; }
    public String getRule4Direction() { return rule4Direction; }

    public boolean isForNode() { return nodeId != null; }
}
