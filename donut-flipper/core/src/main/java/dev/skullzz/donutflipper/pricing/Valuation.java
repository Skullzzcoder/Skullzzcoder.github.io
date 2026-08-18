package dev.skullzz.donutflipper.pricing;

import java.time.Instant;
import java.util.List;

/**
 * What one item key is worth, and how much that estimate should be trusted.
 *
 * @param exactKey         the item key this values
 * @param fairUnitPrice    estimated clearing price for a single unit
 * @param sampleCount      clean sales behind the estimate
 * @param distinctSellers  how many different people sold it -- diversity, not just volume
 * @param salesPerDay      liquidity; how fast the item actually moves
 * @param confidence       trust level derived from the above
 * @param trendPerDay      fractional price drift per day; negative means falling
 * @param rejectedSamples  sales discarded by {@link ManipulationFilter}
 * @param notes            why samples were rejected, for display
 * @param computedAt       when this was calculated
 */
public record Valuation(
        String exactKey,
        double fairUnitPrice,
        int sampleCount,
        int distinctSellers,
        double salesPerDay,
        Confidence confidence,
        double trendPerDay,
        int rejectedSamples,
        List<String> notes,
        Instant computedAt
) {

    /** Placeholder for an item with no usable history. Never produces a flip. */
    public static Valuation empty(String exactKey, Instant now) {
        return new Valuation(exactKey, 0.0, 0, 0, 0.0,
                Confidence.NONE, 0.0, 0, List.of(), now);
    }

    public boolean usable() {
        return confidence != Confidence.NONE && fairUnitPrice > 0;
    }

    /**
     * True when the item is losing value fast enough that today's "discount" is
     * likely to be tomorrow's fair price. Buying into this is the classic way to
     * turn a paper profit into real inventory you cannot shift.
     */
    public boolean falling() {
        return trendPerDay < -0.05;
    }

    /**
     * Rough odds the item sells within the horizon, from its observed rate.
     *
     * <p>Modelled as a Poisson process: with an arrival rate of {@code
     * salesPerDay}, the chance of at least one sale in {@code days} is
     * {@code 1 - e^(-rate * days)}. That is a simplification -- it assumes your
     * listing is the one that sells, which is optimistic when several are
     * competing -- but it captures the property that matters: an item trading
     * once a week is drastically less likely to clear than one trading hourly,
     * and profit should be discounted accordingly rather than counted in full.
     */
    public double probabilityOfSaleWithin(double days) {
        if (salesPerDay <= 0) {
            return 0.0;
        }
        return 1.0 - Math.exp(-salesPerDay * days);
    }
}
