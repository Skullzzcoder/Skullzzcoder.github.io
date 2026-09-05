package dev.skullzz.mirage.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * Every game, on one screen, instead of a command per thing.
 *
 * <p>Rigs down the left, the one that is running down the right, with the controls that
 * game actually has: whichever of arm, fire, refill, pick a winner, deal a card or step
 * the answer applies to it. What is on the right changes with the game, because a roulette
 * rig has nothing to say about winners and a blackjack rig has nothing to arm.
 *
 * <p>Buttons and one text field, drawn through {@link RyneDraw}, for the same reason as
 * every other screen here: those are the calls this mod has watched compile.
 */
public class RyneRigScreen extends Screen {

    private static final int SIDEBAR = 170;
    private static final int PAD = 14;
    private static final int ROW = 24;

    private final Screen parent;
    private String said;
    private TextFieldWidget newName;

    public RyneRigScreen() {
        this(null, "");
    }

    public RyneRigScreen(Screen parent) {
        this(parent, "");
    }

    private RyneRigScreen(Screen parent, String said) {
        super(Text.literal("Rigs"));
        this.parent = parent;
        this.said = said;
    }

    // ---------------------------------------------------------------------- geometry

    private int left() {
        return (this.width - Math.min(this.width - 20, 880)) / 2;
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

    private int panel() {
        return left() + SIDEBAR + PAD;
    }

    // ------------------------------------------------------------------------ layout

    @Override
    protected void init() {
        RigProfile active = ClientDispensers.active();

        int y = top() + 48;
        for (String name : new ArrayList<>(ClientDispensers.profiles().keySet())) {
            String pick = name;
            boolean on = name.equals(ClientDispensers.activeName());
            this.addDrawableChild(ButtonWidget.builder(
                            Text.literal((on ? "> " : "  ") + RyneDraw.trim(name, 16)),
                            ignored -> {
                                ClientDispensers.use(pick);
                                SelfFakes.save();
                                say("Now on '" + pick + "'.");
                            })
                    .dimensions(left() + PAD, y, SIDEBAR - PAD * 2, 20).build());
            y += ROW;
        }

        // Making a rig needs a name, so this is the one place a field earns its keep.
        this.newName = new TextFieldWidget(this.textRenderer, left() + PAD, y + 10,
                SIDEBAR - PAD * 2, 20, Text.literal("new rig name"));
        this.newName.setMaxLength(24);
        this.addDrawableChild(this.newName);

        button("Create", left() + PAD, y + 34, SIDEBAR - PAD * 2, () -> {
            String name = this.newName == null ? "" : this.newName.getText().trim();
            if (name.isEmpty()) {
                say("Type a name for it first.");
                return;
            }
            ClientDispensers.create(name);
            ClientDispensers.use(name);
            SelfFakes.save();
            say("Created '" + name + "' and switched to it.");
        });

        buildControls(active);

        button("Done", right() - PAD - 90, bottom() - PAD - 20, 90, this::close);
    }

    /** The controls this particular game has, and only those. */
    private void buildControls(RigProfile rig) {
        int x = panel();
        int wide = right() - panel() - PAD;
        int y = top() + 92;

        // The switch first: with the rigs off, none of the rest of this does anything.
        boolean on = SelfFakes.rigsOn();
        button(on ? "Rigs are ON" : "Rigs are OFF", x, y, 130, () -> {
            SelfFakes.setRigsOn(!on);
            if (on) ClientDispensers.standDown();
            say(on ? "Rigs off. Builds and schematics still work." : "Rigs back on.");
        });
        button("Fire all watched", x + 136, y, 140, () -> {
            int fired = ClientDispensers.fireAllWatched();
            say(fired == 0 ? "Nothing watched to fire." : "Fired " + fired + ".");
        });
        button("Refill", x + 282, y, 90, () -> {
            int filled = ClientDispensers.refillWatched();
            SelfFakes.save();
            say("Refilled " + filled + ".");
        });

        y += 30;

        // What the rig answers with, stepped the same way F and R step it in game.
        button("< " + rig.backLabel(), x, y, (wide - 8) / 2, () -> {
            ClientDispensers.cyclePreset(-1);
            SelfFakes.save();
            say("Answer: " + answer());
        });
        button(rig.forwardLabel() + " >", x + (wide - 8) / 2 + 8, y, (wide - 8) / 2, () -> {
            ClientDispensers.cyclePreset(1);
            SelfFakes.save();
            say("Answer: " + answer());
        });

        y += 34;

        if (rig.roulette) {
            button(ClientDispensers.isArmed() ? "Disarm" : "Arm the next shot", x, y, 170,
                    () -> {
                        if (ClientDispensers.isArmed()) ClientDispensers.disarm();
                        else ClientDispensers.armNext();
                        say(ClientDispensers.isArmed() ? "Armed." : "Disarmed.");
                    });
            button("Reset shots", x + 176, y, 130, () -> {
                rig.resetShots();
                SelfFakes.save();
                say("Back to shot 1.");
            });
            y += 30;
        }

        if (rig.hasSides()) {
            int column = 0;
            for (String side : rig.sideNames()) {
                String pick = side;
                boolean chosen = side.equals(rig.winner);
                button((chosen ? "> " : "  ") + RyneDraw.trim(side, 14),
                        x + column * 126, y, 120, () -> {
                            rig.winner = pick;
                            rig.roundTick = Long.MIN_VALUE;
                            SelfFakes.save();
                            say(pick + " wins the next round.");
                        });
                column++;
            }
            button(rig.winner.isEmpty() ? "> Random" : "  Random", x + column * 126, y, 120,
                    () -> {
                        rig.winner = "";
                        rig.roundTick = Long.MIN_VALUE;
                        SelfFakes.save();
                        say("Left to chance.");
                    });
            y += 30;
        }

        if (rig.blackjack) {
            int column = 0;
            for (String side : rig.sideNames()) {
                String pick = side;
                button("Deal to " + RyneDraw.trim(pick, 10), x + column * 146, y, 140, () -> {
                    String dealt = ClientDispensers.dealTo(pick);
                    SelfFakes.save();
                    say(dealt);
                });
                column++;
            }
            button("New hand", x + column * 146, y, 110, () -> {
                ClientDispensers.newHand();
                SelfFakes.save();
                say("Fresh hands.");
            });
        }
    }

    private ButtonWidget button(String label, int x, int y, int width, Runnable action) {
        ButtonWidget widget = ButtonWidget.builder(Text.literal(label),
                        ignored -> action.run())
                .dimensions(x, y, width, 20).build();
        this.addDrawableChild(widget);
        return widget;
    }

    private void say(String message) {
        this.said = message;
        reopen();
    }

    /** Reopened rather than rebuilt in place, like every other screen here. */
    private void reopen() {
        if (this.client == null) return;
        this.client.setScreen(new RyneRigScreen(this.parent, this.said));
    }

    private String answer() {
        FakeSpec spec = ClientDispensers.result();
        return spec == null ? "nothing set" : spec.label();
    }

    // ------------------------------------------------------------------------ paint

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        RyneTheme.Theme theme = RyneTheme.current();
        RigProfile rig = ClientDispensers.active();

        RyneDraw.box(context, left(), top(), right() - left(), bottom() - top(), theme.page);
        RyneDraw.box(context, left(), top(), SIDEBAR, bottom() - top(), theme.panel);
        RyneDraw.box(context, left() + SIDEBAR, top(), 1, bottom() - top(), theme.line);
        RyneDraw.box(context, left() + PAD, top() + PAD, 4, 18, theme.accent);

        RyneDraw.text(context, this.textRenderer, "RIGS", left() + PAD + 12, top() + PAD + 5,
                theme.text);
        RyneDraw.text(context, this.textRenderer, "RIG", left() + PAD, top() + 34, theme.dim);

        super.render(context, mouseX, mouseY, delta);

        int x = panel();
        RyneDraw.text(context, this.textRenderer, rig.name + "   -   " + rig.mode(),
                x, top() + PAD + 2, theme.text);

        // The three things that decide whether anything will happen at all, said plainly:
        // silence about them is how "nothing is dispensing" went unexplained three times.
        String missing = ClientDispensers.noAnswer();
        RyneDraw.text(context, this.textRenderer,
                "Machines watched: " + ClientDispensers.watchedCount(),
                x, top() + 34, theme.dim);
        RyneDraw.text(context, this.textRenderer,
                "Answer: " + (missing == null ? answer() : missing),
                x, top() + 48, missing == null ? theme.dim : theme.accent);
        RyneDraw.text(context, this.textRenderer,
                SelfFakes.rigsOn() ? "Rigs are on" : "Rigs are OFF - nothing below will fire",
                x, top() + 62, SelfFakes.rigsOn() ? theme.dim : theme.accent);

        if (rig.blackjack) paintHands(context, theme, rig);

        if (this.said != null && !this.said.isEmpty()) {
            RyneDraw.text(context, this.textRenderer, RyneDraw.trim(this.said, 90),
                    x, bottom() - PAD - 14, theme.dim);
        }
    }

    private void paintHands(DrawContext context, RyneTheme.Theme theme, RigProfile rig) {
        int y = bottom() - PAD - 44;
        for (String side : rig.sideNames()) {
            List<Integer> hand = rig.handFor(side);
            RyneDraw.text(context, this.textRenderer,
                    side + ": " + hand + " = " + rig.totalFor(side),
                    panel(), y, theme.dim);
            y += 12;
        }
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
