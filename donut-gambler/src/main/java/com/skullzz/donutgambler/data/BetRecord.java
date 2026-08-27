package com.skullzz.donutgambler.data;

/**
 * One logged wager. Plain fields with a no-arg constructor so Gson can round-trip it
 * without needing reflection on records.
 */
public class BetRecord {
	/** Epoch millis the bet was resolved. */
	public long time;
	/** Id of the {@code GameDef} this bet belongs to. */
	public String gameId = "unknown";
	/** Display name captured at log time, so history survives a game being renamed/deleted. */
	public String gameName = "Unknown";
	/** Other player, or null/empty when the game has no opponent (house games). */
	public String opponent = "";
	/** Amount put at risk. */
	public double stake;
	/** Signed profit: positive on a win, negative on a loss, 0 on a push. */
	public double net;
	public Outcome outcome = Outcome.LOSS;
	/** "chat" when auto-detected, "manual" when logged by command/GUI. */
	public String source = "chat";
	/** Raw chat line that produced this record (empty for manual entries). */
	public String rawLine = "";

	public BetRecord() {
	}

	public BetRecord(long time, String gameId, String gameName, String opponent,
			double stake, double net, Outcome outcome, String source, String rawLine) {
		this.time = time;
		this.gameId = gameId;
		this.gameName = gameName;
		this.opponent = opponent == null ? "" : opponent;
		this.stake = stake;
		this.net = net;
		this.outcome = outcome;
		this.source = source;
		this.rawLine = rawLine == null ? "" : rawLine;
	}

	public boolean hasOpponent() {
		return opponent != null && !opponent.isBlank();
	}
}
