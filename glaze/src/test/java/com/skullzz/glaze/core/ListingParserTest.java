package com.skullzz.glaze.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ListingParserTest {
	private final ListingParser parser = ListingParser.withDefaults();

	@Test
	void readsPriceAndSellerFromLore() {
		AuctionListing listing = parser.parse("Netherite Ingot", 16,
				List.of("Price: $4,200,000", "Seller: Notch", "Time left: 2h 15m"), 12).orElseThrow();

		assertEquals("Netherite Ingot", listing.itemName());
		assertEquals(4_200_000L, listing.price());
		assertEquals("Notch", listing.seller());
		assertEquals(16, listing.quantity());
		assertEquals(12, listing.slot());
	}

	@Test
	void unitPriceDividesByStackSize() {
		AuctionListing listing = parser.parse("Diamond", 64,
				List.of("Price: $640,000"), 0).orElseThrow();

		assertEquals(10_000L, listing.unitPrice());
	}

	@Test
	void readsSuffixedPrices() {
		AuctionListing listing = parser.parse("Elytra", 1,
				List.of("§7Buy Now: §a$12.5m"), 3).orElseThrow();

		assertEquals(12_500_000L, listing.price());
	}

	@Test
	void skipsSlotsWithoutAPrice() {
		assertTrue(parser.parse("Gray Stained Glass Pane", 1, List.of(" "), 0).isEmpty());
		assertTrue(parser.parse("Next Page", 1, List.of("Click to continue"), 53).isEmpty());
	}

	@Test
	void skipsEmptyInput() {
		assertTrue(parser.parse("", 1, List.of("Price: $10"), 0).isEmpty());
		assertTrue(parser.parse("Diamond", 1, null, 0).isEmpty());
	}

	@Test
	void listingWithoutSellerStillParses() {
		AuctionListing listing = parser.parse("Ender Pearl", 16,
				List.of("Price: $50,000"), 5).orElseThrow();

		assertEquals("", listing.seller());
		assertTrue(listing.hasPrice());
	}
}
