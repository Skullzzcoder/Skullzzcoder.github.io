package com.skullzz.donutgambler.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.skullzz.donutgambler.DonutGambler;
import com.skullzz.donutgambler.advisor.Advice;
import com.skullzz.donutgambler.chat.MoneyParser;
import com.skullzz.donutgambler.config.GamblerConfig;
import com.skullzz.donutgambler.config.HudAnchor;
import com.skullzz.donutgambler.data.Agg;
import com.skullzz.donutgambler.gui.Theme;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** The in-game overlay: session P/L, record, streak, bet size, and the current verdict. */
public final class GamblerHud {
	private static final int LINE_HEIGHT = 10;
	private static final int PADDING = 4;
	private static final int MAX_TEXT_WIDTH = 190;

	private record Row(String text, int color) {
	}

	private GamblerHud() {
	}

	public static void render(GuiGraphics context, DeltaTracker tickCounter) {
		Minecraft client = Minecraft.getInstance();
		GamblerConfig config = DonutGambler.config();

		if (!config.hudEnabled || client.player == null || client.options.hideGui) return;

		Font font = client.font;
		List<Row> rows = buildRows(font, config);
		if (rows.isEmpty()) return;

		int width = 0;

		for (Row row : rows) {
			width = Math.max(width, font.width(row.text()));
		}

		width += PADDING * 2;
		int height = rows.size() * LINE_HEIGHT + PADDING * 2 - 2;

		float scale = (float) Math.max(0.5, Math.min(3.0, config.hudScale));
		HudAnchor anchor = config.hudAnchor == null ? HudAnchor.TOP_LEFT : config.hudAnchor;

		float x = anchor.isRight() ? context.guiWidth() - width * scale - config.hudX : config.hudX;
		float y = anchor.isBottom() ? context.guiHeight() - height * scale - config.hudY : config.hudY;

		context.pose().pushMatrix();
		context.pose().translate(x, y);
		context.pose().scale(scale, scale);

		if (config.hudBackgroundAlpha > 0) {
			context.fill(0, 0, width, height, Theme.withAlpha(0x101216, config.hudBackgroundAlpha));
			Theme.border(context, 0, 0, width, height, Theme.withAlpha(0x2C313A, config.hudBackgroundAlpha));
		}

		int lineY = PADDING;

		for (Row row : rows) {
			context.drawString(font, row.text(), PADDING, lineY, row.color());
			lineY += LINE_HEIGHT;
		}

		context.pose().popMatrix();
	}

	private static List<Row> buildRows(Font font, GamblerConfig config) {
		List<Row> rows = new ArrayList<>();
		Agg session = DonutGambler.log().aggSession();
		Advice advice = DonutGambler.advice();

		rows.add(new Row(DonutGambler.NAME + "  [" + advice.verdict.label + "]",
				Theme.opaque(advice.verdict.color)));

		if (config.hudShowSession) {
			rows.add(new Row(String.format(Locale.ROOT, "Session %s   %d-%d   %s",
					MoneyParser.formatSigned(session.net), session.wins, session.losses,
					session.bets == 0 ? "-" : MoneyParser.percent(session.winRate())),
					Theme.money(session.net)));
		}

		if (config.hudShowAllTime) {
			Agg all = DonutGambler.log().aggAll();
			rows.add(new Row(String.format(Locale.ROOT, "All time %s   %d-%d",
					MoneyParser.formatSigned(all.net), all.wins, all.losses), Theme.money(all.net)));
		}

		if (config.hudShowStreak && session.bets > 0) {
			String streak = session.streak == 0 ? "-"
					: (session.streak > 0 ? session.streak + "W" : Math.abs(session.streak) + "L");
			rows.add(new Row("Streak " + streak + "   avg bet " + MoneyParser.format(session.avgStake()),
					session.streak < 0 ? Theme.RED : Theme.MUTED));
		}

		if (config.hudShowRecommendedBet) {
			String bet = advice.recommendedBet > 0
					? "Max bet " + MoneyParser.format(advice.recommendedBet)
					: "Max bet: none (-EV)";
			rows.add(new Row(bet + (advice.focusGameName.isEmpty() ? "" : "  " + advice.focusGameName),
					advice.recommendedBet > 0 ? Theme.GREEN : Theme.YELLOW));
		}

		if (config.hudShowAdvice && advice.verdict != Advice.Verdict.GREEN) {
			for (String line : Theme.wrap(font, advice.primaryReason(), MAX_TEXT_WIDTH, 2)) {
				rows.add(new Row(line, Theme.opaque(advice.verdict.color)));
			}
		}

		return rows;
	}
}
