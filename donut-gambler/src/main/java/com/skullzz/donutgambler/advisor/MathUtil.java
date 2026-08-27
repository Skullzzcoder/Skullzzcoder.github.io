package com.skullzz.donutgambler.advisor;

/**
 * The gambling maths the advisor runs on. Everything here is pure and unit-testable:
 * no Minecraft, no config, no state.
 *
 * <p>Convention used throughout: a bet stakes 1 unit, wins {@code b} units of <em>profit</em>
 * with probability {@code p}, and loses the 1 unit staked with probability {@code 1 - p}.
 * So an even-money coinflip with a 5% house tax is {@code p = 0.5, b = 0.95}.
 */
public final class MathUtil {
	private MathUtil() {
	}

	public static double clamp(double v, double min, double max) {
		return v < min ? min : Math.min(v, max);
	}

	/** Expected profit per unit staked. Negative means the game bleeds you long-run. */
	public static double expectedValue(double p, double b) {
		return p * b - (1 - p);
	}

	/** Expected value expressed as a percentage of the stake (the "edge"). */
	public static double edgePercent(double p, double b) {
		return expectedValue(p, b) * 100.0;
	}

	/**
	 * Full-Kelly stake as a fraction of bankroll: {@code (p*b - q) / b}.
	 * Returns 0 when the bet has no edge (never bet a negative-EV game).
	 */
	public static double kellyFraction(double p, double b) {
		if (b <= 0) return 0;
		double f = (p * b - (1 - p)) / b;
		return Math.max(0, f);
	}

	/** Variance of the per-unit return, used by the risk-of-ruin approximation. */
	public static double variancePerUnit(double p, double b) {
		double mean = expectedValue(p, b);
		double second = p * b * b + (1 - p);
		return Math.max(1e-9, second - mean * mean);
	}

	/**
	 * Probability of losing the whole bankroll while flat-betting {@code bet} at these odds.
	 *
	 * <p>Even-money games use the exact gambler's-ruin result {@code (q/p)^units}. Everything
	 * else uses the standard diffusion approximation {@code exp(-2*mu*units/sigma^2)}, which is
	 * close enough for bankroll decisions and always lands in [0, 1].
	 */
	public static double riskOfRuin(double p, double b, double bankroll, double bet) {
		if (bet <= 0 || bankroll <= 0) return 0;
		if (bet >= bankroll) return 1 - p;

		double units = bankroll / bet;
		double mean = expectedValue(p, b);
		if (mean <= 0) return 1;

		if (Math.abs(b - 1.0) < 1e-9) {
			double q = 1 - p;
			if (p <= q) return 1;
			return clamp(Math.pow(q / p, units), 0, 1);
		}

		double variance = variancePerUnit(p, b);
		return clamp(Math.exp(-2.0 * mean * units / variance), 0, 1);
	}

	/** How many flat bets of this size the bankroll survives before it is gone. */
	public static double unitsOfBankroll(double bankroll, double bet) {
		return bet <= 0 ? Double.POSITIVE_INFINITY : bankroll / bet;
	}

	/** Abramowitz & Stegun 7.1.26 error function; max error ~1.5e-7. */
	public static double erf(double x) {
		double sign = Math.signum(x);
		double ax = Math.abs(x);
		double t = 1.0 / (1.0 + 0.3275911 * ax);
		double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t
				+ 0.254829592) * t * Math.exp(-ax * ax);
		return sign * y;
	}

	/** Standard normal CDF. */
	public static double normalCdf(double z) {
		return 0.5 * (1.0 + erf(z / Math.sqrt(2)));
	}

	/**
	 * One-sided p-value for "{@code k} or more successes out of {@code n}" under a fair
	 * probability {@code p}, via the normal approximation with a continuity correction.
	 * Small p means the result is hard to explain by luck alone.
	 */
	public static double pValueAtLeast(int k, int n, double p) {
		if (n <= 0) return 1;
		double mean = n * p;
		double sd = Math.sqrt(n * p * (1 - p));
		if (sd <= 0) return k > mean ? 0 : 1;
		double z = (k - 0.5 - mean) / sd;
		return clamp(1.0 - normalCdf(z), 0, 1);
	}

	/** Lower bound of the Wilson score interval for a proportion. */
	public static double wilsonLower(int successes, int n, double z) {
		if (n <= 0) return 0;
		double phat = (double) successes / n;
		double z2 = z * z;
		double denom = 1 + z2 / n;
		double centre = phat + z2 / (2 * n);
		double margin = z * Math.sqrt((phat * (1 - phat) + z2 / (4 * n)) / n);
		return clamp((centre - margin) / denom, 0, 1);
	}

	/** Upper bound of the Wilson score interval for a proportion. */
	public static double wilsonUpper(int successes, int n, double z) {
		if (n <= 0) return 1;
		double phat = (double) successes / n;
		double z2 = z * z;
		double denom = 1 + z2 / n;
		double centre = phat + z2 / (2 * n);
		double margin = z * Math.sqrt((phat * (1 - phat) + z2 / (4 * n)) / n);
		return clamp((centre + margin) / denom, 0, 1);
	}

	/**
	 * Bets needed before a win-rate measurement is tight enough to be worth acting on:
	 * the sample size at which the 95% interval is about {@code halfWidth} wide.
	 */
	public static int samplesForPrecision(double p, double halfWidth) {
		if (halfWidth <= 0) return Integer.MAX_VALUE;
		double n = 1.96 * 1.96 * p * (1 - p) / (halfWidth * halfWidth);
		return (int) Math.ceil(n);
	}
}
