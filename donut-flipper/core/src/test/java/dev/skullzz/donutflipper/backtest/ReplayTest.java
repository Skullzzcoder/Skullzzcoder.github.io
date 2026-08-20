package dev.skullzz.donutflipper.backtest;

import dev.skullzz.donutflipper.config.Profile;
import dev.skullzz.donutflipper.model.AuctionItem;
import dev.skullzz.donutflipper.model.Listing;
import dev.skullzz.donutflipper.model.Sale;
import dev.skullzz.donutflipper.pricing.Valuator;
import dev.skullzz.donutflipper.scan.FlipScanner;
import dev.skullzz.donutflipper.store.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The backtest is the gate that decides whether to trust this tool with real
 * coins, which makes a broken backtest worse than none at all -- it produces
 * confidence rather than merely failing to produce it.
 *
 * <p>The central test here is {@link #cannotSeeTheFuture()}. It is trivially
 * easy to write a backtest that looks spectacular by letting sales from after
 * the replay instant leak into the valuation; the tool then "predicts" prices it
 * was shown. If that test ever fails, every other number this class reports is
 * meaningless.
 */
class ReplayTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    /** The moment we rewind to. Sales before it are evidence; after it, outcome. */
    private static final Instant REPLAY_AT = NOW.minus(Duration.ofDays(2));

    private static final AuctionItem DIAMOND_STACK =
            AuctionItem.simple("minecraft:diamond", 64);
    private static final String DIAMOND_KEY = "diamond";

    private Database db;
    private Replay replay;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.openInMemory();
        replay = new Replay(db, new Valuator(), new FlipScanner(0.05));
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    /** Deep, diverse history ending just before the replay instant. */
    private List<Sale> historyBefore(long unitPrice) {
        String[] sellers = {"Alex", "Steve", "Nova", "Kai", "Rhea", "Milo"};
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            sales.add(new Sale("before-" + i, sellers[i % sellers.length], "Buyer" + i,
                    unitPrice * 64, DIAMOND_STACK,
                    REPLAY_AT.minus(Duration.ofHours(2 + i * 2))));
        }
        return sales;
    }

    /** What the market actually did after the replay instant. */
    private List<Sale> outcomeAfter(long unitPrice) {
        String[] sellers = {"Wren", "Pax", "Juno", "Sage"};
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            sales.add(new Sale("after-" + i, sellers[i % sellers.length], "Buyer" + i,
                    unitPrice * 64, DIAMOND_STACK,
                    REPLAY_AT.plus(Duration.ofHours(1 + i))));
        }
        return sales;
    }

    private Listing bargainListing(long totalPrice) {
        return new Listing("bargain", "Seller", totalPrice, DIAMOND_STACK,
                REPLAY_AT.minus(Duration.ofMinutes(30)), NOW);
    }

    @Test
    @DisplayName("a flip that would have worked is scored as a win")
    void profitableFlipIsAWin() throws Exception {
        db.insertSales(historyBefore(1_000));
        db.insertSales(outcomeAfter(1_000));
        db.upsertListings(List.of(bargainListing(30_000)));

        Replay.Report report = replay.run(REPLAY_AT, Duration.ofDays(1), Profile.BALANCED);

        assertEquals(1, report.evaluated(), "the underpriced listing should be proposed");
        assertEquals(1, report.resolved(), "the item traded afterwards, so the outcome is known");
        assertEquals(1, report.wins());
        assertEquals(1.0, report.hitRate(), 0.001);
        // 64k gross, 5% tax, bought at 30k -> 30.8k profit on 30k spent.
        assertEquals(1.027, report.realisedRoi(), 0.01);
    }

    @Test
    @DisplayName("THE CRITICAL TEST: valuations cannot see sales after the replay instant")
    void cannotSeeTheFuture() throws Exception {
        // All the evidence sits *after* the moment we rewind to. A leaky backtest
        // would value the diamond at 1,000 from these and confidently "find" the
        // bargain. An honest one has nothing to price with and proposes nothing.
        db.insertSales(outcomeAfter(1_000));
        db.upsertListings(List.of(bargainListing(30_000)));

        Replay.Report report = replay.run(REPLAY_AT, Duration.ofDays(1), Profile.BALANCED);

        assertEquals(0, report.evaluated(),
                "future sales leaked into the valuation -- every backtest number is invalid");
    }

    @Test
    @DisplayName("a flip that would have lost is scored as a loss")
    void unprofitableFlipIsALoss() throws Exception {
        // History says 1,000/unit, so buying a stack at 55k looks like a modest win.
        // The market then crashed to 300/unit and the trade lost badly.
        db.insertSales(historyBefore(1_000));
        db.insertSales(outcomeAfter(300));
        db.upsertListings(List.of(bargainListing(40_000)));

        Replay.Report report = replay.run(REPLAY_AT, Duration.ofDays(1), Profile.BALANCED);

        assertEquals(1, report.resolved());
        assertEquals(0, report.wins(), "a crash after purchase is a loss, not a win");
        assertTrue(report.realisedRoi() < 0,
                "realised ROI should be negative, got " + report.realisedRoi());
        // The tool must be capable of reporting that it was wrong. A backtest that
        // cannot produce a losing verdict is not measuring anything.
        assertFalse(report.trustworthy());
    }

    @Test
    @DisplayName("predicted and realised ROI are reported separately")
    void predictedAndRealisedAreDistinct() throws Exception {
        db.insertSales(historyBefore(1_000));
        db.insertSales(outcomeAfter(700));   // sold for less than predicted
        db.upsertListings(List.of(bargainListing(30_000)));

        Replay.Report report = replay.run(REPLAY_AT, Duration.ofDays(1), Profile.BALANCED);

        assertTrue(report.predictedRoi() > report.realisedRoi(),
                "the gap between prediction and reality is the whole point of the report");
    }

    @Test
    @DisplayName("a flip whose item never traded again is unresolved, not a win")
    void noOutcomeIsNotCountedAsSuccess() throws Exception {
        // Counting silence as success is how a backtest reports 100% on items
        // that in fact never sold and left capital stranded.
        db.insertSales(historyBefore(1_000));
        db.upsertListings(List.of(bargainListing(30_000)));

        Replay.Report report = replay.run(REPLAY_AT, Duration.ofDays(1), Profile.BALANCED);

        assertEquals(1, report.evaluated());
        assertEquals(0, report.resolved());
        assertEquals(0, report.wins());
        assertFalse(report.trustworthy());
    }

    @Test
    @DisplayName("listings that had already vanished are not bought in hindsight")
    void goneListingsAreExcluded() throws Exception {
        db.insertSales(historyBefore(1_000));
        db.insertSales(outcomeAfter(1_000));

        // Last seen three hours before the replay instant, then absent from the
        // sweep an hour before it -- so it was already gone and not available to
        // buy. Including it would flatter the results for free.
        db.upsertListings(List.of(new Listing("bargain", "Seller", 30_000, DIAMOND_STACK,
                REPLAY_AT.minus(Duration.ofHours(4)),
                REPLAY_AT.minus(Duration.ofHours(3)))));
        long laterSweep = db.upsertListings(List.of());
        db.markMissingAsGone(laterSweep, REPLAY_AT.minus(Duration.ofHours(1)));

        Replay.Report report = replay.run(REPLAY_AT, Duration.ofDays(1), Profile.BALANCED);

        assertEquals(0, report.evaluated());
    }

    @Test
    @DisplayName("listings created after the replay instant are not visible")
    void futureListingsAreExcluded() throws Exception {
        db.insertSales(historyBefore(1_000));
        db.insertSales(outcomeAfter(1_000));
        db.upsertListings(List.of(new Listing("later", "Seller", 30_000, DIAMOND_STACK,
                REPLAY_AT.plus(Duration.ofHours(6)), NOW)));

        Replay.Report report = replay.run(REPLAY_AT, Duration.ofDays(1), Profile.BALANCED);

        assertEquals(0, report.evaluated(),
                "a listing that did not exist yet cannot have been bought");
    }

    @Test
    @DisplayName("the horizon bounds how far ahead an outcome is looked for")
    void horizonIsRespected() throws Exception {
        db.insertSales(historyBefore(1_000));
        // The item only traded again five days later, well past a one-day horizon.
        db.insertSales(List.of(new Sale("late", "Wren", "B", 64_000, DIAMOND_STACK,
                REPLAY_AT.plus(Duration.ofDays(5)))));
        db.upsertListings(List.of(bargainListing(30_000)));

        Replay.Report shortHorizon =
                replay.run(REPLAY_AT, Duration.ofDays(1), Profile.BALANCED);
        assertEquals(0, shortHorizon.resolved());

        Replay.Report longHorizon =
                replay.run(REPLAY_AT, Duration.ofDays(7), Profile.BALANCED);
        assertEquals(1, longHorizon.resolved());
    }

    @Test
    @DisplayName("the trustworthy verdict demands a real sample, not one lucky trade")
    void trustworthyRequiresVolume() {
        // One perfect flip proves nothing. The gate needs enough resolved outcomes
        // for the hit rate to mean something.
        Replay.Report tiny = new Replay.Report(1, 1, 1, 1.0, 0.5, 0.5, 2.0);
        assertFalse(tiny.trustworthy());

        Replay.Report solid = new Replay.Report(40, 30, 24, 0.80, 0.45, 0.35, 4.0);
        assertTrue(solid.trustworthy());

        // High hit rate but the wins are tiny and the losses are not.
        Replay.Report churn = new Replay.Report(60, 50, 40, 0.80, 0.40, 0.01, 6.0);
        assertFalse(churn.trustworthy(),
                "a strategy that wins often but nets nothing is not a strategy");
    }

    @Test
    @DisplayName("an empty database reports nothing rather than throwing")
    void emptyDatabase() throws Exception {
        Replay.Report report = replay.run(REPLAY_AT, Duration.ofDays(1), Profile.BALANCED);

        assertEquals(0, report.evaluated());
        assertFalse(report.trustworthy());
        assertNotNull(report.toString());
    }
}
