package dev.skullzz.donutflipper.pricing;

import dev.skullzz.donutflipper.model.AuctionItem;
import dev.skullzz.donutflipper.model.Sale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The defence against being farmed. Each test is a specific attack.
 */
class ManipulationFilterTest {

    private static final AuctionItem DIAMOND = AuctionItem.simple("minecraft:diamond", 1);
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

    private static Sale sale(String id, String seller, String buyer, long price, long minutesAgo) {
        return new Sale(id, seller, buyer, price, DIAMOND, NOW.minusSeconds(minutesAgo * 60));
    }

    /** Honest baseline: many sellers, prices clustered around 1000. */
    private static List<Sale> honestMarket() {
        List<Sale> sales = new ArrayList<>();
        String[] sellers = {"Alex", "Steve", "Nova", "Kai", "Rhea", "Milo", "Juno", "Pax"};
        for (int i = 0; i < sellers.length; i++) {
            sales.add(sale("ok-" + i, sellers[i], "Buyer" + i, 1_000 + (i * 10), i * 30L));
        }
        return sales;
    }

    @Test
    @DisplayName("a seller trading repeatedly with one buyer is discarded entirely")
    void washTradingPairIsRemoved() {
        List<Sale> sales = honestMarket();
        // The attack: pump the apparent value with three self-dealt trades.
        for (int i = 0; i < 3; i++) {
            sales.add(sale("wash-" + i, "Scammer", "ScammerAlt", 10_000_000, i * 5L));
        }

        ManipulationFilter.Result result = ManipulationFilter.clean(sales);

        assertEquals(3, result.rejected(), "all three wash trades should go");
        assertTrue(result.accepted().stream().noneMatch(s -> s.seller().equals("Scammer")),
                "no trade from the coordinating pair may survive");
        assertFalse(result.reasons().isEmpty());
    }

    @Test
    @DisplayName("all of a flagged pair's trades go, not just the excess")
    void entirePairHistoryIsDiscarded() {
        // Keeping the first two would still let an attacker set the price with them.
        List<Sale> sales = honestMarket();
        for (int i = 0; i < 5; i++) {
            sales.add(sale("wash-" + i, "Scammer", "ScammerAlt", 9_000_000, i * 5L));
        }

        ManipulationFilter.Result result = ManipulationFilter.clean(sales);

        assertEquals(0, result.accepted().stream()
                .filter(s -> "Scammer".equals(s.seller())).count());
    }

    @Test
    @DisplayName("selling to yourself is not a market price")
    void selfTradeIsRemoved() {
        List<Sale> sales = honestMarket();
        sales.add(sale("self", "Loop", "loop", 50_000_000, 1));

        ManipulationFilter.Result result = ManipulationFilter.clean(sales);

        assertTrue(result.accepted().stream().noneMatch(s -> "Loop".equals(s.seller())));
    }

    @Test
    @DisplayName("one pair dominating volume is rejected even below the trade threshold")
    void dominantPairIsRejected() {
        // Only two trades, but they are most of the item's entire history.
        List<Sale> sales = new ArrayList<>();
        sales.add(sale("ok-0", "Alex", "Buyer0", 1_000, 60));
        sales.add(sale("w-0", "Scammer", "ScammerAlt", 8_000_000, 10));
        sales.add(sale("w-1", "Scammer", "ScammerAlt", 8_000_000, 20));

        ManipulationFilter.Result result = ManipulationFilter.clean(sales);

        assertTrue(result.accepted().stream().noneMatch(s -> "Scammer".equals(s.seller())));
    }

    @Test
    @DisplayName("price outliers are trimmed from both tails")
    void outliersAreTrimmedBothWays() {
        List<Sale> sales = honestMarket();
        sales.add(sale("high", "Whale", "W1", 90_000, 10));  // ~90x median
        sales.add(sale("low", "Gifter", "G1", 5, 20));       // a giveaway

        ManipulationFilter.Result result = ManipulationFilter.clean(sales);

        assertTrue(result.accepted().stream().noneMatch(s -> "Whale".equals(s.seller())),
                "a 90x sale is not evidence of market price");
        assertTrue(result.accepted().stream().noneMatch(s -> "Gifter".equals(s.seller())),
                "a 5-coin gift would drag the valuation down and hide real flips");
    }

    @Test
    @DisplayName("an honest market survives untouched")
    void honestDataIsNotDamaged() {
        // A filter that quietly eats good data is worse than no filter.
        ManipulationFilter.Result result = ManipulationFilter.clean(honestMarket());

        assertEquals(0, result.rejected());
        assertEquals(8, result.accepted().size());
    }

    @Test
    @DisplayName("empty and null input are handled")
    void emptyInput() {
        assertEquals(0, ManipulationFilter.clean(List.of()).accepted().size());
        assertEquals(0, ManipulationFilter.clean(null).accepted().size());
    }

    @Test
    @DisplayName("with no buyer identity, pair detection degrades instead of failing")
    void anonymousBuyersDoNotBreakTheFilter() {
        // If the API turns out not to expose buyers, the filter must still run --
        // outlier trimming carries the load on its own.
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            sales.add(sale("a-" + i, "Seller" + i, null, 1_000 + i, i * 30L));
        }
        sales.add(sale("spike", "Scammer", null, 5_000_000, 1));

        ManipulationFilter.Result result = ManipulationFilter.clean(sales);

        assertTrue(result.accepted().stream().noneMatch(s -> "Scammer".equals(s.seller())),
                "outlier trimming must still catch the inflated price");
    }
}
