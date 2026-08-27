package com.skullzz.donutgambler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.skullzz.donutgambler.config.ConfigManager;
import com.skullzz.donutgambler.data.Agg;
import com.skullzz.donutgambler.data.BetLog;
import com.skullzz.donutgambler.data.BetRecord;
import com.skullzz.donutgambler.data.Outcome;

class BetLogTest {
	private static BetRecord bet(long time, String opponent, double stake, double net, Outcome outcome) {
		return new BetRecord(time, "coinflip", "Coinflip", opponent, stake, net, outcome, "chat", "");
	}

	private static BetLog seeded(Path dir) {
		BetLog log = new BetLog(dir.resolve("history.json"));
		log.add(bet(1000, "Alice", 1000, 950, Outcome.WIN));
		log.add(bet(2000, "Alice", 1000, -1000, Outcome.LOSS));
		log.add(bet(3000, "Bob", 2000, -2000, Outcome.LOSS));
		return log;
	}

	@Test
	void aggregatesCountsMoneyAndStreaks(@TempDir Path dir) {
		Agg all = seeded(dir).aggAll();

		assertEquals(3, all.bets);
		assertEquals(1, all.wins);
		assertEquals(2, all.losses);
		assertEquals(-2050, all.net, 1e-9);
		assertEquals(4000, all.wagered, 1e-9);
		assertEquals(-2, all.streak, "two losses in a row");
		assertEquals(2, all.longestLossStreak);
		assertEquals(1.0 / 3, all.winRate(), 1e-9);
	}

	@Test
	void splitsByGameAndOpponent(@TempDir Path dir) {
		BetLog log = seeded(dir);

		assertEquals(1, log.byGame().size());
		assertEquals(2, log.byOpponent().size());
		assertEquals(-2000, log.byOpponent().get("bob").net, 1e-9);
	}

	@Test
	void roundTripsThroughDisk(@TempDir Path dir) {
		BetLog log = seeded(dir);
		assertNull(log.save());

		BetLog reloaded = new BetLog(dir.resolve("history.json"));
		assertNull(reloaded.load());
		assertEquals(3, reloaded.size());
		assertEquals(-2050, reloaded.aggAll().net, 1e-9);
	}

	@Test
	void undoRemovesTheNewestBet(@TempDir Path dir) {
		BetLog log = seeded(dir);
		BetRecord removed = log.removeLast();

		assertEquals("Bob", removed.opponent);
		assertEquals(2, log.size());
		assertEquals(-50, log.aggAll().net, 1e-9);
	}

	@Test
	void historyLimitDropsTheOldestBets(@TempDir Path dir) {
		BetLog log = new BetLog(dir.resolve("history.json"));
		log.setHistoryLimit(100);

		for (int i = 0; i < 150; i++) {
			log.add(bet(i, "Alice", 10, -10, Outcome.LOSS));
		}

		assertEquals(100, log.size());
		assertEquals(50, log.all().get(0).time, "oldest 50 dropped");
	}

	@Test
	void cumulativeCurveEndsAtNetProfit(@TempDir Path dir) {
		double[] curve = seeded(dir).cumulative(10);

		assertEquals(0, curve[0], 1e-9);
		assertEquals(-2050, curve[curve.length - 1], 1e-9);
	}

	@Test
	void csvHasOneRowPerBet(@TempDir Path dir) {
		assertEquals(4, seeded(dir).toCsv().split("\n").length, "header plus three bets");
	}

	@Test
	void configRoundTripsAndRecompilesPatterns(@TempDir Path dir) throws IOException {
		ConfigManager manager = new ConfigManager(dir.resolve("config.json"));
		assertNull(manager.load());

		manager.get().bankroll = 5_000_000;
		assertNull(manager.save());

		ConfigManager reloaded = new ConfigManager(dir.resolve("config.json"));
		assertNull(reloaded.load());
		assertEquals(5_000_000, reloaded.get().bankroll, 1e-6);
		assertTrue(reloaded.get().gameById("coinflip").winRegex() != null, "regex is usable after a reload");
	}
}
