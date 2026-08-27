package com.skullzz.donutgambler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.skullzz.donutgambler.chat.BetMatcher;
import com.skullzz.donutgambler.chat.PatternBuilder;
import com.skullzz.donutgambler.config.GamblerConfig;
import com.skullzz.donutgambler.data.BetRecord;
import com.skullzz.donutgambler.data.Outcome;

class BetMatcherTest {
	private final GamblerConfig config = GamblerConfig.createDefault();

	@Test
	void readsAWinLineIncludingTheOpponent() {
		BetRecord record = BetMatcher.match(config, "§aYou won §f$250,000 §afrom §fSteve_Miner", 1L);

		assertNotNull(record);
		assertEquals(Outcome.WIN, record.outcome);
		assertEquals(250_000, record.net);
		assertEquals("Steve_Miner", record.opponent);
		// The default coinflip prints winnings, so the stake is implied by the payout.
		assertEquals(250_000 / 0.95, record.stake, 1e-6);
	}

	@Test
	void readsALossLine() {
		BetRecord record = BetMatcher.match(config, "You lost $1.5m to Notch_", 1L);

		assertNotNull(record);
		assertEquals(Outcome.LOSS, record.outcome);
		assertEquals(-1_500_000, record.net);
		assertEquals(1_500_000, record.stake);
		assertEquals("Notch_", record.opponent);
	}

	@Test
	void ignoresUnrelatedChat() {
		assertNull(BetMatcher.match(config, "Steve_Miner joined the game", 1L));
		assertNull(BetMatcher.match(config, "<Steve> anyone want to cf?", 1L));
	}

	@Test
	void disabledGamesNeverMatch() {
		config.games.forEach(game -> game.enabled = false);
		assertNull(BetMatcher.match(config, "You won $10k from Steve", 1L));
	}

	@Test
	void readsTheBalanceLine() {
		assertEquals(12_345_678, BetMatcher.matchBalance(config, "Your balance: $12,345,678"));
		assertTrue(Double.isNaN(BetMatcher.matchBalance(config, "You won $10k")));
	}

	@Test
	void builtPatternsMatchTheirOwnLineAndGeneralise() {
		String line = "[Casino] You won $12,500 in Dice against Herobrine!";
		String regex = PatternBuilder.fromLine(line, "Herobrine");

		Matcher matcher = Pattern.compile(regex).matcher(BetMatcher.strip(line));
		assertTrue(matcher.find(), "generated pattern should match the line it came from");
		assertEquals("12,500", BetMatcher.group(matcher, "amount"));
		assertEquals("Herobrine", BetMatcher.group(matcher, "opponent"));

		assertTrue(Pattern.compile(regex).matcher("[Casino] You won $99k in Dice against SomeoneElse!").find(),
				"generated pattern should still match a different amount and player");
	}

	@Test
	void detectsTheOpponentNameInALine() {
		assertEquals("Herobrine", PatternBuilder.autoDetectName("You won $500 against Herobrine"));
		assertEquals("", PatternBuilder.autoDetectName("You won $500"));
	}
}
