package dev.skullzz.mirage.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.client.font.TextRenderer;

/**
 * The two things every screen here paints with, in one place.
 *
 * <p>{@link #box} is the only call in this mod that has not already been watched compile:
 * nothing else fills a rectangle. It lives here, alone, so that if {@code fill} has been
 * renamed in some version of Minecraft it is one compile error in one method rather than
 * one in every screen -- and {@code gradlew inspectApi} prints what replaced it.
 *
 * <p>{@link #text} is not in that position at all; it is here only so the two screens draw
 * text the same way.
 */
public final class RyneDraw {

    private RyneDraw() {
    }

    /** A filled rectangle, in 0xAARRGGBB. */
    public static void box(DrawContext context, int x, int y, int width, int height,
                           int colour) {
        context.fill(x, y, x + width, y + height, colour);
    }

    public static void text(DrawContext context, TextRenderer renderer, String message,
                            int x, int y, int colour) {
        context.drawTextWithShadow(renderer, Text.literal(message), x, y, colour);
    }

    /** Cut to fit a column, so a long name cannot run into the next one. */
    public static String trim(String message, int most) {
        return message.length() <= most ? message : message.substring(0, most - 1) + "...";
    }
}
