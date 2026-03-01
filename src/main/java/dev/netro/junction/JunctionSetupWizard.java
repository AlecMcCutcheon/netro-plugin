package dev.netro.junction;

import java.util.ArrayList;
import java.util.List;

/**
 * State for guided junction setup: click Side A, Side B, then gate slots, then done.
 */
public class JunctionSetupWizard {

    public enum Step { SIDE_A, SIDE_B, GATES }

    private final String junctionId;
    private final String junctionName;
    private Step step = Step.SIDE_A;
    private int sideAX, sideAY, sideAZ;
    private int sideBX, sideBY, sideBZ;
    private final List<int[]> gateSlots = new ArrayList<>();

    public JunctionSetupWizard(String junctionId, String junctionName) {
        this.junctionId = junctionId;
        this.junctionName = junctionName;
    }

    public String getJunctionId() { return junctionId; }
    public String getJunctionName() { return junctionName; }
    public Step getStep() { return step; }
    public void setStep(Step step) { this.step = step; }
    public int getSideAX() { return sideAX; }
    public int getSideAY() { return sideAY; }
    public int getSideAZ() { return sideAZ; }
    public void setSideA(int x, int y, int z) { sideAX = x; sideAY = y; sideAZ = z; }
    public int getSideBX() { return sideBX; }
    public int getSideBY() { return sideBY; }
    public int getSideBZ() { return sideBZ; }
    public void setSideB(int x, int y, int z) { sideBX = x; sideBY = y; sideBZ = z; }
    public List<int[]> getGateSlots() { return gateSlots; }
    public void addGateSlot(int x, int y, int z) { gateSlots.add(new int[] { x, y, z }); }
}
