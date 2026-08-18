package dev.skullzz.donutflipper.pricing;

/**
 * How much a valuation should be trusted, derived from the volume and diversity
 * of the sale history behind it.
 *
 * <p>This exists as a first-class concept because the expensive mistake in
 * auction flipping is not misjudging a price -- it is acting confidently on a
 * price derived from two sales, both by the same person, both an hour ago.
 * Every valuation carries its own confidence, and the scanner refuses to alert
 * below the active profile's floor.
 */
public enum Confidence {

    /** Not enough evidence to price this at all. Never alerted on, ever. */
    NONE(0, "No data"),

    /** Some history, but thin or single-sourced. Informational only. */
    LOW(1, "Thin data"),

    /** Enough sales from enough distinct sellers to be worth acting on. */
    MEDIUM(2, "Fair"),

    /** Deep, diverse, recent history. Safe to act on. */
    HIGH(3, "Strong");

    private final int rank;
    private final String label;

    Confidence(int rank, String label) {
        this.rank = rank;
        this.label = label;
    }

    public int rank() {
        return rank;
    }

    public String label() {
        return label;
    }

    public boolean atLeast(Confidence other) {
        return this.rank >= other.rank;
    }

    /**
     * Multiplier applied to a flip's score, so that a merely-adequate valuation
     * ranks below a well-evidenced one of the same nominal profit.
     */
    public double weight() {
        return switch (this) {
            case NONE -> 0.0;
            case LOW -> 0.35;
            case MEDIUM -> 0.7;
            case HIGH -> 1.0;
        };
    }
}
