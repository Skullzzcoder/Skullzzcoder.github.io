package com.skullzz.donutgambler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.skullzz.donutgambler.chat.MoneyParser;

class MoneyParserTest {
	@Test
	void parsesPlainAndSuffixedAmounts() {
		assertEquals(1250, MoneyParser.parse("$1,250"));
		assertEquals(7500, MoneyParser.parse("7.5k"));
		assertEquals(2_000_000, MoneyParser.parse("2M"));
		assertEquals(1.2e9, MoneyParser.parse("1.2 b"), 1e-3);
		assertEquals(1_000_000_000_000d, MoneyParser.parse("1t"));
	}

	@Test
	void returnsNaNWhenThereIsNoNumber() {
		assertTrue(Double.isNaN(MoneyParser.parse("nothing here")));
		assertTrue(Double.isNaN(MoneyParser.parse(null)));
	}

	@Test
	void formatsCompactly() {
		assertEquals("$1.50K", MoneyParser.format(1500));
		assertEquals("$2.50M", MoneyParser.format(2_500_000));
		assertEquals("-$1.20K", MoneyParser.formatSigned(-1200));
		assertEquals("+$2.50M", MoneyParser.formatSigned(2_500_000));
	}
}
