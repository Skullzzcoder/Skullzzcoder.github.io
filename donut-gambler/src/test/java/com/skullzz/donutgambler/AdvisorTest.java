package com.skullzz.donutgambler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.skullzz.donutgambler.advisor.Advice;
import com.skullzz.donutgambler.advisor.Advisor;
import com.skullzz.donutgambler.advisor.OpponentFlag;
import com.skullzz.donutgambler.config.GamblerConfig;
import com.skullzz.donutgambler.config.GameDef;
import com.skullzz.donutgambler.data.BetLog;
import com.skullzz.donutgambler.data.BetRecord;
import com.skullzz.donutgambler.data.Outcome;

class AdvisorTest {
	private static GamblerConfig winnableConfig() {
		GamblerConfig config = GamblerConfig.createDefault();
		config.bankroll = 10_000_000;
		config.sessionStartBankroll = 10_000_000;

		GameDef coinflip = config.gameById("coinflip");
		coinflip.winChance = 0.6;
		coinflip.houseTaxPercent = 0;
		return config;
	}

	private static void addLosses(BetLog log, String opponent, int count, double stake) {
		long now = System.currentTimeMillis();

		for (int i = 0; i < count; i++) {
			// Inside the session window: the log starts a session when it is constructed.
			log.add(new BetRecord(now + i, "coinflip", "Coinflip", opponent,
					stake, -stake, Outcome.LOSS, "chat", ""));
		}
	}

	@Test
	void sizesAQuarterKellyBetOnARealEdge(@TempDir Path dir) {
		GamblerConfig config = winnableConfig();
		Advice advice = Advisor.evaluate(config, new BetLog(dir.resolve("h.json")));

		// Full Kelly on a 60/40 even-money bet is 20% of bankroll; the default is a quarter of that.
		assertEquals(0.2 * 0.25 * 10_000_000, advice.recommendedBet, 1.0);
		assertFalse(advice.verdict == Advice.Verdict.RED);
	}

	@Test
	void refusesToSizeANegativeEdgeGame(@TempDir Path dir) {
		GamblerConfig config = GamblerConfig.createDefault();
		config.bankroll = 10_000_000;

		Advice advice = Advisor.evaluate(config, new BetLog(dir.resolve("h.json")));

		assertEquals(0, advice.recommendedBet, 1e-9);
		assertEquals(Advice.Verdict.RED, advice.verdict, "a taxed coinflip is -EV, so betting it is a stop");
	}

	@Test
	void callsALosingStreak(@TempDir Path dir) {
		GamblerConfig config = winnableConfig();
		config.lossStreakAlert = 4;

		BetLog log = new BetLog(dir.resolve("h.json"));
		addLosses(log, "Rick", 6, 100_000);

		Advice advice = Advisor.evaluate(config, log);

		assertEquals(Advice.Verdict.RED, advice.verdict);
		assertTrue(advice.lines.stream().anyMatch(line -> line.text().contains("losses in a row")));
	}

	@Test
	void callsOutChasingAfterALoss(@TempDir Path dir) {
		GamblerConfig config = winnableConfig();
		config.lossStreakAlert = 0;
		config.escalationFactor = 1.5;

		BetLog log = new BetLog(dir.resolve("h.json"));
		long now = System.currentTimeMillis();
		log.add(new BetRecord(now, "coinflip", "Coinflip", "Rick", 100_000, -100_000, Outcome.LOSS, "chat", ""));
		log.add(new BetRecord(now + 1, "coinflip", "Coinflip", "Rick", 400_000, 400_000, Outcome.WIN, "chat", ""));

		Advice advice = Advisor.evaluate(config, log);

		assertTrue(advice.lines.stream().anyMatch(line -> line.text().startsWith("Chasing:")),
				"raising the stake 4x straight after a loss should be flagged");
	}

	@Test
	void firesTheStopLoss(@TempDir Path dir) {
		GamblerConfig config = winnableConfig();
		config.lossStreakAlert = 0;
		config.stopLossPercent = 20;

		BetLog log = new BetLog(dir.resolve("h.json"));
		addLosses(log, "Rick", 3, 1_000_000);

		Advice advice = Advisor.evaluate(config, log);

		assertEquals(Advice.Verdict.RED, advice.verdict);
		assertTrue(advice.lines.stream().anyMatch(line -> line.text().contains("stop-loss")));
	}

	@Test
	void flagsAnOpponentWhoWinsTooOften(@TempDir Path dir) {
		GamblerConfig config = winnableConfig();
		config.opponentMinSamples = 10;

		BetLog log = new BetLog(dir.resolve("h.json"));
		addLosses(log, "Rigged_Rick", 20, 50_000);

		List<OpponentFlag> flags = Advisor.opponentFlags(config, log);

		assertEquals(1, flags.size());
		assertTrue(flags.get(0).suspicious(), "20 losses out of 20 is not luck");
		assertTrue(flags.get(0).pValue() < 0.0001);
	}

	@Test
	void staysQuietAboutOpponentsWithTooLittleData(@TempDir Path dir) {
		GamblerConfig config = winnableConfig();
		config.opponentMinSamples = 12;

		BetLog log = new BetLog(dir.resolve("h.json"));
		addLosses(log, "Rick", 4, 10_000);

		assertFalse(Advisor.opponentFlags(config, log).get(0).suspicious());
	}
}
