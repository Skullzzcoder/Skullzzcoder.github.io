package dev.skullzz.donutflipper.pricing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatisticsTest {

    @Test
    @DisplayName("median ignores an extreme outlier that would wreck a mean")
    void medianResistsWhaleSales() {
        double[] prices = {100, 105, 98, 102, 101, 99, 5_000_000};

        assertEquals(101, Statistics.median(prices), 0.001);
        // The arithmetic mean of the same data is over 714,000 -- which is why
        // nothing in this pipeline uses one.
        double mean = java.util.Arrays.stream(prices).average().orElse(0);
        assertTrue(mean > 700_000);
    }

    @Test
    @DisplayName("median handles even-length input")
    void medianEvenLength() {
        assertEquals(15.0, Statistics.median(new double[]{10, 20, 12, 18}), 0.001);
    }

    @Test
    @DisplayName("empty input yields zero rather than throwing")
    void emptyIsSafe() {
        assertEquals(0.0, Statistics.median(new double[]{}), 0.001);
        assertEquals(0.0, Statistics.percentile(new double[]{}, 0.5), 0.001);
        assertEquals(0.0, Statistics.trimmedMean(new double[]{}, 0.1), 0.001);
        assertEquals(0.0, Statistics.median(null), 0.001);
    }

    @Test
    @DisplayName("percentile interpolates between neighbours")
    void percentileInterpolates() {
        double[] v = {10, 20, 30, 40};
        assertEquals(10.0, Statistics.percentile(v, 0.0), 0.001);
        assertEquals(40.0, Statistics.percentile(v, 1.0), 0.001);
        assertEquals(25.0, Statistics.percentile(v, 0.5), 0.001);
    }

    @Test
    @DisplayName("trimmed mean drops both tails")
    void trimmedMeanDropsTails() {
        double[] v = {1, 100, 101, 102, 103, 104, 10_000};
        double trimmed = Statistics.trimmedMean(v, 0.15);
        assertTrue(trimmed > 90 && trimmed < 200,
                "expected the central cluster, got " + trimmed);
    }

    @Test
    @DisplayName("median does not mutate the caller's array")
    void inputIsNotMutated() {
        double[] v = {5, 1, 3};
        Statistics.median(v);
        assertArrayEquals(new double[]{5, 1, 3}, v, 0.001);
    }
}
