package dev.netro.gui;

/**
 * Stored when the player chooses "Set rail state" in the rule wizard.
 * They must then use the Railroad Controller on a rail to open the direction picker and choose the shape.
 * When editing, editRuleId is set so that when they pick a shape we update that rule instead of opening step3 to create.
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
    String editRuleId
) {
    public PendingSetRailStateRule(String contextType, String contextId, String contextSide,
                                   String triggerType, boolean destinationPositive, String destinationId, String rulesTitle) {
        this(contextType, contextId, contextSide, triggerType, destinationPositive, destinationId, rulesTitle, null);
    }
}
