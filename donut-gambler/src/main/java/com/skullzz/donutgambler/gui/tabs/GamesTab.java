package com.skullzz.donutgambler.gui.tabs;

import java.util.List;
import java.util.Locale;

import com.skullzz.donutgambler.DonutGambler;
import com.skullzz.donutgambler.advisor.Advisor;
import com.skullzz.donutgambler.chat.MoneyParser;
import com.skullzz.donutgambler.config.GameDef;
import com.skullzz.donutgambler.data.Agg;
import com.skullzz.donutgambler.gui.Fields;
import com.skullzz.donutgambler.gui.GamblerScreen;
import com.skullzz.donutgambler.gui.GameEditScreen;
import com.skullzz.donutgambler.gui.ScrollTab;
import com.skullzz.donutgambler.gui.Theme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** The list of games: what each one pays, what it costs you, and where to edit it. */
public class GamesTab extends ScrollTab {
	private static final int ROW_HEIGHT = 22;
	private static final int HEADER_OFFSET = 26;

	@Override
	public String title() {
		return "Games";
	}

	@Override
	public void init(GamblerScreen screen) {
		screen.add(Fields.button(screen.marginX(), screen.footerY(), 90, 20, "Add game", () -> {
			GameDef game = new GameDef(DonutGambler.config().uniqueId("new_game"), "New game");
			game.enabled = false;
			DonutGambler.config().games.add(game);
			DonutGambler.markConfigDirty();
			Minecraft.getInstance().setScreen(new GameEditScreen(screen, game));
		}));

		screen.add(Fields.button(screen.marginX() + 94, screen.footerY(), 150, 20,
				"Chat log / pattern builder", () ->
				Minecraft.getInstance().setScreen(new com.skullzz.donutgambler.gui.PatternBuilderScreen(screen, null))));
	}

	@Override
	public void render(GamblerScreen screen, GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		Font font = screen.font();
		List<GameDef> games = DonutGambler.config().games;
		int x = screen.contentX();
		int w = screen.contentWidth();

		int[] cols = columns(x, w);
		int headerY = screen.contentY() + 10;
		graphics.drawString(font, "ON", cols[0], headerY, Theme.MUTED);
		graphics.drawString(font, "GAME", cols[1], headerY, Theme.MUTED);
		graphics.drawString(font, "EDGE", cols[2], headerY, Theme.MUTED);
		graphics.drawString(font, "BET", cols[3], headerY, Theme.MUTED);
		graphics.drawString(font, "MEASURED", cols[4], headerY, Theme.MUTED);

		for (int i = 0; i < games.size(); i++) {
			GameDef game = games.get(i);
			int y = rowY(screen, i, ROW_HEIGHT, HEADER_OFFSET);

			if (y + ROW_HEIGHT < screen.contentY() || y > screen.contentY() + screen.contentHeight()) continue;

			boolean hover = mouseY >= y && mouseY < y + ROW_HEIGHT && screen.inContent(mouseX, mouseY);

			if (hover) {
				graphics.fill(x + 2, y, x + w - 6, y + ROW_HEIGHT - 2, Theme.ROW_HOVER);
			} else if (i % 2 == 1) {
				graphics.fill(x + 2, y, x + w - 6, y + ROW_HEIGHT - 2, Theme.ROW_ALT);
			}

			int textY = y + 6;
			graphics.drawString(font, game.enabled ? "ON" : "off", cols[0], textY,
					game.enabled ? Theme.GREEN : Theme.DIM);

			String problem = game.problem();
			graphics.drawString(font, Theme.fit(font, game.name, cols[2] - cols[1] - 6), cols[1], textY,
					problem == null ? Theme.TEXT : Theme.YELLOW);

			double edge = game.edgePercent();
			graphics.drawString(font, String.format(Locale.ROOT, "%+.2f%%", edge), cols[2], textY,
					edge < 0 ? Theme.RED : edge > 0 ? Theme.GREEN : Theme.MUTED);

			double bet = Advisor.recommendedBet(DonutGambler.config(), game);
			graphics.drawString(font, bet > 0 ? MoneyParser.format(bet) : "-", cols[3], textY,
					bet > 0 ? Theme.TEXT : Theme.DIM);

			Agg measured = DonutGambler.log().byGame().get(game.id);
			String record = measured == null ? "no data"
					: String.format(Locale.ROOT, "%d-%d  %s  %s", measured.wins, measured.losses,
							MoneyParser.percent(measured.winRate()), MoneyParser.formatSigned(measured.net));
			graphics.drawString(font, Theme.fit(font, record, cols[5] - cols[4] - 8), cols[4], textY,
					measured == null ? Theme.DIM : Theme.money(measured.net));

			graphics.drawString(font, "edit", cols[5], textY, Theme.ACCENT);
			graphics.drawString(font, "x", cols[6], textY, Theme.RED);

			if (problem != null) {
				graphics.drawString(font, Theme.fit(font, problem, w - 40), cols[1], y + 14, Theme.DIM);
			}
		}

		if (games.isEmpty()) {
			graphics.drawString(font, "No games yet. Add one, then build its patterns from a real chat line.",
					x + 10, screen.contentY() + HEADER_OFFSET + 6, Theme.DIM);
		}

		contentHeight = HEADER_OFFSET + games.size() * ROW_HEIGHT + 10;
		clampScroll(screen);
		drawScrollbar(screen, graphics);
	}

	@Override
	public boolean mouseClicked(GamblerScreen screen, double mouseX, double mouseY, int button) {
		List<GameDef> games = DonutGambler.config().games;
		int index = (int) ((mouseY - screen.contentY() - HEADER_OFFSET + scroll) / ROW_HEIGHT);

		if (index < 0 || index >= games.size()) return false;
		if (mouseY < screen.contentY() + HEADER_OFFSET) return false;

		GameDef game = games.get(index);
		int[] cols = columns(screen.contentX(), screen.contentWidth());

		if (mouseX >= cols[6] - 4) {
			games.remove(index);
			DonutGambler.markConfigDirty();
			return true;
		}

		if (mouseX >= cols[5] - 4) {
			Minecraft.getInstance().setScreen(new GameEditScreen(screen, game));
			return true;
		}

		if (mouseX < cols[1] - 4) {
			game.enabled = !game.enabled;
			DonutGambler.markConfigDirty();
			return true;
		}

		Minecraft.getInstance().setScreen(new GameEditScreen(screen, game));
		return true;
	}

	/** Column x positions: on, name, edge, bet, measured, edit, delete. */
	private static int[] columns(int x, int w) {
		return new int[] {
				x + 10,
				x + 34,
				x + (int) (w * 0.36),
				x + (int) (w * 0.48),
				x + (int) (w * 0.60),
				x + w - 42,
				x + w - 16,
		};
	}
}
