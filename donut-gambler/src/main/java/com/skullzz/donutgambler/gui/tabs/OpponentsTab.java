package com.skullzz.donutgambler.gui.tabs;

import java.util.List;
import java.util.Locale;

import com.skullzz.donutgambler.ClientChat;
import com.skullzz.donutgambler.DonutGambler;
import com.skullzz.donutgambler.advisor.Advisor;
import com.skullzz.donutgambler.advisor.OpponentFlag;
import com.skullzz.donutgambler.chat.MoneyParser;
import com.skullzz.donutgambler.data.Agg;
import com.skullzz.donutgambler.gui.Fields;
import com.skullzz.donutgambler.gui.GamblerScreen;
import com.skullzz.donutgambler.gui.ScrollTab;
import com.skullzz.donutgambler.gui.Theme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Who you gamble against and how that has gone. The p-value column answers a single question:
 * if this player were beating you on a fair coin, how often would their run look this good?
 */
public class OpponentsTab extends ScrollTab {
	private static final int ROW_HEIGHT = 20;
	private static final int HEADER_OFFSET = 40;

	private enum Sort {
		SUSPICION("suspicion"),
		MONEY("money lost"),
		VOLUME("bets");

		final String label;

		Sort(String label) {
			this.label = label;
		}
	}

	private Sort sort = Sort.SUSPICION;

	@Override
	public String title() {
		return "Opponents";
	}

	@Override
	public void init(GamblerScreen screen) {
		screen.add(Fields.cycle(screen.marginX(), screen.footerY(), 150, 20, "Sort by",
				() -> sort.label, () -> sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length]));

		screen.add(Fields.button(screen.marginX() + 154, screen.footerY(), 130, 20, "Copy flagged list", () -> {
			StringBuilder sb = new StringBuilder("Flagged opponents\n");

			for (OpponentFlag flag : Advisor.opponentFlags(DonutGambler.config(), DonutGambler.log())) {
				if (!flag.suspicious()) continue;
				sb.append(String.format(Locale.ROOT, "%s: beat me %d/%d (p=%.4f), took %s%n",
						flag.name(), flag.agg().losses, flag.agg().wins + flag.agg().losses,
						flag.pValue(), MoneyParser.format(-flag.agg().net)));
			}

			Minecraft.getInstance().keyboardHandler.setClipboard(sb.toString());
			ClientChat.info("Flagged opponents copied to clipboard.");
		}));
	}

	@Override
	public void render(GamblerScreen screen, GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		Font font = screen.font();
		int x = screen.contentX();
		int w = screen.contentWidth();
		int[] cols = columns(x, w);

		List<OpponentFlag> flags = sorted();

		graphics.drawString(font, "Only bets that captured an opponent name appear here.",
				x + 10, screen.contentY() + 10, Theme.DIM);

		int headerY = screen.contentY() + 24;
		graphics.drawString(font, "PLAYER", cols[0], headerY, Theme.MUTED);
		graphics.drawString(font, "BETS", cols[1], headerY, Theme.MUTED);
		graphics.drawString(font, "YOU W-L", cols[2], headerY, Theme.MUTED);
		graphics.drawString(font, "THEIR WIN%", cols[3], headerY, Theme.MUTED);
		graphics.drawString(font, "NET", cols[4], headerY, Theme.MUTED);
		graphics.drawString(font, "P-VALUE", cols[5], headerY, Theme.MUTED);

		for (int i = 0; i < flags.size(); i++) {
			OpponentFlag flag = flags.get(i);
			Agg agg = flag.agg();
			int y = rowY(screen, i, ROW_HEIGHT, HEADER_OFFSET);

			if (y + ROW_HEIGHT < screen.contentY() || y > screen.contentY() + screen.contentHeight()) continue;

			if (flag.suspicious()) {
				graphics.fill(x + 2, y, x + w - 6, y + ROW_HEIGHT - 2, 0x30E5544B);
			} else if (i % 2 == 1) {
				graphics.fill(x + 2, y, x + w - 6, y + ROW_HEIGHT - 2, Theme.ROW_ALT);
			}

			int textY = y + 5;
			graphics.drawString(font, Theme.fit(font, flag.name(), cols[1] - cols[0] - 6), cols[0], textY,
					flag.suspicious() ? Theme.RED : Theme.TEXT);
			graphics.drawString(font, String.valueOf(agg.bets), cols[1], textY, Theme.MUTED);
			graphics.drawString(font, agg.wins + "-" + agg.losses, cols[2], textY, Theme.TEXT);
			graphics.drawString(font, MoneyParser.percent(flag.theirWinRate()), cols[3], textY,
					flag.theirWinRate() > 0.5 ? Theme.YELLOW : Theme.GREEN);
			graphics.drawString(font, MoneyParser.formatSigned(agg.net), cols[4], textY, Theme.money(agg.net));
			graphics.drawString(font, String.format(Locale.ROOT, "%.4f", flag.pValue()), cols[5], textY,
					flag.suspicious() ? Theme.RED : Theme.DIM);
		}

		if (flags.isEmpty()) {
			graphics.drawString(font, "No opponents logged yet.", x + 10,
					screen.contentY() + HEADER_OFFSET + 6, Theme.DIM);
		}

		contentHeight = HEADER_OFFSET + flags.size() * ROW_HEIGHT + 10;
		clampScroll(screen);
		drawScrollbar(screen, graphics);
	}

	private List<OpponentFlag> sorted() {
		List<OpponentFlag> flags = Advisor.opponentFlags(DonutGambler.config(), DonutGambler.log());

		switch (sort) {
		case MONEY -> flags.sort((a, b) -> Double.compare(a.agg().net, b.agg().net));
		case VOLUME -> flags.sort((a, b) -> Integer.compare(b.agg().bets, a.agg().bets));
		default -> {
			// Advisor already returns them most-suspicious first.
		}
		}

		return flags;
	}

	private static int[] columns(int x, int w) {
		return new int[] {
				x + 10,
				x + (int) (w * 0.28),
				x + (int) (w * 0.38),
				x + (int) (w * 0.52),
				x + (int) (w * 0.68),
				x + (int) (w * 0.85),
		};
	}
}
