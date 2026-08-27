package com.skullzz.donutgambler.advisor;

import com.skullzz.donutgambler.data.Agg;

/** An opponent's record against you, with how unlikely it is to be luck. */
public record OpponentFlag(String name, Agg agg, double pValue, boolean suspicious) {
	/** How often they beat you. */
	public double theirWinRate() {
		int decided = agg.wins + agg.losses;
		return decided == 0 ? 0 : (double) agg.losses / decided;
	}
}
