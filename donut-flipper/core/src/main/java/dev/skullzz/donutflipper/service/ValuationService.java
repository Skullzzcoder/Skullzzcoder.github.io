package dev.skullzz.donutflipper.service;

import dev.skullzz.donutflipper.model.Sale;
import dev.skullzz.donutflipper.pricing.Valuation;
import dev.skullzz.donutflipper.pricing.Valuator;
import dev.skullzz.donutflipper.store.Database;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes and caches a valuation per item key.
 *
 * <p>Recomputing on every UI refresh would mean a database query and a full
 * statistical pass per distinct item, several times a second, while the game is
 * running. Valuations move on the timescale of hours, so they are refreshed on a
 * timer and served from memory in between.
 */
public final class ValuationService {

    /** How long a cached valuation stays fresh. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final Database db;
    private final Valuator valuator;

    private final Map<String, Valuation> cache = new HashMap<>();
    private volatile Instant lastRefresh = Instant.EPOCH;

    public ValuationService(Database db, Valuator valuator) {
        this.db = db;
        this.valuator = valuator;
    }

    /** Recomputes every key that has recent sale history. */
    public synchronized int refresh(Instant now) throws SQLException {
        Instant windowStart = valuator.windowStart(now);
        List<String> keys = db.keysWithSales(windowStart);

        Map<String, Valuation> rebuilt = new HashMap<>(keys.size());
        for (String key : keys) {
            List<Sale> sales = db.salesForKey(key, windowStart, Valuator.MAX_SAMPLES);
            rebuilt.put(key, valuator.valuate(key, sales, now));
        }

        cache.clear();
        cache.putAll(rebuilt);
        lastRefresh = now;
        return rebuilt.size();
    }

    public synchronized void refreshIfStale(Instant now) throws SQLException {
        if (Duration.between(lastRefresh, now).compareTo(CACHE_TTL) >= 0) {
            refresh(now);
        }
    }

    /**
     * Looks up a valuation. Returns an explicitly-empty one for unknown keys
     * rather than null, so callers cannot accidentally treat "never seen" as
     * "worth nothing" or dereference their way into a crash mid-sweep.
     */
    public synchronized Valuation get(String exactKey) {
        Valuation v = cache.get(exactKey);
        return v != null ? v : Valuation.empty(exactKey, Instant.now());
    }

    public synchronized int size() {
        return cache.size();
    }

    public Instant lastRefresh() {
        return lastRefresh;
    }
}
