package dev.netro.gui;

/**
 * Stored when the player chooses "Set rail state" in the rule wizard.
 * They must then use the Railroad Controller on a rail to open the direction picker and choose the shape.
 */
public record PendingSetRailStateRule(
    String contextType,
    String contextId,
    String contextSide,
    String triggerType,
    boolean destinationPositive,
    String destinationId
) {}
