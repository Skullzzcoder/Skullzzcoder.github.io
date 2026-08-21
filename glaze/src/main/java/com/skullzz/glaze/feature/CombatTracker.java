package com.skullzz.glaze.feature;

import com.skullzz.glaze.GlazeClient;

/**
 * Tracks the server's combat tag so you know when it is safe to log or teleport.
 *
 * <p>Driven by the server's own combat messages rather than by guessing from
 * damage events, so the countdown matches what the server actually enforces. The
 * tag length comes from config because only the server truly knows it.
 */
public final class CombatTracker {
	private static long taggedUntil;

	private CombatTracker() {
	}

	public static void start(long now) {
		taggedUntil = now + GlazeClient.config().hud.combatTagSeconds * 1000L;
	}

	public static void clear() {
		taggedUntil = 0;
	}

	public static boolean tagged() {
		return remainingMillis() > 0;
	}

	public static long remainingMillis() {
		return Math.max(0, taggedUntil - System.currentTimeMillis());
	}


	/** Seconds left, rounded up, so the last second reads "1" rather than "0". */
	public static int remainingSeconds() {
		return (int) Math.ceil(remainingMillis() / 1000.0);
	}
}
