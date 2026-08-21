package com.skullzz.glaze.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PriceBookTest {
	private static final long T0 = 1_700_000_000_000L;

	@Test
	void normalisesPricesToSingleItems() {
		PriceBook book = new PriceBook();
		book.record("Diamond", 640_000, 64, T0, "ah");

		assertEquals(10_000L, book.stats("Diamond").orElseThrow().median());
	}

	@Test
	void itemKeysAreCaseInsensitive() {
		PriceBook book = new PriceBook();
		book.record("Netherite Ingot", 1_000, 1, T0, "ah");

		assertTrue(book.stats("netherite ingot").isPresent());
		assertTrue(book.stats("  NETHERITE INGOT  ").isPresent());
	}

	@Test
	void computesQuantilesFromObservedPrices() {
		PriceBook book = new PriceBook();

		for (long price : new long[]{100, 200, 300, 400, 500}) {
			book.record("Elytra", price, 1, T0, "ah");
		}

		PriceStats stats = book.stats("Elytra").orElseThrow();
		assertEquals(5, stats.samples());
		assertEquals(100L, stats.low());
		assertEquals(500L, stats.high());
		assertEquals(300L, stats.median());
		assertEquals(200L, stats.p25());
		assertEquals(400L, stats.p75());
	}

	@Test
	void quantilesAlwaysReturnARealObservedPrice() {
		long[] sorted = {10, 20, 30, 40};

		for (double q : new double[]{0.0, 0.25, 0.5, 0.75, 1.0}) {
			long value = PriceBook.quantile(sorted, q);
			boolean found = false;

			for (long s : sorted) {
				found |= s == value;
			}

			assertTrue(found, () -> "quantile " + q + " produced unseen price " + value);
		}
	}

	@Test
	void newestTracksTheLatestObservationNotTheLowest() {
		PriceBook book = new PriceBook();
		book.record("Totem of Undying", 500, 1, T0, "ah");
		book.record("Totem of Undying", 900, 1, T0 + 60_000, "ah");

		assertEquals(900L, book.stats("Totem of Undying").orElseThrow().newest());
	}

	@Test
	void dropsSamplesOlderThanRetentionWindow() {
		PriceBook book = new PriceBook();
		book.setRetention(100, 1_000);
		book.record("Ender Pearl", 50, 1, T0, "ah");
		book.record("Ender Pearl", 70, 1, T0 + 5_000, "ah");

		PriceStats stats = book.stats("Ender Pearl").orElseThrow();
		assertEquals(1, stats.samples());
		assertEquals(70L, stats.median());
	}

	@Test
	void keepsOnlyTheNewestSamplesWhenOverCapacity() {
		PriceBook book = new PriceBook();
		book.setRetention(3, PriceBook.DEFAULT_MAX_AGE_MILLIS);

		for (int i = 0; i < 10; i++) {
			book.record("Shulker Box", 100L + i, 1, T0 + i * 1_000L, "ah");
		}

		PriceStats stats = book.stats("Shulker Box").orElseThrow();
		assertEquals(3, stats.samples());
		assertEquals(107L, stats.low());
		assertEquals(109L, stats.high());
	}

	@Test
	void ignoresNonsenseInput() {
		PriceBook book = new PriceBook();
		book.record("", 100, 1, T0, "ah");
		book.record("Stone", 0, 1, T0, "ah");
		book.record("Stone", 100, 0, T0, "ah");

		assertEquals(0, book.sampleCount());
	}

	@Test
	void dealDetectionNeedsEnoughSamples() {
		PriceStats thin = new PriceStats(2, 100, 100, 1_000, 1_000, 1_000, 100);
		assertFalse(thin.isDeal(100, 0.7, 5), "two samples should not be enough to call a deal");

		PriceStats solid = new PriceStats(20, 100, 100, 1_000, 1_000, 1_000, 100);
		assertTrue(solid.isDeal(500, 0.7, 5));
		assertFalse(solid.isDeal(900, 0.7, 5));
	}

	@Test
	void marginIsPositiveWhenUnderMedian() {
		PriceStats stats = new PriceStats(10, 100, 400, 1_000, 1_500, 2_000, 900);
		assertEquals(0.5, stats.marginAgainstMedian(500), 1e-9);
		assertEquals(-0.5, stats.marginAgainstMedian(1_500), 1e-9);
	}

	@Test
	void survivesRoundTripThroughSnapshot() {
		PriceBook book = new PriceBook();
		book.record("Diamond", 1_000, 1, T0, "ah");

		PriceBook restored = new PriceBook();
		restored.loadFrom(book.snapshot());

		assertEquals(1_000L, restored.stats("Diamond").orElseThrow().median());
	}
}
