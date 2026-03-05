package dev.netro.util;

import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.event.block.SignChangeEvent;

/**
 * Shared sign color styling for [Station] signs. Line 1 = type (bold), line 2 = name, line 3 = address.
 */
public final class SignColors {

    private static final String BOLD = "§l";
    private static final String COLOR_STATION = "§9";   // blue – [Station]
    private static final String COLOR_STATION_NAME = "§f";  // white – station name
    private static final String COLOR_STATION_ADDRESS = "§7"; // gray – address

    private SignColors() {}

    public static void applyStationSign(SignChangeEvent event, String name, String address) {
        event.setLine(0, COLOR_STATION + BOLD + "[Station]");
        event.setLine(1, COLOR_STATION_NAME + (name != null ? name : ""));
        event.setLine(2, COLOR_STATION_ADDRESS + (address != null ? address : ""));
    }

    /** Updates the sign block state (front side). Use after editing for 1.20+ SignSide API. */
    public static void applyStationSign(Sign sign, String name, String address) {
        SignSide front = sign.getSide(Side.FRONT);
        if (front != null) {
            front.setLine(0, COLOR_STATION + BOLD + "[Station]");
            front.setLine(1, COLOR_STATION_NAME + (name != null ? name : ""));
            front.setLine(2, COLOR_STATION_ADDRESS + (address != null ? address : ""));
        }
    }
}
