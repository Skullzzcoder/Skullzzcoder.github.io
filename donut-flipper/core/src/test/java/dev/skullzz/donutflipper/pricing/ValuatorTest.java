package dev.skullzz.donutflipper.pricing;

import dev.skullzz.donutflipper.fixture.MarketSimulator;
import dev.skullzz.donutflipper.model.AuctionItem;
import dev.skullzz.donutflipper.model.Sale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValuatorTest {

    private static final AuctionItem DIAMOND = AuctionItem.simple("minecraft:diamond", 1);
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private static final String KEY = "diamond";

    private final Valuator valuator = new Valuator();

    private static Sale sale(String id, String seller, long price, double hoursAgo) {
        return new Sale(id, seller, "Buyer" + id, price, DIAMOND,
                NOW.minusSeconds((long) (hoursAgo * 3600)));
    }

    /** Deep, diverse history spread over several days. */
    private static List<Sale> healthyHistory() {
        List<Sale> sales = new ArrayList<>();
        String[] sellers = {"Alex", "Steve", "Nova", "Kai", "Rhea", "Milo"};
        for (int i = 0; i < 24; i++) {
            sales.add(sale("s" + i, sellers[i % sellers.length], 1_000 + (i % 5) * 20, i * 5.0));
        }
        return sales;
    }

    @Test
    @DisplayName("fair value lands near the true clearing price")
    void fairValueTracksTheCluster() {
        Valuation v = valuator.valuate(KEY, healthyHistory(), NOW);

        assertTrue(v.usable());
        assertEquals(1_040, v.fairUnitPrice(), 60,
                "expected roughly the centre of the cluster, got " + v.fairUnitPrice());
    }

    @Test
    @DisplayName("deep diverse history earns HIGH confidence")
    void healthyHistoryIsHighConfidence() {
        Valuation v = valuator.valuate(KEY, healthyHistory(), NOW);
        assertEquals(Confidence.HIGH, v.confidence());
        assertTrue(v.distinctSellers() >= 3);
    }

    @Test
    @DisplayName("a burst from one seller in one hour is never trusted")
    void singleSellerBurstIsRefused() {
        // The signature of manipulation the pair-filter cannot prove, because
        // there is only one account visible. Refuse it outright.
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            sales.add(sale("burst" + i, "OnlyGuy", 900_000, i * 0.05));
        }

        Valuation v = valuator.valuate(KEY, sales, NOW);

        assertEquals(Confidence.NONE, v.confidence());
        assertFalse(v.usable(), "an unusable valuation can never produce a flip");
    }

    @Test
    @DisplayName("two sales are thin data, not a price")
    void thinHistoryIsLowConfidence() {
        List<Sale> sales = List.of(
                sale("a", "Alex", 1_000, 10),
                sale("b", "Steve", 1_100, 30));

        Valuation v = valuator.valuate(KEY, sales, NOW);
        assertEquals(Confidence.LOW, v.confidence());
    }

    @Test
    @DisplayName("no history at all yields NONE, not a guess")
    void emptyHistory() {
        Valuation v = valuator.valuate(KEY, List.of(), NOW);
        assertEquals(Confidence.NONE, v.confidence());
        assertFalse(v.usable());
    }

    @Test
    @DisplayName("sales older than the window are ignored")
    void staleSalesAreExcluded() {
        List<Sale> old = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            old.add(sale("old" + i, "Seller" + i, 1_000, 24 * 30));  // 30 days ago
        }

        Valuation v = valuator.valuate(KEY, old, NOW);
        assertEquals(Confidence.NONE, v.confidence());
    }

    @Test
    @DisplayName("wash trading does not move the valuation")
    void washTradesDoNotInflateValue() {
        // The whole point of the manipulation layer, asserted end to end:
        // an attacker pumps at 100x and the number must not follow.
        List<Sale> sales = new ArrayList<>(healthyHistory());
        for (int i = 0; i < 4; i++) {
            sales.add(sale("wash" + i, "Scammer", 100_000, i * 0.2));
        }

        Valuation v = valuator.valuate(KEY, sales, NOW);

        assertTrue(v.fairUnitPrice() < 1_500,
                "valuation was pumped to " + v.fairUnitPrice() + " -- the filter failed");
        assertTrue(v.rejectedSamples() > 0);
    }

    @Test
    @DisplayName("liquidity is measured against observed time, not the nominal window")
    void liquidityUsesObservedSpan() {
        // Collector has only been running ~2 hours. Four sales in that time is a
        // fast-moving item; dividing by a 7-day window would call it nearly dead.
        List<Sale> recent = List.of(
                sale("a", "Alex", 1_000, 0.5),
                sale("b", "Steve", 1_010, 1.0),
                sale("c", "Nova", 990, 1.5),
                sale("d", "Kai", 1_005, 2.0));

        Valuation v = valuator.valuate(KEY, recent, NOW);

        assertTrue(v.salesPerDay() > 5.0,
                "expected a high rate over a short observed span, got " + v.salesPerDay());
    }

    @Test
    @DisplayName("a falling market is flagged so the scanner can refuse it")
    void fallingTrendIsDetected() {
        List<Sale> sales = new ArrayList<>();
        String[] sellers = {"Alex", "Steve", "Nova", "Kai"};
        // Older half ~2000, newer half ~1000: a steep, sustained decline.
        for (int i = 0; i < 8; i++) {
            sales.add(sale("old" + i, sellers[i % 4], 2_000, 100 - i));
        }
        for (int i = 0; i < 8; i++) {
            sales.add(sale("new" + i, sellers[i % 4], 1_000, 20 - i));
        }

        Valuation v = valuator.valuate(KEY, sales, NOW);

        assertTrue(v.trendPerDay() < 0, "trend should be negative, got " + v.trendPerDay());
        assertTrue(v.falling(), "a halving over days must register as falling");
    }

    @Test
    @DisplayName("a stable market is not reported as falling")
    void stableMarketIsNotFalling() {
        Valuation v = valuator.valuate(KEY, healthyHistory(), NOW);
        assertFalse(v.falling());
    }

    @Test
    @DisplayName("probability of sale rises with liquidity")
    void saleOddsFollowLiquidity() {
        Valuation busy = new Valuation(KEY, 100, 20, 5, 10.0,
                Confidence.HIGH, 0, 0, List.of(), NOW);
        Valuation quiet = new Valuation(KEY, 100, 20, 5, 0.1,
                Confidence.HIGH, 0, 0, List.of(), NOW);

        assertTrue(busy.probabilityOfSaleWithin(1.0) > 0.99);
        assertTrue(quiet.probabilityOfSaleWithin(1.0) < 0.15);
    }

    @Test
    @DisplayName("against a simulated market, valuation recovers the true price")
    void recoversTruePriceFromSimulation() {
        // Ground truth is only available in simulation -- against live data there
        // is no way to tell a good valuation from a confident wrong one.
        MarketSimulator sim = new MarketSimulator(42);
        List<MarketSimulator.Archetype> archetypes = MarketSimulator.defaultArchetypes();
        MarketSimulator.Archetype diamonds = archetypes.get(0);

        List<Sale> sales = sim.generateSales(List.of(diamonds), NOW, Duration.ofDays(7));
        String key = diamonds.item().materialId().replace("minecraft:", "");
        Valuation v = valuator.valuate(key, sales, NOW);

        double error = Math.abs(v.fairUnitPrice() - diamonds.trueUnit()) / diamonds.trueUnit();
        assertTrue(error < 0.10,
                "expected within 10% of true value " + diamonds.trueUnit()
                        + ", got " + v.fairUnitPrice() + " (" + Math.round(error * 100) + "% off)");
        assertEquals(Confidence.HIGH, v.confidence());
    }
}
