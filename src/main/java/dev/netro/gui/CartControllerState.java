package dev.netro.gui;

import org.bukkit.util.Vector;

/**
 * Per-cart state for the controller GUI: cached velocity (for Stop/Start), speed level (1–10), and cruise vs stopped mode.
 * In stopped mode: speed +/- only updates the level; velocity is not applied until Start. Rails and manual control still affect the cart.
 * In cruise mode: we may re-apply set speed (cruise control). Detectors/READY/Min/Max have priority and can yield cruise.
 */
public class CartControllerState {

    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 10;
    /** Minecraft minecart velocity is 0–1 (1 = max). Level 1 → 0.1, level 10 → 1.0. */
    private static final double MIN_VELOCITY = 0.1;
    private static final double MAX_VELOCITY = 1.0;

    private Vector cachedVelocity = new Vector(0, 0, 0);
    private int speedLevel = 5;
    /** When set by a rule (SET_CRUISE_SPEED), use this magnitude instead of level-derived; null = use level. */
    private Double customSpeedMagnitude = null;
    /** false = stopped/yield (we don't apply velocity; rails and detectors have control). true = cruise (we re-apply set speed). */
    private boolean cruiseActive = false;

    public Vector getCachedVelocity() {
        return cachedVelocity.clone();
    }

    public void setCachedVelocity(Vector v) {
        this.cachedVelocity = v != null ? v.clone() : new Vector(0, 0, 0);
    }

    public int getSpeedLevel() {
        return speedLevel;
    }

    public void setSpeedLevel(int level) {
        this.speedLevel = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    /** Target velocity magnitude in Minecraft's 0–1 range (0.1 at level 1, 1.0 at level 10). If custom (from rule) is set, that is used. */
    public double getTargetSpeedMagnitude() {
        if (customSpeedMagnitude != null) return Math.max(0, Math.min(1, customSpeedMagnitude));
        return MIN_VELOCITY + (speedLevel - 1) * (MAX_VELOCITY - MIN_VELOCITY) / (MAX_LEVEL - MIN_LEVEL);
    }

    public void setCustomSpeedMagnitude(Double magnitude) {
        this.customSpeedMagnitude = magnitude;
    }

    /** Speed level (1–10) that best matches the given velocity magnitude (0–1 scale). */
    public static int speedLevelFromMagnitude(double magnitude) {
        if (magnitude <= 0) return MIN_LEVEL;
        int level = (int) Math.round(magnitude * MAX_LEVEL);
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    public boolean isCruiseActive() {
        return cruiseActive;
    }

    public void setCruiseActive(boolean cruiseActive) {
        this.cruiseActive = cruiseActive;
    }
}
