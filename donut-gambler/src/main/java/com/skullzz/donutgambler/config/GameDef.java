package com.skullzz.donutgambler.config;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.skullzz.donutgambler.advisor.MathUtil;

/**
 * One gambling game the mod knows about: how to recognise its results in chat, and what
 * the odds behind it are. Everything is editable in-game, because server message formats
 * change and the shipped patterns are only starting points.
 */
public class GameDef {
	public String id = "game";
	public String name = "Game";
	public boolean enabled = true;

	/** Regex matched against incoming chat. Optional named groups: {@code amount}, {@code opponent}. */
	public String winPattern = "";
	public String lossPattern = "";
	/** Optional: a tie/refund line. Logged as a push (stake back, no profit). */
	public String pushPattern = "";

	/** True probability of winning a single bet, 0..1. */
	public double winChance = 0.5;
	/** Profit per unit staked on a win, before tax. Even money = 1.0, 2:1 payout = 2.0. */
	public double payout = 1.0;
	/** Server cut on winnings, in percent. Folded into the effective payout. */
	public double houseTaxPercent = 0.0;

	/** When true the captured amount is the winnings, not the stake. */
	public boolean amountIsProfit = false;
	/** Fallback stake when a pattern matches but captures no amount. */
	public double defaultStake = 0;

	public String notes = "";

	private transient Pattern winCompiled;
	private transient Pattern lossCompiled;
	private transient Pattern pushCompiled;
	private transient String winSource;
	private transient String lossSource;
	private transient String pushSource;

	public GameDef() {
	}

	public GameDef(String id, String name) {
		this.id = id;
		this.name = name;
	}

	/** Payout actually received after the server takes its cut. */
	public double effectivePayout() {
		return payout * (1.0 - MathUtil.clamp(houseTaxPercent, 0, 100) / 100.0);
	}

	/** Expected profit per unit staked. */
	public double expectedValue() {
		return MathUtil.expectedValue(clampedChance(), effectivePayout());
	}

	public double edgePercent() {
		return expectedValue() * 100.0;
	}

	public double kellyFraction() {
		return MathUtil.kellyFraction(clampedChance(), effectivePayout());
	}

	public double clampedChance() {
		return MathUtil.clamp(winChance, 0, 1);
	}

	/** Profit this game pays on a winning stake. */
	public double profitForStake(double stake) {
		return stake * effectivePayout();
	}

	/** Stake implied by a winning payout, for games whose chat only prints the winnings. */
	public double stakeForProfit(double profit) {
		double b = effectivePayout();
		return b <= 0 ? profit : profit / b;
	}

	public Pattern winRegex() {
		if (winCompiled == null || !equalsSource(winSource, winPattern)) {
			winCompiled = compile(winPattern);
			winSource = winPattern;
		}

		return winCompiled;
	}

	public Pattern lossRegex() {
		if (lossCompiled == null || !equalsSource(lossSource, lossPattern)) {
			lossCompiled = compile(lossPattern);
			lossSource = lossPattern;
		}

		return lossCompiled;
	}

	public Pattern pushRegex() {
		if (pushCompiled == null || !equalsSource(pushSource, pushPattern)) {
			pushCompiled = compile(pushPattern);
			pushSource = pushPattern;
		}

		return pushCompiled;
	}

	/** Drops cached patterns so the next match recompiles edited regexes. */
	public void invalidate() {
		winCompiled = null;
		lossCompiled = null;
		pushCompiled = null;
	}

	/** Null when the pattern is blank or does not compile. */
	private static Pattern compile(String source) {
		if (source == null || source.isBlank()) return null;

		try {
			return Pattern.compile(source);
		} catch (PatternSyntaxException e) {
			return null;
		}
	}

	private static boolean equalsSource(String cached, String current) {
		return cached != null && cached.equals(current);
	}

	/** Human-readable reason the game cannot match anything, or null when it is usable. */
	public String problem() {
		if (winPattern.isBlank() && lossPattern.isBlank()) {
			return "No win or loss pattern set";
		}

		if (!winPattern.isBlank() && winRegex() == null) return "Win pattern is not valid regex";
		if (!lossPattern.isBlank() && lossRegex() == null) return "Loss pattern is not valid regex";
		if (!pushPattern.isBlank() && pushRegex() == null) return "Push pattern is not valid regex";

		return null;
	}

	public GameDef copy() {
		GameDef g = new GameDef(id, name);
		g.enabled = enabled;
		g.winPattern = winPattern;
		g.lossPattern = lossPattern;
		g.pushPattern = pushPattern;
		g.winChance = winChance;
		g.payout = payout;
		g.houseTaxPercent = houseTaxPercent;
		g.amountIsProfit = amountIsProfit;
		g.defaultStake = defaultStake;
		g.notes = notes;
		return g;
	}

	public static String slug(String name) {
		String s = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
		s = s.replaceAll("^_+|_+$", "");
		return s.isEmpty() ? "game" : s;
	}
}
