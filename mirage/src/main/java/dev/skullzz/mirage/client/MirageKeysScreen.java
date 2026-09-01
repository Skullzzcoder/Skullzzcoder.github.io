package dev.skullzz.mirage.client;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import org.lwjgl.glfw.GLFW;

import dev.skullzz.mirage.client.MirageClient.Bind;

/**
 * Every key the mod owns, changeable in one place.
 *
 * <p>These are ordinary key bindings, so vanilla's own Controls screen has always been able
 * to change them. What it cannot do is show them together, say what each one is for in this
 * mod's words, or tell you that the key you just picked already belongs to something else --
 * and that last one is the whole reason this screen exists. The default result key is F,
 * which vanilla uses for swapping to the offhand: Minecraft runs both, so rigging a game
 * threw your sword into your other hand, and nothing anywhere said why.
 *
 * <p>So a clash is not merely marked here, it is offered a way out. The other binding can be
 * cleared from this screen without going looking for it.
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

    private final List<ButtonWidget> keyButtons = new ArrayList<>();
    private final Screen parent;

    /** Which row is waiting for a key, or -1 when none is. */
    private int listening = -1;
    private Text status = Text.empty();

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
        this.keyButtons.clear();

        List<Bind> binds = MirageClient.binds();
        for (int i = 0; i < binds.size(); i++) {
            int row = i;
            ButtonWidget button = ButtonWidget.builder(Text.empty(), ignored -> listen(row))
                    .dimensions(columnLeft(i / rowsPerColumn()) + LABEL_WIDTH, rowTop(i),
                            KEY_WIDTH, 20)
                    .build();
            this.keyButtons.add(button);
            this.addDrawableChild(button);
        }

        // Pinned to the bottom rather than hung below the last row, the way the other
        // screen does it: at a large GUI scale the window is short enough that a footer
        // measured from the top ends up under the edge of the screen.
        int footer = this.height - 28;
        int left = columnLeft(0);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Put them all back"),
                        ignored -> resetAll())
                .dimensions(left, footer, 140, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Clear the clashes"),
                        ignored -> clearClashes())
                .dimensions(left + 146, footer, 140, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"),
                        ignored -> this.close())
                .dimensions(left + 292, footer, 124, 20).build());

        refresh();
    }

    // ----------------------------------------------------------------- binding

    private void listen(int row) {
        this.listening = row;
        this.status = Text.literal("Press a key, or Escape to leave it unbound.")
                .formatted(Formatting.GRAY);
        refresh();
    }

    /** Puts a key on the row that is listening, and stops listening. */
    private void assign(InputUtil.Key key) {
        List<Bind> binds = MirageClient.binds();
        if (this.listening < 0 || this.listening >= binds.size()) return;

        Bind bind = binds.get(this.listening);
        this.listening = -1;

        this.client.options.setKeyCode(bind.binding(), key);
        KeyBinding.updateKeysByCode();
        this.client.options.write();

        String other = clashFor(bind);
        this.status = other == null
                ? Text.literal(bind.label() + " is now "
                        + bind.binding().getBoundKeyLocalizedText().getString())
                        .formatted(Formatting.GRAY)
                // Said rather than merely coloured: a clash that is only a red button is a
                // clash nobody reads until the key does two things at once mid-game.
                : Text.literal("That key already does \"" + other
                        + "\". Minecraft will run both.").formatted(Formatting.RED);
        refresh();
    }

    private void resetAll() {
        for (Bind bind : MirageClient.binds()) {
            this.client.options.setKeyCode(bind.binding(),
                    bind.defaultCode() == GLFW.GLFW_KEY_UNKNOWN
                            ? InputUtil.UNKNOWN_KEY
                            : InputUtil.Type.KEYSYM.createFromCode(bind.defaultCode()));
        }
        KeyBinding.updateKeysByCode();
        this.client.options.write();

        this.listening = -1;
        this.status = Text.literal("Back to the keys they started with.")
                .formatted(Formatting.GRAY);
        refresh();
    }

    /**
     * Unbinds whatever else holds a key one of ours wants.
     *
     * <p>The other one, never ours: this screen is for setting this mod's keys, and taking
     * one of them away to settle a clash would be answering a question nobody asked.
     */
    private void clearClashes() {
        int cleared = 0;
        for (Bind bind : MirageClient.binds()) {
            if (bind.binding().isUnbound()) continue;

            for (KeyBinding other : this.client.options.allKeys) {
                if (other == bind.binding() || isOurs(other)) continue;
                if (!sameKey(bind.binding(), other)) continue;

                this.client.options.setKeyCode(other, InputUtil.UNKNOWN_KEY);
                cleared++;
            }
        }
        KeyBinding.updateKeysByCode();
        this.client.options.write();

        this.listening = -1;
        this.status = Text.literal(cleared == 0
                        ? "Nothing else wants these keys."
                        : "Cleared " + cleared + " of Minecraft's own binding"
                                + (cleared == 1 ? "" : "s") + ".")
                .formatted(Formatting.GRAY);
        refresh();
    }

    private static boolean isOurs(KeyBinding binding) {
        for (Bind bind : MirageClient.binds()) {
            if (bind.binding() == binding) return true;
        }
        return false;
    }

    /** What else answers to this key, or null if nothing does. */
    private String clashFor(Bind bind) {
        if (bind.binding().isUnbound()) return null;

        for (KeyBinding other : this.client.options.allKeys) {
            if (other == bind.binding() || !sameKey(bind.binding(), other)) continue;
            return Text.translatable(other.getTranslationKey()).getString();
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

    private void refresh() {
        List<Bind> binds = MirageClient.binds();
        for (int i = 0; i < this.keyButtons.size() && i < binds.size(); i++) {
            KeyBinding binding = binds.get(i).binding();
            Text name = binding.isUnbound()
                    ? Text.literal("- none -").formatted(Formatting.DARK_GRAY)
                    : binding.getBoundKeyLocalizedText();

            this.keyButtons.get(i).setMessage(i == this.listening
                    ? Text.literal("> ... <").formatted(Formatting.YELLOW)
                    : name);
        }
    }

    // ------------------------------------------------------------------- input

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.listening < 0) return super.keyPressed(keyCode, scanCode, modifiers);

        // Escape means "no key at all" here rather than "go back", which is what vanilla's
        // own controls screen does with it, and the only way to unbind something.
        assign(keyCode == GLFW.GLFW_KEY_ESCAPE
                ? InputUtil.UNKNOWN_KEY
                : InputUtil.Type.KEYSYM.createFromCode(keyCode));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Only once a row is waiting, so the click that started it is not the key it takes.
        if (this.listening >= 0) {
            assign(InputUtil.Type.MOUSE.createFromCode(button));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }

    // ----------------------------------------------------------------- drawing

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14,
                0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Click a key to change it. A red key is one Minecraft also uses.")
                        .formatted(Formatting.DARK_GRAY),
                this.width / 2, 28, QUIET_COLOUR);

        List<Bind> binds = MirageClient.binds();
        for (int i = 0; i < binds.size(); i++) {
            Bind bind = binds.get(i);
            boolean clash = clashFor(bind) != null;

            context.drawTextWithShadow(this.textRenderer,
                    Text.literal(bind.label()),
                    columnLeft(i / rowsPerColumn()), rowTop(i) + 6,
                    clash ? CLASH_COLOUR : LABEL_COLOUR);
        }

        if (!this.status.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, this.status,
                    this.width / 2, this.height - 42, 0xFFC8CDD3);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
