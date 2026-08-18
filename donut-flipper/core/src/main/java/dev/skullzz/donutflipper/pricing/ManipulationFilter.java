package dev.skullzz.donutflipper.pricing;

import dev.skullzz.donutflipper.model.Sale;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes sales that are evidence of nothing.
 *
 * <p>This is the defence against being farmed, and it is not paranoia -- it is
 * the obvious counter-play against any auction bot. The attack costs an attacker
 * nothing beyond the auction tax:
 *
 * <ol>
 *   <li>Sell a junk item to your own alt for 10,000,000, three times.</li>
 *   <li>Any bot valuing from raw sale history now believes that item is worth
 *       10,000,000.</li>
 *   <li>List your junk copies at 6,000,000. The bot sees a 40% discount against
 *       "market" and buys every one of them.</li>
 * </ol>
 *
 * <p>A flipper without this filter is not a trading tool, it is a payout button
 * for whoever notices it first. Filters run in a deliberate order: fake
 * counterparties are removed <em>before</em> the median is computed, because an
 * outlier test calibrated against a poisoned median rejects the honest sales and
 * keeps the fraudulent ones.
 */
public final class ManipulationFilter {

    /**
     * A pair trading this many times inside one valuation window is not two
     * strangers meeting repeatedly by chance on a server-wide auction house.
     */
    private static final int SUSPICIOUS_PAIR_TRADES = 3;

    /** No single counterparty pair may account for more than this share of volume. */
    private static final double MAX_PAIR_VOLUME_SHARE = 0.5;

    /** Sales above this multiple of the clean median are discarded as outliers. */
    private static final double HIGH_OUTLIER_MULTIPLE = 3.0;

    /** ...and below this fraction, which catches fat-finger dumps and gifts. */
    private static final double LOW_OUTLIER_FRACTION = 0.25;

    /**
     * @param accepted  sales that survived
     * @param rejected  how many were discarded
     * @param reasons   human-readable notes, surfaced in the UI so a suspicious
     *                  valuation can be understood rather than merely distrusted
     */
    public record Result(List<Sale> accepted, int rejected, List<String> reasons) {
        public boolean anyRejected() {
            return rejected > 0;
        }
    }

    private ManipulationFilter() {
    }

    public static Result clean(List<Sale> sales) {
        if (sales == null || sales.isEmpty()) {
            return new Result(List.of(), 0, List.of());
        }

        List<String> reasons = new ArrayList<>();
        List<Sale> working = new ArrayList<>(sales);
        int originalSize = working.size();

        working = removeSelfTrades(working, reasons);
        working = removeWashPairs(working, reasons);
        working = removeOutliers(working, reasons);

        return new Result(List.copyOf(working), originalSize - working.size(), List.copyOf(reasons));
    }

    /**
     * A player selling to themselves is not a market price. Only detectable when
     * the API exposes buyer identity; if it does not, this is a no-op and the
     * volume-share check below carries the load.
     */
    private static List<Sale> removeSelfTrades(List<Sale> sales, List<String> reasons) {
        List<Sale> out = new ArrayList<>(sales.size());
        int removed = 0;
        for (Sale s : sales) {
            if (s.hasKnownBuyer() && s.seller() != null
                    && s.seller().equalsIgnoreCase(s.buyer())) {
                removed++;
            } else {
                out.add(s);
            }
        }
        if (removed > 0) {
            reasons.add(removed + " self-trade(s) removed");
        }
        return out;
    }

    /**
     * Drops every sale belonging to a counterparty pair that traded suspiciously
     * often, or that dominates this item's volume.
     *
     * <p>All of a flagged pair's sales go, not just the excess. Once a pair is
     * established as coordinating, none of their prices are evidence -- keeping
     * the first two and discarding the rest would still let an attacker set the
     * price using their first two trades.
     */
    private static List<Sale> removeWashPairs(List<Sale> sales, List<String> reasons) {
        Map<String, Integer> pairCounts = new HashMap<>();
        int withBuyer = 0;
        for (Sale s : sales) {
            if (s.hasKnownBuyer()) {
                withBuyer++;
                pairCounts.merge(s.counterpartyPair(), 1, Integer::sum);
            }
        }
        if (withBuyer == 0) {
            return sales;
        }

        Set<String> banned = new HashSet<>();
        for (Map.Entry<String, Integer> e : pairCounts.entrySet()) {
            double share = (double) e.getValue() / (double) withBuyer;
            if (e.getValue() >= SUSPICIOUS_PAIR_TRADES || share > MAX_PAIR_VOLUME_SHARE) {
                banned.add(e.getKey());
            }
        }
        if (banned.isEmpty()) {
            return sales;
        }

        List<Sale> out = new ArrayList<>(sales.size());
        int removed = 0;
        for (Sale s : sales) {
            if (s.hasKnownBuyer() && banned.contains(s.counterpartyPair())) {
                removed++;
            } else {
                out.add(s);
            }
        }
        if (removed > 0) {
            reasons.add(removed + " sale(s) from " + banned.size()
                    + " repeated counterparty pair(s) removed -- possible wash trading");
        }
        return out;
    }

    /**
     * Trims both tails against the median of the already-cleaned set.
     *
     * <p>Both directions matter and for different reasons. High outliers inflate
     * a valuation and make junk look like a bargain. Low outliers -- a friend
     * selling something for 1 coin, a misplaced decimal -- deflate it and cause
     * the tool to dismiss genuinely good flips.
     */
    private static List<Sale> removeOutliers(List<Sale> sales, List<String> reasons) {
        if (sales.size() < 4) {
            // Below this, a "median" is barely meaningful and trimming would
            // discard most of the little evidence available. Confidence scoring
            // handles the thin-data case instead.
            return sales;
        }
        double median = Statistics.median(sales.stream().mapToDouble(Sale::unitPrice).toArray());
        if (median <= 0) {
            return sales;
        }
        double high = median * HIGH_OUTLIER_MULTIPLE;
        double low = median * LOW_OUTLIER_FRACTION;

        List<Sale> out = new ArrayList<>(sales.size());
        int removed = 0;
        for (Sale s : sales) {
            double unit = s.unitPrice();
            if (unit > high || unit < low) {
                removed++;
            } else {
                out.add(s);
            }
        }
        if (removed > 0) {
            reasons.add(removed + " price outlier(s) trimmed");
        }
        // If trimming would leave almost nothing, the distribution is genuinely
        // wide rather than contaminated. Keep the original and let confidence fall.
        return out.size() < 3 ? sales : out;
    }
}
