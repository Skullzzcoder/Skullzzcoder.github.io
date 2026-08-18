package dev.skullzz.donutflipper.ingest;

import com.google.gson.JsonElement;
import dev.skullzz.donutflipper.api.ApiMapper;
import dev.skullzz.donutflipper.api.DonutApiClient;
import dev.skullzz.donutflipper.model.Listing;
import dev.skullzz.donutflipper.model.Sale;
import dev.skullzz.donutflipper.store.Database;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Walks the paginated auction endpoints and writes what it finds to storage.
 *
 * <p>The sale-history feed is the more important of the two sweeps, even though
 * the listing feed is the one you look at. Listings tell you what is for sale
 * right now; sales are the only thing that can tell you what any of it is worth.
 * A day of listing data with no transaction data is worth nothing at all.
 */
public final class AuctionPoller {

    private static final Logger LOG = Logger.getLogger(AuctionPoller.class.getName());

    /**
     * Hard stop on pagination. Without it, an endpoint that echoes the last page
     * forever -- or simply has more pages than the rate limit can afford --
     * would consume the entire request budget in one sweep and starve everything else.
     */
    private static final int MAX_PAGES = 60;

    private final DonutApiClient client;
    private final Database db;

    public AuctionPoller(DonutApiClient client, Database db) {
        this.client = client;
        this.db = db;
    }

    /**
     * @param pages    pages fetched
     * @param records  records parsed
     * @param stored   records newly written
     * @param skipped  records the mapper could not understand
     */
    public record SweepResult(int pages, int records, int stored, int skipped) {
        /**
         * A sweep where most records failed to map means the field aliases are
         * wrong, not that the market is quiet. Worth shouting about, because the
         * silent-failure version of this looks identical to a healthy empty market.
         */
        public boolean suspicious() {
            return records > 0 && skipped > records / 2;
        }
    }

    /** Sweeps every page of current listings and reconciles what disappeared. */
    public SweepResult sweepListings() throws Exception {
        Instant sweepStart = Instant.now();
        List<Listing> all = new ArrayList<>();
        int pages = 0;
        int skipped = 0;

        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonElement root = client.auctionList(page);
            ApiMapper.Result<Listing> parsed = ApiMapper.parseListings(root, sweepStart);
            pages++;
            skipped += parsed.skipped();

            if (parsed.records().isEmpty()) {
                break;
            }
            all.addAll(parsed.records());
        }

        if (!all.isEmpty()) {
            db.upsertListings(all);
            // Anything not touched by this sweep has left the auction house.
            // Reconciling only after a complete sweep matters: doing it per page
            // would mark every listing on page 2 as gone while page 1 was written.
            db.markMissingAsGone(sweepStart);
        }

        SweepResult result = new SweepResult(pages, all.size(), all.size(), skipped);
        if (result.suspicious()) {
            LOG.warning("Listing sweep mapped " + all.size() + " records but skipped "
                    + skipped + " -- ApiMapper aliases are probably wrong. Run the probe.");
        }
        return result;
    }

    /**
     * Sweeps completed transactions.
     *
     * <p>Stops early once a page yields nothing new. The feed is ordered newest
     * first and overlaps heavily between polls, so once we reach already-known
     * sales, every remaining page is history we already hold -- continuing would
     * spend the request budget re-reading the past instead of watching the present.
     */
    public SweepResult sweepTransactions() throws Exception {
        Instant now = Instant.now();
        int pages = 0;
        int records = 0;
        int stored = 0;
        int skipped = 0;

        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonElement root = client.auctionTransactions(page);
            ApiMapper.Result<Sale> parsed = ApiMapper.parseSales(root, now);
            pages++;
            skipped += parsed.skipped();

            if (parsed.records().isEmpty()) {
                break;
            }
            records += parsed.records().size();
            int inserted = db.insertSales(parsed.records());
            stored += inserted;

            if (inserted == 0 && page > 1) {
                break;
            }
        }

        SweepResult result = new SweepResult(pages, records, stored, skipped);
        if (result.suspicious()) {
            LOG.warning("Transaction sweep mapped " + records + " records but skipped "
                    + skipped + " -- ApiMapper aliases are probably wrong. Run the probe.");
        }
        return result;
    }
}
