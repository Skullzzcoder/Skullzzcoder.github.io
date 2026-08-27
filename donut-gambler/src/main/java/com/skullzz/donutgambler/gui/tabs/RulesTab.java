package com.skullzz.donutgambler.gui.tabs;

import com.skullzz.donutgambler.DonutGambler;
import com.skullzz.donutgambler.config.GamblerConfig;
import com.skullzz.donutgambler.gui.Fields;
import com.skullzz.donutgambler.gui.GamblerScreen;

/** Bankroll, limits, tilt thresholds, and what the chat parser is allowed to touch. */
public class RulesTab extends SettingsTabBase {
	@Override
	public String title() {
		return "Rules";
	}

	@Override
	public void init(GamblerScreen screen) {
		labels.clear();
		GamblerConfig c = DonutGambler.config();

		// ── left column: money ──
		label(screen, 0, 0, "Bankroll");
		screen.add(Fields.number(screen.font(), controlX(screen, 0), rowY(screen, 0), CONTROL_WIDTH, 18,
				"Bankroll", c.bankroll, 0, 1e15, v -> c.bankroll = v));

		label(screen, 0, 1, "Kelly fraction (%)");
		screen.add(Fields.number(screen.font(), controlX(screen, 0), rowY(screen, 1), CONTROL_WIDTH, 18,
				"Kelly", c.kellyFraction * 100, 1, 100, v -> c.kellyFraction = v / 100));

		label(screen, 0, 2, "Max bet (% of bankroll)");
		screen.add(Fields.number(screen.font(), controlX(screen, 0), rowY(screen, 2), CONTROL_WIDTH, 18,
				"Max bet", c.maxBetPercent, 0.01, 100, v -> c.maxBetPercent = v));

		label(screen, 0, 3, "Stop-loss (% of bankroll)");
		screen.add(Fields.number(screen.font(), controlX(screen, 0), rowY(screen, 3), CONTROL_WIDTH, 18,
				"Stop loss", c.stopLossPercent, 0, 100, v -> c.stopLossPercent = v));

		label(screen, 0, 4, "Stop-win (% of bankroll)");
		screen.add(Fields.number(screen.font(), controlX(screen, 0), rowY(screen, 4), CONTROL_WIDTH, 18,
				"Stop win", c.stopWinPercent, 0, 1000, v -> c.stopWinPercent = v));

		label(screen, 0, 5, "Session bet cap (0 = off)");
		screen.add(Fields.number(screen.font(), controlX(screen, 0), rowY(screen, 5), CONTROL_WIDTH, 18,
				"Bet cap", c.sessionBetCap, 0, 100000, v -> c.sessionBetCap = (int) v));

		label(screen, 0, 6, "History limit (bets kept)");
		screen.add(Fields.number(screen.font(), controlX(screen, 0), rowY(screen, 6), CONTROL_WIDTH, 18,
				"History", c.historyLimit, 100, 500000, v -> {
					c.historyLimit = (int) v;
					DonutGambler.log().setHistoryLimit(c.historyLimit);
				}));

		screen.add(Fields.toggle(labelX(screen, 0), rowY(screen, 7), CONTROL_WIDTH + 90, 18,
				"Read results from chat", () -> c.parseChat, v -> c.parseChat = v));

		screen.add(Fields.toggle(labelX(screen, 0), rowY(screen, 8), CONTROL_WIDTH + 90, 18,
				"Also parse player chat", () -> c.parsePlayerChat, v -> c.parsePlayerChat = v));

		// ── right column: behaviour ──
		label(screen, 1, 0, "Loss streak alert");
		screen.add(Fields.number(screen.font(), controlX(screen, 1), rowY(screen, 0), CONTROL_WIDTH, 18,
				"Loss streak", c.lossStreakAlert, 0, 100, v -> c.lossStreakAlert = (int) v));

		label(screen, 1, 1, "Chase multiplier");
		screen.add(Fields.number(screen.font(), controlX(screen, 1), rowY(screen, 1), CONTROL_WIDTH, 18,
				"Escalation", c.escalationFactor, 1, 100, v -> c.escalationFactor = v));

		label(screen, 1, 2, "Bets per minute alert");
		screen.add(Fields.number(screen.font(), controlX(screen, 1), rowY(screen, 2), CONTROL_WIDTH, 18,
				"Rapid bets", c.rapidBetsPerMinute, 0, 600, v -> c.rapidBetsPerMinute = (int) v));

		label(screen, 1, 3, "Opponent min. bets");
		screen.add(Fields.number(screen.font(), controlX(screen, 1), rowY(screen, 3), CONTROL_WIDTH, 18,
				"Min samples", c.opponentMinSamples, 3, 10000, v -> c.opponentMinSamples = (int) v));

		label(screen, 1, 4, "Opponent alert p-value");
		screen.add(Fields.number(screen.font(), controlX(screen, 1), rowY(screen, 4), CONTROL_WIDTH, 18,
				"Alert p", c.opponentAlertP, 0.00001, 0.5, v -> c.opponentAlertP = v));

		screen.add(Fields.toggle(controlX(screen, 1) - 84, rowY(screen, 5), CONTROL_WIDTH + 84, 18,
				"Tilt detection", () -> c.tiltDetection, v -> c.tiltDetection = v));

		screen.add(Fields.toggle(controlX(screen, 1) - 84, rowY(screen, 6), CONTROL_WIDTH + 84, 18,
				"Opponent tracking", () -> c.opponentTracking, v -> c.opponentTracking = v));

		screen.add(Fields.toggle(controlX(screen, 1) - 84, rowY(screen, 7), CONTROL_WIDTH + 84, 18,
				"Read balance from chat", () -> c.trackBalanceFromChat, v -> c.trackBalanceFromChat = v));

		note(screen, 1, 8, "Balance pattern (regex, group: amount)");
		screen.add(Fields.text(screen.font(), labelX(screen, 1), rowY(screen, 9), columnWidth(screen) - 24, 18,
				"Balance pattern", c.balancePattern, 400, v -> {
					c.balancePattern = v;
					DonutGambler.markConfigDirty();
				}));
	}
}
