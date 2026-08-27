package com.skullzz.donutgambler.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Shared colours and small drawing helpers. All colours are ARGB with an explicit alpha. */
public final class Theme {
	public static final int TEXT = 0xFFE9EAEE;
	public static final int MUTED = 0xFF959AA4;
	public static final int DIM = 0xFF6C717B;
	public static final int GREEN = 0xFF4CE07A;
	public static final int RED = 0xFFE5544B;
	public static final int YELLOW = 0xFFE8C547;
	public static final int ACCENT = 0xFFE2A33A;

	public static final int PANEL = 0xD8101216;
	public static final int PANEL_RAISED = 0xFF181B21;
	public static final int BORDER = 0xFF2C313A;
	public static final int ROW_ALT = 0x14FFFFFF;
	public static final int ROW_HOVER = 0x30E2A33A;
	public static final int CHART_ZERO = 0xFF3A404B;

	private Theme() {
	}

	/** Colour for a money figure: green when up, red when down, grey at zero. */
	public static int money(double value) {
		if (value > 0) return GREEN;
		if (value < 0) return RED;
		return MUTED;
	}

	/** Adds full opacity to a colour that was stored without an alpha channel. */
	public static int opaque(int rgb) {
		return 0xFF000000 | rgb;
	}

	public static int withAlpha(int rgb, int alpha) {
		return ((alpha & 0xFF) << 24) | (rgb & 0xFFFFFF);
	}

	public static void panel(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, PANEL);
		border(g, x, y, w, h, BORDER);
	}

	public static void raisedPanel(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, PANEL_RAISED);
		border(g, x, y, w, h, BORDER);
	}

	public static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + 1, color);
		g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y, x + 1, y + h, color);
		g.fill(x + w - 1, y, x + w, y + h, color);
	}

	/** Truncates with an ellipsis so a long value never runs into the next column. */
	public static String fit(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) return text;

		String ellipsis = "...";
		StringBuilder sb = new StringBuilder();

		for (int i = 0; i < text.length(); i++) {
			if (font.width(sb.toString() + text.charAt(i) + ellipsis) > maxWidth) break;
			sb.append(text.charAt(i));
		}

		return sb + ellipsis;
	}

	/** Word-wraps to a pixel width, capped at {@code maxLines}. */
	public static List<String> wrap(Font font, String text, int maxWidth, int maxLines) {
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();

		for (String word : text.split(" ")) {
			String candidate = current.isEmpty() ? word : current + " " + word;

			if (font.width(candidate) > maxWidth && !current.isEmpty()) {
				lines.add(current.toString());
				current = new StringBuilder(word);

				if (lines.size() == maxLines - 1) {
					break;
				}
			} else {
				current = new StringBuilder(candidate);
			}
		}

		if (lines.size() < maxLines && !current.isEmpty()) {
			lines.add(fit(font, current.toString(), maxWidth));
		}

		return lines;
	}
}
