package dev.skullzz.donutflipper.scan;

import dev.skullzz.donutflipper.config.Profile;
import dev.skullzz.donutflipper.model.AuctionItem;
import dev.skullzz.donutflipper.model.Listing;
import dev.skullzz.donutflipper.pricing.Confidence;
import dev.skullzz.donutflipper.pricing.Valuation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each gate in the scanner exists because skipping it loses money in a specific,
 * recognisable way. These tests name the failure each one prevents.
 */
class FlipScannerTest {

    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private final FlipScanner scanner = new FlipScanner(0.05);

    private static Listing listingAt(long price, int count) {
        return new Listing("L1", "Seller", price,
                AuctionItem.simple("minecraft:diamond", count), NOW, NOW);
    }

    private static Valuation valuation(double unit, double salesPerDay, Confidence c) {
        return new Valuation("diamond", unit, 20, 5, salesPerDay, c, 0.0, 0, List.of(), NOW);
    }

    @Test
    @DisplayName("a genuine bargain is found and its arithmetic is right")
    void findsAGenuineBargain() {
        // Worth 1000/unit, 64 in the stack = 64,000 gross. 5% tax = 60,800 net of
        // tax. Listed at 30,000, so profit is 30,800.
        Listing listing = listingAt(30_000, 64);
        FlipCandidate c = scanner.evaluate(listing, valuation(1_000, 10, Confidence.HIGH),
                Profile.BALANCED);

        assertNotNull(c);
        assertEquals(30_800, c.netProfit());
        assertEquals(30_800.0 / 30_000.0, c.roi(), 0.001);
        assertTrue(c.discountPercent() > 50);
    }

    @Test
    @DisplayName("an illiquid item is refused however large the paper margin")
    void refusesIlliquidItems() {
        // 10x margin, but it trades once every ten days. That is not a flip,
        // it is capital locked in an item nobody is asking for.
        FlipCandidate c = scanner.evaluate(listingAt(100_000, 1),
                valuation(1_000_000, 0.1, Confidence.HIGH), Profile.BALANCED);

        assertNull(c);
    }

    @Test
    @DisplayName("a thin-evidence valuation is refused under a HIGH-confidence profile")
    void refusesLowConfidence() {
        FlipCandidate c = scanner.evaluate(listingAt(30_000, 64),
                valuation(1_000, 10, Confidence.LOW), Profile.BALANCED);

        assertNull(c);
    }

    @Test
    @DisplayName("a falling market is refused -- the discount is a falling knife")
    void refusesFallingMarket() {
        Valuation falling = new Valuation("diamond", 1_000, 20, 5, 10.0,
                Confidence.HIGH, -0.20, 0, List.of(), NOW);

        assertNull(scanner.evaluate(listingAt(30_000, 64), falling, Profile.BALANCED));
    }

    @Test
    @DisplayName("a valuation with no data can never produce a flip")
    void refusesUnusableValuation() {
        assertNull(scanner.evaluate(listingAt(1, 64), Valuation.empty("diamond", NOW),
                Profile.BALANCED));
        assertNull(scanner.evaluate(listingAt(1, 64), null, Profile.BALANCED));
    }

    @Test
    @DisplayName("a zero-price listing is treated as a data error, not free money")
    void refusesZeroPrice() {
        assertNull(scanner.evaluate(listingAt(0, 64), valuation(1_000, 10, Confidence.HIGH),
                Profile.BALANCED));
    }

    @Test
    @DisplayName("the auction tax is subtracted, so thin margins are correctly rejected")
    void taxIsAppliedBeforeJudgingProfit() {
        // Gross 64,000 at 5% tax nets 60,800. Buying at 60,000 leaves 800 profit --
        // a 1.3% return that looks like a win only if you forget the tax.
        FlipCandidate c = scanner.evaluate(listingAt(60_000, 64),
                valuation(1_000, 10, Confidence.HIGH), Profile.BALANCED);

        assertNull(c, "a sub-2% margin must not pass a 25%-ROI profile");
    }

    @Test
    @DisplayName("results are ranked by liquidity-adjusted profit, not raw margin")
    void ranksByAdjustedProfit() {
        // Same net profit; one sells constantly, one barely moves. The liquid one
        // must rank first -- ranking on margin alone fills the top of the list
        // with items that never clear.
        Listing liquid = new Listing("liquid", "A", 30_000,
                AuctionItem.simple("minecraft:diamond", 64), NOW, NOW);
        Listing illiquid = new Listing("illiquid", "B", 30_000,
                AuctionItem.simple("minecraft:emerald", 64), NOW, NOW);

        Map<String, Valuation> book = Map.of(
                "diamond", valuation(1_000, 20.0, Confidence.HIGH),
                "emerald", new Valuation("emerald", 1_000, 20, 5, 1.2,
                        Confidence.HIGH, 0.0, 0, List.of(), NOW));

        List<FlipCandidate> results = scanner.scan(List.of(illiquid, liquid), book::get,
                Profile.BALANCED);

        assertEquals(2, results.size());
        assertEquals("liquid", results.get(0).listing().listingId());
        assertTrue(results.get(0).score() > results.get(1).score());
    }

    @Test
    @DisplayName("the whale profile ignores small flips and keeps big ones")
    void whaleProfileFiltersBySize() {
        Listing small = listingAt(30_000, 64);
        FlipCandidate viaBalanced = scanner.evaluate(small,
                valuation(1_000, 10, Confidence.HIGH), Profile.BALANCED);
        FlipCandidate viaWhale = scanner.evaluate(small,
                valuation(1_000, 10, Confidence.HIGH), Profile.WHALE);

        assertNotNull(viaBalanced, "30k profit is a fine balanced-profile flip");
        assertNull(viaWhale, "...and far below the whale profile's 250k floor");
    }

    @Test
    @DisplayName("the whale profile accepts slow-moving high-value gear")
    void whaleProfileAcceptsIlliquidBigTicket() {
        Listing elytra = new Listing("E", "Seller", 900_000,
                AuctionItem.simple("minecraft:elytra", 1), NOW, NOW);
        Valuation v = new Valuation("elytra", 1_800_000, 12, 4, 0.4,
                Confidence.HIGH, 0.0, 0, List.of(), NOW);

        assertNotNull(scanner.evaluate(elytra, v, Profile.WHALE));
        assertNull(scanner.evaluate(elytra, v, Profile.BALANCED),
                "balanced demands liquidity this item does not have");
    }
}
