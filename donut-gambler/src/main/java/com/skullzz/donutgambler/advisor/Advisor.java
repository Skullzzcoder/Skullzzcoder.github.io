package com.skullzz.donutgambler.advisor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.skullzz.donutgambler.advisor.Advice.Severity;
import com.skullzz.donutgambler.chat.MoneyParser;
import com.skullzz.donutgambler.config.GamblerConfig;
import com.skullzz.donutgambler.config.GameDef;
import com.skullzz.donutgambler.data.Agg;
import com.skullzz.donutgambler.data.BetLog;
import com.skullzz.donutgambler.data.BetRecord;
import com.skullzz.donutgambler.data.Outcome;

/**
 * Turns config + history into advice. Four inputs feed it: the configured odds of a game,
 * bankroll maths, your own measured results, and who you have been playing.
 *
 * <p>Pure and deterministic - no Minecraft types - so the rules can be unit-tested.
 */
public final class Advisor {
	/** A streak from more than half an hour ago is history, not tilt. */
	private static final long STREAK_MEMORY_MS = 30 * 60_000L;

	private Advisor() {
	}

	/** Kelly-sized stake for a game, after the fractional-Kelly setting and the hard cap. */
	public static double recommendedBet(GamblerConfig config, GameDef game) {
		if (game == null || config.bankroll <= 0) return 0;
		if (game.expectedValue() <= 0) return 0;

		double kelly = game.kellyFraction() * MathUtil.clamp(config.kellyFraction, 0.01, 1.0);
		double cap = config.bankroll * MathUtil.clamp(config.maxBetPercent, 0.01, 100) / 100.0;
		return Math.min(kelly * config.bankroll, cap);
	}

	/** The game the advice is about: the one you last bet on, else the first enabled one. */
	public static GameDef focusGame(GamblerConfig config, BetLog log) {
		BetRecord last = log.last();

		if (last != null) {
			GameDef g = config.gameById(last.gameId);
			if (g != null) return g;
		}

		for (GameDef g : config.games) {
			if (g.enabled) return g;
		}

		return config.games.isEmpty() ? null : config.games.get(0);
	}

	public static Advice evaluate(GamblerConfig config, BetLog log) {
		return evaluate(config, log, focusGame(config, log));
	}

	public static Advice evaluate(GamblerConfig config, BetLog log, GameDef focus) {
		Advice advice = new Advice();
		Agg session = log.aggSession();
		Agg allTime = log.aggAll();

		if (focus != null) {
			advice.focusGameId = focus.id;
			advice.focusGameName = focus.name;
		}

		bankrollLines(config, advice, focus, session, allTime);
		sessionGuardRails(config, log, advice, session);
		tiltLines(config, log, advice, session);
		measuredOddsLines(config, log, advice, focus);
		opponentLines(config, log, advice);
		contextLines(advice, session, allTime);

		advice.headline = headline(advice, session);
		return advice;
	}

	// ── bankroll + per-game EV ──────────────────────────────────────────────────

	private static void bankrollLines(GamblerConfig config, Advice advice, GameDef focus, Agg session, Agg allTime) {
		if (focus == null) {
			advice.add(Severity.INFO, "No games configured. Open the Games tab to add one.");
			return;
		}

		double edge = focus.edgePercent();
		String odds = String.format(Locale.ROOT, "%s: %.0f%% win chance at %.2fx payout",
				focus.name, focus.clampedChance() * 100, focus.effectivePayout());

		if (edge < -0.05) {
			advice.add(Severity.BAD, String.format(Locale.ROOT,
					"%s -> %.2f%% edge against you. Every $1M staked gives back about %s long-run.",
					odds, edge, MoneyParser.format(1_000_000 * (1 + edge / 100))));
		} else if (edge > 0.05) {
			advice.add(Severity.GOOD, String.format(Locale.ROOT,
					"%s -> +%.2f%% edge in your favour.", odds, edge));
		} else {
			advice.add(Severity.WARN, odds + " -> break-even before variance. No edge to press.");
		}

		if (config.bankroll <= 0) {
			advice.add(Severity.INFO,
					"Bankroll unknown. Run /gambler balance <amount> (or let chat tracking read it) for bet sizing.");
			return;
		}

		double bet = recommendedBet(config, focus);
		advice.recommendedBet = bet;

		if (bet <= 0) {
			advice.add(Severity.BAD, "Correct bet size for " + focus.name
					+ " is $0 - no stake is +EV when the edge is against you.");
		} else {
			advice.add(Severity.GOOD, String.format(Locale.ROOT,
					"Bet up to %s (%.0f%% Kelly of a %s bankroll, capped at %.1f%%).",
					MoneyParser.format(bet), config.kellyFraction * 100,
					MoneyParser.format(config.bankroll), config.maxBetPercent));
		}

		double typical = typicalStake(session, allTime, bet);

		if (typical > 0) {
			double ror = MathUtil.riskOfRuin(focus.clampedChance(), focus.effectivePayout(), config.bankroll, typical);
			advice.riskOfRuin = ror;

			Severity sev = ror >= 0.5 ? Severity.BAD : ror >= 0.15 ? Severity.WARN : Severity.INFO;
			advice.add(sev, String.format(Locale.ROOT,
					"Flat-betting %s of a %s bankroll: %.0f%% chance of losing all of it (%.0f bets deep).",
					MoneyParser.format(typical), MoneyParser.format(config.bankroll), ror * 100,
					MathUtil.unitsOfBankroll(config.bankroll, typical)));
		}

		double cap = config.bankroll * config.maxBetPercent / 100.0;
		double lastStake = session.bets > 0 ? session.avgStake() : 0;

		if (lastStake > cap * 1.05 && cap > 0) {
			advice.add(Severity.WARN, String.format(Locale.ROOT,
					"Your average bet this session (%s) is over your %.1f%% cap (%s).",
					MoneyParser.format(lastStake), config.maxBetPercent, MoneyParser.format(cap)));
		}
	}

	private static double typicalStake(Agg session, Agg allTime, double recommended) {
		if (session.bets >= 3) return session.avgStake();
		if (allTime.bets >= 3) return allTime.avgStake();
		return recommended;
	}

	// ── session limits ──────────────────────────────────────────────────────────

	private static void sessionGuardRails(GamblerConfig config, BetLog log, Advice advice, Agg session) {
		double baseline = config.sessionStartBankroll > 0 ? config.sessionStartBankroll : config.bankroll;

		if (baseline > 0 && config.stopLossPercent > 0) {
			double limit = -baseline * config.stopLossPercent / 100.0;

			if (session.net <= limit) {
				advice.add(Severity.BAD, String.format(Locale.ROOT,
						"Session stop-loss hit: %s (%.1f%% of the bankroll you started with). Stop for today.",
						MoneyParser.formatSigned(session.net), Math.abs(session.net) / baseline * 100));
			}
		}

		if (baseline > 0 && config.stopWinPercent > 0) {
			double target = baseline * config.stopWinPercent / 100.0;

			if (session.net >= target) {
				advice.add(Severity.WARN, String.format(Locale.ROOT,
						"Session target reached: %s (+%.1f%%). Booking it now is the whole point of setting one.",
						MoneyParser.formatSigned(session.net), session.net / baseline * 100));
			}
		}

		if (config.sessionBetCap > 0 && session.bets >= config.sessionBetCap) {
			advice.add(Severity.WARN, "Session bet cap reached: " + session.bets + " bets.");
		}
	}

	// ── tilt ────────────────────────────────────────────────────────────────────

	private static void tiltLines(GamblerConfig config, BetLog log, Advice advice, Agg session) {
		if (!config.tiltDetection) return;

		int streak = currentStreak(log, session);

		if (config.lossStreakAlert > 0 && streak <= -config.lossStreakAlert) {
			advice.add(Severity.BAD, Math.abs(streak)
					+ " losses in a row. The coin has no memory, but your balance does - take a break.");
		}

		List<BetRecord> bets = log.all();

		if (bets.size() >= 2) {
			BetRecord last = bets.get(bets.size() - 1);
			BetRecord prev = bets.get(bets.size() - 2);

			if (prev.outcome == Outcome.LOSS && prev.stake > 0
					&& last.stake >= prev.stake * Math.max(1.01, config.escalationFactor)) {
				advice.add(Severity.BAD, String.format(Locale.ROOT,
						"Chasing: you raised your stake %.1fx (%s -> %s) straight after a loss.",
						last.stake / prev.stake, MoneyParser.format(prev.stake), MoneyParser.format(last.stake)));
			}
		}

		if (config.rapidBetsPerMinute > 0) {
			int recent = log.betsSince(System.currentTimeMillis() - 60_000);

			if (recent > config.rapidBetsPerMinute) {
				advice.add(Severity.WARN, recent + " bets in the last minute. That pace is tilt, not strategy.");
			}
		}
	}

	/**
	 * The run of wins or losses you are on right now. Uses the session when it has bets, and
	 * otherwise the tail of the history - but only while it is still fresh, so yesterday's
	 * losing streak does not greet you on login.
	 */
	private static int currentStreak(BetLog log, Agg session) {
		if (session.bets > 0) return session.streak;

		BetRecord last = log.last();

		if (last != null && System.currentTimeMillis() - last.time < STREAK_MEMORY_MS) {
			return log.aggAll().streak;
		}

		return 0;
	}

	// ── measured odds vs configured odds ────────────────────────────────────────

	private static void measuredOddsLines(GamblerConfig config, BetLog log, Advice advice, GameDef focus) {
		if (focus == null) return;

		Agg measured = log.byGame().get(focus.id);
		if (measured == null) return;

		int decided = measured.wins + measured.losses;

		if (decided < 20) {
			advice.add(Severity.INFO, String.format(Locale.ROOT,
					"%d %s bets logged - too few to judge the odds. About %d decide it.",
					decided, focus.name, MathUtil.samplesForPrecision(focus.clampedChance(), 0.05)));
			return;
		}

		double lower = measured.winRateLower95();
		double upper = measured.winRateUpper95();
		double configured = focus.clampedChance();

		String observed = String.format(Locale.ROOT,
				"Measured %s: you win %.1f%% of %d (95%% CI %.0f-%.0f%%), config says %.0f%%.",
				focus.name, measured.winRate() * 100, decided, lower * 100, upper * 100, configured * 100);

		if (configured > upper) {
			advice.add(Severity.BAD, observed + " You are doing worse than the stated odds - "
					+ "either the game is not what it claims, or your pattern is mislabelling results.");
		} else if (configured < lower) {
			advice.add(Severity.GOOD, observed + " You are running above the stated odds.");
		} else {
			advice.add(Severity.INFO, observed + " That is within normal variance.");
		}

		advice.add(measured.net < 0 ? Severity.WARN : Severity.GOOD, String.format(Locale.ROOT,
				"%s lifetime: %s over %d bets (%s ROI on %s wagered).",
				focus.name, MoneyParser.formatSigned(measured.net), measured.bets,
				MoneyParser.signedPercent(measured.roi()), MoneyParser.format(measured.wagered)));
	}

	// ── opponents ───────────────────────────────────────────────────────────────

	private static void opponentLines(GamblerConfig config, BetLog log, Advice advice) {
		if (!config.opponentTracking) return;

		List<OpponentFlag> flags = opponentFlags(config, log);

		for (OpponentFlag flag : flags) {
			if (!flag.suspicious()) break;

			advice.add(Severity.BAD, String.format(Locale.ROOT,
					"%s has beaten you %d of %d (%.0f%%, p=%.4f) and taken %s. That is hard to explain by luck.",
					flag.name(), flag.agg().losses, flag.agg().wins + flag.agg().losses,
					flag.theirWinRate() * 100, flag.pValue(), MoneyParser.format(-flag.agg().net)));
		}
	}

	/**
	 * Every opponent you have a record against, worst first.
	 * The p-value answers "if this were a fair 50/50, how often would they beat me this hard?".
	 */
	public static List<OpponentFlag> opponentFlags(GamblerConfig config, BetLog log) {
		List<OpponentFlag> out = new ArrayList<>();

		for (Map.Entry<String, Agg> e : log.byOpponent().entrySet()) {
			Agg agg = e.getValue();
			int decided = agg.wins + agg.losses;
			if (decided == 0) continue;

			double p = MathUtil.pValueAtLeast(agg.losses, decided, 0.5);
			boolean suspicious = decided >= Math.max(3, config.opponentMinSamples) && p <= config.opponentAlertP;
			out.add(new OpponentFlag(agg.label, agg, p, suspicious));
		}

		out.sort(Comparator.comparingDouble(OpponentFlag::pValue));
		return out;
	}

	// ── context ─────────────────────────────────────────────────────────────────

	private static void contextLines(Advice advice, Agg session, Agg allTime) {
		if (allTime.bets > 0) {
			advice.add(allTime.net < 0 ? Severity.WARN : Severity.GOOD, String.format(Locale.ROOT,
					"All time: %s over %d bets, %d-%d, %s ROI.",
					MoneyParser.formatSigned(allTime.net), allTime.bets, allTime.wins, allTime.losses,
					MoneyParser.signedPercent(allTime.roi())));
		} else {
			advice.add(Severity.INFO, "No bets logged yet. Gamble once, or log one with /gambler log.");
		}
	}

	private static String headline(Advice advice, Agg session) {
		return switch (advice.verdict) {
		case RED -> "Stop: " + advice.primaryReason();
		case YELLOW -> "Caution: " + advice.primaryReason();
		case GREEN -> session.bets == 0
				? "Nothing flagged. Set your limits before the first bet."
				: "Nothing flagged. Session " + MoneyParser.formatSigned(session.net) + ".";
		};
	}
}
