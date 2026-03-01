package dev.netro.signal;

import dev.netro.NetroPlugin;

/**
 * Manages station lecterns and signal bindings (schema: lecterns, signal_bindings).
 * Updates lectern display levels based on events (e.g. cart arrived, gate state).
 */
public class SignalEngine {

    private final NetroPlugin plugin;

    public SignalEngine(NetroPlugin plugin) {
        this.plugin = plugin;
    }

    public void setLevel(String stationId, String lecternLabel, int level) {
        // TODO: update lecterns table and block state at (x,y,z)
    }

    public void onEvent(String stationId, String eventType) {
        // TODO: find signal_bindings for event_type, set target_level on bound lecterns
    }
}
