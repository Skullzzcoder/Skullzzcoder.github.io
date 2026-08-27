package com.skullzz.donutgambler.gui.tabs;

import java.util.ArrayList;
import java.util.List;

import com.skullzz.donutgambler.gui.GamblerScreen;
import com.skullzz.donutgambler.gui.GamblerTab;
import com.skullzz.donutgambler.gui.Theme;

import net.minecraft.client.gui.GuiGraphics;

/** Shared two-column layout for the settings pages: a label on the left, a control on the right. */
public abstract class SettingsTabBase implements GamblerTab {
	protected static final int ROW_HEIGHT = 22;
	protected static final int CONTROL_WIDTH = 76;

	protected record Label(int x, int y, String text, boolean muted) {
	}

	protected final List<Label> labels = new ArrayList<>();

	/** X of the label column, for column 0 or 1. */
	protected int labelX(GamblerScreen screen, int column) {
		return screen.contentX() + 12 + column * columnWidth(screen);
	}

	/** X of the control in the given column. */
	protected int controlX(GamblerScreen screen, int column) {
		return labelX(screen, column) + columnWidth(screen) - CONTROL_WIDTH - 24;
	}

	protected int columnWidth(GamblerScreen screen) {
		return (screen.contentWidth() - 16) / 2;
	}

	protected int rowY(GamblerScreen screen, int row) {
		return screen.contentY() + 14 + row * ROW_HEIGHT;
	}

	protected void label(GamblerScreen screen, int column, int row, String text) {
		labels.add(new Label(labelX(screen, column), rowY(screen, row) + 6, text, false));
	}

	protected void note(GamblerScreen screen, int column, int row, String text) {
		labels.add(new Label(labelX(screen, column), rowY(screen, row) + 6, text, true));
	}

	@Override
	public void render(GamblerScreen screen, GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		for (Label label : labels) {
			graphics.drawString(screen.font(), Theme.fit(screen.font(), label.text(),
					columnWidth(screen) - CONTROL_WIDTH - 34), label.x(), label.y(),
					label.muted() ? Theme.DIM : Theme.TEXT);
		}
	}
}
