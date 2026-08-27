package com.skullzz.donutgambler.gui.tabs;

import java.util.List;
import java.util.Locale;

import com.skullzz.donutgambler.ClientChat;
import com.skullzz.donutgambler.DonutGambler;
import com.skullzz.donutgambler.advisor.Advice;
import com.skullzz.donutgambler.chat.MoneyParser;
import com.skullzz.donutgambler.data.Agg;
import com.skullzz.donutgambler.gui.Fields;
import com.skullzz.donutgambler.gui.GamblerScreen;
import com.skullzz.donutgambler.gui.ScrollTab;
import com.skullzz.donutgambler.gui.Theme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Headline numbers, the profit curve, and the advisor's full reasoning. */
public class DashboardTab extends ScrollTab {
	private static final int CARD_HEIGHT = 52;
	private static final int CHART_HEIGHT = 76;

	@Override
	public String title() {
		return "Dashboard";
	}

	@Override
	public void init(GamblerScreen screen) {
		int y = screen.footerY();
		int x = screen.marginX();

		screen.add(Fields.button(x, y, 96, 20, "Reset session", () -> {
			DonutGambler.log().startNewSession();
			DonutGambler.config().sessionStartBankroll = DonutGambler.config().bankroll;
			DonutGambler.markConfigDirty();
			DonutGambler.invalidateAdvice();
		}));

		screen.add(Fields.button(x + 100, y, 84, 20, "Export CSV", DashboardTab::exportCsv));

		screen.add(Fields.button(x + 188, y, 96, 20, "Copy summary", () -> {
			Minecraft.getInstance().keyboardHandler.setClipboard(summary());
			ClientChat.info("Summary copied to clipboard.");
		}));
	}

	@Override
	public void render(GamblerScreen screen, GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		Font font = screen.font();
		int x = screen.contentX() + 10;
		int width = screen.contentWidth() - 24;
		int y = screen.contentY() + 10 - (int) scroll;

		Agg session = DonutGambler.log().aggSession();
		Agg all = DonutGambler.log().aggAll();
		Advice advice = DonutGambler.advice();

		int cardW = (width - 16) / 3;
		drawCard(graphics, font, x, y, cardW, "SESSION",
				MoneyParser.formatSigned(session.net), Theme.money(session.net),
				String.format(Locale.ROOT, "%d bets  %d-%d  %s", session.bets, session.wins, session.losses,
						session.bets == 0 ? "-" : MoneyParser.percent(session.winRate())),
				"wagered " + MoneyParser.format(session.wagered));

		drawCard(graphics, font, x + cardW + 8, y, cardW, "ALL TIME",
				MoneyParser.formatSigned(all.net), Theme.money(all.net),
				String.format(Locale.ROOT, "%d bets  %d-%d  %s", all.bets, all.wins, all.losses,
						all.bets == 0 ? "-" : MoneyParser.percent(all.winRate())),
				"ROI " + MoneyParser.signedPercent(all.roi()));

		drawCard(graphics, font, x + (cardW + 8) * 2, y, cardW, "VERDICT",
				advice.verdict.label, Theme.opaque(advice.verdict.color),
				"bankroll " + MoneyParser.format(DonutGambler.config().bankroll),
				advice.recommendedBet > 0
						? "max bet " + MoneyParser.format(advice.recommendedBet)
						: "no +EV bet size");

		y += CARD_HEIGHT + 10;
		drawChart(graphics, font, x, y, width, CHART_HEIGHT);
		y += CHART_HEIGHT + 12;

		graphics.drawString(font, "WHY", x, y, Theme.MUTED);
		y += 12;

		for (Advice.Line line : advice.lines) {
			List<String> wrapped = Theme.wrap(font, line.text(), width - 12, 3);

			for (String part : wrapped) {
				graphics.fill(x, y - 1, x + 2, y + 9, line.severity().color);
				graphics.drawString(font, part, x + 8, y, line.severity().color);
				y += 11;
			}

			y += 2;
		}

		contentHeight = y + (int) scroll - screen.contentY() + 8;
		clampScroll(screen);
		drawScrollbar(screen, graphics);
	}

	private void drawCard(GuiGraphics graphics, Font font, int x, int y, int w,
			String label, String value, int valueColor, String sub1, String sub2) {
		Theme.raisedPanel(graphics, x, y, w, CARD_HEIGHT);
		graphics.drawString(font, label, x + 8, y + 7, Theme.MUTED);
		graphics.drawString(font, Theme.fit(font, value, w - 16), x + 8, y + 20, valueColor);
		graphics.drawString(font, Theme.fit(font, sub1, w - 16), x + 8, y + 32, Theme.MUTED);
		graphics.drawString(font, Theme.fit(font, sub2, w - 16), x + 8, y + 42, Theme.DIM);
	}

	/** Area chart of cumulative profit: green above the break-even line, red below. */
	private void drawChart(GuiGraphics graphics, Font font, int x, int y, int w, int h) {
		Theme.raisedPanel(graphics, x, y, w, h);
		double[] points = DonutGambler.log().cumulative(200);

		graphics.drawString(font, "CUMULATIVE PROFIT (last " + Math.max(0, points.length - 1) + " bets)",
				x + 8, y + 6, Theme.MUTED);

		if (points.length < 2) {
			graphics.drawString(font, "No bets logged yet.", x + 8, y + h / 2, Theme.DIM);
			return;
		}

		double min = 0;
		double max = 0;

		for (double p : points) {
			min = Math.min(min, p);
			max = Math.max(max, p);
		}

		double span = Math.max(1e-9, max - min);
		int plotX = x + 8;
		int plotY = y + 18;
		int plotW = w - 16;
		int plotH = h - 26;
		int zeroY = plotY + (int) ((max - 0) / span * plotH);

		graphics.fill(plotX, zeroY, plotX + plotW, zeroY + 1, Theme.CHART_ZERO);

		for (int i = 1; i < points.length; i++) {
			int px = plotX + (int) ((i - 1) / (double) (points.length - 1) * (plotW - 1));
			int pw = Math.max(1, plotW / (points.length - 1));
			int py = plotY + (int) ((max - points[i]) / span * plotH);

			int top = Math.min(py, zeroY);
			int bottom = Math.max(py, zeroY);
			graphics.fill(px, top, px + pw, bottom + 1, points[i] >= 0 ? 0x804CE07A : 0x80E5544B);
		}

		int lastY = plotY + (int) ((max - points[points.length - 1]) / span * plotH);
		graphics.fill(plotX, lastY, plotX + plotW, lastY + 1,
				points[points.length - 1] >= 0 ? Theme.GREEN : Theme.RED);

		String maxLabel = MoneyParser.formatSigned(max);
		String minLabel = MoneyParser.formatSigned(min);
		graphics.drawString(font, maxLabel, x + w - 8 - font.width(maxLabel), plotY - 1, Theme.DIM);
		graphics.drawString(font, minLabel, x + w - 8 - font.width(minLabel), plotY + plotH - 8, Theme.DIM);
	}

	private static void exportCsv() {
		try {
			java.nio.file.Path out = DonutGambler.dataDir()
					.resolve("export-" + System.currentTimeMillis() + ".csv");
			java.nio.file.Files.createDirectories(out.getParent());
			java.nio.file.Files.writeString(out, DonutGambler.log().toCsv());
			ClientChat.good("Exported " + DonutGambler.log().size() + " bets to " + out.getFileName());
		} catch (Exception e) {
			ClientChat.bad("Export failed: " + e.getMessage());
		}
	}

	/** Plain-text session/all-time summary, for pasting into Discord. */
	public static String summary() {
		Agg session = DonutGambler.log().aggSession();
		Agg all = DonutGambler.log().aggAll();
		Advice advice = DonutGambler.advice();

		StringBuilder sb = new StringBuilder();
		sb.append("Donut Gambler summary\n");
		sb.append(String.format(Locale.ROOT, "Session: %s over %d bets (%d-%d, %s)%n",
				MoneyParser.formatSigned(session.net), session.bets, session.wins, session.losses,
				session.bets == 0 ? "-" : MoneyParser.percent(session.winRate())));
		sb.append(String.format(Locale.ROOT, "All time: %s over %d bets (%d-%d, ROI %s)%n",
				MoneyParser.formatSigned(all.net), all.bets, all.wins, all.losses,
				MoneyParser.signedPercent(all.roi())));
		sb.append("Verdict: ").append(advice.verdict.label).append(" - ").append(advice.headline).append('\n');

		DonutGambler.log().byGame().forEach((id, agg) -> sb.append(String.format(Locale.ROOT,
				"  %s: %s over %d bets (%d-%d)%n",
				agg.label, MoneyParser.formatSigned(agg.net), agg.bets, agg.wins, agg.losses)));

		return sb.toString();
	}
}
