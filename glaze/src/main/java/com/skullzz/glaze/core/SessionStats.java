package com.skullzz.glaze.core;

/**
 * Playtime, kills, deaths and earnings for the current play session.
 *
 * <p>Time only accrues while you are actually doing something. Standing in an AFK
 * pool for six hours should not make your money-per-hour look like a rounding
 * error, and a session left running overnight should not swamp the average.
 */
public final class SessionStats {
	/**
	 * The largest gap a single update may contribute. A paused game, a long GC or a
	 * hung server would otherwise dump minutes into the total in one tick.
	 */
	static final long MAX_STEP_MILLIS = 5_000;

	private long startedAt;
	private long lastUpdateAt;
	private long activeMillis;

	private int kills;
	private int deaths;

	private boolean balanceKnown;
	private long startBalance;
	private long currentBalance;
	private long grossIn;
	private long grossOut;

	public void start(long now) {
		startedAt = now;
		lastUpdateAt = now;
		activeMillis = 0;
		kills = 0;
		deaths = 0;
		balanceKnown = false;
		startBalance = 0;
		currentBalance = 0;
		grossIn = 0;
		grossOut = 0;
	}

	/**
	 * Advances the clock.
	 *
	 * @param active whether the player has done anything recently; when false the
	 *               elapsed time is discarded rather than banked
	 */
	public void update(long now, boolean active) {
		long delta = now - lastUpdateAt;
		lastUpdateAt = now;

		if (active && delta > 0) {
			activeMillis += Math.min(delta, MAX_STEP_MILLIS);
		}
	}

	public void addKill() {
		kills++;
	}

	public void addDeath() {
		deaths++;
	}

	/**
	 * Records an authoritative balance reading.
	 *
	 * <p>The first reading of a session becomes the baseline that session earnings
	 * are measured against.
	 */
	public void observeBalance(long balance) {
		if (!balanceKnown) {
			balanceKnown = true;
			startBalance = balance;
		}

		currentBalance = balance;
	}

	/** Money received from another player, tracked separately from net change. */
	public void addIncome(long amount) {
		if (amount > 0) {
			grossIn += amount;

			if (balanceKnown) {
				currentBalance += amount;
			}
		}
	}

	/** Money paid out to another player or a shop. */
	public void addSpend(long amount) {
		if (amount > 0) {
			grossOut += amount;

			if (balanceKnown) {
				currentBalance -= amount;
			}
		}
	}

	public long activeMillis() {
		return activeMillis;
	}

	public long wallClockMillis() {
		return Math.max(0, lastUpdateAt - startedAt);
	}

	public int kills() {
		return kills;
	}

	public int deaths() {
		return deaths;
	}

	/** Kills per death, counting a deathless session as its kill count. */
	public double killDeathRatio() {
		return deaths == 0 ? kills : kills / (double) deaths;
	}

	public boolean balanceKnown() {
		return balanceKnown;
	}

	public long balance() {
		return currentBalance;
	}

	/** Net change in balance since the first reading this session. */
	public long netEarnings() {
		return balanceKnown ? currentBalance - startBalance : 0;
	}

	public long grossIncome() {
		return grossIn;
	}

	public long grossSpending() {
		return grossOut;
	}

	/**
	 * Net earnings extrapolated to an hour of active play.
	 *
	 * <p>Returns 0 until a minute of activity has accumulated: before that the
	 * figure swings wildly on a single trade and is worse than no number at all.
	 */
	public double moneyPerHour() {
		if (activeMillis < 60_000) {
			return 0;
		}

		return netEarnings() / (activeMillis / 3_600_000.0);
	}

	/** {@code 2h 14m} style duration for HUD display. */
	public static String formatDuration(long millis) {
		long totalSeconds = Math.max(0, millis) / 1000;
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;

		if (hours > 0) {
			return hours + "h " + minutes + "m";
		}

		if (minutes > 0) {
			return minutes + "m " + seconds + "s";
		}

		return seconds + "s";
	}
}
