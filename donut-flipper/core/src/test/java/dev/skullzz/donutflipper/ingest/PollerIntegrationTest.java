package dev.skullzz.donutflipper.ingest;

import com.sun.net.httpserver.HttpServer;
import dev.skullzz.donutflipper.api.DonutApiClient;
import dev.skullzz.donutflipper.api.RateLimiter;
import dev.skullzz.donutflipper.store.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end ingest test against a real local HTTP server.
 *
 * <p>Deliberately not a mock. This path -- HTTP, retry, JSON parsing, item
 * keying, batch insert, reconciliation -- is where an integration bug can quietly
 * produce an empty or subtly wrong database, and a mocked client would test the
 * one part that is certainly correct while skipping the parts that are not.
 */
class PollerIntegrationTest {

    private HttpServer server;
    private Database db;
    private AuctionPoller poller;

    /** path -> body. */
    private final Map<String, String> routes = new HashMap<>();
    /** path -> remaining number of 429s to return before succeeding. */
    private final Map<String, AtomicInteger> throttle = new HashMap<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            String path = exchange.getRequestURI().getPath();

            AtomicInteger remaining = throttle.get(path);
            if (remaining != null && remaining.getAndDecrement() > 0) {
                respond(exchange, 429, "{\"error\":\"rate limited\"}");
                return;
            }
            if ("/unauthorised".equals(path)) {
                respond(exchange, 401, "{\"error\":\"bad key\"}");
                return;
            }
            respond(exchange, 200, routes.getOrDefault(path, "{\"result\":[]}"));
        });
        server.start();

        db = Database.openInMemory();
        // Generous limit: this test measures ingest correctness, not throttling.
        DonutApiClient client = new DonutApiClient("test-key", new RateLimiter(6000), baseUrl());
        poller = new AuctionPoller(client, db);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        db.close();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String listingPage(String... ids) {
        StringBuilder sb = new StringBuilder("{\"result\":[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("""
                    {"id":"%s","seller":"Seller%s","price":%d,
                     "item":{"id":"minecraft:diamond","count":64}}"""
                    .formatted(ids[i], ids[i], 30_000 + i));
        }
        return sb.append("]}").toString();
    }

    private static String salePage(String... ids) {
        StringBuilder sb = new StringBuilder("{\"result\":[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("""
                    {"id":"%s","seller":"S%s","buyer":"B%s","price":64000,"timestamp":1787313600,
                     "item":{"id":"minecraft:diamond","count":64}}"""
                    .formatted(ids[i], ids[i], ids[i]));
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("pagination walks pages and stops at the first empty one")
    void paginationStopsAtEmptyPage() throws Exception {
        routes.put("/auction/list/1", listingPage("a", "b"));
        routes.put("/auction/list/2", listingPage("c"));
        // page 3 falls through to the empty default

        AuctionPoller.SweepResult result = poller.sweepListings();

        assertEquals(3, result.pages(), "should have probed one page past the last full one");
        assertEquals(3, result.records());
        assertEquals(3, db.activeListings().size());
    }

    @Test
    @DisplayName("reconciliation runs after the whole sweep, not per page")
    void reconciliationWaitsForTheFullSweep() throws Exception {
        // The bug this guards against: marking listings gone after page 1 would
        // wipe out everything that only appears on page 2.
        routes.put("/auction/list/1", listingPage("a", "b"));
        routes.put("/auction/list/2", listingPage("c", "d"));

        poller.sweepListings();

        assertEquals(4, db.activeListings().size(),
                "listings from later pages must survive the sweep that found them");
    }

    @Test
    @DisplayName("a listing that leaves the feed is marked gone on the next sweep")
    void vanishedListingIsMarkedGone() throws Exception {
        routes.put("/auction/list/1", listingPage("a", "b"));
        poller.sweepListings();
        assertEquals(2, db.activeListings().size());

        // "b" sold or was cancelled.
        routes.put("/auction/list/1", listingPage("a"));
        poller.sweepListings();

        List<String> stillActive = db.activeListings().stream()
                .map(l -> l.listingId()).toList();
        assertEquals(List.of("a"), stillActive);
    }

    @Test
    @DisplayName("re-seeing a listing does not duplicate it")
    void repeatedSweepsDoNotDuplicate() throws Exception {
        routes.put("/auction/list/1", listingPage("a", "b"));

        poller.sweepListings();
        poller.sweepListings();
        poller.sweepListings();

        assertEquals(2, db.countRows("listings"));
    }

    @Test
    @DisplayName("overlapping transaction pages are deduplicated")
    void transactionsAreDeduplicated() throws Exception {
        routes.put("/auction/transactions/1", salePage("s1", "s2"));

        AuctionPoller.SweepResult first = poller.sweepTransactions();
        assertEquals(2, first.stored());

        // The feed overlaps between polls; the same sales come back.
        AuctionPoller.SweepResult second = poller.sweepTransactions();
        assertEquals(0, second.stored(), "already-known sales must not be counted twice");
        assertEquals(2, db.countRows("sales"));
    }

    @Test
    @DisplayName("the transaction sweep stops once it reaches known history")
    void transactionSweepStopsAtKnownHistory() throws Exception {
        routes.put("/auction/transactions/1", salePage("s1"));
        routes.put("/auction/transactions/2", salePage("s2"));
        routes.put("/auction/transactions/3", salePage("s3"));
        poller.sweepTransactions();

        requestCount.set(0);
        poller.sweepTransactions();

        // Page 1 is all known, so there is no reason to keep reading backwards
        // through history we already hold -- that would spend the request budget
        // re-reading the past instead of watching the present.
        assertTrue(requestCount.get() <= 2,
                "expected an early stop, made " + requestCount.get() + " requests");
    }

    @Test
    @DisplayName("a 429 is retried and the sweep still completes")
    void rateLimitIsRetried() throws Exception {
        throttle.put("/auction/list/1", new AtomicInteger(1));
        routes.put("/auction/list/1", listingPage("a"));

        AuctionPoller.SweepResult result = poller.sweepListings();

        assertEquals(1, result.records(), "the sweep should recover from one 429");
    }

    @Test
    @DisplayName("a bad API key fails immediately instead of burning retries")
    void badKeyDoesNotRetry() throws Exception {
        DonutApiClient client =
                new DonutApiClient("wrong-key", new RateLimiter(6000), baseUrl());
        requestCount.set(0);

        DonutApiClient.ApiException e = assertThrows(DonutApiClient.ApiException.class,
                () -> client.get("/unauthorised"));

        assertEquals(401, e.statusCode());
        assertEquals(1, requestCount.get(),
                "retrying a rejected key just wastes the request budget");
        assertTrue(e.getMessage().contains("/api"),
                "the error should say how to fix it: " + e.getMessage());
    }

    @Test
    @DisplayName("a page of unmappable records is flagged as suspicious")
    void unmappablePageIsFlagged() throws Exception {
        // The signal that the field aliases are wrong rather than the market
        // being quiet -- the failure mode that otherwise goes unnoticed for days.
        routes.put("/auction/list/1", """
                {"result":[{"nonsense":1},{"nonsense":2},{"nonsense":3},
                           {"id":"ok","price":100,"item":{"id":"minecraft:diamond","count":1}}]}
                """);

        AuctionPoller.SweepResult result = poller.sweepListings();

        assertTrue(result.skipped() >= 3);
        assertTrue(result.suspicious(),
                "a mostly-unmapped page must be reported, not silently accepted");
    }

    @Test
    @DisplayName("collected listings are keyed and valuable downstream")
    void collectedDataIsUsable() throws Exception {
        routes.put("/auction/list/1", """
                {"result":[{"id":"x","seller":"Alex","price":420000,
                  "item":{"id":"minecraft:netherite_sword","count":1,
                          "enchantments":{"sharpness":5,"mending":1}}}]}
                """);

        poller.sweepListings();

        var listing = db.activeListings().get(0);
        assertEquals("netherite_sword|e:mending=1,sharpness=5", listing.key().exact());
        assertEquals(420_000.0, listing.unitPrice(), 0.001);
    }
}
