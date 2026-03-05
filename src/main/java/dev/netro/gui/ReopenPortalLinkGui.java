package dev.netro.gui;

/**
 * After the player saves a portal link (right-click), reopen the Portal Link GUI
 * with this context so they see the updated state (e.g. button shown as enchanted).
 */
public record ReopenPortalLinkGui(String nodeId, String pairedNodeId, String pairedLabel, String rulesTitle) {}
