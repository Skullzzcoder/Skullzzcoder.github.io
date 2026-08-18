package dev.skullzz.donutflipper.config;

import dev.skullzz.donutflipper.pricing.Confidence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A strategy preset: the thresholds a listing must clear before it is shown as
 * a flip. Swapping profiles is how you move between grinding small margins and
 * hunting big-ticket items, without touching code.
 *
 * @param name              display name
 * @param minRoi            minimum return on the buy price, as a fraction (0.25 = 25%)
 * @param minNetProfit      minimum absolute profit in coins after tax
 * @param minSalesPerDay    liquidity floor -- how often this item actually moves
 * @param minConfidence     how much sale history is required before trusting a valuation
 * @param maxBuyPrice       skip anything above this, regardless of margin (0 = no cap)
 */
public record Profile(
        String name,
        double minRoi,
        long minNetProfit,
        double minSalesPerDay,
        Confidence minConfidence,
        long maxBuyPrice
) {

    /**
     * Default. Demands real sale history and real liquidity, so it surfaces
     * fewer flips but most of them are genuine.
     */
    public static final Profile BALANCED = new Profile(
            "balanced", 0.25, 5_000L, 1.0, Confidence.HIGH, 0L);

    /**
     * Grinder mode: thinner margins, looser evidence, far more alerts. Only
     * worth running once the backtest says your valuations are trustworthy,
     * because at this threshold a bad valuation gets acted on many times a day.
     */
    public static final Profile VOLUME = new Profile(
            "volume", 0.15, 1_000L, 3.0, Confidence.MEDIUM, 0L);

    /**
     * Big-ticket gear. Accepts low liquidity -- these items sell slowly by
     * nature -- but demands a large absolute profit to justify the capital
     * being tied up while it sits.
     */
    public static final Profile WHALE = new Profile(
            "whale", 0.30, 250_000L, 0.3, Confidence.HIGH, 0L);

    private static final Map<String, Profile> BUILT_IN = new LinkedHashMap<>();

    static {
        for (Profile p : new Profile[]{BALANCED, VOLUME, WHALE}) {
            BUILT_IN.put(p.name(), p);
        }
    }

    public static Profile byName(String name) {
        return BUILT_IN.getOrDefault(name == null ? "" : name.toLowerCase(), BALANCED);
    }

    public static Map<String, Profile> builtIn() {
        return Map.copyOf(BUILT_IN);
    }

    /** Cycles through the presets -- drives the profile button in the in-game UI. */
    public Profile next() {
        var names = BUILT_IN.keySet().toArray(new String[0]);
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return BUILT_IN.get(names[(i + 1) % names.length]);
            }
        }
        return BALANCED;
    }
}
