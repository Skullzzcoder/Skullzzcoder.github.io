package com.skullzz.donutgambler.chat;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and writes DonutSMP-style money strings: {@code $1,250}, {@code 7.5k}, {@code 2M}, {@code 1.2b}.
 */
public final class MoneyParser {
	/** Matches a money token; group 1 is the number, group 2 the optional magnitude suffix. */
	public static final Pattern MONEY_TOKEN = Pattern.compile("\\$?\\s*(\\d[\\d,]*(?:\\.\\d+)?)(?:\\s?([kKmMbBtT]))?");

	/** The fragment injected as the amount capture group when building a pattern from chat. */
	public static final String AMOUNT_GROUP = "\\$?(?<amount>[\\d,.]+\\s*[kKmMbBtT]?)";

	private MoneyParser() {
	}

	/**
	 * Parses a money string. Returns {@link Double#NaN} when the text holds no number,
	 * so callers can reject a bad regex capture instead of logging a bogus 0-value bet.
	 */
	public static double parse(String raw) {
		if (raw == null) return Double.NaN;
		Matcher m = MONEY_TOKEN.matcher(raw.trim());
		if (!m.find()) return Double.NaN;

		double value;

		try {
			value = Double.parseDouble(m.group(1).replace(",", ""));
		} catch (NumberFormatException e) {
			return Double.NaN;
		}

		return value * suffixMultiplier(m.group(2));
	}

	public static double suffixMultiplier(String suffix) {
		if (suffix == null || suffix.isEmpty()) return 1;

		return switch (Character.toLowerCase(suffix.charAt(0))) {
		case 'k' -> 1_000d;
		case 'm' -> 1_000_000d;
		case 'b' -> 1_000_000_000d;
		case 't' -> 1_000_000_000_000d;
		default -> 1;
		};
	}

	/** Compact display form: {@code $12.5K}, {@code -$1.20M}. */
	public static String format(double value) {
		double abs = Math.abs(value);
		String sign = value < 0 ? "-" : "";

		if (abs >= 1_000_000_000_000d) return sign + "$" + trim(abs / 1_000_000_000_000d) + "T";
		if (abs >= 1_000_000_000d) return sign + "$" + trim(abs / 1_000_000_000d) + "B";
		if (abs >= 1_000_000d) return sign + "$" + trim(abs / 1_000_000d) + "M";
		if (abs >= 1_000d) return sign + "$" + trim(abs / 1_000d) + "K";

		return sign + "$" + trim(abs);
	}

	/** Same as {@link #format} but always carries an explicit + or - sign. */
	public static String formatSigned(double value) {
		if (value > 0) return "+" + format(value);
		return format(value);
	}

	/** Exact value with thousands separators, for the log and exports. */
	public static String formatExact(double value) {
		return String.format(Locale.ROOT, "%,.2f", value);
	}

	private static String trim(double v) {
		if (v >= 100 || v == Math.rint(v)) return String.format(Locale.ROOT, "%.0f", v);
		if (v >= 10) return String.format(Locale.ROOT, "%.1f", v);
		return String.format(Locale.ROOT, "%.2f", v);
	}

	public static String percent(double fraction) {
		return String.format(Locale.ROOT, "%.1f%%", fraction * 100);
	}

	public static String signedPercent(double fraction) {
		return (fraction > 0 ? "+" : "") + String.format(Locale.ROOT, "%.2f%%", fraction * 100);
	}
}
