package dev.netro.util;

/**
 * Helpers for reading sign text that may contain Minecraft color/format codes (§ or &amp;).
 * Use when absorbing, editing, or parsing sign lines so values are read correctly with or without formatting.
 */
public final class SignTextHelper {

    private static final String SECTION_SIGN = "\u00A7";
    /** One character after § or & that is a valid Minecraft color/format code (0-9, a-f, A-F, k, l, m, n, o, r and uppercase). */
    private static final String AMPERSAND_CODES = "0123456789aAbBcCdDeEfFkKlLmMnNoOrR";

    private SignTextHelper() {}

    /**
     * Strip Minecraft color and format codes from sign text so it can be parsed reliably.
     * Removes § (section sign) + one character, and &amp; + one character when that character is a valid code.
     */
    public static String stripFormatting(String raw) {
        if (raw == null || raw.isEmpty()) return raw == null ? "" : raw;
        String s = raw;
        // § + any one character (color/format)
        s = s.replaceAll(SECTION_SIGN + ".", "");
        // & + valid code character (so we don't strip & in e.g. "Station & Node")
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && AMPERSAND_CODES.indexOf(s.charAt(i + 1)) >= 0) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Read a sign line: null-safe, strip formatting, then trim. Use this for any sign line used for parsing or comparison.
     */
    public static String readSignLine(String raw) {
        if (raw == null) return "";
        return stripFormatting(raw).strip();
    }
}
