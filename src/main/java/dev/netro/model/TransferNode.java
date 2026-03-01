package dev.netro.model;

/**
 * A transfer node lives at one station and represents one connection endpoint.
 * Two paired transfer nodes form a complete bidirectional rail connection.
 *
 * Physical components:
 *   - One or more transfer switches (divert cart off main line into siding)
 *   - Two hold switches (one near station, one near main line)
 *   - One or more gate slots (powered rails in siding, form the physical queue buffer)
 */
public class TransferNode {

    public enum SetupState {
        PENDING_STATION,
        PENDING_REMOTE,
        PENDING_SWITCHES,
        PENDING_HOLD,
        READY;

        public static SetupState fromDb(String value) {
            if (value == null) return PENDING_STATION;
            return switch (value) {
                case "pending_station" -> PENDING_STATION;
                case "pending_remote" -> PENDING_REMOTE;
                case "pending_switches" -> PENDING_SWITCHES;
                case "pending_hold" -> PENDING_HOLD;
                case "ready" -> READY;
                default -> PENDING_STATION;
            };
        }

        public String toDb() {
            return switch (this) {
                case PENDING_STATION -> "pending_station";
                case PENDING_REMOTE -> "pending_remote";
                case PENDING_SWITCHES -> "pending_switches";
                case PENDING_HOLD -> "pending_hold";
                case READY -> "ready";
            };
        }
    }

    private final String id;
    private String name;
    private String stationId;
    private String pairedNodeId;
    private SetupState setupState;
    private boolean terminal;
    private Integer terminalIndex;
    private boolean releaseReversed;

    public TransferNode(String id, String name) {
        this.id = id;
        this.name = name;
        this.setupState = SetupState.PENDING_STATION;
    }

    public String getId()             { return id; }
    public String getName()           { return name; }
    public String getStationId()      { return stationId; }
    public String getPairedNodeId()   { return pairedNodeId; }
    public SetupState getSetupState() { return setupState; }
    public boolean isTerminal()       { return terminal; }
    public Integer getTerminalIndex() { return terminalIndex; }
    public boolean isReleaseReversed() { return releaseReversed; }

    public void setStationId(String s)        { this.stationId = s; }
    public void setPairedNodeId(String p)     { this.pairedNodeId = p; }
    public void setSetupState(SetupState s)   { this.setupState = s; }
    public void setTerminal(boolean t)        { this.terminal = t; }
    public void setTerminalIndex(Integer idx) { this.terminalIndex = idx; }
    public void setReleaseReversed(boolean r) { this.releaseReversed = r; }

    public boolean isReady()  { return setupState == SetupState.READY; }
    public boolean isPaired() { return pairedNodeId != null; }

    /**
     * Full terminal address for this node: stationAddress + "." + terminalIndex.
     * Returns null if not a terminal or index not yet assigned.
     */
    public String terminalAddress(String stationAddress) {
        if (!terminal || terminalIndex == null) return null;
        return stationAddress + "." + terminalIndex;
    }
}
