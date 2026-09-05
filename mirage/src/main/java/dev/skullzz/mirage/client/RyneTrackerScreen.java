package dev.skullzz.mirage.client;

import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * The tracker, as its own screen: sessions on the left, the numbers in the middle,
 * settings on the right.
 *
 * <p>Everything it shows is arithmetic on chat lines already on your screen, so the only
 * way it can mislead is by being quiet about not working. That is why the first thing it
 * draws, above the totals, is whether chat is being read at all -- a session sitting at
 * zero because nothing happened looks exactly like one sitting at zero because nothing
 * was heard.
 */
public class RyneTrackerScreen extends Screen {

    private static final int SIDE = 190;
    private static final int PAD = 14;
    private static final int ROW = 24;

    private final Screen parent;
    private String said;
    private boolean showOwed;

    public RyneTrackerScreen() {
        this(null, "", false);
    }

    public RyneTrackerScreen(Screen parent) {
        this(parent, "", false);
    }

    private RyneTrackerScreen(Screen parent, String said, boolean showOwed) {
        super(Text.literal("Tracker"));
        this.parent = parent;
        this.said = said;
        this.showOwed = showOwed;
    }

    // ---------------------------------------------------------------------- geometry

    private int left() {
        return (this.width - Math.min(this.width - 20, 960)) / 2;
    }

    private int right() {
        return this.width - left();
    }

    private int top() {
        return (this.height - Math.min(this.height - 20, 470)) / 2;
    }

    private int bottom() {
        return this.height - top();
    }

    /** Where the middle column starts, and where the settings column does. */
    private int middle() {
        return left() + SIDE + PAD;
    }

    private int settings() {
        return right() - SIDE;
    }

    // ------------------------------------------------------------------------ layout

    @Override
    protected void init() {
        boolean running = Sessions.current() != null;

        // ---- sessions, on the left
        button(running ? "End session" : "Start session", left() + PAD, top() + 40,
                SIDE - PAD * 2, () -> {
                    if (running) {
                        String net = Tracker.money(Sessions.current().net());
                        Sessions.stop();
                        say("Ended at " + net + ".");
                    } else {
                        Sessions.start();
                        say(Sessions.tracking() ? "Started."
                                : "Started, but tracking is off - turn it on at the right.");
                    }
                });

        int y = top() + 88;
        List<Tracker.Session> past = Sessions.past();
        for (int i = 0; i < Math.min(past.size(), 8); i++) {
            int index = i;
            button("Forget", left() + PAD, y, 56, () -> {
                Sessions.forget(index);
                say("Forgotten.");
            });
            y += ROW;
        }

        // ---- settings, on the right
        int sy = top() + 40;
        button(Sessions.tracking() ? "Tracking ON" : "Tracking OFF", settings(), sy,
                SIDE - PAD, () -> {
                    Sessions.setTracking(!Sessions.tracking());
                    say(Sessions.tracking()
                            ? (ChatHook.attached() ? "Reading chat."
                                    : "On, but chat cannot be read: " + ChatHook.reason())
                            : "Not reading chat.");
                });
        sy += 26;
        button(Sessions.hud() ? "HUD bar ON" : "HUD bar OFF", settings(), sy,
                SIDE - PAD, () -> {
                    Sessions.setHud(!Sessions.hud());
                    say("HUD " + (Sessions.hud() ? "on" : "off") + ".");
                });

        sy += 40;
        button("-", settings(), sy, 34, () ->
                Sessions.setAlertAfter(Sessions.alertAfter() - 1));
        button("+", settings() + 40, sy, 34, () ->
                Sessions.setAlertAfter(Sessions.alertAfter() + 1));

        sy += 40;
        button("-", settings(), sy, 34, () ->
                Sessions.setRakebackBps(Sessions.rakebackBps() - 50));
        button("+", settings() + 40, sy, 34, () ->
                Sessions.setRakebackBps(Sessions.rakebackBps() + 50));

        sy += 34;
        button(this.showOwed ? "Hide the list" : "Who is owed", settings(), sy,
                SIDE - PAD, () -> {
                    this.showOwed = !this.showOwed;
                    say("");
                });

        button("Done", right() - PAD - 84, bottom() - PAD - 20, 84, this::close);
    }

    private void button(String label, int x, int y, int width, Runnable action) {
        this.addDrawableChild(ButtonWidget.builder(Text.literal(label),
                        ignored -> action.run())
                .dimensions(x, y, width, 20).build());
    }

    private void say(String message) {
        this.said = message;
        if (this.client != null) {
            this.client.setScreen(new RyneTrackerScreen(this.parent, message, this.showOwed));
        }
    }

    // ------------------------------------------------------------------------- paint

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        RyneTheme.Theme theme = RyneTheme.current();

        RyneDraw.box(context, left(), top(), right() - left(), bottom() - top(), theme.page);
        RyneDraw.box(context, left(), top(), SIDE, bottom() - top(), theme.panel);
        RyneDraw.box(context, settings() - PAD, top(), SIDE + PAD, bottom() - top(),
                theme.panel);
        RyneDraw.box(context, left() + PAD, top() + PAD, 4, 16, theme.accent);

        text(context, "SESSIONS", left() + PAD + 12, top() + PAD + 4, theme.text);
        text(context, "SETTINGS", settings(), top() + PAD + 4, theme.text);
        text(context, "TRACKER", middle(), top() + PAD + 4, theme.text);

        // Above the totals, always: a zero because nothing happened and a zero because
        // nothing was heard are the same zero, and only one of them is worth acting on.
        boolean reading = ChatHook.attached() && Sessions.tracking();
        text(context, ChatHook.attached()
                        ? (Sessions.tracking() ? "reading chat" : "PAUSED - tracking is off")
                        : "CANNOT READ CHAT - " + RyneDraw.trim(ChatHook.reason(), 46),
                middle() + 92, top() + PAD + 4, reading ? theme.dim : theme.accent);

        super.render(context, mouseX, mouseY, delta);

        paintSessions(context, theme);
        paintNumbers(context, theme);
        paintSettings(context, theme);

        if (this.said != null && !this.said.isEmpty()) {
            text(context, RyneDraw.trim(this.said, 92), middle(), bottom() - PAD - 14,
                    theme.dim);
        }
    }

    private void text(DrawContext context, String message, int x, int y, int colour) {
        RyneDraw.text(context, this.textRenderer, message, x, y, colour);
    }

    private void paintSessions(DrawContext context, RyneTheme.Theme theme) {
        Tracker.Session current = Sessions.current();
        text(context, current == null ? "no active session"
                        : "running - " + Tracker.money(current.net()),
                left() + PAD, top() + 68, current == null ? theme.dim : theme.accent);

        List<Tracker.Session> past = Sessions.past();
        if (past.isEmpty()) {
            text(context, "nothing kept yet", left() + PAD, top() + 92, theme.dim);
            return;
        }

        int y = top() + 94;
        for (int i = 0; i < Math.min(past.size(), 8); i++) {
            Tracker.Session session = past.get(i);
            text(context, Tracker.money(session.net()), left() + PAD + 62, y, theme.text);
            text(context, session.wins() + "W/" + session.losses() + "L",
                    left() + PAD + 62, y + 10, theme.dim);
            y += ROW;
        }
        if (past.size() > 8) {
            text(context, "+" + (past.size() - 8) + " more", left() + PAD, y + 4, theme.dim);
        }
    }

    private void paintNumbers(DrawContext context, RyneTheme.Theme theme) {
        int x = middle();
        int y = top() + 44;
        Tracker.Session session = Sessions.current();

        text(context, "SESSION", x, y, theme.dim);
        if (session == null) {
            text(context, "--", x, y + 14, theme.text);
            text(context, "start one on the left", x, y + 28, theme.dim);
        } else {
            text(context, Tracker.money(session.net()), x, y + 14,
                    session.net() >= 0 ? theme.accent : 0xFFE0655F);
            text(context, "in  " + Tracker.money(session.in()), x, y + 28, theme.dim);
            text(context, "out " + Tracker.money(session.out()), x, y + 40, theme.dim);
            text(context, "trades " + session.wins() + " + " + session.losses(),
                    x, y + 52, theme.dim);
        }

        long[] all = Sessions.allTime();
        int mid = x + 190;
        text(context, "ALL TIME", mid, y, theme.dim);
        text(context, Tracker.money(all[0] - all[1]), mid, y + 14, theme.text);
        text(context, "in  " + Tracker.money(all[0]), mid, y + 28, theme.dim);
        text(context, "out " + Tracker.money(all[1]), mid, y + 40, theme.dim);
        text(context, "trades " + all[2] + " + " + all[3], mid, y + 52, theme.dim);

        int far = x + 380;
        text(context, "STREAKS", far, y, theme.dim);
        if (session == null) {
            text(context, "--", far, y + 14, theme.text);
        } else {
            text(context, session.lossStreak() + "L / " + session.winStreak() + "W",
                    far, y + 14, session.lossStreak() >= Sessions.alertAfter()
                            ? 0xFFE0655F : theme.text);
            text(context, "worst " + session.worstLossRun() + " out", far, y + 28, theme.dim);
            text(context, "best  " + session.bestWinRun() + " in", far, y + 40, theme.dim);
            text(context, "warns at " + Sessions.alertAfter(), far, y + 52, theme.dim);
        }

        text(context, this.showOwed ? "WHO IS OWED" : "RECENT PAYMENTS", x, y + 76, theme.dim);
        RyneDraw.box(context, x, y + 90, settings() - PAD - x - 8,
                bottom() - (top() + y + 90) - 40, theme.card);

        if (this.showOwed) paintOwed(context, theme, x, y + 100, session);
        else paintRecent(context, theme, x, y + 100);
    }

    private void paintRecent(DrawContext context, RyneTheme.Theme theme, int x, int y) {
        List<Tracker.Payment> recent = Sessions.recent();
        if (recent.isEmpty()) {
            text(context, Sessions.tracking()
                            ? "nothing yet - payments land here as they happen"
                            : "tracking is off, so nothing is being recorded",
                    x + 8, y, theme.dim);
            return;
        }

        int row = y;
        for (int i = recent.size() - 1; i >= 0 && row < bottom() - 60; i--) {
            Tracker.Payment payment = recent.get(i);
            text(context, (payment.incoming ? "+ " : "- ") + Tracker.money(payment.cents)
                            + "   " + payment.player,
                    x + 8, row, payment.incoming ? theme.accent : 0xFFE0655F);
            row += 12;
        }
    }

    private void paintOwed(DrawContext context, RyneTheme.Theme theme, int x, int y,
                           Tracker.Session session) {
        if (session == null) {
            text(context, "no session running", x + 8, y, theme.dim);
            return;
        }

        Map<String, long[]> owed = session.rakeback(Sessions.rakebackBps());
        if (owed.isEmpty()) {
            text(context, "nobody has sent you anything this session", x + 8, y, theme.dim);
            return;
        }

        int row = y;
        for (Map.Entry<String, long[]> entry : owed.entrySet()) {
            if (row >= bottom() - 60) break;
            text(context, entry.getKey() + "   sent " + Tracker.money(entry.getValue()[0])
                            + "   owed " + Tracker.money(entry.getValue()[1]),
                    x + 8, row, theme.text);
            row += 12;
        }
    }

    private void paintSettings(DrawContext context, RyneTheme.Theme theme) {
        int x = settings();
        text(context, "alert after", x, top() + 96, theme.dim);
        text(context, Sessions.alertAfter() + " out in a row", x + 84, top() + 122, theme.text);
        text(context, "rakeback", x, top() + 136, theme.dim);
        text(context, rake(), x + 84, top() + 162, theme.text);
    }

    /** Basis points as a percentage, without losing the half. */
    static String rake() {
        int points = Sessions.rakebackBps();
        return (points % 100 == 0 ? String.valueOf(points / 100)
                : String.format("%.1f", points / 100.0)) + "%";
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(this.parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
