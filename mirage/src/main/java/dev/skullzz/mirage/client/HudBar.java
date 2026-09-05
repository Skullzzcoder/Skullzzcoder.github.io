package dev.skullzz.mirage.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import dev.skullzz.mirage.Mirage;

/**
 * The tracker's line across the top of the screen.
 *
 * <p>Drawn through Fabric's HUD event, which is subscribed to by name through
 * {@link Events} rather than imported: that package is versioned, this mod has never
 * compiled against it, and a guessed name is a build that does not compile. What the
 * callback is handed has also changed shape between versions -- a matrix stack once, a
 * draw context now -- so the one thing needed is picked out of the arguments by type.
 *
 * <p>If it cannot be hooked the bar simply never appears, and {@link #reason()} says why,
 * which the tracker screen and {@code /fake track} both show.
 */
public final class HudBar {

    private static final String CALLBACK =
            "net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback";

    private static boolean attached;
    private static String reason = "not attached yet";

    private HudBar() {
    }

    public static boolean attached() {
        return attached;
    }

    public static String reason() {
        return reason;
    }

    public static void register() {
        Events.Result result = Events.subscribe(CALLBACK, "EVENT",
                (proxy, method, args) -> {
                    draw(args);
                    return null;
                });

        attached = result.ok;
        reason = result.reason;
        if (!result.ok) Mirage.LOGGER.warn("Mirage could not draw a HUD bar: {}", reason);
    }

    /** Finds the thing to draw on among whatever the callback was handed. */
    private static void draw(Object[] args) {
        if (args == null) return;

        for (Object argument : args) {
            if (argument instanceof DrawContext context) {
                try {
                    paint(context);
                } catch (RuntimeException failure) {
                    // A HUD that throws takes the whole screen down with it.
                    Mirage.LOGGER.warn("Mirage stumbled drawing the HUD bar", failure);
                }
                return;
            }
        }
    }

    private static void paint(DrawContext context) {
        if (!Sessions.hud() || !SelfFakes.enabled()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;
        // Nothing over a screen: the bar belongs on the game, not on top of a menu.
        if (client.currentScreen != null) return;

        String line = line();
        if (line == null) return;

        RyneTheme.Theme theme = RyneTheme.current();
        Tracker.Session session = Sessions.current();
        boolean up = session == null || session.net() >= 0;
        boolean warning = session != null && session.lossStreak() >= Sessions.alertAfter();

        // Measured by counting, not by asking. TextRenderer.getWidth is almost certainly
        // still there, but "almost certainly" is what the four dead names in the keys
        // screen were, and a bar a few pixels too wide is a cosmetic problem where a
        // guessed method is a build that does not compile.
        int width = line.length() * 6 + 16;
        int x = (client.getWindow().getScaledWidth() - width) / 2;
        int y = 4;

        RyneDraw.box(context, x, y, width, 14, 0xC00B0D12);
        // A three-pixel edge in the colour that says how it is going: green up, red down,
        // amber while a losing run is running. Read at a glance, which is the whole point
        // of a bar you are not looking at.
        RyneDraw.box(context, x, y, 3, 14,
                warning ? 0xFFE0A55F : up ? theme.accent : 0xFFE0655F);
        RyneDraw.text(context, client.textRenderer, line, x + 8, y + 3,
                warning ? 0xFFE0A55F : up ? 0xFFE8EAED : 0xFFE0655F);
    }

    /** What the bar says, or null when there is nothing worth a line. */
    static String line() {
        if (!ChatHook.attached()) return "tracker: cannot read chat";
        if (!Sessions.tracking()) return null;

        Tracker.Session session = Sessions.current();
        if (session == null) return "tracker: no session";

        String text = Tracker.money(session.net()) + "   "
                + session.wins() + "W / " + session.losses() + "L";
        int run = session.lossStreak();
        return run >= 2 ? text + "   " + run + " out in a row" : text;
    }
}
