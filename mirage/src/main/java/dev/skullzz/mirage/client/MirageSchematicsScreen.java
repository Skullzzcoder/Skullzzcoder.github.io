package dev.skullzz.mirage.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/**
 * Schematics, the way Litematica shows them: what is on disk on one side, what is loaded
 * and where it stands on the other, and buttons that do the placing.
 *
 * <p>Built out of buttons and text only. Scroll lists, text boxes and key handling all
 * moved in this version of Minecraft, and guessing at them is how the keys screen ended up
 * half-finished -- so this pages with buttons instead. It is a little more clicking and it
 * compiles, which is the trade being made deliberately.
 *
 * <p>Loading and placing are separate here for the same reason Litematica separates them:
 * a schematic you have loaded is one you can put down repeatedly, in different places,
 * without reading the file again.
 */
public class MirageSchematicsScreen extends Screen {

    private static final int ROW = 22;
    private static final int PER_PAGE = 7;
    private static final int TOP = 46;
    private static final int NAME_WIDTH = 150;

    private static final int HEAD_COLOUR = 0xFFC8CDD3;
    private static final int QUIET_COLOUR = 0xFF6E7076;
    private static final int UP_COLOUR = 0xFF7FD18B;
    private static final int ELSEWHERE_COLOUR = 0xFFE0A55F;

    private final Screen parent;

    private int page;
    /** Which loaded build the placing buttons act on, by name. */
    private String chosen;
    private String said = "";

    public MirageSchematicsScreen(Screen parent) {
        this(parent, 0, null, "");
    }

    public MirageSchematicsScreen() {
        this(null, 0, null, "");
    }

    private MirageSchematicsScreen(Screen parent, int page, String chosen, String said) {
        super(Text.literal("Mirage schematics"));
        this.parent = parent;
        this.page = page;
        this.chosen = chosen;
        this.said = said;
    }

    // ------------------------------------------------------------------- the lists

    /** Files on disk that have not been loaded under their own name yet. */
    private List<String> files() {
        return Schematic.files();
    }

    private List<String> loaded() {
        return new ArrayList<>(FakeBlocks.builds().keySet());
    }

    private int pages() {
        int rows = Math.max(files().size(), loaded().size());
        return Math.max(1, (rows + PER_PAGE - 1) / PER_PAGE);
    }

    private int left() {
        return this.width / 2 - 300;
    }

    private int rightColumn() {
        return this.width / 2 + 10;
    }

    /** The name a file is loaded under: its own, without the ending. */
    static String nameFor(String fileName) {
        String cut = fileName;
        int dot = cut.lastIndexOf('.');
        if (dot > 0) cut = cut.substring(0, dot);
        // Command arguments are single words, and a build is named by one.
        return cut.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    // ---------------------------------------------------------------------- layout

    @Override
    protected void init() {
        this.page = Math.max(0, Math.min(this.page, pages() - 1));

        List<String> files = files();
        List<String> loaded = loaded();

        for (int row = 0; row < PER_PAGE; row++) {
            int index = this.page * PER_PAGE + row;
            int y = TOP + row * ROW;

            if (index < files.size()) {
                String file = files.get(index);
                this.addDrawableChild(ButtonWidget.builder(Text.literal("Load"),
                                ignored -> load(file))
                        .dimensions(left() + NAME_WIDTH + 6, y, 44, 20).build());
            }

            if (index < loaded.size()) {
                String name = loaded.get(index);
                boolean up = FakeBlocks.placed().containsKey(name);

                this.addDrawableChild(ButtonWidget.builder(
                                Text.literal(name.equals(this.chosen) ? "> " + name : name),
                                ignored -> { this.chosen = name; rebuild(); })
                        .dimensions(rightColumn(), y, NAME_WIDTH, 20).build());

                this.addDrawableChild(ButtonWidget.builder(Text.literal(up ? "Take" : "Put"),
                                ignored -> { this.chosen = name; placeOrTake(name, up); })
                        .dimensions(rightColumn() + NAME_WIDTH + 6, y, 44, 20).build());

                this.addDrawableChild(ButtonWidget.builder(Text.literal("Forget"),
                                ignored -> {
                                    FakeBlocks.forget(name);
                                    FakeBlocks.persist();
                                    this.said = "Forgot '" + name + "'.";
                                    rebuild();
                                })
                        .dimensions(rightColumn() + NAME_WIDTH + 54, y, 56, 20).build());
            }
        }

        int nudges = TOP + PER_PAGE * ROW + 12;
        addNudges(nudges);

        int footer = this.height - 28;
        if (pages() > 1) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("< Back"),
                            ignored -> { this.page = Math.max(0, this.page - 1); rebuild(); })
                    .dimensions(left(), footer, 70, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("More >"),
                            ignored -> {
                                this.page = Math.min(pages() - 1, this.page + 1);
                                rebuild();
                            })
                    .dimensions(left() + 76, footer, 70, 20).build());
        }

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"),
                        ignored -> { this.said = ""; rebuild(); })
                .dimensions(this.width / 2 + 100, footer, 80, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"),
                        ignored -> this.close())
                .dimensions(this.width / 2 + 186, footer, 104, 20).build());
    }

    /**
     * The nudging row.
     *
     * <p>The reason this screen is worth having at all: lining a build up by typing a
     * command, reading the result and typing it again is the same job done blind.
     */
    private void addNudges(int y) {
        int x = rightColumn();
        button("West", x, y, () -> nudge(-1, 0, 0));
        button("East", x + 52, y, () -> nudge(1, 0, 0));
        button("North", x + 104, y, () -> nudge(0, 0, -1));
        button("South", x + 156, y, () -> nudge(0, 0, 1));
        button("Up", x + 208, y, () -> nudge(0, 1, 0));
        button("Down", x + 252, y, () -> nudge(0, -1, 0));
    }

    private void button(String label, int x, int y, Runnable action) {
        this.addDrawableChild(ButtonWidget.builder(Text.literal(label), ignored -> action.run())
                .dimensions(x, y, label.length() > 3 ? 48 : 40, 20).build());
    }

    /**
     * Redrawn after a click, since what a row says depends on state the click just changed.
     *
     * <p>Opened afresh rather than having its widgets cleared and rebuilt in place. Both
     * would work; only one of them uses a method this mod has already seen compile, and
     * the keys screen is half-finished because of a guess exactly like that one.
     */
    private void rebuild() {
        if (this.client == null) return;
        this.client.setScreen(
                new MirageSchematicsScreen(this.parent, this.page, this.chosen, this.said));
    }

    // --------------------------------------------------------------------- actions

    private void load(String file) {
        String name = nameFor(file);
        int blocks = Schematic.load(file, name);
        this.said = blocks < 0 ? Schematic.lastReason()
                : "'" + name + "' " + Schematic.lastReason();
        if (blocks >= 0) this.chosen = name;
        rebuild();
    }

    private void placeOrTake(String name, boolean up) {
        if (up) {
            FakeBlocks.take(name);
            this.said = "Took '" + name + "' down.";
        } else {
            // At the player's feet, not where they are looking: this screen is open, so
            // there is nothing being looked at.
            BlockPos corner = this.client == null || this.client.player == null ? null
                    : this.client.player.getBlockPos();
            if (corner == null) {
                this.said = "Nowhere to stand it up.";
            } else {
                int count = FakeBlocks.put(name, corner);
                this.said = count < 0 ? "No build called '" + name + "'."
                        : "'" + name + "' is up at your feet. Nudge it into place below.";
            }
        }
        FakeBlocks.persist();
        rebuild();
    }

    private void nudge(int east, int up, int south) {
        if (this.chosen == null) {
            this.said = "Pick a build on the right first.";
            rebuild();
            return;
        }

        BlockPos corner = FakeBlocks.move(this.chosen, east, up, south);
        this.said = corner == null
                ? "'" + this.chosen + "' is not standing. Put it up first."
                : this.chosen + " at " + corner.getX() + " " + corner.getY() + " "
                        + corner.getZ();
        FakeBlocks.persist();
        rebuild();
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }

    // --------------------------------------------------------------------- drawing

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, 12, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Drop .litematic files in " + Schematic.folder())
                        .formatted(Formatting.DARK_GRAY),
                this.width / 2, 26, QUIET_COLOUR);

        context.drawTextWithShadow(this.textRenderer,
                Text.literal("On disk").formatted(Formatting.GRAY), left(), TOP - 12,
                HEAD_COLOUR);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal("Loaded").formatted(Formatting.GRAY), rightColumn(), TOP - 12,
                HEAD_COLOUR);

        List<String> files = files();
        List<String> loaded = loaded();

        for (int row = 0; row < PER_PAGE; row++) {
            int index = this.page * PER_PAGE + row;
            int y = TOP + row * ROW + 6;

            if (index < files.size()) {
                context.drawTextWithShadow(this.textRenderer,
                        Text.literal(trim(files.get(index), 26)), left(), y, HEAD_COLOUR);
            }
            if (index < loaded.size()) {
                String name = loaded.get(index);
                FakeBlocks.Build build = FakeBlocks.builds().get(name);
                BlockPos at = FakeBlocks.placed().get(name);

                // Where it stands, and whether it stands here: a build up in another world
                // is the answer to "why can I not see it".
                String state = at == null ? build.count() + " blocks, " + build.size()
                        : at.getX() + " " + at.getY() + " " + at.getZ();
                int colour = at == null ? QUIET_COLOUR
                        : FakeBlocks.belongsHere(name) ? UP_COLOUR : ELSEWHERE_COLOUR;
                if (at != null && !FakeBlocks.belongsHere(name)) state += " (another world)";

                context.drawTextWithShadow(this.textRenderer, Text.literal(trim(state, 30)),
                        rightColumn() + NAME_WIDTH + 116, y, colour);
            }
        }

        if (files.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("Nothing here yet - the folder above, or your Desktop,")
                            .formatted(Formatting.DARK_GRAY), left(), TOP + 6, QUIET_COLOUR);
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("Downloads or Pictures, then press Refresh.")
                            .formatted(Formatting.DARK_GRAY), left(), TOP + 20, QUIET_COLOUR);
        }

        context.drawTextWithShadow(this.textRenderer,
                Text.literal(this.chosen == null ? "Nudging: pick one on the right"
                        : "Nudging: " + this.chosen).formatted(Formatting.GRAY),
                rightColumn() + 300, TOP + PER_PAGE * ROW + 18, HEAD_COLOUR);

        if (!this.said.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(trim(this.said, 110)).formatted(Formatting.GRAY),
                    this.width / 2, this.height - 46, HEAD_COLOUR);
        }
    }

    /** Long enough to say which one, short enough not to run into the next column. */
    static String trim(String text, int most) {
        return text.length() <= most ? text : text.substring(0, most - 1) + "…";
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
