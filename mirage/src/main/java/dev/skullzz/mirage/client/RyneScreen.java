package dev.skullzz.mirage.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * One screen for everything the mod does, laid out like a client menu: a sidebar of
 * modules on the left, a search box across the top, and cards for whatever is selected.
 *
 * <p>Every interactive thing here is a {@link ButtonWidget} and every piece of text goes
 * through the two draw calls this mod has already seen compile. That is a deliberate
 * constraint rather than a limitation of taste -- custom click handling and text drawing
 * both moved in this version of Minecraft, and the keys screen is still half-finished
 * because four names were guessed at instead of looked up.
 *
 * <p>The one exception is {@link #box}, which draws a filled rectangle. There is nothing
 * else in the mod that fills one, so that name is the single unproven call in this file.
 * It is isolated in one method for exactly that reason: if it has moved, it is one
 * compile error in one place, and {@code gradlew inspectApi} prints what replaced it.
 */
public class RyneScreen extends Screen {

    /** A page in the sidebar: what it is called, and which group it sits under. */
    private enum Page {
        BUILDS("Builds", "MODULES"),
        SCHEMATICS("Schematics", "MODULES"),
        MAP_ART("Map art", "MODULES"),
        ITEMS("Fake items", "MODULES"),
        RIGS("Rigs", "MODULES"),
        KEYS("Keys", "GENERAL"),
        THEME("Theme", "GENERAL");

        final String label;
        final String group;

        Page(String label, String group) {
            this.label = label;
            this.group = group;
        }
    }

    private static final int SIDEBAR = 168;
    private static final int PAD = 14;
    private static final int ROW = 26;

    private final Screen parent;
    private Page page;
    private String filter = "";
    private String said = "";
    private TextFieldWidget search;

    public RyneScreen() {
        this(null, Page.BUILDS, "", "");
    }

    public RyneScreen(Screen parent) {
        this(parent, Page.BUILDS, "", "");
    }

    private RyneScreen(Screen parent, Page page, String filter, String said) {
        super(Text.literal("Ryne Client"));
        this.parent = parent;
        this.page = page;
        this.filter = filter;
        this.said = said;
    }

    // ---------------------------------------------------------------------- geometry

    private int left() {
        return (this.width - Math.min(this.width - 20, 900)) / 2;
    }

    private int right() {
        return this.width - left();
    }

    private int top() {
        return (this.height - Math.min(this.height - 20, 480)) / 2;
    }

    private int bottom() {
        return this.height - top();
    }

    private int contentLeft() {
        return left() + SIDEBAR + PAD;
    }

    // ------------------------------------------------------------------------ layout

    @Override
    protected void init() {
        this.search = new TextFieldWidget(this.textRenderer, contentLeft(), top() + PAD,
                right() - contentLeft() - PAD, 20, Text.literal("Search modules"));
        this.search.setMaxLength(64);
        this.search.setText(this.filter);
        this.addDrawableChild(this.search);

        // The sidebar. Group headings are drawn, not clickable, so only the pages are
        // buttons -- which keeps every clickable thing in this screen one widget type.
        int y = top() + 58;
        String group = "";
        for (Page candidate : Page.values()) {
            if (!matches(candidate)) continue;
            if (!candidate.group.equals(group)) {
                group = candidate.group;
                y += 16;
            }
            Page it = candidate;
            this.addDrawableChild(ButtonWidget.builder(
                            Text.literal((it == this.page ? "> " : "  ") + it.label),
                            ignored -> go(it))
                    .dimensions(left() + PAD, y, SIDEBAR - PAD * 2, 20).build());
            y += ROW;
        }

        buildPage();

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"),
                        ignored -> this.close())
                .dimensions(right() - PAD - 90, bottom() - PAD - 20, 90, 20).build());
    }

    /** Whether a page survives what is typed in the search box. */
    private boolean matches(Page candidate) {
        if (this.filter.isEmpty()) return true;
        return candidate.label.toLowerCase(Locale.ROOT)
                .contains(this.filter.toLowerCase(Locale.ROOT));
    }

    private void go(Page next) {
        this.page = next;
        this.said = "";
        reopen();
    }

    /**
     * Opened afresh rather than having its widgets cleared in place.
     *
     * <p>Same reason as the schematics screen: setScreen is a call this mod has watched
     * compile, and clearChildren is not.
     */
    private void reopen() {
        if (this.client == null) return;
        this.filter = this.search == null ? this.filter : this.search.getText();
        this.client.setScreen(new RyneScreen(this.parent, this.page, this.filter, this.said));
    }

    private ButtonWidget add(String label, int x, int y, int width, Runnable action) {
        ButtonWidget button = ButtonWidget.builder(Text.literal(label),
                        ignored -> { action.run(); reopen(); })
                .dimensions(x, y, width, 20).build();
        this.addDrawableChild(button);
        return button;
    }

    // ------------------------------------------------------------------- the pages

    private void buildPage() {
        int x = contentLeft();
        int y = top() + 74;
        int wide = right() - contentLeft() - PAD;

        switch (this.page) {
            case BUILDS -> {
                add("Take down what I am looking at", x, y, 230, () -> {
                    this.said = "Look at a build in the world, not at this screen.";
                });
                add("Take everything down", x + 236, y, 150, () -> {
                    FakeBlocks.takeAll();
                    FakeBlocks.persist();
                    this.said = "Every build is down.";
                });
            }
            case SCHEMATICS -> add("Open the schematic picker", x, y, 220, () -> {
                if (this.client != null) this.client.setScreen(new MirageSchematicsScreen(this));
            });
            case MAP_ART -> add("Map art is driven by /fake map", x, y, 240, () -> {
                this.said = "/fake map save held <name>, /fake map import <file> <name>.";
            });
            case ITEMS -> add("Open the fake items screen", x, y, 220, () -> {
                if (this.client != null) this.client.setScreen(new FakeItemsScreen());
            });
            case RIGS -> {
                boolean on = SelfFakes.rigsOn();
                add(on ? "Rigs are ON - turn them off" : "Rigs are OFF - turn them on",
                        x, y, 250, () -> {
                            SelfFakes.setRigsOn(!on);
                            if (on) ClientDispensers.standDown();
                            this.said = on
                                    ? "Rigs off. Builds, schematics and map art still work."
                                    : "Rigs back on, set up as they were.";
                        });
            }
            case KEYS -> add("Open the keys screen", x, y, 200, () -> {
                if (this.client != null) this.client.setScreen(new MirageKeysScreen(this));
            });
            case THEME -> {
                // Two columns of theme cards, like a client's theme picker.
                int half = (wide - 10) / 2;
                for (int i = 0; i < RyneTheme.ALL.size(); i++) {
                    RyneTheme.Theme theme = RyneTheme.ALL.get(i);
                    int column = i % 2;
                    int row = i / 2;
                    int index = i;
                    add((i == RyneTheme.index() ? "> " : "  ") + theme.name,
                            x + column * (half + 10), y + row * 46, half, () -> {
                                RyneTheme.choose(index);
                                SelfFakes.save();
                                this.said = "Theme: " + RyneTheme.current().name;
                            });
                }
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------------ paint

    /**
     * A filled rectangle.
     *
     * <p>The one call in this file the mod has not already seen compile, kept in one place
     * on purpose. If {@code fill} has been renamed in this version, this method is the only
     * thing that needs changing, and {@code gradlew inspectApi} prints the new name.
     */
    private static void box(DrawContext context, int x, int y, int width, int height,
                            int colour) {
        context.fill(x, y, x + width, y + height, colour);
    }

    private void line(DrawContext context, String text, int x, int y, int colour) {
        context.drawTextWithShadow(this.textRenderer, Text.literal(text), x, y, colour);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        RyneTheme.Theme theme = RyneTheme.current();

        box(context, left(), top(), right() - left(), bottom() - top(), theme.page);
        box(context, left(), top(), SIDEBAR, bottom() - top(), theme.panel);
        box(context, left() + SIDEBAR, top(), 1, bottom() - top(), theme.line);

        // The brand block, and the accent square that carries the theme colour.
        box(context, left() + PAD, top() + PAD, 22, 22, theme.accent);
        line(context, "RYNE CLIENT", left() + PAD + 30, top() + PAD + 7, theme.text);

        int y = top() + 58;
        String group = "";
        for (Page candidate : Page.values()) {
            if (!matches(candidate)) continue;
            if (!candidate.group.equals(group)) {
                group = candidate.group;
                line(context, group, left() + PAD, y + 4, theme.dim);
                y += 16;
            }
            if (candidate == this.page) {
                box(context, left() + PAD - 4, y, 3, 20, theme.accent);
            }
            y += ROW;
        }

        super.render(context, mouseX, mouseY, delta);

        line(context, this.page.label, contentLeft(), top() + 50, theme.text);
        paintCards(context, theme);

        if (!this.said.isEmpty()) {
            line(context, this.said, contentLeft(), bottom() - PAD - 14, theme.dim);
        }
    }

    /** The panel behind whatever the page put on it, and the theme swatches. */
    private void paintCards(DrawContext context, RyneTheme.Theme theme) {
        int x = contentLeft();
        int wide = right() - contentLeft() - PAD;

        if (this.page == Page.THEME) {
            int half = (wide - 10) / 2;
            for (int i = 0; i < RyneTheme.ALL.size(); i++) {
                RyneTheme.Theme swatch = RyneTheme.ALL.get(i);
                int column = i % 2;
                int row = i / 2;
                int cardX = x + column * (half + 10);
                int cardY = top() + 74 + row * 46;

                box(context, cardX, cardY + 22, half, 20,
                        i == RyneTheme.index() ? swatch.accentSoft : theme.card);
                box(context, cardX + 6, cardY + 28, 8, 8, swatch.accent);
                line(context, swatch.blurb, cardX + 20, cardY + 30, theme.dim);
            }
            return;
        }

        line(context, describe(), x, top() + 108, theme.dim);
    }

    /** One line of what this page's part of the mod is currently doing. */
    private String describe() {
        return switch (this.page) {
            case BUILDS -> FakeBlocks.builds().size() + " saved, "
                    + FakeBlocks.placed().size() + " standing";
            case SCHEMATICS -> Schematic.files().size() + " file(s) it can see";
            case MAP_ART -> MapArt.names().size() + " design(s) kept";
            case ITEMS -> "Fake items live in their own screen";
            case RIGS -> SelfFakes.rigsOn()
                    ? "Rig: " + ClientDispensers.active().name
                    : "Off. Builds, schematics and map art are unaffected.";
            case KEYS -> MirageClient.binds().size() + " keys";
            case THEME -> "Pick one. It is remembered.";
        };
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
