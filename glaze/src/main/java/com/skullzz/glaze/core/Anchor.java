package com.skullzz.glaze.core;

/**
 * Which screen corner a HUD element measures its offset from.
 *
 * <p>Anchoring rather than storing absolute coordinates keeps a layout correct
 * when the window is resized or the GUI scale changes - a readout pinned to the
 * bottom right stays there instead of drifting into the middle of the screen.
 */
public enum Anchor {
	TOP_LEFT(0.0, 0.0),
	TOP_CENTER(0.5, 0.0),
	TOP_RIGHT(1.0, 0.0),
	BOTTOM_LEFT(0.0, 1.0),
	BOTTOM_CENTER(0.5, 1.0),
	BOTTOM_RIGHT(1.0, 1.0);

	private final double fx;
	private final double fy;

	Anchor(double fx, double fy) {
		this.fx = fx;
		this.fy = fy;
	}

	/**
	 * Resolves the on-screen left edge for an element.
	 *
	 * @param offsetX   the configured offset, measured inward from the anchored edge
	 * @param width     the element's width
	 * @param screenWidth the scaled screen width
	 */
	public int resolveX(int offsetX, int width, int screenWidth) {
		int base = (int) Math.round(screenWidth * fx - width * fx);
		return clamp(base + (fx >= 1.0 ? -offsetX : offsetX), width, screenWidth);
	}

	/** Resolves the on-screen top edge for an element. */
	public int resolveY(int offsetY, int height, int screenHeight) {
		int base = (int) Math.round(screenHeight * fy - height * fy);
		return clamp(base + (fy >= 1.0 ? -offsetY : offsetY), height, screenHeight);
	}

	/** Keeps an element fully on screen even after a resize shrinks the window. */
	private static int clamp(int value, int size, int limit) {
		if (size >= limit) {
			return 0;
		}

		return Math.max(0, Math.min(value, limit - size));
	}

	/** The anchor nearest to a point, used when a dragged element is dropped. */
	public static Anchor nearest(int x, int y, int screenWidth, int screenHeight) {
		boolean bottom = y > screenHeight / 2;
		double third = screenWidth / 3.0;

		if (x < third) {
			return bottom ? BOTTOM_LEFT : TOP_LEFT;
		}

		if (x > 2 * third) {
			return bottom ? BOTTOM_RIGHT : TOP_RIGHT;
		}

		return bottom ? BOTTOM_CENTER : TOP_CENTER;
	}

	/** Converts an absolute position back into an offset from this anchor. */
	public int toOffsetX(int absoluteX, int width, int screenWidth) {
		int base = (int) Math.round(screenWidth * fx - width * fx);
		int delta = absoluteX - base;
		return Math.max(0, fx >= 1.0 ? -delta : delta);
	}

	/** Converts an absolute position back into an offset from this anchor. */
	public int toOffsetY(int absoluteY, int height, int screenHeight) {
		int base = (int) Math.round(screenHeight * fy - height * fy);
		int delta = absoluteY - base;
		return Math.max(0, fy >= 1.0 ? -delta : delta);
	}
}
