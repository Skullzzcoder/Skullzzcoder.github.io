package dev.skullzz.mirage.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The click GUI's shape: panels, where they are, what is under the pointer, and how far
 * through an animation each one is.
 *
 * <p>No Minecraft in here. Hit testing and drag arithmetic are exactly the kind of thing
 * that is wrong by four pixels and looks almost right, and easing that is tied to the
 * frame rate looks fine at 60fps and wrong at 30 -- so all of it is kept where it can be
 * run and checked rather than eyeballed in game.
 *
 * <p>What a row does is a {@link Runnable} and what it shows is a {@link BooleanSupplier},
 * both of which are the JDK's, so the panels can drive the real mod without this file
 * knowing anything about it.
 */
public final class RyneGui {

    public static final int TITLE_HEIGHT = 20;
    public static final int ROW_HEIGHT = 18;
    public static final int PANEL_WIDTH = 132;

    /** Below this a panel counts as shut and its rows stop taking clicks. */
    private static final float SHUT = 0.02f;

    /** What kind of thing a row is, which decides how it is drawn. */
    public enum Kind { TOGGLE, ACTION, LABEL }

    public static final class Row {
        public final String label;
        public final Kind kind;
        public final Runnable action;
        public final BooleanSupplier state;
        /** 0 to 1, how lit the row is. Eased toward 1 while the pointer is on it. */
        public float glow;

        public Row(String label, Kind kind, Runnable action, BooleanSupplier state) {
            this.label = label;
            this.kind = kind;
            this.action = action;
            this.state = state;
        }

        public boolean on() {
            return this.state != null && this.state.getAsBoolean();
        }
    }

    public static final class Panel {
        public final String id;
        public final String title;
        public final List<Row> rows = new ArrayList<>();
        public int x;
        public int y;
        public boolean open = true;
        /** 0 shut, 1 open. Eased, so the panel slides rather than snapping. */
        public float openness = 1f;
        public float glow;

        public Panel(String id, String title, int x, int y) {
            this.id = id;
            this.title = title;
            this.x = x;
            this.y = y;
        }

        public Panel add(String label, Kind kind, Runnable action, BooleanSupplier state) {
            this.rows.add(new Row(label, kind, action, state));
            return this;
        }

        /** How tall it is right now, part way through opening included. */
        public int height() {
            return TITLE_HEIGHT + Math.round(this.rows.size() * ROW_HEIGHT * this.openness);
        }

        public boolean shut() {
            return this.openness <= SHUT;
        }
    }

    private final List<Panel> panels = new ArrayList<>();
    private Panel dragging;
    private int grabX;
    private int grabY;

    public List<Panel> panels() {
        return this.panels;
    }

    public Panel add(Panel panel) {
        this.panels.add(panel);
        return panel;
    }

    public Panel byId(String id) {
        for (Panel panel : this.panels) {
            if (panel.id.equals(id)) return panel;
        }
        return null;
    }

    // ---------------------------------------------------------------- what is where

    public static boolean inTitle(Panel panel, double mouseX, double mouseY) {
        return mouseX >= panel.x && mouseX < panel.x + PANEL_WIDTH
                && mouseY >= panel.y && mouseY < panel.y + TITLE_HEIGHT;
    }

    /**
     * Which row the pointer is over, or -1.
     *
     * <p>A row part way through sliding out does not take clicks: half a row is not a
     * target, and a click that lands on whatever the animation happens to be showing is
     * the kind of thing that fires the wrong toggle once in twenty.
     */
    public static int rowAt(Panel panel, double mouseX, double mouseY) {
        if (panel.shut()) return -1;
        if (mouseX < panel.x || mouseX >= panel.x + PANEL_WIDTH) return -1;

        double top = panel.y + TITLE_HEIGHT;
        double bottom = panel.y + panel.height();
        if (mouseY < top || mouseY >= bottom) return -1;

        int index = (int) ((mouseY - top) / ROW_HEIGHT);
        return index >= 0 && index < panel.rows.size() ? index : -1;
    }

    /** The topmost panel under the pointer, so overlapping ones do not both answer. */
    public Panel topmostAt(double mouseX, double mouseY) {
        for (int i = this.panels.size() - 1; i >= 0; i--) {
            Panel panel = this.panels.get(i);
            if (mouseX >= panel.x && mouseX < panel.x + PANEL_WIDTH
                    && mouseY >= panel.y && mouseY < panel.y + panel.height()) {
                return panel;
            }
        }
        return null;
    }

    /** Brings a panel to the front, so a dragged one is not left under another. */
    public void raise(Panel panel) {
        if (this.panels.remove(panel)) this.panels.add(panel);
    }

    // --------------------------------------------------------------------- dragging

    public void beginDrag(Panel panel, double mouseX, double mouseY) {
        this.dragging = panel;
        // The offset within the title bar, so the panel does not jump to put its corner
        // under the pointer the moment it is grabbed.
        this.grabX = (int) Math.round(mouseX - panel.x);
        this.grabY = (int) Math.round(mouseY - panel.y);
        raise(panel);
    }

    public boolean isDragging() {
        return this.dragging != null;
    }

    public Panel dragged() {
        return this.dragging;
    }

    public void dragTo(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        if (this.dragging == null) return;
        this.dragging.x = (int) Math.round(mouseX) - this.grabX;
        this.dragging.y = (int) Math.round(mouseY) - this.grabY;
        clamp(this.dragging, screenWidth, screenHeight);
    }

    public void endDrag() {
        this.dragging = null;
    }

    /**
     * Keeps a panel where it can be got at.
     *
     * <p>Its title bar has to stay on screen, or a panel dragged off the edge is a panel
     * that cannot be dragged back. The body is allowed to hang off the bottom, since the
     * handle is what you need.
     */
    public static void clamp(Panel panel, int screenWidth, int screenHeight) {
        panel.x = Math.max(0, Math.min(panel.x, screenWidth - PANEL_WIDTH));
        panel.y = Math.max(0, Math.min(panel.y, screenHeight - TITLE_HEIGHT));
    }

    public void clampAll(int screenWidth, int screenHeight) {
        for (Panel panel : this.panels) clamp(panel, screenWidth, screenHeight);
    }

    // -------------------------------------------------------------------- animation

    /**
     * Moves a value toward a target, at a rate that does not depend on the frame rate.
     *
     * <p>The naive {@code value += (target - value) * 0.2f} is twice as fast at 120fps as
     * at 60, so the same menu feels different on two machines. This is the same curve
     * measured in seconds instead of frames.
     */
    public static float ease(float value, float target, float perSecond, float seconds) {
        if (seconds <= 0f) return value;
        float step = 1f - (float) Math.exp(-perSecond * seconds);
        float moved = value + (target - value) * step;
        // Land exactly, so a panel does not sit at 0.999 open forever.
        return Math.abs(target - moved) < 0.001f ? target : moved;
    }

    /** Advances every animation by however long the last frame took. */
    public void tick(float seconds, double mouseX, double mouseY) {
        Panel hovered = topmostAt(mouseX, mouseY);

        for (Panel panel : this.panels) {
            panel.openness = ease(panel.openness, panel.open ? 1f : 0f, 14f, seconds);

            boolean onTitle = panel == hovered && inTitle(panel, mouseX, mouseY);
            panel.glow = ease(panel.glow, onTitle ? 1f : 0f, 18f, seconds);

            int over = panel == hovered ? rowAt(panel, mouseX, mouseY) : -1;
            for (int i = 0; i < panel.rows.size(); i++) {
                Row row = panel.rows.get(i);
                row.glow = ease(row.glow, i == over ? 1f : 0f, 18f, seconds);
            }
        }
    }

    /** Blends two 0xAARRGGBB colours, channel by channel. */
    public static int blend(int from, int to, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = (from >>> shift) & 0xFF;
            int b = (to >>> shift) & 0xFF;
            out |= (Math.round(a + (b - a) * t) & 0xFF) << shift;
        }
        return out;
    }

    /** A colour with its alpha scaled, for fading the whole menu in. */
    public static int fade(int colour, float amount) {
        int alpha = Math.round(((colour >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, amount)));
        return (alpha << 24) | (colour & 0xFFFFFF);
    }
}
