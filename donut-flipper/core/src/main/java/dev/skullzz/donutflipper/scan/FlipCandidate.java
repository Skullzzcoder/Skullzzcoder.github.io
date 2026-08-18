package dev.skullzz.donutflipper.scan;

import dev.skullzz.donutflipper.model.Listing;
import dev.skullzz.donutflipper.pricing.Valuation;

/**
 * A listing the scanner believes is underpriced, with the arithmetic that led
 * to that conclusion attached.
 *
 * <p>Every intermediate figure is kept rather than just the final score, because
 * the in-game UI shows them. A tool that says "buy this, trust me" is one you
 * stop using the first time it is wrong; a tool that says "worth 40k, listed at
 * 22k, sells about 3x a day, based on 14 sales from 6 sellers" is one you can
 * sanity-check at a glance.
 *
 * @param listing       the live listing to buy
 * @param valuation     what we think the item is worth
 * @param netProfit     expected coins after the auction tax, if it sells at fair value
 * @param roi           netProfit as a fraction of the buy price
 * @param saleOdds      probability of clearing within the scoring horizon
 * @param score         ranking value: risk- and liquidity-adjusted expected profit
 */
public record FlipCandidate(
        Listing listing,
        Valuation valuation,
        long netProfit,
        double roi,
        double saleOdds,
        double score
) {

    public String itemName() {
        String display = listing.item().displayName();
        return display == null || display.isBlank() ? listing.item().materialId() : display;
    }

    public long buyPrice() {
        return listing.price();
    }

    /** What the whole stack should fetch at fair value, before tax. */
    public double grossResale() {
        return valuation.fairUnitPrice() * listing.item().count();
    }

    /** Percentage below fair value this is listed at -- the headline "discount". */
    public double discountPercent() {
        double gross = grossResale();
        if (gross <= 0) {
            return 0.0;
        }
        return (1.0 - (listing.price() / gross)) * 100.0;
    }
}
