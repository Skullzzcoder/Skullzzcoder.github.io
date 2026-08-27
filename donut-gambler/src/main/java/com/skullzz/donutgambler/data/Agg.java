package com.skullzz.donutgambler.data;

import java.util.List;

import com.skullzz.donutgambler.advisor.MathUtil;

/** Rolled-up numbers for a slice of bets (all-time, one game, one opponent, this session...). */
public final class Agg {
	public String label = "";
	public int bets;
	public int wins;
	public int losses;
	public int pushes;
	public double wagered;
	public double net;
	public double biggestWin;
	public double biggestLoss;
	/** Positive = current win streak, negative = current loss streak. */
	public int streak;
	public int longestWinStreak;
	public int longestLossStreak;
	public long firstTime = Long.MAX_VALUE;
	public long lastTime;

	public static Agg of(String label, List<BetRecord> records) {
		Agg a = new Agg();
		a.label = label;

		for (BetRecord r : records) {
			a.accept(r);
		}

		return a;
	}

	public void accept(BetRecord r) {
		bets++;
		wagered += r.stake;
		net += r.net;
		firstTime = Math.min(firstTime, r.time);
		lastTime = Math.max(lastTime, r.time);

		switch (r.outcome) {
		case WIN -> {
			wins++;
			biggestWin = Math.max(biggestWin, r.net);
			streak = streak >= 0 ? streak + 1 : 1;
			longestWinStreak = Math.max(longestWinStreak, streak);
		}
		case LOSS -> {
			losses++;
			biggestLoss = Math.min(biggestLoss, r.net);
			streak = streak <= 0 ? streak - 1 : -1;
			longestLossStreak = Math.max(longestLossStreak, -streak);
		}
		case PUSH -> pushes++;
		}
	}

	/** Wins as a share of decided (non-push) bets; 0 when there is nothing to divide by. */
	public double winRate() {
		int decided = wins + losses;
		return decided == 0 ? 0 : (double) wins / decided;
	}

	/** Net profit as a share of everything wagered. */
	public double roi() {
		return wagered == 0 ? 0 : net / wagered;
	}

	public double avgStake() {
		return bets == 0 ? 0 : wagered / bets;
	}

	/** Lower bound of the 95% Wilson interval on the win rate. */
	public double winRateLower95() {
		return MathUtil.wilsonLower(wins, wins + losses, 1.96);
	}

	public double winRateUpper95() {
		return MathUtil.wilsonUpper(wins, wins + losses, 1.96);
	}

	public boolean isEmpty() {
		return bets == 0;
	}
}
