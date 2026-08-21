package com.skullzz.glaze.core;

/**
 * A summary of what an item has been going for, derived from observed listings.
 *
 * <p>All figures are per single item, never per stack, so a listing of 64 diamonds
 * and a listing of one diamond are directly comparable.
 */
public record PriceStats(int samples, long low, long p25, long median, long p75, long high, long newest) {
	/**
	 * Whether {@code unitPrice} is cheap enough to flag, at the given fraction of
	 * the median. A threshold of {@code 0.7} flags anything 30% under median.
	 *
	 * <p>Needs a few samples first - flagging "deals" off a single observation just
	 * highlights whatever you happened to look at last.
	 */
	public boolean isDeal(long unitPrice, double threshold, int minSamples) {
		return samples >= minSamples && median > 0 && unitPrice <= median * threshold;
	}

	/** How far under (positive) or over (negative) median a price sits, as a fraction. */
	public double marginAgainstMedian(long unitPrice) {
		if (median <= 0) {
			return 0;
		}

		return (median - unitPrice) / (double) median;
	}
}
