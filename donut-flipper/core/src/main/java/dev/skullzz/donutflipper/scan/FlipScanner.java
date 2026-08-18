package dev.skullzz.donutflipper.scan;

import dev.skullzz.donutflipper.config.Profile;
import dev.skullzz.donutflipper.model.Listing;
import dev.skullzz.donutflipper.pricing.Valuation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Joins live listings against valuations and ranks what is worth buying.
 *
 * <p>The ranking is expected profit adjusted for how likely the item is to
 * actually sell and how much the valuation deserves to be believed. Ranking by
 * raw margin instead -- which is the obvious first implementation -- fills the
 * top of the list with items showing enormous paper profits that never move,
 * because a large discount on an illiquid item usually means the last person to
 * price it was wrong, not that free money is sitting there.
 */
public final class FlipScanner {

    /**
     * Horizon used when converting liquidity into odds of selling. A day is the
     * span over which tying up capital still feels like flipping rather than
     * investing.
     */
    public static final double SALE_HORIZON_DAYS = 1.0;

    private final double taxRate;

    /**
     * @param taxRate auction house cut as a fraction. Overestimating it makes the
     *                scanner miss marginal flips; underestimating it makes it
     *                recommend losing trades, so it defaults high until confirmed.
     */
    public FlipScanner(double taxRate) {
        this.taxRate = Math.min(0.5, Math.max(0.0, taxRate));
    }

    /**
     * @param listings   currently active listings
     * @param valuations resolves an exact item key to its valuation
     * @param profile    thresholds a candidate must clear
     */
    public List<FlipCandidate> scan(List<Listing> listings,
                                    Function<String, Valuation> valuations,
                                    Profile profile) {
        List<FlipCandidate> out = new ArrayList<>();

        for (Listing listing : listings) {
            Valuation valuation = valuations.apply(listing.key().exact());
            FlipCandidate candidate = evaluate(listing, valuation, profile);
            if (candidate != null) {
                out.add(candidate);
            }
        }

        out.sort(Comparator.comparingDouble(FlipCandidate::score).reversed());
        return out;
    }

    /**
     * Scores one listing, or returns null if it fails any gate.
     *
     * <p>Gates are checked cheapest-first, and each one exists because skipping
     * it produces a specific, recognisable way to lose money.
     */
    public FlipCandidate evaluate(Listing listing, Valuation valuation, Profile profile) {
        if (valuation == null || !valuation.usable()) {
            return null;
        }
        // Never act on a price we do not have the evidence to defend.
        if (!valuation.confidence().atLeast(profile.minConfidence())) {
            return null;
        }
        // Illiquid items are not flips. The margin may be real and you will still
        // be holding the item next week.
        if (valuation.salesPerDay() < profile.minSalesPerDay()) {
            return null;
        }
        // A steep discount on something in freefall is not an opportunity; the
        // "fair value" it is measured against is already stale.
        if (valuation.falling()) {
            return null;
        }
        if (profile.maxBuyPrice() > 0 && listing.price() > profile.maxBuyPrice()) {
            return null;
        }

        long buyPrice = listing.price();
        if (buyPrice <= 0) {
            // Free or malformed listings are almost always a data error, and
            // acting on one is how a bot buys something that does not exist.
            return null;
        }

        double gross = valuation.fairUnitPrice() * listing.item().count();
        double net = gross * (1.0 - taxRate) - buyPrice;
        if (net < profile.minNetProfit()) {
            return null;
        }

        double roi = net / buyPrice;
        if (roi < profile.minRoi()) {
            return null;
        }

        double saleOdds = valuation.probabilityOfSaleWithin(SALE_HORIZON_DAYS);
        double score = net * saleOdds * valuation.confidence().weight();
        if (score <= 0) {
            return null;
        }

        return new FlipCandidate(listing, valuation, Math.round(net), roi, saleOdds, score);
    }

    public double taxRate() {
        return taxRate;
    }
}
