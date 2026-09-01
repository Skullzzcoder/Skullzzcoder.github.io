package dev.skullzz.mirage.client;

import java.util.List;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import dev.skullzz.mirage.client.MirageClient.Bind;

/**
 * Every key the mod owns, in one place, with what each is for and whether anything else
 * answers to it.
 *
 * <p>The clash is the reason this exists. Minecraft runs both bindings when two share a key,
 * and the default result key is F, which vanilla uses for swapping to the offhand -- so
 * rigging a game also threw your sword into your other hand, and nothing anywhere said why.
 * Vanilla's own controls screen will happily let you do that and never mention it.
 *
 * <p>Changing a key from inside this screen is not wired up yet. Setting a binding and
 * reading a key press both moved in this version of Minecraft, and the names that replaced
 * them are not worth guessing at a third time -- {@code gradlew inspectApi} prints them, and
 * this screen grows the two buttons back once it has. Until then it says which key each
 * thing is on and where to change it, which is the half that could be built without
 * guessing.
 */
public class MirageKeysScreen extends Screen {
    private static final int ROW = 22;
    private static final int LABEL_WIDTH = 210;
    private static final int KEY_WIDTH = 96;
    private static final int COLUMN = LABEL_WIDTH + KEY_WIDTH + 8;
    private static final int TOP = 52;

    /** Red enough to read as a warning against the grey, without being alarming. */
    private static final int CLASH_COLOUR = 0xFFE0655F;
    private static final int LABEL_COLOUR = 0xFFC8CDD3;
    private static final int QUIET_COLOUR = 0xFF6E7076;

    private final Screen parent;

    public MirageKeysScreen(Screen parent) {
        super(Text.literal("Mirage keys"));
        this.parent = parent;
    }

    public MirageKeysScreen() {
        this(null);
    }

    // ------------------------------------------------------------------ layout

    private int rowsPerColumn() {
        return (MirageClient.binds().size() + 1) / 2;
    }

    private int columnLeft(int column) {
        return (this.width - (2 * COLUMN - 8)) / 2 + column * COLUMN;
    }

    private int rowTop(int index) {
        return TOP + (index % rowsPerColumn()) * ROW;
    }

    @Override
    protected void init() {
        // Pinned to the bottom rather than hung below the last row: at a large GUI scale
        // the window is short enough that a footer measured from the top falls off it.
        int footer = this.height - 28;
        int left = columnLeft(0);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"),
                        ignored -> this.close())
                .dimensions(left + 292, footer, 124, 20).build());
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }

    // ----------------------------------------------------------------- clashes

    /** What else answers to this key, or null if nothing does. */
    private KeyBinding clashFor(Bind bind) {
        if (bind.binding().isUnbound()) return null;

        for (KeyBinding other : this.client.options.allKeys) {
            if (other == bind.binding() || !sameKey(bind.binding(), other)) continue;
            return other;
        }
        return null;
    }

    /**
     * Whether two bindings answer to the same key.
     *
     * <p>Asked of the keys rather than of the bindings. KeyBinding does carry an equals that
     * compares bound keys, but it is an overload taking a KeyBinding rather than an override
     * of the usual one -- so if it ever moved, the call would quietly fall back to comparing
     * identities and no clash would ever be found again. A wrong answer that still compiles
     * is the one kind of mistake nothing here would catch.
     */
    private static boolean sameKey(KeyBinding one, KeyBinding two) {
        return KeyBindingHelper.getBoundKeyOf(one).equals(KeyBindingHelper.getBoundKeyOf(two));
    }

    private int clashCount() {
        int clashes = 0;
        for (Bind bind : MirageClient.binds()) {
            if (clashFor(bind) != null) clashes++;
        }
        return clashes;
    }

    // ----------------------------------------------------------------- drawing

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14,
                0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Change them in Options -> Controls -> Miscellaneous")
                        .formatted(Formatting.DARK_GRAY),
                this.width / 2, 28, QUIET_COLOUR);

        List<Bind> binds = MirageClient.binds();
        for (int i = 0; i < binds.size(); i++) {
            Bind bind = binds.get(i);
            boolean clash = clashFor(bind) != null;
            int x = columnLeft(i / rowsPerColumn());
            int y = rowTop(i) + 6;

            context.drawTextWithShadow(this.textRenderer, Text.literal(bind.label()),
                    x, y, clash ? CLASH_COLOUR : LABEL_COLOUR);

            KeyBinding binding = bind.binding();
            Text key = binding.isUnbound()
                    ? Text.literal("- none -").formatted(Formatting.DARK_GRAY)
                    : binding.getBoundKeyLocalizedText();
            context.drawTextWithShadow(this.textRenderer, key, x + LABEL_WIDTH, y,
                    clash ? CLASH_COLOUR : LABEL_COLOUR);
        }

        int clashes = clashCount();
        context.drawCenteredTextWithShadow(this.textRenderer,
                clashes == 0
                        ? Text.literal("Nothing else in Minecraft wants these keys.")
                                .formatted(Formatting.GRAY)
                        // Named as the thing to act on. A key Minecraft also uses does not
                        // fail, it does two things at once, which is far harder to spot.
                        : Text.literal(clashes + " key" + (clashes == 1 ? "" : "s")
                                + " in red are also Minecraft's. It runs both - clear its "
                                + "side in Options -> Controls.").formatted(Formatting.RED),
                this.width / 2, this.height - 42, 0xFFC8CDD3);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
