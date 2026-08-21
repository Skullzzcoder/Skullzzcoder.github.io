package com.skullzz.glaze.feature;

import com.skullzz.glaze.GlazeClient;
import com.skullzz.glaze.core.SessionStats;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Keeps {@link SessionStats} current and notices deaths.
 *
 * <p>"Active" means the player moved or looked around in the last few seconds.
 * Position and rotation are used rather than key state because they need no
 * option fields and they cleanly exclude an AFK pool, which is the case that
 * would otherwise wreck a money-per-hour figure.
 */
public final class SessionTracker {
	/** How long after the last input the player still counts as active. */
	private static final long ACTIVITY_GRACE_MILLIS = 5_000;

	private static double lastX;
	private static double lastY;
	private static double lastZ;
	private static float lastYaw;
	private static float lastPitch;
	private static long lastActivityAt;
	private static boolean wasDead;
	private static boolean primed;

	private SessionTracker() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(SessionTracker::tick);
	}

	/** Resets the baseline, called when joining a server. */
	public static void onJoin(long now) {
		primed = false;
		wasDead = false;
		lastActivityAt = now;
		GlazeClient.session().start(now);
	}

	private static void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;

		if (player == null) {
			return;
		}

		long now = System.currentTimeMillis();

		if (!primed) {
			snapshot(player);
			primed = true;
			lastActivityAt = now;
		}

		if (moved(player)) {
			snapshot(player);
			lastActivityAt = now;
		}

		boolean active = now - lastActivityAt < ACTIVITY_GRACE_MILLIS;
		GlazeClient.session().update(now, active);

		checkDeath(player, now);
	}

	private static boolean moved(ClientPlayerEntity player) {
		return Math.abs(player.getX() - lastX) > 0.01
				|| Math.abs(player.getY() - lastY) > 0.01
				|| Math.abs(player.getZ() - lastZ) > 0.01
				|| Math.abs(player.getYaw() - lastYaw) > 0.1F
				|| Math.abs(player.getPitch() - lastPitch) > 0.1F;
	}

	private static void snapshot(ClientPlayerEntity player) {
		lastX = player.getX();
		lastY = player.getY();
		lastZ = player.getZ();
		lastYaw = player.getYaw();
		lastPitch = player.getPitch();
	}

	/** Counts a death once per death, on the transition into it. */
	private static void checkDeath(ClientPlayerEntity player, long now) {
		boolean dead = player.getHealth() <= 0.0F;

		if (dead && !wasDead) {
			GlazeClient.session().addDeath();
			CombatTracker.clear();
			WaypointFeature.recordDeathPoint(player, now);
		}

		wasDead = dead;
	}

	/** Whether the player counts as active right now, for the HUD. */
	public static boolean active() {
		return System.currentTimeMillis() - lastActivityAt < ACTIVITY_GRACE_MILLIS;
	}


	/** Formats the session line, e.g. {@code 1h 12m} or {@code 1h 12m (afk)}. */
	public static String describe(SessionStats stats) {
		String base = SessionStats.formatDuration(stats.activeMillis());
		return active() ? base : base + " (afk)";
	}
}
