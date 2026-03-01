package dev.netro.util;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

/**
 * Builds the Netro in-game guide book using Spigot's BookMeta.Spigot() and
 * BaseComponent[] so pages are serialized correctly (not shown as raw JSON).
 * Page limit ~256 chars per page; use CHANGE_PAGE for ToC (keeps book open).
 */
public final class NetroBook {

    private NetroBook() {}

    /** Creates one written book. Page numbers in ToC links are 1-based. */
    public static ItemStack createGuideBook() {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        if (meta == null) return item;

        meta.setTitle("Netro Guide");
        meta.setAuthor("Netro");

        // Use Spigot's component API so the client gets proper format, not raw strings
        meta.spigot().setPages(
            page1Title(),
            page2Toc(),
            page3HowItWorks1(),
            page4HowItWorks2(),
            page5Signals1(),
            page6Signals2(),
            page7SetupStation(),
            page8SetupTransfer(),
            page8SetupTransfer2(),
            page9Wands(),
            page10CauseEffect1(),
            page11CauseEffect2(),
            page12JunctionRule()
        );

        item.setItemMeta(meta);
        return item;
    }

    private static BaseComponent[] page1Title() {
        return new BaseComponent[]{
            new TextComponent(
                "Netro Guide\n\n" +
                "Rail network: stations,\n" +
                "transfer nodes, junctions.\n" +
                "Detectors & controllers\n" +
                "on copper bulbs.\n\n" +
                "Use the table of\n" +
                "contents (next page)\n" +
                "to jump to sections."
            )
        };
    }

    private static BaseComponent[] page2Toc() {
        return new BaseComponent[]{
            new TextComponent("Contents\n\n"),
            link("How it works", 3),
            new TextComponent("\n"),
            link("Signals & shorthand", 5),
            new TextComponent("\n"),
            link("Setup: station", 7),
            new TextComponent("\n"),
            link("Setup: transfer", 8),
            new TextComponent("\n"),
            link("Wands", 10),
            new TextComponent("\n"),
            link("Cause & effect", 11),
            new TextComponent("\n"),
            link("Junction rule", 13)
        };
    }

    private static TextComponent link(String label, int pageNumber) {
        TextComponent t = new TextComponent(label);
        t.setClickEvent(new ClickEvent(ClickEvent.Action.CHANGE_PAGE, String.valueOf(pageNumber)));
        t.setUnderlined(true);
        t.setColor(net.md_5.bungee.api.ChatColor.DARK_BLUE);
        return t;
    }

    private static BaseComponent[] page3HowItWorks1() {
        return new BaseComponent[]{
            new TextComponent(
                "How it works\n\n" +
                "Netro splits work into\n" +
                "two parts:\n\n" +
                "The plugin decides:\n" +
                "• Where the cart goes\n" +
                "  next (routing).\n" +
                "• When it is safe to\n" +
                "  hold or release it.\n" +
                "• Which direction the\n" +
                "  cart is traveling.\n\n" +
                "The plugin never moves\n" +
                "rails or powers them\n" +
                "directly. It only\n" +
                "toggles copper bulbs."
            )
        };
    }

    private static BaseComponent[] page4HowItWorks2() {
        return new BaseComponent[]{
            new TextComponent(
                "What you do:\n\n" +
                "• Build the track,\n" +
                "  sidings, and switches.\n" +
                "• Place copper bulbs\n" +
                "  and signs so the\n" +
                "  plugin knows what is\n" +
                "  a detector and what\n" +
                "  is a controller.\n" +
                "• Wire each bulb to\n" +
                "  your redstone (the\n" +
                "  switch, the gate).\n\n" +
                "Why? So you choose\n" +
                "how to build. The\n" +
                "plugin only says \"turn\n" +
                "this bulb on or off\";\n" +
                "your circuit does the\n" +
                "rest."
            )
        };
    }

    private static BaseComponent[] page5Signals1() {
        return new BaseComponent[]{
            new TextComponent(
                "Signals & shorthand\n\n" +
                "A detector is a copper\n" +
                "bulb + sign: you tell\n" +
                "the plugin \"a cart\n" +
                "passed here.\" The\n" +
                "plugin uses that for\n" +
                "segment occupancy and\n" +
                "(at junctions) to drive\n" +
                "controller bulbs.\n\n" +
                "Sign line 1: [Transfer]\n" +
                "= transfer boundary\n" +
                "(one per node); [Terminal]\n" +
                "= terminal; [Junction]\n" +
                "= junction side. Or\n" +
                "[Detector] = generic.\n" +
                "[Controller] = bulb\n" +
                "plugin drives (junctions,\n" +
                "terminals). Valid signs\n" +
                "get colored by the plugin."
            )
        };
    }

    private static BaseComponent[] page6Signals2() {
        return new BaseComponent[]{
            new TextComponent(
                "Detector roles: ENT=\n" +
                "ENTRY, REA=READY,\n" +
                "CLE=CLEAR. :L/:R =\n" +
                "LEFT/RIGHT. Transfer\n" +
                "nodes only use ENT and\n" +
                "CLE (segment boundary).\n\n" +
                "Controller roles (used\n" +
                "at junctions, and at\n" +
                "terminals for queues):\n" +
                "DIV=DIVERT, NOD=\n" +
                "NOT_DIVERT, REL=RELEASE.\n" +
                "+ means CLEAR turns it\n" +
                "off. Station-level:\n" +
                "TRANSFER, NOT_TRANSFER.\n" +
                "Rest of book uses these."
            )
        };
    }

    private static BaseComponent[] page7SetupStation() {
        return new BaseComponent[]{
            new TextComponent(
                "Setup: Station\n\n" +
                "A station is a named\n" +
                "place (e.g. CentralHub).\n" +
                "The plugin gives it an\n" +
                "address from world\n" +
                "coords so carts can\n" +
                "route there.\n\n" +
                "1. Place a sign.\n" +
                "2. Line 1: [Station]\n" +
                "3. Line 2: station name\n\n" +
                "Address appears on the\n" +
                "sign. Use /station info\n" +
                "for details. You need\n" +
                "a station before any\n" +
                "transfer nodes at it."
            )
        };
    }

    private static BaseComponent[] page8SetupTransfer() {
        return new BaseComponent[]{
            new TextComponent(
                "Setup: Transfer\n\n" +
                "A transfer node is one\n" +
                "end of a link between\n" +
                "two stations. One sign\n" +
                "per node.\n\n" +
                "1. Copper bulb by the\n" +
                "   rail at the boundary.\n" +
                "2. Sign: Line 1 [Transfer]\n" +
                "   Line 2 Station:Node\n" +
                "   Lines 3-4 ENT:L CLE:R\n\n" +
                "If the node doesn't exist\n" +
                "yet but the station does,\n" +
                "placing the sign creates\n" +
                "the node. Errors show in\n" +
                "chat only."
            )
        };
    }

    private static BaseComponent[] page8SetupTransfer2() {
        return new BaseComponent[]{
            new TextComponent(
                "Setup: Transfer (cont.)\n\n" +
                "DIV, NOD, REL are for\n" +
                "junctions and terminals.\n" +
                "Transfer = ENT/CLE only.\n\n" +
                "Terminal: [Terminal] sign\n" +
                "with Station:Node; add\n" +
                "READY/RELEASE for queue.\n" +
                "Same create-on-first.\n\n" +
                "Junction: Line 2 = junction\n" +
                "name. First detector\n" +
                "creates the junction.\n\n" +
                "3. Pair with pairing wand\n" +
                "   (click each end). Link\n" +
                "   is then live."
            )
        };
    }

    private static BaseComponent[] page9Wands() {
        return new BaseComponent[]{
            new TextComponent(
                "Wands\n\n" +
                "/netro pairingwand:\n" +
                "Get the wand, then click\n" +
                "transfer detector A,\n" +
                "then transfer detector B.\n" +
                "That links the two nodes\n" +
                "so carts can route between\n" +
                "them. No typing names.\n\n" +
                "/netro segmentwand:\n" +
                "Click transfer A, then\n" +
                "junction detector(s) in\n" +
                "order, then transfer B.\n" +
                "Tells the plugin which\n" +
                "junctions are on that\n" +
                "segment. /absorb wand:\n" +
                "click signs to re-register\n" +
                "after DB reset or move."
            )
        };
    }

    private static BaseComponent[] page10CauseEffect1() {
        return new BaseComponent[]{
            new TextComponent(
                "Cause & effect\n\n" +
                "At a transfer node:\n" +
                "only ENT and CLE matter.\n" +
                "They tell the plugin\n" +
                "when a cart entered or\n" +
                "left the segment (for\n" +
                "traffic). No DIV/REL\n" +
                "there.\n\n" +
                "At a junction (siding\n" +
                "between two nodes):\n" +
                "ENT:L/R fires -> plugin\n" +
                "decides divert or not,\n" +
                "sets DIV:L/R and NOD:L/R\n" +
                "on [Controller] bulbs so\n" +
                "your redstone throws the\n" +
                "switch. REA (cart stopped\n" +
                "in junction) -> when safe,\n" +
                "plugin turns REL on so\n" +
                "you can open the gate."
            )
        };
    }

    private static BaseComponent[] page11CauseEffect2() {
        return new BaseComponent[]{
            new TextComponent(
                "Cause & effect (cont.)\n\n" +
                "CLE (cart left junction\n" +
                "or terminal): Plugin\n" +
                "turns DIV+, NOD+, and\n" +
                "REL OFF so the bulb\n" +
                "resets. CLE never turns\n" +
                "a controller ON; it only\n" +
                "turns the + roles and\n" +
                "RELEASE off. Your switch\n" +
                "returns to default until\n" +
                "the next cart.\n\n" +
                "Terminals: can have\n" +
                "READY/RELEASE for their\n" +
                "arrival/departure queue.\n" +
                "Same idea: REA -> REL on\n" +
                "when safe; CLE -> REL off."
            )
        };
    }

    private static BaseComponent[] page12JunctionRule() {
        return new BaseComponent[]{
            new TextComponent(
                "Junction rule\n\n" +
                "A junction is a siding\n" +
                "between two transfer\n" +
                "nodes so two carts can\n" +
                "pass. When you register\n" +
                "the segment with the\n" +
                "segment wand, order\n" +
                "matters: only connect\n" +
                "RIGHT of one junction to\n" +
                "LEFT of the next (and\n" +
                "vice versa). That keeps\n" +
                "the path A - J1 - J2 - B\n" +
                "clear so the plugin\n" +
                "knows who is \"past\" the\n" +
                "junction for traffic."
            )
        };
    }
}
