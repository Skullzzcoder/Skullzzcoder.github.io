package dev.skullzz.donutflipper.pricing;

import dev.skullzz.donutflipper.model.Sale;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns sale history into a fair price.
 *
 * <p>Built on three rules, each earned the hard way:
 *
 * <ol>
 *   <li><b>Only completed sales count.</b> Listings show what sellers hope for.
 *       An item listed at 10x market sits there forever, still appearing in the
 *       feed, still dragging any listing-based average upward.</li>
 *   <li><b>Order statistics, never a mean.</b> Auction prices are right-skewed;
 *       an average chases the whales and overvalues everything.</li>
 *   <li><b>Confidence travels with the number.</b> A price from six sellers over
 *       a week and a price from one seller an hour ago are not the same claim,
 *       and the scanner must be able to tell them apart.</li>
 * </ol>
 */
public final class Valuator {

    /** Preferred lookback. Long enough to gather evidence, short enough to track real moves. */
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(7);

    /** Ceiling on samples per key, newest first. */
    public static final int MAX_SAMPLES = 120;

    private static final int HIGH_MIN_SALES = 8;
    private static final int HIGH_MIN_SELLERS = 3;
    private static final double HIGH_MIN_SALES_PER_DAY = 0.5;

    private static final int MEDIUM_MIN_SALES = 4;
    private static final int MEDIUM_MIN_SELLERS = 2;

    private final Duration window;

    public Valuator() {
        this(DEFAULT_WINDOW);
    }

    public Valuator(Duration window) {
        this.window = window;
    }

    public Instant windowStart(Instant now) {
        return now.minus(window);
    }

    /**
     * Values one item key from its sale history.
     *
     * @param sales sales for this key, any order; only those inside the window count
     */
    public Valuation valuate(String exactKey, List<Sale> sales, Instant now) {
        if (sales == null || sales.isEmpty()) {
            return Valuation.empty(exactKey, now);
        }

        Instant cutoff = windowStart(now);
        List<Sale> inWindow = new ArrayList<>();
        for (Sale s : sales) {
            if (!s.soldAt().isBefore(cutoff)) {
                inWindow.add(s);
            }
        }
        if (inWindow.isEmpty()) {
            return Valuation.empty(exactKey, now);
        }

        ManipulationFilter.Result cleaned = ManipulationFilter.clean(inWindow);
        List<Sale> clean = cleaned.accepted();
        if (clean.isEmpty()) {
            // Everything was manipulated or malformed. Explicitly unusable rather
            // than falling back to the dirty data -- an attacker who can poison
            // the whole window should get silence, not a slightly-worse estimate.
            return new Valuation(exactKey, 0.0, 0, 0, 0.0, Confidence.NONE,
                    0.0, cleaned.rejected(), cleaned.reasons(), now);
        }

        double[] unitPrices = clean.stream().mapToDouble(Sale::unitPrice).toArray();

        // Trimmed mean once there is enough data for the tails to be meaningful,
        // median otherwise. With few samples the median is the more robust of the two.
        double fair = unitPrices.length >= 10
                ? Statistics.trimmedMean(unitPrices, 0.15)
                : Statistics.median(unitPrices);

        Set<String> sellers = new HashSet<>();
        for (Sale s : clean) {
            if (s.seller() != null) {
                sellers.add(s.seller().toLowerCase());
            }
        }

        double observedDays = observedSpanDays(clean, now);
        double salesPerDay = clean.size() / Math.max(0.5, observedDays);

        Confidence confidence = gradeConfidence(clean, sellers.size(), salesPerDay, observedDays);

        return new Valuation(
                exactKey,
                fair,
                clean.size(),
                sellers.size(),
                salesPerDay,
                confidence,
                computeTrend(clean),
                cleaned.rejected(),
                cleaned.reasons(),
                now);
    }

    /**
     * How many days of history we actually observed.
     *
     * <p>Uses the span from the oldest sale to now, not the full window width.
     * If the collector has only been running six hours, dividing by seven days
     * would report an item that sold four times this morning as clearing 0.6
     * times a day, and every liquidity check downstream would be wrong in the
     * pessimistic direction.
     */
    private double observedSpanDays(List<Sale> sales, Instant now) {
        Instant oldest = now;
        for (Sale s : sales) {
            if (s.soldAt().isBefore(oldest)) {
                oldest = s.soldAt();
            }
        }
        double days = Duration.between(oldest, now).toSeconds() / 86400.0;
        return Math.min(window.toDays(), Math.max(0.0, days));
    }

    /**
     * Grades trust from volume, seller diversity and how long we have watched.
     *
     * <p>Seller diversity is weighted as heavily as raw count on purpose. Twenty
     * sales from one seller is one person's pricing decision repeated twenty
     * times, not twenty independent observations of what the market will pay.
     */
    private Confidence gradeConfidence(List<Sale> clean, int distinctSellers,
                                       double salesPerDay, double observedDays) {
        int n = clean.size();

        // A burst of trades from a single account inside one short window is the
        // exact shape of price manipulation the filter could not prove. Refuse it.
        if (distinctSellers <= 1 && observedDays < 1.0) {
            return Confidence.NONE;
        }
        if (n < 2) {
            return Confidence.NONE;
        }
        if (n >= HIGH_MIN_SALES
                && distinctSellers >= HIGH_MIN_SELLERS
                && salesPerDay >= HIGH_MIN_SALES_PER_DAY) {
            return Confidence.HIGH;
        }
        if (n >= MEDIUM_MIN_SALES && distinctSellers >= MEDIUM_MIN_SELLERS) {
            return Confidence.MEDIUM;
        }
        return Confidence.LOW;
    }

    /**
     * Fractional price drift per day, from the median of the older half of the
     * window against the newer half.
     *
     * <p>Halves rather than a regression: with a dozen noisy points a least-squares
     * slope is dominated by whichever end happens to have an odd sale, while two
     * medians degrade gracefully. The number only needs to answer "is this falling
     * fast enough that I should not buy the dip", not to forecast a price.
     */
    private double computeTrend(List<Sale> clean) {
        if (clean.size() < 6) {
            return 0.0;
        }
        List<Sale> sorted = new ArrayList<>(clean);
        sorted.sort((a, b) -> a.soldAt().compareTo(b.soldAt()));

        int half = sorted.size() / 2;
        double olderMedian = Statistics.median(
                sorted.subList(0, half).stream().mapToDouble(Sale::unitPrice).toArray());
        double newerMedian = Statistics.median(
                sorted.subList(half, sorted.size()).stream().mapToDouble(Sale::unitPrice).toArray());

        if (olderMedian <= 0) {
            return 0.0;
        }

        double spanDays = Duration.between(
                sorted.get(0).soldAt(), sorted.get(sorted.size() - 1).soldAt()).toSeconds() / 86400.0;
        if (spanDays < 0.5) {
            // Too short a span to call a trend; the divisor would amplify noise
            // into an alarming-looking daily rate.
            return 0.0;
        }
        return ((newerMedian - olderMedian) / olderMedian) / spanDays;
    }
}
