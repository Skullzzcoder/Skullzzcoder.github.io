import dev.skullzz.mirage.client.RyneGui;

/** Drives the click GUI's model and prints what it decided, one case per line. */
public class Harness {
    static int fails = 0;

    static void check(String what, boolean ok) {
        if (!ok) { System.out.println("FAIL " + what); fails++; }
    }

    public static void main(String[] args) {
        RyneGui gui = new RyneGui();
        RyneGui.Panel a = gui.add(new RyneGui.Panel("a", "Alpha", 10, 10));
        a.add("one", RyneGui.Kind.TOGGLE, () -> {}, () -> true);
        a.add("two", RyneGui.Kind.ACTION, () -> {}, null);
        a.add("three", RyneGui.Kind.ACTION, () -> {}, null);

        // --- geometry
        check("title height", RyneGui.TITLE_HEIGHT == 20);
        check("open panel height", a.height() == 20 + 3 * 18);
        check("in title at top-left", RyneGui.inTitle(a, 10, 10));
        check("in title at bottom-right", RyneGui.inTitle(a, 10 + 131, 29));
        check("not in title one past the right", !RyneGui.inTitle(a, 10 + 132, 15));
        check("not in title one below", !RyneGui.inTitle(a, 15, 30));

        // Rows start immediately under the title, and each is exactly ROW_HEIGHT.
        check("first row at its top edge", RyneGui.rowAt(a, 15, 30) == 0);
        check("first row at its bottom edge", RyneGui.rowAt(a, 15, 47) == 0);
        check("second row starts at 48", RyneGui.rowAt(a, 15, 48) == 1);
        check("last row is the last", RyneGui.rowAt(a, 15, 20 + 10 + 3 * 18 - 1) == 2);
        check("one past the last row is nothing", RyneGui.rowAt(a, 15, 10 + 20 + 3 * 18) == -1);
        check("the title is not a row", RyneGui.rowAt(a, 15, 15) == -1);
        check("outside to the left is nothing", RyneGui.rowAt(a, 9, 35) == -1);

        // --- a shut panel takes no row clicks, however it is drawn
        a.open = false;
        a.openness = 0f;
        check("a shut panel has only its title", a.height() == 20);
        check("a shut panel takes no row clicks", RyneGui.rowAt(a, 15, 35) == -1);
        check("but its title still answers", RyneGui.inTitle(a, 15, 15));
        a.openness = 0.5f;
        check("a half open panel still takes clicks on what is shown",
                RyneGui.rowAt(a, 15, 35) == 0);
        a.open = true;
        a.openness = 1f;

        // --- overlapping panels: only the topmost answers
        RyneGui.Panel b = gui.add(new RyneGui.Panel("b", "Beta", 10, 10));
        b.add("only", RyneGui.Kind.ACTION, () -> {}, null);
        check("the later panel is on top", gui.topmostAt(15, 15) == b);
        gui.raise(a);
        check("raising brings it to the front", gui.topmostAt(15, 15) == a);

        // --- dragging keeps the grab offset
        gui.beginDrag(a, 40, 18);
        gui.dragTo(140, 118, 1000, 600);
        check("dragged by the delta, not snapped", a.x == 110 && a.y == 110);
        check("dragging raises it", gui.topmostAt(115, 115) == a);
        gui.endDrag();
        check("drag ends", !gui.isDragging());

        // --- a panel may not be dragged off where it cannot be got back
        gui.beginDrag(a, a.x + 5, a.y + 5);
        gui.dragTo(-500, -500, 1000, 600);
        check("cannot be dragged off the top left", a.x == 0 && a.y == 0);
        gui.dragTo(5000, 5000, 1000, 600);
        check("cannot be dragged off the bottom right",
                a.x == 1000 - RyneGui.PANEL_WIDTH && a.y == 600 - RyneGui.TITLE_HEIGHT);
        check("its handle is still on screen", a.y + RyneGui.TITLE_HEIGHT <= 600);
        gui.endDrag();

        // --- easing is measured in seconds, not frames
        float slow = RyneGui.ease(0f, 1f, 14f, 1f / 30f);
        float fast = RyneGui.ease(0f, 1f, 14f, 1f / 60f);
        float twice = RyneGui.ease(fast, 1f, 14f, 1f / 60f);
        check("one big step matches two small ones", Math.abs(slow - twice) < 0.002f);
        check("easing moves toward the target", slow > 0f && slow < 1f);
        check("easing lands exactly", RyneGui.ease(0.9999f, 1f, 14f, 1f) == 1f);
        check("no time is no movement", RyneGui.ease(0.3f, 1f, 14f, 0f) == 0.3f);

        // --- colours
        check("blend at zero is the first", RyneGui.blend(0x11223344, 0x99887766, 0f) == 0x11223344);
        check("blend at one is the second", RyneGui.blend(0x11223344, 0x99887766, 1f) == 0x99887766);
        check("blend halfway is between",
                ((RyneGui.blend(0xFF000000, 0xFF0000FF, 0.5f)) & 0xFF) == 128);
        check("fade halves the alpha", ((RyneGui.fade(0xFF123456, 0.5f) >>> 24) & 0xFF) == 128);
        check("fade keeps the colour", (RyneGui.fade(0xFF123456, 0.5f) & 0xFFFFFF) == 0x123456);

        // --- the animation loop touches everything
        // Put back where it started: the clamp test above left it in the far corner, and
        // hovering (15, 15) there tests nothing. The first run of this failed on exactly
        // that, which is the test being wrong rather than the code.
        a.x = 10;
        a.y = 10;
        gui.tick(1f / 60f, 15, 15);
        check("a hovered title lights up", a.glow > 0f);
        b.open = false;
        for (int i = 0; i < 200; i++) gui.tick(1f / 60f, -1, -1);
        check("a closed panel finishes closing", b.openness == 0f);
        check("an unhovered title goes dark", a.glow == 0f);

        System.out.println(fails == 0 ? "OK" : fails + " FAILED");
    }
}
