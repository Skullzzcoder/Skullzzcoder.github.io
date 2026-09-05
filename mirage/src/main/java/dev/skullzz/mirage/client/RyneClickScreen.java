package dev.skullzz.mirage.client;

import java.lang.reflect.Proxy;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.Event;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.Window;
import net.minecraft.text.Text;

/**
 * The click GUI: panels you drag where you like, that slide open and shut.
 *
 * <p>Nothing here is a {@link net.minecraft.client.gui.widget.ButtonWidget}. Everything is
 * drawn and hit-tested by hand, which is the only way to get panels that move and animate
 * -- and it is done through the paths this mod has already watched compile: the click
 * event and the render event, both subscribed to the way FakeClicks does, the pointer read
 * the way FakeClicks reads it, and rectangles through {@link RyneDraw}.
 *
 * <p>The arithmetic underneath -- where a click lands, where a dragged panel ends up, how
 * far through an animation everything is -- lives in {@link RyneGui}, which has no
 * Minecraft in it and is run by check-gui.py. Being four pixels out is invisible in a
 * screenshot and obvious in use.
 */
public class RyneClickScreen extends Screen {

    /** How long the whole menu takes to fade in, in seconds. */
    private static final float FADE_SPEED = 16f;

    private static final RyneGui GUI = new RyneGui();
    private static boolean built;

    private final Screen parent;
    private float shown;
    private long lastFrame;
    /** Whether the button was down last frame, so a press and a release can be told apart. */
    private boolean wasDown;

    public RyneClickScreen() {
        this(null);
    }

    public RyneClickScreen(Screen parent) {
        super(Text.literal("Ryne"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------- the panels

    /**
     * Builds the panels once, and never again.
     *
     * <p>Once, because where they have been dragged to is the state worth keeping: a menu
     * that rebuilds its panels on every open is a menu that forgets where you put them.
     */
    private static void buildOnce() {
        if (built) return;
        built = true;

        GUI.add(new RyneGui.Panel("client", "Client", 12, 12)
                .add("Rigs", RyneGui.Kind.TOGGLE, () -> {
                    SelfFakes.setRigsOn(!SelfFakes.rigsOn());
                    if (!SelfFakes.rigsOn()) ClientDispensers.standDown();
                }, SelfFakes::rigsOn)
                .add("Everything", RyneGui.Kind.TOGGLE,
                        () -> SelfFakes.setEnabled(!SelfFakes.enabled()), SelfFakes::enabled)
                .add("Quiet", RyneGui.Kind.TOGGLE,
                        () -> SelfFakes.setQuiet(!SelfFakes.quiet()), SelfFakes::quiet));

        GUI.add(new RyneGui.Panel("tracker", "Tracker", 12, 122)
                .add("Tracking", RyneGui.Kind.TOGGLE,
                        () -> Sessions.setTracking(!Sessions.tracking()), Sessions::tracking)
                .add("HUD bar", RyneGui.Kind.TOGGLE,
                        () -> Sessions.setHud(!Sessions.hud()), Sessions::hud)
                .add("Session", RyneGui.Kind.ACTION, () -> {
                    if (Sessions.current() == null) Sessions.start();
                    else Sessions.stop();
                }, null)
                .add("Open it", RyneGui.Kind.ACTION, () -> open(new RyneTrackerScreen()), null));

        GUI.add(new RyneGui.Panel("world", "World", 160, 12)
                .add("Take all builds", RyneGui.Kind.ACTION, () -> {
                    FakeBlocks.takeAll();
                    FakeBlocks.persist();
                }, null)
                .add("Schematics", RyneGui.Kind.ACTION,
                        () -> open(new MirageSchematicsScreen()), null)
                .add("Fake items", RyneGui.Kind.ACTION,
                        () -> open(new FakeItemsScreen()), null));

        GUI.add(new RyneGui.Panel("rigs", "Rigs", 160, 100)
                .add("Fire watched", RyneGui.Kind.ACTION,
                        ClientDispensers::fireAllWatched, null)
                .add("Refill", RyneGui.Kind.ACTION, ClientDispensers::refillWatched, null)
                .add("Next rig", RyneGui.Kind.ACTION,
                        () -> ClientDispensers.cycleProfile(1), null)
                .add("Open rigs", RyneGui.Kind.ACTION, () -> open(new RyneRigScreen()), null));

        RyneGui.Panel themes = new RyneGui.Panel("theme", "Theme", 308, 12);
        for (int i = 0; i < RyneTheme.ALL.size(); i++) {
            int index = i;
            themes.add(RyneTheme.ALL.get(i).name, RyneGui.Kind.TOGGLE, () -> {
                RyneTheme.choose(index);
                SelfFakes.save();
            }, () -> RyneTheme.index() == index);
        }
        GUI.add(themes);

        RyneLayout.load(GUI);
    }

    private static void open(Screen screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) client.setScreen(screen);
    }

    // ------------------------------------------------------------------ the plumbing

    /**
     * Hooks the click event for this screen.
     *
     * <p>Through the screen event rather than by overriding mouseClicked, which moved in
     * this version and is one of the four names the keys screen is still unfinished over.
     */
    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof RyneClickScreen menu)) return;
            menu.listen(ScreenMouseEvents.allowMouseClick(screen));
        });
    }

    private void listen(Event<ScreenMouseEvents.AllowMouseClick> event) {
        Object listener = Proxy.newProxyInstance(
                RyneClickScreen.class.getClassLoader(),
                new Class<?>[] { ScreenMouseEvents.AllowMouseClick.class },
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> "ryne menu";
                        };
                    }
                    click();
                    // Never let the click through: everything on this screen is ours.
                    return false;
                });

        @SuppressWarnings("unchecked")
        Event<Object> untyped = (Event<Object>) (Event<?>) event;
        untyped.register(listener);
    }

    @Override
    protected void init() {
        buildOnce();
        GUI.clampAll(this.width, this.height);
        this.lastFrame = System.nanoTime();
    }

    /** Where the pointer is, in the same units the panels are laid out in. */
    private double[] pointer() {
        MinecraftClient client = MinecraftClient.getInstance();
        Window window = client.getWindow();
        if (window.getWidth() == 0 || window.getHeight() == 0) return null;

        return new double[] {
                client.mouse.getX() * window.getScaledWidth() / window.getWidth(),
                client.mouse.getY() * window.getScaledHeight() / window.getHeight() };
    }

    private boolean mouseDown() {
        MinecraftClient client = MinecraftClient.getInstance();
        return GLFW.glfwGetMouseButton(client.getWindow().getHandle(),
                GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    /** One click: a title grabs the panel, a row does its thing. */
    private void click() {
        double[] mouse = pointer();
        if (mouse == null) return;

        RyneGui.Panel panel = GUI.topmostAt(mouse[0], mouse[1]);
        if (panel == null) return;

        if (RyneGui.inTitle(panel, mouse[0], mouse[1])) {
            GUI.beginDrag(panel, mouse[0], mouse[1]);
            return;
        }

        int row = RyneGui.rowAt(panel, mouse[0], mouse[1]);
        if (row < 0) return;

        RyneGui.Row hit = panel.rows.get(row);
        if (hit.action != null) hit.action.run();
    }

    // ---------------------------------------------------------------------- drawing

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        float seconds = Math.min(0.1f, (now - this.lastFrame) / 1_000_000_000f);
        this.lastFrame = now;

        double[] mouse = pointer();
        double px = mouse == null ? -1 : mouse[0];
        double py = mouse == null ? -1 : mouse[1];

        // A press that began on a title bar drags until the button comes back up. Polled
        // rather than taken from a release event, because the press event is the only one
        // this mod has watched compile.
        boolean down = mouseDown();
        if (!down && this.wasDown) {
            // Released without having moved: that was a click on the title, which folds
            // the panel away rather than leaving it where it was.
            RyneGui.Panel tapped = GUI.endDrag();
            if (tapped != null) {
                tapped.open = !tapped.open;
                RyneLayout.save(GUI);
            } else {
                RyneLayout.save(GUI);
            }
        }
        this.wasDown = down;
        if (down && GUI.isDragging()) GUI.dragTo(px, py, this.width, this.height);

        this.shown = RyneGui.ease(this.shown, 1f, FADE_SPEED, seconds);
        GUI.tick(seconds, px, py);

        RyneTheme.Theme theme = RyneTheme.current();
        // A wash over the world, not a blackout: the point of a click menu is that you
        // can still see what you are doing behind it.
        RyneDraw.box(context, 0, 0, this.width, this.height,
                RyneGui.fade(0x60000000, this.shown));

        for (RyneGui.Panel panel : GUI.panels()) paint(context, panel, theme);

        RyneDraw.text(context, this.textRenderer,
                "drag a title to move it, click it to fold it away",
                8, this.height - 12, RyneGui.fade(theme.dim, this.shown));
    }

    private void paint(DrawContext context, RyneGui.Panel panel, RyneTheme.Theme theme) {
        int width = RyneGui.PANEL_WIDTH;
        int height = panel.height();
        float a = this.shown;

        RyneDraw.box(context, panel.x, panel.y, width, height,
                RyneGui.fade(theme.panel, a));

        // The title bar lifts toward the accent as the pointer crosses it, so it is
        // obvious which strip is the handle without a label saying so.
        int bar = RyneGui.blend(theme.card, theme.accent, panel.glow * 0.55f);
        RyneDraw.box(context, panel.x, panel.y, width, RyneGui.TITLE_HEIGHT,
                RyneGui.fade(bar, a));
        RyneDraw.box(context, panel.x, panel.y, 2, RyneGui.TITLE_HEIGHT,
                RyneGui.fade(theme.accent, a));
        RyneDraw.text(context, this.textRenderer, panel.title, panel.x + 8, panel.y + 6,
                RyneGui.fade(theme.text, a));
        RyneDraw.text(context, this.textRenderer, panel.open ? "-" : "+",
                panel.x + width - 12, panel.y + 6, RyneGui.fade(theme.dim, a));

        if (panel.shut()) return;

        int y = panel.y + RyneGui.TITLE_HEIGHT;
        int bottom = panel.y + height;
        for (RyneGui.Row row : panel.rows) {
            if (y >= bottom) break;
            paintRow(context, panel, row, y, Math.min(RyneGui.ROW_HEIGHT, bottom - y),
                    theme, a);
            y += RyneGui.ROW_HEIGHT;
        }
    }

    private void paintRow(DrawContext context, RyneGui.Panel panel, RyneGui.Row row, int y,
                          int height, RyneTheme.Theme theme, float a) {
        boolean on = row.on();
        int back = RyneGui.blend(theme.panel, theme.cardHover, row.glow);
        RyneDraw.box(context, panel.x, y, RyneGui.PANEL_WIDTH, height,
                RyneGui.fade(back, a));

        // The slice of accent that slides in from the left as the pointer arrives. It is
        // the whole reason this is drawn by hand rather than assembled from buttons.
        int slice = Math.round(RyneGui.PANEL_WIDTH * row.glow * 0.06f);
        if (slice > 0) {
            RyneDraw.box(context, panel.x, y, slice, height,
                    RyneGui.fade(theme.accent, a));
        }

        if (height >= 8) {
            RyneDraw.text(context, this.textRenderer, row.label, panel.x + 10, y + 5,
                    RyneGui.fade(on ? theme.accent : theme.text, a));
            if (row.kind == RyneGui.Kind.TOGGLE) {
                RyneDraw.box(context, panel.x + RyneGui.PANEL_WIDTH - 18, y + 6, 8, 6,
                        RyneGui.fade(on ? theme.accent : theme.line, a));
            }
        }
    }

    @Override
    public void close() {
        RyneLayout.save(GUI);
        if (this.client != null) this.client.setScreen(this.parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
