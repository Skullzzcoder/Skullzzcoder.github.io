package com.skullzz.donutgambler.gui;

import com.skullzz.donutgambler.advisor.MathUtil;

import net.minecraft.client.gui.GuiGraphics;

/** A tab whose content can be taller than the window. Handles the wheel and draws the bar. */
public abstract class ScrollTab implements GamblerTab {
	protected double scroll;
	/** Total height of the content, set by the tab while rendering or laying out. */
	protected int contentHeight;

	@Override
	public boolean mouseScrolled(GamblerScreen screen, double mouseX, double mouseY, double amountX, double amountY) {
		if (!screen.inContent(mouseX, mouseY)) return false;

		scroll = MathUtil.clamp(scroll - amountY * 16, 0, maxScroll(screen));
		return true;
	}

	protected double maxScroll(GamblerScreen screen) {
		return Math.max(0, contentHeight - screen.contentHeight());
	}

	/** Y position of a row once scrolling is applied. */
	protected int rowY(GamblerScreen screen, int index, int rowHeight, int topPadding) {
		return screen.contentY() + topPadding + index * rowHeight - (int) scroll;
	}

	protected void clampScroll(GamblerScreen screen) {
		scroll = MathUtil.clamp(scroll, 0, maxScroll(screen));
	}

	protected void drawScrollbar(GamblerScreen screen, GuiGraphics graphics) {
		double max = maxScroll(screen);
		if (max <= 0) return;

		int trackX = screen.contentX() + screen.contentWidth() - 4;
		int trackY = screen.contentY() + 2;
		int trackH = screen.contentHeight() - 4;

		graphics.fill(trackX, trackY, trackX + 3, trackY + trackH, 0x30FFFFFF);

		int thumbH = Math.max(16, (int) (trackH * (screen.contentHeight() / (double) contentHeight)));
		int thumbY = trackY + (int) ((trackH - thumbH) * (scroll / max));
		graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, Theme.ACCENT);
	}
}
