package com.skullzz.donutgambler.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.skullzz.donutgambler.config.GamblerConfig;
import com.skullzz.donutgambler.config.GameDef;
import com.skullzz.donutgambler.data.BetRecord;
import com.skullzz.donutgambler.data.Outcome;

/** Turns a line of chat into a {@link BetRecord}. Pure: no Minecraft, no side effects. */
public final class BetMatcher {
	private static final Pattern FORMAT_CODES = Pattern.compile("(?i)[\u00a7&][0-9a-fk-or]");

	private BetMatcher() {
	}

	/** Removes legacy colour codes and collapses whitespace so patterns match plain words. */
	public static String strip(String raw) {
		if (raw == null) return "";
		return FORMAT_CODES.matcher(raw).replaceAll("").replaceAll("\\s+", " ").trim();
	}

	/**
	 * Finds the first enabled game whose pattern claims this line.
	 * Games are tried in config order, so put the specific ones above the catch-alls.
	 *
	 * @return the parsed bet, or null when nothing matched or no usable amount was captured
	 */
	public static BetRecord match(GamblerConfig config, String line, long now) {
		String text = strip(line);
		if (text.isEmpty()) return null;

		for (GameDef game : config.games) {
			if (!game.enabled) continue;

			BetRecord win = tryPattern(game, game.winRegex(), text, Outcome.WIN, now);
			if (win != null) return win;

			BetRecord push = tryPattern(game, game.pushRegex(), text, Outcome.PUSH, now);
			if (push != null) return push;

			BetRecord loss = tryPattern(game, game.lossRegex(), text, Outcome.LOSS, now);
			if (loss != null) return loss;
		}

		return null;
	}

	private static BetRecord tryPattern(GameDef game, Pattern pattern, String text, Outcome outcome, long now) {
		if (pattern == null) return null;

		Matcher m = pattern.matcher(text);
		if (!m.find()) return null;

		double amount = MoneyParser.parse(group(m, "amount"));

		if (Double.isNaN(amount) || amount <= 0) {
			amount = game.defaultStake;
		}

		if (amount <= 0) return null;

		String opponent = group(m, "opponent");
		double stake;
		double net;

		switch (outcome) {
		case WIN -> {
			if (game.amountIsProfit) {
				net = amount;
				stake = game.stakeForProfit(amount);
			} else {
				stake = amount;
				net = game.profitForStake(amount);
			}
		}
		case LOSS -> {
			// On a loss the printed number is what left your balance, whichever
			// convention the win line uses.
			stake = amount;
			net = -amount;
		}
		default -> {
			stake = amount;
			net = 0;
		}
		}

		return new BetRecord(now, game.id, game.name, opponent, stake, net, outcome, "chat", text);
	}

	/** Reads your balance out of a chat line, or NaN when the line is not a balance line. */
	public static double matchBalance(GamblerConfig config, String line) {
		if (!config.trackBalanceFromChat || config.balancePattern == null || config.balancePattern.isBlank()) {
			return Double.NaN;
		}

		Pattern p;

		try {
			p = Pattern.compile(config.balancePattern);
		} catch (Exception e) {
			return Double.NaN;
		}

		Matcher m = p.matcher(strip(line));
		if (!m.find()) return Double.NaN;

		String amount = group(m, "amount");
		return MoneyParser.parse(amount == null || amount.isEmpty() ? m.group() : amount);
	}

	/** Named group value, or "" when the pattern has no such group or it did not participate. */
	public static String group(Matcher m, String name) {
		try {
			String v = m.group(name);
			return v == null ? "" : v.trim();
		} catch (IllegalArgumentException | IllegalStateException e) {
			return "";
		}
	}

	/** Counts how many of the given lines a pattern matches - powers the "Test" button. */
	public static int countMatches(String regex, Iterable<String> lines) {
		Pattern p;

		try {
			p = Pattern.compile(regex);
		} catch (Exception e) {
			return -1;
		}

		int n = 0;

		for (String line : lines) {
			if (p.matcher(strip(line)).find()) n++;
		}

		return n;
	}
}
