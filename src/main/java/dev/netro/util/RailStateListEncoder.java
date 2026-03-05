package dev.netro.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes/decodes multiple rail state entries for rule action_data.
 * Format: entry1|entry2|... where each entry is world,x,y,z,shapeName.
 * Single entry (no pipe) is valid for backward compatibility.
 */
public final class RailStateListEncoder {

    private static final String ENTRY_SEP = "|";

    private RailStateListEncoder() {}

    /** Parse action_data into a list of "world,x,y,z,shape" strings. */
    public static List<String> parseEntries(String actionData) {
        List<String> out = new ArrayList<>();
        if (actionData == null || actionData.isBlank()) return out;
        String s = actionData.strip();
        if (s.contains(ENTRY_SEP)) {
            for (String entry : s.split("\\" + ENTRY_SEP, -1)) {
                String t = entry.strip();
                if (!t.isEmpty()) out.add(t);
            }
        } else {
            out.add(s);
        }
        return out;
    }

    /** Encode a list of "world,x,y,z,shape" entries into action_data. */
    public static String encodeEntries(List<String> entries) {
        if (entries == null || entries.isEmpty()) return "";
        if (entries.size() == 1) return entries.get(0);
        return String.join(ENTRY_SEP, entries);
    }

    /** Format one entry "world,x,y,z,shape" for display: "x, y, z: Shape Name". */
    public static String formatEntryDisplay(String entry) {
        if (entry == null) return "—";
        String[] parts = entry.split(",", -1);
        if (parts.length >= 5) {
            String shape = parts[4].trim().replace("_", " ");
            return parts[1] + ", " + parts[2] + ", " + parts[3] + " → " + shape;
        }
        return entry.replace("_", " ");
    }
}
