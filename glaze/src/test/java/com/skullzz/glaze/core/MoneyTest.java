package com.skullzz.glaze.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MoneyTest {
	@Test
	void parsesGroupedDigits() {
		assertEquals(Optional.of(1234L), Money.parse("$1,234"));
		assertEquals(Optional.of(1234567L), Money.parse("Your balance: $1,234,567"));
	}

	@Test
	void parsesMagnitudeSuffixes() {
		assertEquals(Optional.of(12_500L), Money.parse("12.5k"));
		assertEquals(Optional.of(3_400_000L), Money.parse("$3.4m"));
		assertEquals(Optional.of(1_200_000_000L), Money.parse("1.2b"));
		assertEquals(Optional.of(2_000_000_000_000L), Money.parse("$2t"));
	}

	@Test
	void suffixesAreCaseInsensitive() {
		assertEquals(Money.parse("$3.4m"), Money.parse("$3.4M"));
		assertEquals(Money.parse("1.2b"), Money.parse("1.2B"));
	}

	@Test
	void stripsColourCodes() {
		assertEquals(Optional.of(3_400_000L), Money.parse("§a$3.4m§r"));
		assertEquals(Optional.of(500L), Money.parse("&e$500"));
	}

	@Test
	void handlesNegativeAmounts() {
		assertEquals(Optional.of(-500L), Money.parse("-$500"));
	}

	@Test
	void returnsEmptyWhenNoAmountPresent() {
		assertEquals(Optional.empty(), Money.parse("no numbers here"));
		assertEquals(Optional.empty(), Money.parse(""));
		assertEquals(Optional.empty(), Money.parse(null));
	}

	@Test
	void findsEveryAmountInALine() {
		List<Long> all = Money.parseAll("Bought 64x Diamond for $10,000, sold for $12.5k");
		assertTrue(all.contains(10_000L), () -> "expected 10000 in " + all);
		assertTrue(all.contains(12_500L), () -> "expected 12500 in " + all);
	}

	@Test
	void formatsCompactly() {
		assertEquals("$0", Money.compact(0));
		assertEquals("$999", Money.compact(999));
		assertEquals("$1k", Money.compact(1_000));
		assertEquals("$1.5k", Money.compact(1_500));
		assertEquals("$1.23m", Money.compact(1_234_567));
		assertEquals("$1.2b", Money.compact(1_200_000_000));
		assertEquals("-$5k", Money.compact(-5_000));
	}

	@Test
	void formatsFullWithSeparators() {
		assertEquals("$1,234,567", Money.full(1_234_567));
		assertEquals("-$500", Money.full(-500));
		assertEquals("$0", Money.full(0));
	}

	@Test
	void compactAndParseRoundTripOnCleanValues() {
		long[] values = {0, 999, 1_000, 1_500, 3_400_000, 1_200_000_000};

		for (long v : values) {
			assertEquals(Optional.of(v), Money.parse(Money.compact(v)),
					() -> "round trip failed for " + v);
		}
	}
}
