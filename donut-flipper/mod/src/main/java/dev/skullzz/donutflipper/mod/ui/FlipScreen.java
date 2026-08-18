package dev.skullzz.donutflipper.mod.ui;

import dev.skullzz.donutflipper.mod.AssistController;
import dev.skullzz.donutflipper.mod.FlipperState;
import dev.skullzz.donutflipper.service.FlipDto;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The main in-game flip board.
 *
 * <p>Shows every figure that went into the recommendation, not just a verdict.
 * A tool that says "buy this" is one you stop trusting the first time it is
 * wrong; one that shows the estimate, the sample size, the seller diversity and
 * the liquidity behind it is one you can sanity-check in two seconds before
 * spending real coins. The confidence column earns its width.
 */
public class FlipScreen extends Screen {

    // Column widths as fractions of the table, so the layout survives any GUI scale.
    private static final float[] COLUMN_WEIGHTS = {0.26f, 0.12f, 0.12f, 0.13f, 0.08f, 0.10f, 0.10f, 0.09f};
    private static final String[] COLUMN_NAMES = {
            "ITEM", "BUY", "EST. VALUE", "PROFIT", "ROI", "SALES/DAY", "CONFIDENCE", "SELLER"};

    private static final int ROW_HEIGHT = 14;
    private static final int HEADER_Y = 56;
    private static final int TABLE_TOP = HEADER_Y + 14;
    private static final int BOTTOM_MARGIN = 30;

    private static final int COLOUR_BG = 0xE8101216;
    private static final int COLOUR_ROW_ALT = 0x18FFFFFF;
    private static final int COLOUR_ROW_HOVER = 0x38FFFFFF;
    private static final int COLOUR_HEADER = 0xFF8A93A6;
    private static final int COLOUR_TEXT = 0xFFE6E9EF;
    private static final int COLOUR_MUTED = 0xFF7A8294;
    private static final int COLOUR_PROFIT = 0xFF4ADE80;
    private static final int COLOUR_WARN = 0xFFFBBF24;
    private static final int COLOUR_BAD = 0xFFF87171;

    private final FlipperState state;

    private TextFieldWidget searchField;
    private String search = "";
    private String profile = "balanced";

    private int sortColumn = 3;      // profit
    private boolean sortDescending = true;
    private double scroll = 0;
    private List<FlipDto> visible = List.of();

    public FlipScreen(FlipperState state) {
        super(Text.literal("Donut Flipper"));
        this.state = state;
    }

    @Override
    protected void init() {
        int margin = tableLeft();

        searchField = new TextFieldWidget(this.textRenderer, margin, 30,
                180, 16, Text.literal("Search"));
        searchField.setPlaceholder(Text.literal("filter by item name..."));
        searchField.setChangedListener(value -> {
            search = value.toLowerCase(Locale.ROOT);
            scroll = 0;
        });
        addDrawableChild(searchField);

        int x = margin + 190;
        for (String name : new String[]{"balanced", "volume", "whale"}) {
            addDrawableChild(ButtonWidget.builder(
                            Text.literal(capitalise(name)), b -> {
                                profile = name;
                                state.setProfile(name);
                                scroll = 0;
                            })
                    .dimensions(x, 30, 60, 16)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                            Text.literal(profileHint(name))))
                    .build());
            x += 64;
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(this.width - margin - 50, 30, 50, 16)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, this.width, this.height, COLOUR_BG);

        drawHeader(context);

        visible = filterAndSort(state.flips());

        int left = tableLeft();
        int right = this.width - tableLeft();
        int[] columnX = computeColumns(left, right);

        drawColumnHeaders(context, columnX, mouseX, mouseY);

        int bottom = this.height - BOTTOM_MARGIN;
        int rows = Math.max(0, (bottom - TABLE_TOP) / ROW_HEIGHT);
        int start = (int) Math.floor(scroll);
        start = Math.max(0, Math.min(start, Math.max(0, visible.size() - rows)));

        if (visible.isEmpty()) {
            drawEmptyState(context, left, TABLE_TOP + 20);
        } else {
            for (int i = 0; i < rows && start + i < visible.size(); i++) {
                int y = TABLE_TOP + i * ROW_HEIGHT;
                boolean hovered = mouseY >= y && mouseY < y + ROW_HEIGHT
                        && mouseX >= left && mouseX <= right;
                drawRow(context, visible.get(start + i), columnX, y, i, hovered);
            }
        }

        drawFooter(context, start, rows);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawHeader(DrawContext context) {
        context.drawText(this.textRenderer, "Donut Flipper", tableLeft(), 12, COLOUR_TEXT, false);

        String status = state.connected()
                ? state.status() + "  -  updated " + state.secondsSinceUpdate() + "s ago"
                : state.status();
        context.drawText(this.textRenderer, status,
                tableLeft() + 90, 12, state.connected() ? COLOUR_MUTED : COLOUR_BAD, false);
    }

    private void drawColumnHeaders(DrawContext context, int[] columnX, int mouseX, int mouseY) {
        for (int c = 0; c < COLUMN_NAMES.length; c++) {
            String label = COLUMN_NAMES[c] + (c == sortColumn ? (sortDescending ? " v" : " ^") : "");
            context.drawText(this.textRenderer, label, columnX[c], HEADER_Y,
                    c == sortColumn ? COLOUR_TEXT : COLOUR_HEADER, false);
        }
        context.fill(tableLeft(), HEADER_Y + 10, this.width - tableLeft(),
                HEADER_Y + 11, 0x40FFFFFF);
    }

    private void drawRow(DrawContext context, FlipDto f, int[] columnX,
                         int y, int index, boolean hovered) {
        if (hovered) {
            context.fill(tableLeft(), y - 2, this.width - tableLeft(), y + ROW_HEIGHT - 2,
                    COLOUR_ROW_HOVER);
        } else if (index % 2 == 1) {
            context.fill(tableLeft(), y - 2, this.width - tableLeft(), y + ROW_HEIGHT - 2,
                    COLOUR_ROW_ALT);
        }

        String name = f.count() > 1 ? f.itemName() + " x" + f.count() : f.itemName();

        context.drawText(this.textRenderer, trim(name, columnWidth(0)), columnX[0], y, COLOUR_TEXT, false);
        context.drawText(this.textRenderer, coins(f.buyPrice()), columnX[1], y, COLOUR_TEXT, false);
        context.drawText(this.textRenderer, coins(f.estimatedValue()), columnX[2], y, COLOUR_MUTED, false);
        context.drawText(this.textRenderer, coins(f.netProfit()), columnX[3], y, COLOUR_PROFIT, false);
        context.drawText(this.textRenderer, Math.round(f.roiPercent()) + "%", columnX[4], y, COLOUR_PROFIT, false);
        context.drawText(this.textRenderer, String.format("%.1f", f.salesPerDay()),
                columnX[5], y, liquidityColour(f.salesPerDay()), false);
        context.drawText(this.textRenderer, confidenceLabel(f), columnX[6], y,
                confidenceColour(f.confidence()), false);
        context.drawText(this.textRenderer, trim(f.seller(), columnWidth(7)),
                columnX[7], y, COLOUR_MUTED, false);
    }

    /**
     * Confidence shown with its sample size attached. "Strong" on its own is a
     * claim; "Strong (14/6)" lets you see it rests on fourteen sales from six
     * different sellers and judge for yourself.
     */
    private String confidenceLabel(FlipDto f) {
        return switch (f.confidence()) {
            case "HIGH" -> "Strong " + f.sampleCount() + "/" + f.distinctSellers();
            case "MEDIUM" -> "Fair " + f.sampleCount() + "/" + f.distinctSellers();
            case "LOW" -> "Thin " + f.sampleCount();
            default -> "None";
        };
    }

    private void drawEmptyState(DrawContext context, int x, int y) {
        String[] lines = state.connected()
                ? new String[]{
                        "No flips match the current profile.",
                        "",
                        "This is normal early on. Valuations need completed sales,",
                        "and the collector needs a day or two of history before it",
                        "can tell an underpriced listing from an ordinary one.",
                        "",
                        "Try the Volume profile for a looser filter."}
                : new String[]{
                        "Not connected to the collector.",
                        "",
                        "Start it with:  java -jar daemon-all.jar collect",
                        "",
                        "The mod only draws what the collector finds; it does not",
                        "talk to the API itself."};

        int lineY = y;
        for (String line : lines) {
            context.drawText(this.textRenderer, line, x, lineY,
                    line.startsWith("No flips") || line.startsWith("Not connected")
                            ? COLOUR_WARN : COLOUR_MUTED, false);
            lineY += 12;
        }
    }

    private void drawFooter(DrawContext context, int start, int rows) {
        String left = visible.isEmpty()
                ? ""
                : "showing " + (start + 1) + "-" + Math.min(start + rows, visible.size())
                        + " of " + visible.size();
        context.drawText(this.textRenderer, left, tableLeft(), this.height - 22, COLOUR_MUTED, false);

        String hint = "click a row to search it in /ah   -   shift-click to copy details   -   scroll to page";
        context.drawText(this.textRenderer, hint,
                tableLeft(), this.height - 11, COLOUR_MUTED, false);
    }

    // ------------------------------------------------------------------
    // Interaction
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = tableLeft();
        int right = this.width - left;
        int[] columnX = computeColumns(left, right);

        // Header click sorts.
        if (mouseY >= HEADER_Y - 2 && mouseY <= HEADER_Y + 10 && mouseX >= left && mouseX <= right) {
            for (int c = COLUMN_NAMES.length - 1; c >= 0; c--) {
                if (mouseX >= columnX[c]) {
                    if (sortColumn == c) {
                        sortDescending = !sortDescending;
                    } else {
                        sortColumn = c;
                        sortDescending = true;
                    }
                    return true;
                }
            }
        }

        // Row click triggers the assist.
        if (mouseY >= TABLE_TOP && mouseX >= left && mouseX <= right && !visible.isEmpty()) {
            int index = (int) Math.floor(scroll) + (int) ((mouseY - TABLE_TOP) / ROW_HEIGHT);
            if (index >= 0 && index < visible.size()) {
                FlipDto flip = visible.get(index);
                if (hasShiftDown()) {
                    AssistController.copyDetails(flip);
                } else {
                    AssistController.assist(flip);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        int rows = Math.max(1, (this.height - BOTTOM_MARGIN - TABLE_TOP) / ROW_HEIGHT);
        double max = Math.max(0, visible.size() - rows);
        scroll = Math.max(0, Math.min(max, scroll - verticalAmount * 3));
        return true;
    }

    @Override
    public boolean shouldPause() {
        // Never pause: the whole point is to check the board mid-play without
        // the world stopping around you.
        return false;
    }

    // ------------------------------------------------------------------
    // Data shaping
    // ------------------------------------------------------------------

    private List<FlipDto> filterAndSort(List<FlipDto> source) {
        List<FlipDto> result = new ArrayList<>(source.size());
        for (FlipDto f : source) {
            if (search.isEmpty()
                    || f.itemName().toLowerCase(Locale.ROOT).contains(search)
                    || f.materialId().toLowerCase(Locale.ROOT).contains(search)) {
                result.add(f);
            }
        }
        Comparator<FlipDto> comparator = switch (sortColumn) {
            case 0 -> Comparator.comparing(FlipDto::itemName);
            case 1 -> Comparator.comparingLong(FlipDto::buyPrice);
            case 2 -> Comparator.comparingLong(FlipDto::estimatedValue);
            case 4 -> Comparator.comparingDouble(FlipDto::roiPercent);
            case 5 -> Comparator.comparingDouble(FlipDto::salesPerDay);
            case 6 -> Comparator.comparingDouble(FlipDto::score);
            case 7 -> Comparator.comparing(FlipDto::seller);
            default -> Comparator.comparingLong(FlipDto::netProfit);
        };
        result.sort(sortDescending ? comparator.reversed() : comparator);
        return result;
    }

    // ------------------------------------------------------------------
    // Layout helpers
    // ------------------------------------------------------------------

    private int tableLeft() {
        return Math.max(12, this.width / 20);
    }

    private int tableWidth() {
        return this.width - tableLeft() * 2;
    }

    private int columnWidth(int column) {
        return (int) (tableWidth() * COLUMN_WEIGHTS[column]) - 4;
    }

    private int[] computeColumns(int left, int right) {
        int[] xs = new int[COLUMN_WEIGHTS.length];
        int width = right - left;
        int x = left;
        for (int i = 0; i < COLUMN_WEIGHTS.length; i++) {
            xs[i] = x;
            x += (int) (width * COLUMN_WEIGHTS[i]);
        }
        return xs;
    }

    private String trim(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        return this.textRenderer.trimToWidth(text, maxWidth - 6) + "...";
    }

    /** Compact coin formatting -- full digits do not fit and are not readable anyway. */
    private static String coins(long amount) {
        if (Math.abs(amount) >= 1_000_000_000L) {
            return String.format("%.2fB", amount / 1_000_000_000.0);
        }
        if (Math.abs(amount) >= 1_000_000L) {
            return String.format("%.2fM", amount / 1_000_000.0);
        }
        if (Math.abs(amount) >= 1_000L) {
            return String.format("%.1fk", amount / 1_000.0);
        }
        return String.valueOf(amount);
    }

    private static int liquidityColour(double salesPerDay) {
        if (salesPerDay >= 5) return COLOUR_PROFIT;
        if (salesPerDay >= 1) return COLOUR_WARN;
        return COLOUR_BAD;
    }

    private static int confidenceColour(String confidence) {
        return switch (confidence) {
            case "HIGH" -> COLOUR_PROFIT;
            case "MEDIUM" -> COLOUR_WARN;
            default -> COLOUR_BAD;
        };
    }

    private static String capitalise(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String profileHint(String name) {
        return switch (name) {
            case "volume" -> "Looser filter: smaller margins, thinner evidence, many more alerts.";
            case "whale" -> "Big-ticket only: 250k+ profit, tolerates slow-moving gear.";
            default -> "Default: demands real sale history and real liquidity.";
        };
    }
}
