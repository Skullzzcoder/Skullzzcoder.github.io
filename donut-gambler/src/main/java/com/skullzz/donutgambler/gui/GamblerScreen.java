package com.skullzz.donutgambler.gui;

import java.util.ArrayList;
import java.util.List;

import com.skullzz.donutgambler.DonutGambler;
import com.skullzz.donutgambler.advisor.Advice;
import com.skullzz.donutgambler.gui.tabs.DashboardTab;
import com.skullzz.donutgambler.gui.tabs.DisplayTab;
import com.skullzz.donutgambler.gui.tabs.GamesTab;
import com.skullzz.donutgambler.gui.tabs.LogTab;
import com.skullzz.donutgambler.gui.tabs.OpponentsTab;
import com.skullzz.donutgambler.gui.tabs.RulesTab;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The main window: a tab strip, a clipped content area, and a row of footer buttons. */
public class GamblerScreen extends Screen {
	private static final int HEADER_HEIGHT = 56;
	private static final int FOOTER_HEIGHT = 36;
	private static final int MARGIN = 14;

	/** Remembered between opens so you come back to the tab you were on. */
	private static int lastTab;

	private final List<GamblerTab> tabs = List.of(
			new DashboardTab(), new GamesTab(), new OpponentsTab(),
			new LogTab(), new RulesTab(), new DisplayTab());
	private final List<int[]> tabBoxes = new ArrayList<>();

	private GamblerTab current;
	private int contentX;
	private int contentY;
	private int contentW;
	private int contentH;

	public GamblerScreen() {
		super(Component.literal(DonutGambler.NAME));
	}

	@Override
	protected void init() {
		contentX = MARGIN;
		contentY = HEADER_HEIGHT;
		contentW = Math.max(120, width - MARGIN * 2);
		contentH = Math.max(60, height - HEADER_HEIGHT - FOOTER_HEIGHT);

		layoutTabStrip();

		if (lastTab < 0 || lastTab >= tabs.size()) lastTab = 0;
		current = tabs.get(lastTab);
		current.init(this);

		add(Fields.button(width - MARGIN - 60, height - 28, 60, 20, "Done", this::onClose));
	}

	private void layoutTabStrip() {
		tabBoxes.clear();
		int x = MARGIN;

		for (GamblerTab tab : tabs) {
			int w = font.width(tab.title()) + 18;
			tabBoxes.add(new int[] {x, 30, w, 20});
			x += w + 3;
		}
	}

	public <T extends AbstractWidget> T add(T widget) {
		return addRenderableWidget(widget);
	}

	public void setTab(int index) {
		if (index < 0 || index >= tabs.size() || tabs.get(index) == current) return;

		current.removed(this);
		lastTab = index;
		rebuildWidgets();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);
		renderChrome(graphics, mouseX, mouseY);

		graphics.enableScissor(contentX + 1, contentY + 1, contentX + contentW - 1, contentY + contentH - 1);
		current.render(this, graphics, mouseX, mouseY, delta);
		graphics.disableScissor();
	}

	private void renderChrome(GuiGraphics graphics, int mouseX, int mouseY) {
		graphics.fill(0, 0, width, HEADER_HEIGHT - 4, Theme.PANEL);
		graphics.fill(0, HEADER_HEIGHT - 5, width, HEADER_HEIGHT - 4, Theme.BORDER);

		graphics.drawString(font, DonutGambler.NAME, MARGIN, 12, Theme.ACCENT);

		Advice advice = DonutGambler.advice();
		String badge = "[" + advice.verdict.label + "] " + Theme.fit(font, advice.headline, Math.max(80, width - 240));
		graphics.drawString(font, badge, width - MARGIN - font.width(badge), 12, Theme.opaque(advice.verdict.color));

		for (int i = 0; i < tabs.size(); i++) {
			int[] box = tabBoxes.get(i);
			boolean active = tabs.get(i) == current;
			boolean hover = inBox(mouseX, mouseY, box);

			graphics.fill(box[0], box[1], box[0] + box[2], box[1] + box[3],
					active ? Theme.PANEL_RAISED : (hover ? Theme.ROW_HOVER : 0x30000000));

			if (active) {
				graphics.fill(box[0], box[1] + box[3] - 2, box[0] + box[2], box[1] + box[3], Theme.ACCENT);
			}

			graphics.drawString(font, tabs.get(i).title(), box[0] + 9, box[1] + 6,
					active ? Theme.TEXT : Theme.MUTED);
		}

		Theme.panel(graphics, contentX, contentY, contentW, contentH);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) return true;

		for (int i = 0; i < tabBoxes.size(); i++) {
			if (inBox((int) mouseX, (int) mouseY, tabBoxes.get(i))) {
				setTab(i);
				return true;
			}
		}

		if (inContent(mouseX, mouseY)) {
			return current.mouseClicked(this, mouseX, mouseY, button);
		}

		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		if (current.mouseScrolled(this, mouseX, mouseY, amountX, amountY)) return true;
		return super.mouseScrolled(mouseX, mouseY, amountX, amountY);
	}

	@Override
	public void onClose() {
		current.removed(this);
		DonutGambler.saveAll();
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static boolean inBox(int x, int y, int[] box) {
		return x >= box[0] && x <= box[0] + box[2] && y >= box[1] && y <= box[1] + box[3];
	}

	public boolean inContent(double x, double y) {
		return x >= contentX && x <= contentX + contentW && y >= contentY && y <= contentY + contentH;
	}

	public int contentX() {
		return contentX;
	}

	public int contentY() {
		return contentY;
	}

	public int contentWidth() {
		return contentW;
	}

	public int contentHeight() {
		return contentH;
	}

	public int footerY() {
		return height - 28;
	}

	public int marginX() {
		return MARGIN;
	}

	public Font font() {
		return font;
	}
}
