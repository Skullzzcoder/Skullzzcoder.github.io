package com.skullzz.glaze.config.gui;

import net.minecraft.client.gui.DrawContext;

/**
 * Colours and drawing primitives for the settings screen.
 *
 * <p>Everything here is built from plain filled rectangles. That is a deliberate
 * choice: sprite-based widgets are the part of the GUI API that churns most
 * between versions, and rectangles do not.
 */
public final class Theme {
	public static final int SCREEN_DIM = 0xC0101014;

	public static final int PANEL = 0xFF1B1B21;
	public static final int PANEL_RAISED = 0xFF24242C;
	public static final int SIDEBAR = 0xFF151519;
	public static final int HEADER_TOP = 0xFF2A2118;
	public static final int HEADER_BOTTOM = 0xFF1B1B21;

	public static final int BORDER = 0xFF33333D;
	public static final int BORDER_BRIGHT = 0xFF45454F;

	/** Glaze gold, used for the accent line, selection and headings. */
	public static final int ACCENT = 0xFFE8A33D;
	public static final int ACCENT_SOFT = 0x40E8A33D;

	public static final int TEXT = 0xFFE9E9EE;
	public static final int TEXT_DIM = 0xFF8C8C99;
	public static final int TEXT_FAINT = 0xFF5E5E6B;

	public static final int ON = 0xFF4ADE80;
	public static final int OFF = 0xFF4A4A56;
	public static final int DANGER = 0xFFF87171;

	public static final int HOVER = 0x18FFFFFF;

	private Theme() {
	}

	/** A filled rectangle with its corner pixels left out, which reads as rounded. */
	public static void rounded(DrawContext context, int x, int y, int width, int height, int colour) {
		if (width <= 2 || height <= 2) {
			context.fill(x, y, x + width, y + height, colour);
			return;
		}

		context.fill(x + 1, y, x + width - 1, y + height, colour);
		context.fill(x, y + 1, x + 1, y + height - 1, colour);
		context.fill(x + width - 1, y + 1, x + width, y + height - 1, colour);
	}

	/** A one-pixel outline around a rectangle. */
	public static void outline(DrawContext context, int x, int y, int width, int height, int colour) {
		context.fill(x, y, x + width, y + 1, colour);
		context.fill(x, y + height - 1, x + width, y + height, colour);
		context.fill(x, y + 1, x + 1, y + height - 1, colour);
		context.fill(x + width - 1, y + 1, x + width, y + height - 1, colour);
	}

	/** The card each setting row sits on. */
	public static void card(DrawContext context, int x, int y, int width, int height, boolean hovered) {
		rounded(context, x, y, width, height, hovered ? PANEL_RAISED : PANEL);

		if (hovered) {
			rounded(context, x, y, width, height, HOVER);
			context.fill(x, y + 1, x + 2, y + height - 1, ACCENT);
		}
	}

	/**
	 * The on/off switch.
	 *
	 * <p>Drawn rather than textured so it stays legible at any GUI scale.
	 */
	public static void toggle(DrawContext context, int x, int y, int width, int height,
			boolean on, boolean hovered) {
		rounded(context, x, y, width, height, on ? ON : OFF);

		if (hovered) {
			rounded(context, x, y, width, height, HOVER);
		}

		int knob = height - 4;
		int knobX = on ? x + width - knob - 2 : x + 2;
		rounded(context, knobX, y + 2, knob, knob, 0xFF14141A);
	}

	/** A slider: a track, a filled portion and a handle. */
	public static void slider(DrawContext context, int x, int y, int width, int height,
			double fraction, boolean hovered) {
		int mid = y + height / 2;
		context.fill(x, mid - 1, x + width, mid + 1, OFF);

		int filled = (int) Math.round(width * clamp01(fraction));
		context.fill(x, mid - 1, x + filled, mid + 1, ACCENT);

		int handleX = x + filled - 2;
		rounded(context, handleX, y + 1, 5, height - 2, hovered ? 0xFFFFFFFF : 0xFFE9E9EE);
	}

	/** The screen's title bar. */
	public static void header(DrawContext context, int x, int y, int width, int height) {
		context.fillGradient(x, y, x + width, y + height, HEADER_TOP, HEADER_BOTTOM);
		context.fill(x, y + height - 1, x + width, y + height, ACCENT);
	}

	public static double clamp01(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	public static boolean within(int mouseX, int mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}
}
