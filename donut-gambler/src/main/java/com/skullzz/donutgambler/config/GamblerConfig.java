package com.skullzz.donutgambler.config;

import java.util.ArrayList;
import java.util.List;

/** Everything the mod remembers between launches, apart from the bet history. */
public class GamblerConfig {
	public int schemaVersion = 1;

	// ── Chat capture ────────────────────────────────────────────────────────────
	/** Master switch for reading results out of chat. Manual logging still works when off. */
	public boolean parseChat = true;
	/** Also scan player chat, not just server/system messages. Off by default: fewer false hits. */
	public boolean parsePlayerChat = false;
	/** Read your balance out of chat so bankroll advice tracks reality. */
	public boolean trackBalanceFromChat = true;
	public String balancePattern = "(?i)\\b(?:balance|bal|money)\\b[^$\\d]{0,16}\\$?(?<amount>[\\d,.]+\\s*[kmbt]?)";

	// ── Bankroll ────────────────────────────────────────────────────────────────
	/** Your current in-game money. Updated from chat when tracking is on. */
	public double bankroll = 0;
	/** Bankroll at the time the session started, for session P/L against a baseline. */
	public double sessionStartBankroll = 0;
	/** Fraction of full Kelly to actually bet. 1.0 is full Kelly; 0.25 is the usual "safe" choice. */
	public double kellyFraction = 0.25;
	/** Hard ceiling on a single bet, as a percent of bankroll. */
	public double maxBetPercent = 5.0;

	// ── Session guard rails ─────────────────────────────────────────────────────
	/** Stop for the session once down this percent of the starting bankroll. 0 disables. */
	public double stopLossPercent = 20.0;
	/** Stop for the session once up this percent. 0 disables. */
	public double stopWinPercent = 30.0;
	/** Warn after this many bets in one session. 0 disables. */
	public int sessionBetCap = 0;

	// ── Tilt detection ──────────────────────────────────────────────────────────
	public boolean tiltDetection = true;
	/** Consecutive losses before the advisor calls tilt. */
	public int lossStreakAlert = 4;
	/** Raising a bet by this multiple straight after a loss counts as chasing. */
	public double escalationFactor = 1.5;
	/** More bets than this in a rolling minute is a speed warning. 0 disables. */
	public int rapidBetsPerMinute = 8;

	// ── Opponent tracking ───────────────────────────────────────────────────────
	public boolean opponentTracking = true;
	/** Minimum bets against someone before their record means anything. */
	public int opponentMinSamples = 12;
	/** Flag an opponent when their run of wins against you is this unlikely by chance. */
	public double opponentAlertP = 0.01;

	// ── Notifications ───────────────────────────────────────────────────────────
	public boolean chatNotifyOnBet = true;
	public boolean chatNotifyOnAlert = true;

	// ── HUD ─────────────────────────────────────────────────────────────────────
	public boolean hudEnabled = true;
	public HudAnchor hudAnchor = HudAnchor.TOP_LEFT;
	public int hudX = 4;
	public int hudY = 4;
	public double hudScale = 1.0;
	public boolean hudShowSession = true;
	public boolean hudShowAllTime = false;
	public boolean hudShowStreak = true;
	public boolean hudShowAdvice = true;
	public boolean hudShowRecommendedBet = true;
	/** Panel background alpha, 0-255. 0 draws text only. */
	public int hudBackgroundAlpha = 130;

	// ── Storage ─────────────────────────────────────────────────────────────────
	/** Oldest bets are dropped past this many records. */
	public int historyLimit = 20000;

	// ── Games ───────────────────────────────────────────────────────────────────
	/** Checked in order; the first game whose pattern matches a line wins it. */
	public List<GameDef> games = new ArrayList<>();

	public GameDef gameById(String id) {
		for (GameDef g : games) {
			if (g.id.equals(id)) return g;
		}

		return null;
	}

	/** Makes an id unique within this config by suffixing a counter. */
	public String uniqueId(String base) {
		String id = base;
		int n = 2;

		while (gameById(id) != null) {
			id = base + "_" + n++;
		}

		return id;
	}

	public void invalidatePatterns() {
		for (GameDef g : games) {
			g.invalidate();
		}
	}

	/**
	 * Ships with one broadly-worded coinflip matcher switched on and the rest switched off,
	 * so nothing double-counts a single chat line before you have tuned the patterns.
	 */
	public static GamblerConfig createDefault() {
		GamblerConfig c = new GamblerConfig();

		GameDef cf = new GameDef("coinflip", "Coinflip");
		cf.winPattern = "(?i)\\byou (?:won|win)\\b.{0,40}?\\$(?<amount>[\\d,.]+\\s*[kmbt]?)"
				+ "(?:.{0,40}?(?:from|against|vs\\.?|beat(?:ing)?)\\s+(?<opponent>[A-Za-z0-9_]{3,16}))?";
		cf.lossPattern = "(?i)\\byou (?:lost|lose)\\b.{0,40}?\\$(?<amount>[\\d,.]+\\s*[kmbt]?)"
				+ "(?:.{0,40}?(?:to|against|vs\\.?)\\s+(?<opponent>[A-Za-z0-9_]{3,16}))?";
		cf.winChance = 0.5;
		cf.payout = 1.0;
		cf.houseTaxPercent = 5.0;
		cf.amountIsProfit = true;
		cf.notes = "Generic catch-all. Open the Games tab and rebuild these from a real chat line "
				+ "(Build from chat) so they match your server's exact wording.";
		c.games.add(cf);

		GameDef dice = new GameDef("dice", "Dice");
		dice.enabled = false;
		dice.winPattern = "(?i)\\bdice\\b.{0,60}?\\byou (?:won|win)\\b.{0,40}?\\$(?<amount>[\\d,.]+\\s*[kmbt]?)";
		dice.lossPattern = "(?i)\\bdice\\b.{0,60}?\\byou (?:lost|lose)\\b.{0,40}?\\$(?<amount>[\\d,.]+\\s*[kmbt]?)";
		dice.winChance = 0.5;
		dice.payout = 1.0;
		dice.houseTaxPercent = 5.0;
		dice.amountIsProfit = true;
		c.games.add(dice);

		GameDef duel = new GameDef("duel", "Duel / Sumo bet");
		duel.enabled = false;
		duel.winPattern = "(?i)\\b(?:duel|sumo)\\b.{0,60}?\\byou (?:won|win)\\b.{0,40}?\\$(?<amount>[\\d,.]+\\s*[kmbt]?)"
				+ "(?:.{0,40}?(?:from|against|vs\\.?)\\s+(?<opponent>[A-Za-z0-9_]{3,16}))?";
		duel.lossPattern = "(?i)\\b(?:duel|sumo)\\b.{0,60}?\\byou (?:lost|lose)\\b.{0,40}?\\$(?<amount>[\\d,.]+\\s*[kmbt]?)"
				+ "(?:.{0,40}?(?:to|against|vs\\.?)\\s+(?<opponent>[A-Za-z0-9_]{3,16}))?";
		duel.winChance = 0.5;
		duel.payout = 1.0;
		duel.amountIsProfit = true;
		duel.notes = "Skill game: set Win chance to how often you actually win, not 50%.";
		c.games.add(duel);

		GameDef casino = new GameDef("casino", "Casino / Slots");
		casino.enabled = false;
		casino.winPattern = "(?i)\\b(?:casino|slots?|jackpot)\\b.{0,60}?\\$(?<amount>[\\d,.]+\\s*[kmbt]?)";
		casino.lossPattern = "";
		casino.winChance = 0.45;
		casino.payout = 1.0;
		casino.amountIsProfit = true;
		casino.notes = "House game: no opponent. Win chance and payout are guesses until you measure them.";
		c.games.add(casino);

		return c;
	}
}
