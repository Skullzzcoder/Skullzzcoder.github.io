package com.skullzz.donutgambler.gui.tabs;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.skullzz.donutgambler.ClientChat;
import com.skullzz.donutgambler.DonutGambler;
import com.skullzz.donutgambler.chat.MoneyParser;
import com.skullzz.donutgambler.data.BetRecord;
import com.skullzz.donutgambler.data.Outcome;
import com.skullzz.donutgambler.gui.Fields;
import com.skullzz.donutgambler.gui.GamblerScreen;
import com.skullzz.donutgambler.gui.ScrollTab;
import com.skullzz.donutgambler.gui.Theme;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Every logged bet, newest first, with undo for the inevitable mis-parse. */
public class LogTab extends ScrollTab {
	private static final int ROW_HEIGHT = 18;
	private static final int HEADER_OFFSET = 26;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());

	private boolean confirmingClear;

	@Override
	public String title() {
		return "Log";
	}

	@Override
	public void init(GamblerScreen screen) {
		confirmingClear = false;

		screen.add(Fields.button(screen.marginX(), screen.footerY(), 90, 20, "Undo last", () -> {
			BetRecord removed = DonutGambler.log().removeLast();

			if (removed == null) {
				ClientChat.info("Nothing to undo.");
				return;
			}

			if (DonutGambler.config().bankroll > 0) {
				DonutGambler.config().bankroll = Math.max(0, DonutGambler.config().bankroll - removed.net);
				DonutGambler.markConfigDirty();
			}

			DonutGambler.invalidateAdvice();
			ClientChat.info("Removed " + removed.gameName + " " + MoneyParser.formatSigned(removed.net) + ".");
		}));

		Button[] clear = new Button[1];
		clear[0] = Fields.button(screen.marginX() + 94, screen.footerY(), 130, 20, "Clear history", () -> {
			if (!confirmingClear) {
				confirmingClear = true;
				clear[0].setMessage(Component.literal("Click again to confirm"));
				return;
			}

			DonutGambler.log().clear();
			DonutGambler.invalidateAdvice();
			confirmingClear = false;
			clear[0].setMessage(Component.literal("Clear history"));
			ClientChat.info("History cleared.");
		});
		screen.add(clear[0]);
	}

	@Override
	public void render(GamblerScreen screen, GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		Font font = screen.font();
		int x = screen.contentX();
		int w = screen.contentWidth();
		int[] cols = columns(x, w);

		List<BetRecord> records = DonutGambler.log().recent(2000);

		int headerY = screen.contentY() + 10;
		graphics.drawString(font, "WHEN", cols[0], headerY, Theme.MUTED);
		graphics.drawString(font, "GAME", cols[1], headerY, Theme.MUTED);
		graphics.drawString(font, "OPPONENT", cols[2], headerY, Theme.MUTED);
		graphics.drawString(font, "STAKE", cols[3], headerY, Theme.MUTED);
		graphics.drawString(font, "RESULT", cols[4], headerY, Theme.MUTED);

		for (int i = 0; i < records.size(); i++) {
			BetRecord record = records.get(i);
			int y = rowY(screen, i, ROW_HEIGHT, HEADER_OFFSET);

			if (y + ROW_HEIGHT < screen.contentY() || y > screen.contentY() + screen.contentHeight()) continue;

			if (i % 2 == 1) {
				graphics.fill(x + 2, y, x + w - 6, y + ROW_HEIGHT - 2, Theme.ROW_ALT);
			}

			int textY = y + 5;
			graphics.drawString(font, TIME.format(Instant.ofEpochMilli(record.time)), cols[0], textY, Theme.DIM);
			graphics.drawString(font, Theme.fit(font, record.gameName, cols[2] - cols[1] - 6), cols[1], textY, Theme.TEXT);
			graphics.drawString(font, Theme.fit(font, record.hasOpponent() ? record.opponent : "-",
					cols[3] - cols[2] - 6), cols[2], textY, Theme.MUTED);
			graphics.drawString(font, MoneyParser.format(record.stake), cols[3], textY, Theme.MUTED);
			graphics.drawString(font, (record.outcome == Outcome.WIN ? "WIN  " : record.outcome == Outcome.LOSS
					? "LOSS " : "PUSH ") + MoneyParser.formatSigned(record.net), cols[4], textY,
					Theme.money(record.net));

			if ("manual".equals(record.source)) {
				graphics.drawString(font, "m", x + w - 14, textY, Theme.DIM);
			}
		}

		if (records.isEmpty()) {
			graphics.drawString(font, "Nothing logged yet.", x + 10, screen.contentY() + HEADER_OFFSET + 6, Theme.DIM);
		}

		contentHeight = HEADER_OFFSET + records.size() * ROW_HEIGHT + 10;
		clampScroll(screen);
		drawScrollbar(screen, graphics);
	}

	private static int[] columns(int x, int w) {
		return new int[] {
				x + 10,
				x + (int) (w * 0.22),
				x + (int) (w * 0.40),
				x + (int) (w * 0.58),
				x + (int) (w * 0.72),
		};
	}
}
