package dev.netro.gui;

/**
 * Stored when the player chooses "Set rail state" in the rule wizard.
 * They must then use the Railroad Controller on a rail to open the direction picker and choose the shape.
 * When editing, editRuleId is set so that when they pick a shape we update that rule instead of opening step3 to create.
 * existingRailStateData = encoded list (entry|entry|...) when adding to list; after shape pick we append and open list.
 * reconfigureIndex = index into existing list to replace when reconfiguring an entry; -1 = not reconfiguring.
 */
public record PendingSetRailStateRule(
    String contextType,
    String contextId,
    String contextSide,
    String triggerType,
    boolean destinationPositive,
    String destinationId,
    String rulesTitle,
    /** When non-null, we are editing this rule; on shape pick update it and go to main. */
    String editRuleId,
    /** Encoded rail state entries when adding another (open list after confirm). Empty/null = first entry. */
    String existingRailStateData,
    /** Index of entry to replace when reconfiguring; -1 = add new. */
    int reconfigureIndex
) {
    public PendingSetRailStateRule(String contextType, String contextId, String contextSide,
                                   String triggerType, boolean destinationPositive, String destinationId, String rulesTitle) {
        this(contextType, contextId, contextSide, triggerType, destinationPositive, destinationId, rulesTitle, null, null, -1);
    }

    public PendingSetRailStateRule(String contextType, String contextId, String contextSide,
                                   String triggerType, boolean destinationPositive, String destinationId, String rulesTitle, String editRuleId) {
        this(contextType, contextId, contextSide, triggerType, destinationPositive, destinationId, rulesTitle, editRuleId, null, -1);
    }
}
