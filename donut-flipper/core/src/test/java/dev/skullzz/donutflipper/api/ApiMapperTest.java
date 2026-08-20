package dev.skullzz.donutflipper.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.skullzz.donutflipper.model.Listing;
import dev.skullzz.donutflipper.model.Sale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The wire format is not yet confirmed against a live server, so the mapper is
 * built to accept several plausible shapes. These tests pin every shape it
 * claims to handle.
 *
 * <p>This class matters more than its size suggests. A mapper that silently
 * understands nothing produces an empty database, and an empty database looks
 * exactly like a quiet market -- the failure is invisible for days. Each test
 * here is a shape that must either map correctly or be counted as skipped, never
 * silently dropped.
 */
class ApiMapperTest {

    private static final Instant OBSERVED = Instant.parse("2026-08-20T12:00:00Z");

    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    @Nested
    @DisplayName("envelope shapes")
    class Envelopes {

        @Test
        @DisplayName("a bare top-level array is read")
        void bareArray() {
            var r = ApiMapper.parseListings(json("""
                    [{"id":"1","seller":"Alex","price":5000,
                      "item":{"id":"minecraft:diamond","count":8}}]
                    """), OBSERVED);

            assertEquals(1, r.records().size());
            assertEquals(0, r.skipped());
        }

        @Test
        @DisplayName("a result-wrapped array is read")
        void resultWrapper() {
            var r = ApiMapper.parseListings(json("""
                    {"status":200,"result":[
                      {"id":"1","seller":"Alex","price":5000,
                       "item":{"id":"minecraft:diamond","count":8}}]}
                    """), OBSERVED);

            assertEquals(1, r.records().size());
        }

        @Test
        @DisplayName("a data-wrapped array is read")
        void dataWrapper() {
            var r = ApiMapper.parseListings(json("""
                    {"data":[{"id":"1","seller":"Alex","price":5000,
                       "item":{"id":"minecraft:diamond","count":8}}]}
                    """), OBSERVED);

            assertEquals(1, r.records().size());
        }

        @Test
        @DisplayName("an empty page yields nothing and is not an error")
        void emptyPage() {
            // The poller relies on this to know it has reached the last page.
            var r = ApiMapper.parseListings(json("{\"result\":[]}"), OBSERVED);
            assertTrue(r.records().isEmpty());
            assertEquals(0, r.skipped());
        }

        @Test
        @DisplayName("an unrecognised envelope yields nothing rather than throwing")
        void unknownEnvelope() {
            // A sweep must survive a surprise. Losing one page costs a minute;
            // an exception kills the scheduled task and stops collection entirely.
            assertDoesNotThrow(() -> ApiMapper.parseListings(
                    json("{\"unexpected\":\"shape\"}"), OBSERVED));
            assertDoesNotThrow(() -> ApiMapper.parseListings(json("null"), OBSERVED));
            assertDoesNotThrow(() -> ApiMapper.parseListings(json("\"a string\""), OBSERVED));
        }
    }

    @Nested
    @DisplayName("item location")
    class ItemShapes {

        @Test
        @DisplayName("item nested under 'item' is read")
        void nestedItem() {
            var r = ApiMapper.parseListings(json("""
                    [{"id":"1","seller":"Alex","price":5000,
                      "item":{"id":"minecraft:diamond","count":8}}]
                    """), OBSERVED);

            assertEquals("minecraft:diamond", r.records().get(0).item().materialId());
            assertEquals(8, r.records().get(0).item().count());
        }

        @Test
        @DisplayName("item flattened onto the record is read")
        void flattenedItem() {
            // Some feeds put material and count directly on the listing.
            var r = ApiMapper.parseListings(json("""
                    [{"listingId":"1","seller":"Alex","price":5000,
                      "material":"minecraft:diamond","count":8}]
                    """), OBSERVED);

            assertEquals(1, r.records().size());
            assertEquals("minecraft:diamond", r.records().get(0).item().materialId());
        }

        @Test
        @DisplayName("a bare material name gets the minecraft namespace applied")
        void namespaceIsAdded() {
            var r = ApiMapper.parseListings(json("""
                    [{"id":"1","price":100,"item":{"id":"DIAMOND","count":1}}]
                    """), OBSERVED);

            // Without this, "DIAMOND" and "minecraft:diamond" would be two
            // different items with two separate, half-empty price histories.
            assertEquals("minecraft:diamond", r.records().get(0).item().materialId());
        }
    }

    @Nested
    @DisplayName("enchantments")
    class Enchantments {

        @Test
        @DisplayName("object form {name: level} is read")
        void objectForm() {
            var r = ApiMapper.parseListings(json("""
                    [{"id":"1","price":100,"item":{"id":"minecraft:netherite_sword","count":1,
                      "enchantments":{"sharpness":5,"mending":1}}}]
                    """), OBSERVED);

            var ench = r.records().get(0).item().enchantments();
            assertEquals(2, ench.size());
            assertEquals(5, ench.get("sharpness"));
        }

        @Test
        @DisplayName("array form [{id, level}] is read")
        void arrayForm() {
            var r = ApiMapper.parseListings(json("""
                    [{"id":"1","price":100,"item":{"id":"minecraft:netherite_sword","count":1,
                      "enchantments":[{"id":"sharpness","level":5},{"id":"mending","level":1}]}}]
                    """), OBSERVED);

            var ench = r.records().get(0).item().enchantments();
            assertEquals(2, ench.size());
            assertEquals(5, ench.get("sharpness"));
        }

        @Test
        @DisplayName("both forms produce the same item key")
        void bothFormsAgree() {
            // If they disagreed, the same sword would price against two separate
            // histories depending on which endpoint it came from.
            var objectForm = ApiMapper.parseListings(json("""
                    [{"id":"1","price":100,"item":{"id":"minecraft:netherite_sword","count":1,
                      "enchantments":{"sharpness":5,"mending":1}}}]
                    """), OBSERVED).records().get(0);

            var arrayForm = ApiMapper.parseListings(json("""
                    [{"id":"2","price":100,"item":{"id":"minecraft:netherite_sword","count":1,
                      "enchantments":[{"id":"mending","level":1},{"id":"sharpness","level":5}]}}]
                    """), OBSERVED).records().get(0);

            assertEquals(objectForm.key().exact(), arrayForm.key().exact());
        }

        @Test
        @DisplayName("a non-numeric enchantment level is dropped, not fatal")
        void malformedLevel() {
            var r = ApiMapper.parseListings(json("""
                    [{"id":"1","price":100,"item":{"id":"minecraft:netherite_sword","count":1,
                      "enchantments":{"sharpness":"five"}}}]
                    """), OBSERVED);

            assertEquals(1, r.records().size());
            assertTrue(r.records().get(0).item().enchantments().isEmpty());
        }
    }

    @Nested
    @DisplayName("prices and timestamps")
    class Scalars {

        @Test
        @DisplayName("a comma-formatted price string is parsed")
        void formattedPrice() {
            var r = ApiMapper.parseListings(json("""
                    [{"id":"1","price":"1,250,000","item":{"id":"minecraft:elytra","count":1}}]
                    """), OBSERVED);

            assertEquals(1_250_000L, r.records().get(0).price());
        }

        @Test
        @DisplayName("epoch seconds and epoch milliseconds are told apart")
        void timestampUnits() {
            // 2026-08-20T12:00:00Z in each unit. Getting this wrong by 1000x puts
            // every sale either in 1970 or 55,000 years out, and the valuation
            // window silently contains nothing.
            var seconds = ApiMapper.parseSales(json("""
                    [{"id":"a","seller":"A","price":100,"timestamp":1787313600,
                      "item":{"id":"minecraft:diamond","count":1}}]
                    """), OBSERVED).records().get(0);

            var millis = ApiMapper.parseSales(json("""
                    [{"id":"b","seller":"A","price":100,"timestamp":1787313600000,
                      "item":{"id":"minecraft:diamond","count":1}}]
                    """), OBSERVED).records().get(0);

            assertEquals(seconds.soldAt(), millis.soldAt());
            assertEquals(2026, seconds.soldAt().atZone(java.time.ZoneOffset.UTC).getYear());
        }

        @Test
        @DisplayName("an ISO-8601 timestamp is parsed")
        void isoTimestamp() {
            var r = ApiMapper.parseSales(json("""
                    [{"id":"a","seller":"A","price":100,"timestamp":"2026-08-20T10:30:00Z",
                      "item":{"id":"minecraft:diamond","count":1}}]
                    """), OBSERVED);

            assertEquals(Instant.parse("2026-08-20T10:30:00Z"), r.records().get(0).soldAt());
        }

        @Test
        @DisplayName("a missing timestamp falls back to observation time")
        void missingTimestamp() {
            var r = ApiMapper.parseSales(json("""
                    [{"id":"a","seller":"A","price":100,
                      "item":{"id":"minecraft:diamond","count":1}}]
                    """), OBSERVED);

            assertEquals(OBSERVED, r.records().get(0).soldAt());
        }

        @Test
        @DisplayName("a future listing timestamp is clamped to observation time")
        void futureListedAtIsClamped() {
            // Clock skew must not create listings that appear to have negative age.
            var r = ApiMapper.parseListings(json("""
                    [{"id":"1","price":100,"timestamp":"2099-01-01T00:00:00Z",
                      "item":{"id":"minecraft:diamond","count":1}}]
                    """), OBSERVED);

            assertEquals(OBSERVED, r.records().get(0).firstSeen());
            assertEquals(0, r.records().get(0).ageSeconds(OBSERVED));
        }
    }

    @Nested
    @DisplayName("malformed records")
    class Malformed {

        @Test
        @DisplayName("a record with no id is skipped, not guessed at")
        void listingWithoutIdIsSkipped() {
            // listingId is the dedupe handle. A listing without one would be
            // re-alerted on every single sweep.
            var r = ApiMapper.parseListings(json("""
                    [{"seller":"Alex","price":5000,"item":{"id":"minecraft:diamond","count":8}}]
                    """), OBSERVED);

            assertTrue(r.records().isEmpty());
            assertEquals(1, r.skipped());
        }

        @Test
        @DisplayName("a record with no material is skipped")
        void noMaterialIsSkipped() {
            var r = ApiMapper.parseListings(json("""
                    [{"id":"1","seller":"Alex","price":5000,"item":{"count":8}}]
                    """), OBSERVED);

            assertEquals(1, r.skipped());
        }

        @Test
        @DisplayName("a negative or missing price is skipped")
        void badPriceIsSkipped() {
            var r = ApiMapper.parseListings(json("""
                    [{"id":"1","seller":"Alex","item":{"id":"minecraft:diamond","count":8}},
                     {"id":"2","seller":"Alex","price":-5,"item":{"id":"minecraft:diamond","count":8}}]
                    """), OBSERVED);

            assertTrue(r.records().isEmpty());
            assertEquals(2, r.skipped());
        }

        @Test
        @DisplayName("good records survive alongside bad ones")
        void partialFailureKeepsGoodRows() {
            // One malformed row in a 5,000-row sweep must not cost the sweep.
            var r = ApiMapper.parseListings(json("""
                    [{"id":"1","seller":"Alex","price":5000,"item":{"id":"minecraft:diamond","count":8}},
                     {"garbage":true},
                     {"id":"3","seller":"Nova","price":7000,"item":{"id":"minecraft:emerald","count":4}}]
                    """), OBSERVED);

            assertEquals(2, r.records().size());
            assertEquals(1, r.skipped());
        }

        @Test
        @DisplayName("a mostly-unmapped page is reported as unhealthy")
        void mostlyUnmappedIsFlagged() {
            // This is the signal that the aliases are wrong rather than the
            // market being quiet.
            var r = ApiMapper.parseListings(json("""
                    [{"id":"1","seller":"Alex","price":5000,"item":{"id":"minecraft:diamond","count":8}},
                     {"garbage":1},{"garbage":2},{"garbage":3}]
                    """), OBSERVED);

            assertEquals(1, r.records().size());
            assertEquals(3, r.skipped());
            assertFalse(r.healthy());
        }
    }

    @Nested
    @DisplayName("sales")
    class Sales {

        @Test
        @DisplayName("buyer identity is captured when present")
        void buyerCaptured() {
            var r = ApiMapper.parseSales(json("""
                    [{"id":"s1","seller":"Alex","buyer":"Nova","price":5000,
                      "item":{"id":"minecraft:diamond","count":8}}]
                    """), OBSERVED);

            Sale s = r.records().get(0);
            assertTrue(s.hasKnownBuyer());
            assertEquals("alex>nova", s.counterpartyPair());
        }

        @Test
        @DisplayName("a sale with no buyer still maps, with detection degraded")
        void missingBuyerIsTolerated() {
            var r = ApiMapper.parseSales(json("""
                    [{"id":"s1","seller":"Alex","price":5000,
                      "item":{"id":"minecraft:diamond","count":8}}]
                    """), OBSERVED);

            assertEquals(1, r.records().size());
            assertFalse(r.records().get(0).hasKnownBuyer());
        }

        @Test
        @DisplayName("a sale with no id gets a stable synthetic one")
        void syntheticIdIsStable() {
            // Must be stable across sweeps: a random id would re-insert the same
            // sale on every poll and inflate every sales-per-day figure.
            String payload = """
                    [{"seller":"Alex","buyer":"Nova","price":5000,"timestamp":1787313600,
                      "item":{"id":"minecraft:diamond","count":8}}]
                    """;

            String first = ApiMapper.parseSales(json(payload), OBSERVED)
                    .records().get(0).saleId();
            String second = ApiMapper.parseSales(json(payload), OBSERVED.plusSeconds(600))
                    .records().get(0).saleId();

            assertEquals(first, second,
                    "synthetic ids must not depend on when we happened to poll");
            assertTrue(first.startsWith("syn:"));
        }

        @Test
        @DisplayName("different sales get different synthetic ids")
        void syntheticIdsDiffer() {
            var r = ApiMapper.parseSales(json("""
                    [{"seller":"Alex","price":5000,"timestamp":1787313600,
                      "item":{"id":"minecraft:diamond","count":8}},
                     {"seller":"Nova","price":7000,"timestamp":1787313600,
                      "item":{"id":"minecraft:diamond","count":8}}]
                    """), OBSERVED);

            assertNotEquals(r.records().get(0).saleId(), r.records().get(1).saleId());
        }
    }

    @Test
    @DisplayName("a realistic full listing maps every field")
    void realisticListing() {
        var r = ApiMapper.parseListings(json("""
                {"status":200,"result":[{
                  "id":"a1b2c3",
                  "seller":"Skullzz",
                  "price":420000,
                  "timestamp":1787310000,
                  "item":{
                    "id":"minecraft:netherite_pickaxe",
                    "displayName":"§6Miner's Friend",
                    "count":1,
                    "damage":150,
                    "maxDamage":2031,
                    "enchantments":{"efficiency":5,"mending":1,"unbreaking":3,"fortune":3}
                  }}]}
                """), OBSERVED);

        assertEquals(1, r.records().size());
        Listing l = r.records().get(0);

        assertEquals("a1b2c3", l.listingId());
        assertEquals("Skullzz", l.seller());
        assertEquals(420_000L, l.price());
        assertEquals(4, l.item().enchantments().size());
        assertEquals(420_000.0, l.unitPrice(), 0.001);

        String key = l.key().exact();
        assertTrue(key.startsWith("netherite_pickaxe"), key);
        assertTrue(key.contains("efficiency=5"), key);
        assertTrue(key.contains("|w:LIGHT"), "150/2031 is light wear: " + key);
        assertTrue(key.contains("|n:custom"), "renamed item should be flagged: " + key);
    }
}
