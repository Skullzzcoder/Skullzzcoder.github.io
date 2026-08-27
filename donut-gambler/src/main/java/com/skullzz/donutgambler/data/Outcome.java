package com.skullzz.donutgambler.data;

/** Result of a single wager. */
public enum Outcome {
	WIN,
	LOSS,
	PUSH;

	public static Outcome fromString(String s) {
		if (s == null) return LOSS;
		try {
			return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return LOSS;
		}
	}
}
