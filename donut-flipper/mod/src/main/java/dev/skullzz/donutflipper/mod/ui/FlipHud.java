package dev.skullzz.donutflipper.mod.ui;

import dev.skullzz.donutflipper.mod.FlipperState;
import dev.skullzz.donutflipper.service.FlipDto;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * Compact corner overlay showing the best few flips while you play.
 *
 * <p>Deliberately small and dim. The screen is for deciding; this is only for
 * noticing. An overlay that competes with the game for attention gets switched
 * off, and an overlay that is switched off finds you nothing.
 */
public final class FlipHud {

    private static final int MAX_ROWS = 3;
    private static final int PADDING = 4;
    private static final int LINE_HEIGHT = 10;

    private static final int COLOUR_PANEL = 0x99101216;
    private static final int COLOUR_TITLE = 0xFFFBBF24;
    private static final int COLOUR_TEXT = 0xFFE6E9EF;
    private static final int COLOUR_PROFIT = 0xFF4ADE80;
    private static final int COLOUR_MUTED = 0xFF7A8294;

    private FlipHud() {
    }

    public static void render(DrawContext context, FlipperState state) {
        MinecraftClient client = MinecraftClient.getInstance();

        // Stay out of the way when the player is reading something else.
        if (client.player == null || client.currentScreen != null || client.options.hudHidden) {
            return;
        }

        List<FlipDto> top = state.top(MAX_ROWS);
        if (top.isEmpty() && state.connected()) {
            return;
        }

        int x = PADDING;
        int y = PADDING;
        int width = 150;
        int height = PADDING * 2 + LINE_HEIGHT * (1 + Math.max(1, top.size()));

        context.fill(x, y, x + width, y + height, COLOUR_PANEL);

        int textY = y + PADDING;
        context.drawText(client.textRenderer, "Flips", x + PADDING, textY, COLOUR_TITLE, false);
        textY += LINE_HEIGHT;

        if (!state.connected()) {
            context.drawText(client.textRenderer, "collector offline",
                    x + PADDING, textY, COLOUR_MUTED, false);
            return;
        }

        for (FlipDto f : top) {
            String name = client.textRenderer.trimToWidth(f.itemName(), 78);
            context.drawText(client.textRenderer, name, x + PADDING, textY, COLOUR_TEXT, false);
            context.drawText(client.textRenderer, "+" + compact(f.netProfit()),
                    x + width - PADDING - client.textRenderer.getWidth("+" + compact(f.netProfit())),
                    textY, COLOUR_PROFIT, false);
            textY += LINE_HEIGHT;
        }
    }

    private static String compact(long amount) {
        if (Math.abs(amount) >= 1_000_000L) {
            return String.format("%.1fM", amount / 1_000_000.0);
        }
        if (Math.abs(amount) >= 1_000L) {
            return String.format("%.0fk", amount / 1_000.0);
        }
        return String.valueOf(amount);
    }
}
