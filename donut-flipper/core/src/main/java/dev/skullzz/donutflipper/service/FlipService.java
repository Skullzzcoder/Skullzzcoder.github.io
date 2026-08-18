package dev.skullzz.donutflipper.service;

import dev.skullzz.donutflipper.config.Profile;
import dev.skullzz.donutflipper.model.Listing;
import dev.skullzz.donutflipper.scan.FlipCandidate;
import dev.skullzz.donutflipper.scan.FlipScanner;
import dev.skullzz.donutflipper.store.Database;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * The single entry point the UI talks to: "what should I buy right now".
 *
 * <p>Deliberately thin. Keeping the assembly of storage, valuation and scanning
 * in one small class means the Minecraft mod, the daemon's console output and
 * the backtest all exercise the same code path -- so what you see in game is
 * what the backtest measured, rather than a second implementation that drifts.
 */
public final class FlipService {

    private final Database db;
    private final ValuationService valuations;
    private final FlipScanner scanner;

    private volatile Profile profile;

    public FlipService(Database db, ValuationService valuations,
                       FlipScanner scanner, Profile profile) {
        this.db = db;
        this.valuations = valuations;
        this.scanner = scanner;
        this.profile = profile;
    }

    /** Current opportunities, best first. */
    public List<FlipCandidate> currentFlips() throws SQLException {
        valuations.refreshIfStale(Instant.now());
        List<Listing> active = db.activeListings();
        return scanner.scan(active, valuations::get, profile);
    }

    /** Opportunities not yet announced, for alerting. */
    public List<FlipCandidate> newFlips() throws SQLException {
        List<FlipCandidate> fresh = new java.util.ArrayList<>();
        for (FlipCandidate c : currentFlips()) {
            if (db.shouldAlert(c.listing().listingId(), c.score(), c.netProfit())) {
                fresh.add(c);
            }
        }
        return fresh;
    }

    public Profile profile() {
        return profile;
    }

    public void setProfile(Profile profile) {
        this.profile = profile;
    }

    public ValuationService valuations() {
        return valuations;
    }
}
