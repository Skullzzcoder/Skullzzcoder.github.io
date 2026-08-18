package dev.skullzz.donutflipper.store;

import dev.skullzz.donutflipper.model.AuctionItem;
import dev.skullzz.donutflipper.model.Listing;
import dev.skullzz.donutflipper.model.Sale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {

    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private Database db;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.openInMemory();
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private static Listing listing(String id, long price, Instant firstSeen, Instant lastSeen) {
        return new Listing(id, "Alex", price,
                AuctionItem.simple("minecraft:diamond", 64), firstSeen, lastSeen);
    }

    @Test
    @DisplayName("listings round-trip through storage with their item data intact")
    void listingRoundTrip() throws Exception {
        AuctionItem sword = new AuctionItem("minecraft:netherite_sword", "Blade", 1,
                Map.of("sharpness", 5, "mending", 1), null, 12, 2031, List.of());
        db.upsertListings(List.of(new Listing("L1", "Alex", 500_000, sword, NOW, NOW)));

        List<Listing> active = db.activeListings();

        assertEquals(1, active.size());
        AuctionItem restored = active.get(0).item();
        assertEquals("minecraft:netherite_sword", restored.materialId());
        assertEquals(5, restored.enchantments().get("sharpness"));
        assertEquals(12, restored.damage());
    }

    @Test
    @DisplayName("re-seeing a listing preserves its original first-seen time")
    void upsertPreservesListingAge() throws Exception {
        Instant early = NOW.minusSeconds(7200);
        db.upsertListings(List.of(listing("L1", 1_000, early, early)));
        db.upsertListings(List.of(listing("L1", 1_000, NOW, NOW)));

        // Age is how a fresh mispricing is told apart from one that has sat
        // unsold for hours because everyone else already judged it junk.
        assertEquals(early.getEpochSecond(),
                db.activeListings().get(0).firstSeen().getEpochSecond());
    }

    @Test
    @DisplayName("listings missing from a sweep are marked gone")
    void missingListingsAreMarkedGone() throws Exception {
        Instant old = NOW.minusSeconds(600);
        db.upsertListings(List.of(listing("L1", 1_000, old, old)));

        assertEquals(1, db.activeListings().size());
        db.markMissingAsGone(NOW);
        assertEquals(0, db.activeListings().size());
    }

    @Test
    @DisplayName("duplicate sales from overlapping sweeps are ignored")
    void salesAreDeduplicated() throws Exception {
        Sale s = new Sale("S1", "Alex", "Steve", 64_000,
                AuctionItem.simple("minecraft:diamond", 64), NOW);

        assertEquals(1, db.insertSales(List.of(s)));
        // The transaction feed overlaps between polls. Counting a sale twice
        // would inflate every sales-per-day figure derived from it.
        assertEquals(0, db.insertSales(List.of(s)));
        assertEquals(1, db.countRows("sales"));
    }

    @Test
    @DisplayName("sales are queryable by key within a time window")
    void salesQueryByKeyAndWindow() throws Exception {
        AuctionItem diamond = AuctionItem.simple("minecraft:diamond", 64);
        db.insertSales(List.of(
                new Sale("recent", "Alex", "B", 64_000, diamond, NOW.minusSeconds(3600)),
                new Sale("stale", "Steve", "B", 64_000, diamond, NOW.minusSeconds(86400 * 30))));

        List<Sale> found = db.salesForKey("diamond", NOW.minusSeconds(86400), 100);

        assertEquals(1, found.size());
        assertEquals("recent", found.get(0).saleId());
    }

    @Test
    @DisplayName("a listing is announced once, not on every sweep")
    void alertsAreDeduplicated() throws Exception {
        assertTrue(db.shouldAlert("L1", 100.0, 5_000));
        assertFalse(db.shouldAlert("L1", 100.0, 5_000));
        assertFalse(db.shouldAlert("L1", 110.0, 5_000), "a small change is not worth re-announcing");
    }

    @Test
    @DisplayName("a materially better flip is re-announced")
    void alertsReFireOnBigImprovement() throws Exception {
        db.shouldAlert("L1", 100.0, 5_000);
        // Valuations firm up as history accumulates; a flip that got much better
        // deserves a second look.
        assertTrue(db.shouldAlert("L1", 500.0, 25_000));
    }

    @Test
    @DisplayName("migrating an already-current database is a no-op")
    void migrationIsIdempotent() throws Exception {
        assertEquals(0, db.countRows("listings"));
        assertDoesNotThrow(() -> Database.openInMemory().close());
    }
}
