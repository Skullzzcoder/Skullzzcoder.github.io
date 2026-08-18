package dev.skullzz.donutflipper.pricing;

import java.util.Arrays;

/**
 * Small statistics helpers, kept deliberately explicit.
 *
 * <p>Every aggregate here is order-statistic based. Auction price distributions
 * are heavily right-skewed -- a handful of whale sales sit far above a dense
 * cluster of ordinary ones -- and a mean tracks those outliers instead of the
 * price a normal buyer will actually pay. Using an average anywhere in this
 * pipeline systematically overvalues items and turns the tool into a machine for
 * overpaying.
 */
public final class Statistics {

    private Statistics() {
    }

    /** Median. Mutates nothing; the input array is copied before sorting. */
    public static double median(double[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1
                ? sorted[mid]
                : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }

    /**
     * Linear-interpolated percentile.
     *
     * @param p 0.0 to 1.0
     */
    public static double percentile(double[] values, double p) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        if (sorted.length == 1) {
            return sorted[0];
        }
        double clamped = Math.min(1.0, Math.max(0.0, p));
        double pos = clamped * (sorted.length - 1);
        int lower = (int) Math.floor(pos);
        int upper = (int) Math.ceil(pos);
        if (lower == upper) {
            return sorted[lower];
        }
        double weight = pos - lower;
        return sorted[lower] * (1 - weight) + sorted[upper] * weight;
    }

    /**
     * Mean of the middle portion, discarding {@code trimFraction} from each tail.
     *
     * <p>Used where a pure median would be too coarse -- with 30 samples the
     * median moves in visible steps, while a trimmed mean still uses the shape of
     * the central cluster without letting the tails vote.
     */
    public static double trimmedMean(double[] values, double trimFraction) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int trim = (int) Math.floor(sorted.length * Math.min(0.4, Math.max(0.0, trimFraction)));
        int from = trim;
        int to = sorted.length - trim;
        if (to - from < 1) {
            return median(values);
        }
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += sorted[i];
        }
        return sum / (to - from);
    }
}
