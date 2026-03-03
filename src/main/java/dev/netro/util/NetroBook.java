package dev.netro.util;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the Netro in-game guide book. Content matches docs/GUIDE.md.
 * Page 1 = clickable table of contents; remaining pages = formatted guide text.
 * Uses ~380 chars per content page for readable line count (~12–14 lines).
 */
public final class NetroBook {

    /** Target chars per content page so each page shows ~12–14 lines. */
    private static final int CHARS_PER_PAGE = 380;

    /** Color: dark gray/black only. §8 = dark gray, §0 = black, §r = reset. */
    private static final String C_TITLE = "§8§l§n";
    private static final String C_SECT  = "§8";
    private static final String C_BODY  = "§8";
    private static final String C_RESET = "§r";
    private static final String C_LINK  = "§8";

    private NetroBook() {}

    /** Creates one written guide book with clickable TOC. */
    public static ItemStack createGuideBook() {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        if (meta == null) return item;

        meta.setTitle("Netro Guide");
        meta.setAuthor("Netro");

        List<String> contentPages = buildContentPages();
        int[] sectionStartPage = sectionStartPages(contentPages);

        BaseComponent[][] allPages = new BaseComponent[1 + contentPages.size()][];
        allPages[0] = buildTocPage(sectionStartPage);
        for (int i = 0; i < contentPages.size(); i++) {
            allPages[1 + i] = new BaseComponent[]{ new TextComponent(contentPages.get(i)) };
        }
        meta.spigot().setPages(allPages);

        item.setItemMeta(meta);
        return item;
    }

    /** Build TOC page with clickable links. Page numbers are 1-based; section N links to sectionStartPage[N]. */
    private static BaseComponent[] buildTocPage(int[] sectionStartPage) {
        String[] titles = {
            "1. What Netro Is",
            "2. Concepts",
            "3. Setting Up a Station",
            "4. Detectors and Nodes",
            "5. Controllers",
            "6. Terminals: ENTRY, CLEAR, READY, RELEASE",
            "7. Pairing Transfer Nodes",
            "8. Rules UI and Creating Rules",
            "9. Cart Controller and Destinations",
            "10. Commands",
            "11. Summary: Entry/Clear vs Ready/Release"
        };
        ComponentBuilder cb = new ComponentBuilder("");
        cb.append(C_TITLE + "Netro Guide" + C_RESET).append("\n\n");
        cb.append(C_SECT + "Table of Contents" + C_RESET).append("\n\n");
        for (int i = 0; i < titles.length && i < sectionStartPage.length; i++) {
            int page = sectionStartPage[i];
            String title = titles[i];
            cb.append(C_LINK + title + C_RESET)
                .event(new ClickEvent(ClickEvent.Action.CHANGE_PAGE, String.valueOf(page)));
            cb.append("\n");
        }
        return cb.create();
    }

    /** Determine 1-based page number where each section starts. Page 1 = TOC; first content page = 2. */
    private static int[] sectionStartPages(List<String> contentPages) {
        int[] start = new int[11];
        for (int s = 1; s <= 11; s++) {
            String header = C_SECT + s + ". ";
            for (int i = 0; i < contentPages.size(); i++) {
                String page = contentPages.get(i);
                if (page.startsWith(header) || page.contains("\n" + header)) {
                    start[s - 1] = i + 2; // 1-based; page 2 = first content page
                    break;
                }
            }
            if (start[s - 1] == 0) start[s - 1] = 2;
        }
        return start;
    }

    private static List<String> buildContentPages() {
        String full = getFullGuideText();
        return splitIntoPages(full, CHARS_PER_PAGE);
    }

    /**
     * Split content into pages so section boundaries (--- and section titles) always start at the top of a page.
     * If a section boundary falls within the current page window, we end the page before it so the next page starts with the section header.
     */
    private static List<String> splitIntoPages(String text, int maxChars) {
        List<String> pages = new ArrayList<>();
        int start = 0;
        final String sectionSep = "\n\n---\n\n";
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            if (end < text.length()) {
                int lastNewline = text.lastIndexOf('\n', end);
                if (lastNewline > start) end = lastNewline + 1;
                // If a section boundary is in this page, end the page before it so the section title starts at top of next page
                int sep = text.indexOf(sectionSep, start);
                if (sep >= start && sep < end) {
                    end = sep;
                }
            }
            // Avoid infinite loop: if section separator is at start, end would equal start and we'd never advance
            if (end <= start) {
                start = start + sectionSep.length();
                continue;
            }
            String page = text.substring(start, end).trim();
            if (!page.isEmpty()) pages.add(page);
            start = end;
        }
        return pages;
    }

    /** Section headers use C_SECT; body uses C_BODY; newlines for paragraphs. */
    private static String getFullGuideText() {
        return ""
            + C_SECT + "1. What Netro Is" + C_RESET + "\n\n"
            + C_BODY + "Netro is a Minecraft (Bukkit/Spigot 1.21) plugin for rail networks: stations, transfer nodes, terminals, and cart routing.\n\n"
            + "It lets you: define stations with hierarchical addresses (e.g. 2.4.7.3); attach transfer nodes and terminals to stations; use detector rails and copper bulbs with signs to trigger rules; route carts by destination with on-the-fly shortest path.\n\n"
            + "Carts are tracked in a database (SQLite) so routing and terminal state persist across chunk loads and restarts. There is no collision detection; dispatch is only blocked when the destination node is full or invalid." + C_RESET + "\n\n"
            + "---\n\n"
            + C_SECT + "2. Concepts" + C_RESET + "\n\n"
            + C_BODY + "Station: A named location with a unique address. Created by placing a sign.\n\n"
            + "Transfer node: A switch at a station that can be paired to another at another station. Carts are routed via that pair.\n\n"
            + "Terminal: A parking slot at a station (0-based index). A cart's destination can be a station or a specific terminal.\n\n"
            + "Detector: A sign on a copper bulb next to a rail. When a cart passes, the detector fires with a role and direction.\n\n"
            + "Controller: A sign on a copper bulb turned ON/OFF by rules (e.g. RELEASE, RULE:N).\n\n"
            + "Rules: Stored per node. Each rule has a trigger (ENTERING, CLEARING, DETECTED, BLOCKED), optional destination condition, and an action (set speed, SEND_ON/OFF, set rail state, set destination)." + C_RESET + "\n\n"
            + "---\n\n"
            + C_SECT + "3. Setting Up a Station" + C_RESET + "\n\n"
            + C_BODY + "Stations are created and removed only by signs.\n\n"
            + "1) Place a wall sign where you want the station.\n"
            + "2) Line 1: [Station]\n"
            + "3) Line 2: Station name (e.g. Hub, Snowy2). Must be unique.\n"
            + "4) Finish editing.\n\n"
            + "The plugin assigns an address from the sign position and writes it on the sign. Breaking the sign removes the station. The plugin keeps chunks loaded for detector rails as needed." + C_RESET + "\n\n"
            + "---\n\n"
            + C_SECT + "4. Detectors and Nodes" + C_RESET + "\n\n"
            + C_BODY + "Detectors are signs on copper bulbs adjacent to a rail. The sign's first line sets the type.\n\n"
            + "[Transfer] Line 2 = StationName:NodeName. Boundary for a transfer node. Roles: ENTRY, CLEAR.\n\n"
            + "[Terminal] Line 2 = StationName:NodeName. Boundary for a terminal. Roles: ENTRY, CLEAR, READY (one READY per terminal).\n\n"
            + "[Detector] Line 2 = StationName:NodeName or node name. Generic detector. Roles: ENTRY, READY, CLEAR.\n\n"
            + "Line 2 for [Transfer]/[Terminal] must be StationName:NodeName. The copper bulb must be next to a rail. Breaking the sign or bulb unregisters the detector.\n\n"
            + "Roles (lines 3–4): Space-separated. Add direction (L/R) so the role only fires when the cart moves that way. Shorthand: ENT, REA, CLE, REL.\n\n"
            + "ENTRY: Fires when entering the node. CLEAR: Fires when leaving; direction is respected. READY: (Terminals only.) Holds the cart; used with RELEASE. Only ENTRY and CLEAR apply rules; READY does not. RELEASE: On controller signs; turned ON when the plugin releases a cart." + C_RESET + "\n\n"
            + "---\n\n"
            + C_SECT + "5. Controllers" + C_RESET + "\n\n"
            + C_BODY + "A controller is a sign on a copper bulb that the plugin powers ON or OFF.\n\n"
            + "RELEASE: Turns ON controllers with REL when releasing the next cart from a terminal. Wire to your release mechanism.\n\n"
            + "RULE:N: When a rule with SEND_ON or SEND_OFF fires, controllers with RULE:N for that node are set ON or OFF. Optional :L/:R for direction.\n\n"
            + "Controller sign: Line 1 [Controller], Line 2 StationName:NodeName. Lines 3–4: at least one of REL or RULE:N." + C_RESET + "\n\n"
            + "---\n\n"
            + C_SECT + "6. Terminals: ENTRY, CLEAR, READY, RELEASE" + C_RESET + "\n\n"
            + C_BODY + "Terminals are parking slots. Flow:\n\n"
            + "1) ENTRY: Cart passes in entering direction. No slot booking; used for rules.\n\n"
            + "2) READY: Cart passes the READY detector. Plugin: increments held count; marks cart held; if destination is this terminal, clears destination; if first in line and dispatch allowed, turns RELEASE ON; brief center hold.\n\n"
            + "3) CLEAR: Cart passes in clearing direction. Plugin: decrements held count; clears held state; turns OFF RELEASE; may turn RELEASE ON for the next cart.\n\n"
            + "READY = cart held in slot. RELEASE = power release mechanism. CLEAR = cart left, update state." + C_RESET + "\n\n"
            + "---\n\n"
            + C_SECT + "7. Pairing Transfer Nodes" + C_RESET + "\n\n"
            + C_BODY + "Transfer nodes at two stations can be paired. Routing uses pairs to compute paths.\n\n"
            + "1) /netro railroadcontroller, then right-click the [Station] sign.\n"
            + "2) Click the transfer node (opens Node Options: Open rules, Relocate, Delete), then Open rules.\n"
            + "3) Click Pair transfer node..., choose the other station and node, confirm.\n\n"
            + "Unpair in the same Rules UI. Relocate is also in Node Options. Routing is on the fly; no rebuild needed." + C_RESET + "\n\n"
            + "---\n\n"
            + C_SECT + "8. Rules UI and Creating Rules" + C_RESET + "\n\n"
            + C_BODY + "Open Rules: Sneak + right-click a [Transfer] or [Terminal] sign. Or: Railroad Controller → right-click [Station] → click node (Node Options) → Open rules. Relocate: from Rules slot 52 or Node Options; click block to place above (one-click). Block above crosshair is highlighted; for Set rail state, the rail you look at is highlighted.\n\n"
            + "Layout: Slots 0–44 = rules. Slot 45 = default blocked policy. Slot 46 = Pair (transfer only). Slot 49 = Create rule. Slot 52 = Relocate (move detector/controller; click block to place above). Slot 53 = Close.\n\n"
            + "Create rule: Trigger → Destination → Action.\n\n"
            + "Trigger: When cart enters (ENTERING = ENTRY only; READY does not apply rules); when cart clears (CLEARING); when terminal blocked (BLOCKED); when cart detected (DETECTED = ENTRY or CLEAR).\n\n"
            + "Destination: Going to / not going to a destination; any destination; not any destination.\n\n"
            + "Action: Turn bulb ON/OFF (SEND_ON/SEND_OFF); Set rail state (right-click normal rail with Railroad Controller; Cancel in shape picker = back to Action menu); Set cart speed (cruise). For BLOCKED, use Set destination to a redirect." + C_RESET + "\n\n"
            + "---\n\n"
            + C_SECT + "9. Cart Controller and Destinations" + C_RESET + "\n\n"
            + C_BODY + "Cart Controller: /netro cartcontroller. Right-click a cart to open the menu: Stop, Lower, Disable Cruise (center), Increase, Start; Direction; Destination (station or station:terminal). Adjust speed (1–10) when in cruise.\n\n"
            + "Command: /netro setdestination <address|name|Station:Terminal>. Examples: 2.4.7.3, Snowy2, Snowy2:0.\n\n"
            + "Carts without a destination may be assigned one when they pass a detector, or removed from tracking if no terminals." + C_RESET + "\n\n"
            + "---\n\n"
            + C_SECT + "10. Commands" + C_RESET + "\n\n"
            + C_BODY + "All under /netro:\n\n"
            + "debug – Toggle debug logging.\n"
            + "guide – Give this book.\n"
            + "station list – List stations.\n"
            + "setdestination – Set a cart's destination.\n"
            + "dns – Address lookup.\n"
            + "cartcontroller – Give Cart Controller.\n"
            + "railroadcontroller – Give Railroad Controller." + C_RESET + "\n\n"
            + "---\n\n"
            + C_SECT + "11. Summary: Entry/Clear vs Ready/Release" + C_RESET + "\n\n"
            + C_BODY + "ENTRY/CLEAR on a detector sign define when the detector fires (by direction). ENTERING/CLEARING in rules match those events. Only ENTRY and CLEAR apply rules; READY does not.\n\n"
            + "READY (one per terminal) = cart held at that slot. READY detectors do not run rules.\n\n"
            + "RELEASE on a controller = power the release mechanism.\n\n"
            + "CLEAR = cart left the slot; count decremented; next cart may get RELEASE.\n\n"
            + "ENTRY → entering; READY → held in slot; RELEASE → mechanism on; CLEAR → left, update state." + C_RESET;
    }
}
