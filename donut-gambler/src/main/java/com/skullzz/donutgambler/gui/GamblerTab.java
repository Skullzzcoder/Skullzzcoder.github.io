package com.skullzz.donutgambler.gui;

import net.minecraft.client.gui.GuiGraphics;

/** One page of the main screen. Widgets are rebuilt by the screen on every tab switch. */
public interface GamblerTab {
	String title();

	/** Add footer buttons and text fields here via {@link GamblerScreen#add}. */
	default void init(GamblerScreen screen) {
	}

	/** Draw the page. The screen has already clipped drawing to its content rectangle. */
	void render(GamblerScreen screen, GuiGraphics graphics, int mouseX, int mouseY, float delta);

	default boolean mouseClicked(GamblerScreen screen, double mouseX, double mouseY, int button) {
		return false;
	}

	default boolean mouseScrolled(GamblerScreen screen, double mouseX, double mouseY, double amountX, double amountY) {
		return false;
	}

	/** Called before the tab is swapped out or the screen closes: commit pending edits here. */
	default void removed(GamblerScreen screen) {
	}
}
