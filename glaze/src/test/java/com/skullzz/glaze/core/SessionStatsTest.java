package com.skullzz.glaze.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SessionStatsTest {
	private static final long T0 = 1_700_000_000_000L;

	@Test
	void idleTimeDoesNotCountTowardsActivePlaytime() {
		SessionStats stats = new SessionStats();
		stats.start(T0);
		stats.update(T0 + 1_000, true);
		stats.update(T0 + 2_000, false);
		stats.update(T0 + 3_000, true);

		assertEquals(2_000, stats.activeMillis());
		assertEquals(3_000, stats.wallClockMillis());
	}

	@Test
	void aSingleHugeGapCannotFloodTheTotal() {
		SessionStats stats = new SessionStats();
		stats.start(T0);
		// Game paused for an hour, then one update lands.
		stats.update(T0 + 3_600_000, true);

		assertEquals(SessionStats.MAX_STEP_MILLIS, stats.activeMillis());
	}

	@Test
	void earningsAreMeasuredFromTheFirstBalanceSeen() {
		SessionStats stats = new SessionStats();
		stats.start(T0);
		stats.observeBalance(1_000_000);
		stats.observeBalance(1_500_000);

		assertEquals(500_000, stats.netEarnings());
		assertEquals(1_500_000, stats.balance());
	}

	@Test
	void earningsAreZeroUntilABalanceIsKnown() {
		SessionStats stats = new SessionStats();
		stats.start(T0);

		assertEquals(0, stats.netEarnings());
		assertTrue(!stats.balanceKnown());
	}

	@Test
	void paymentsAdjustTheRunningBalance() {
		SessionStats stats = new SessionStats();
		stats.start(T0);
		stats.observeBalance(1_000);
		stats.addIncome(500);
		stats.addSpend(200);

		assertEquals(1_300, stats.balance());
		assertEquals(300, stats.netEarnings());
		assertEquals(500, stats.grossIncome());
		assertEquals(200, stats.grossSpending());
	}

	@Test
	void moneyPerHourWaitsForEnoughActiveTime() {
		SessionStats stats = new SessionStats();
		stats.start(T0);
		stats.observeBalance(0);
		stats.update(T0 + 1_000, true);
		stats.observeBalance(1_000_000);

		assertEquals(0, stats.moneyPerHour(), 1e-9, "one second of play is not a rate");
	}

	@Test
	void moneyPerHourExtrapolatesFromActiveTime() {
		SessionStats stats = new SessionStats();
		stats.start(T0);
		stats.observeBalance(0);

		// Half an hour of activity, in steps small enough to be banked in full.
		long now = T0;

		for (int i = 0; i < 360; i++) {
			now += 5_000;
			stats.update(now, true);
		}

		stats.observeBalance(5_000_000);

		assertEquals(1_800_000, stats.activeMillis());
		assertEquals(10_000_000, stats.moneyPerHour(), 1.0);
	}

	@Test
	void killDeathRatioHandlesADeathlessSession() {
		SessionStats stats = new SessionStats();
		stats.start(T0);
		stats.addKill();
		stats.addKill();

		assertEquals(2.0, stats.killDeathRatio(), 1e-9);

		stats.addDeath();
		assertEquals(2.0, stats.killDeathRatio(), 1e-9);
	}

	@Test
	void formatsDurationsForTheHud() {
		assertEquals("45s", SessionStats.formatDuration(45_000));
		assertEquals("2m 5s", SessionStats.formatDuration(125_000));
		assertEquals("2h 14m", SessionStats.formatDuration(8_040_000));
	}
}
