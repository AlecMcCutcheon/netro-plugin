package dev.netro.model;

/**
 * A rule stored in context of a transfer node or terminal.
 * Replaces station-level ROUTE/TRANSFER behavior with configurable trigger + destination + action.
 */
public class Rule {

    public static final String CONTEXT_TRANSFER = "transfer";
    public static final String CONTEXT_TERMINAL = "terminal";

    public static final String TRIGGER_ENTERING = "ENTERING";
    public static final String TRIGGER_CLEARING = "CLEARING";
    /** Deprecated: BLOCKED trigger and SET_DESTINATION action no longer used; kept for loading existing rules from DB. */
    public static final String TRIGGER_BLOCKED = "BLOCKED";
    /** Whenever a cart is detected (any pass: ENTRY, READY, or CLEAR). */
    public static final String TRIGGER_DETECTED = "DETECTED";

    public static final String ACTION_SET_RAIL_STATE = "SET_RAIL_STATE";
    /** Deprecated: only used with BLOCKED trigger; kept for loading existing rules from DB. */
    public static final String ACTION_SET_DESTINATION = "SET_DESTINATION";
    /** Set cart cruise speed (action_data = speed string e.g. "2.5" for 0.25 magnitude). */
    public static final String ACTION_SET_CRUISE_SPEED = "SET_CRUISE_SPEED";
    public static final String ACTION_SEND_ON = "SEND_ON";
    public static final String ACTION_SEND_OFF = "SEND_OFF";

    private final String id;
    private final String contextType;
    private final String contextId;
    private final String contextSide;
    private final int ruleIndex;
    private final String triggerType;
    private final boolean destinationPositive;
    private final String destinationId;
    private final String actionType;
    private final String actionData;
    private final long createdAt;

    public Rule(String id, String contextType, String contextId, String contextSide,
                int ruleIndex, String triggerType, boolean destinationPositive, String destinationId,
                String actionType, String actionData, long createdAt) {
        this.id = id;
        this.contextType = contextType;
        this.contextId = contextId;
        this.contextSide = contextSide;
        this.ruleIndex = ruleIndex;
        this.triggerType = triggerType;
        this.destinationPositive = destinationPositive;
        this.destinationId = destinationId;
        this.actionType = actionType;
        this.actionData = actionData;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getContextType() { return contextType; }
    public String getContextId() { return contextId; }
    public String getContextSide() { return contextSide; }
    public int getRuleIndex() { return ruleIndex; }
    public String getTriggerType() { return triggerType; }
    public boolean isDestinationPositive() { return destinationPositive; }
    public String getDestinationId() { return destinationId; }
    public String getActionType() { return actionType; }
    public String getActionData() { return actionData; }
    public long getCreatedAt() { return createdAt; }
}
