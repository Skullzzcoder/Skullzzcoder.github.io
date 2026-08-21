package com.skullzz.glaze.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsing and formatting for DonutSMP money amounts.
 *
 * <p>The server writes money in a handful of shapes depending on where it shows up:
 * {@code $1,234,567} in chat, {@code $1.2m} in menu lore, sometimes with colour codes
 * still attached. Everything is normalised to a whole number of dollars held in a
 * {@code long}, which comfortably covers the {@code $1t} range the economy reaches.
 */
public final class Money {
	/** Colour/format codes, both the section sign and the ampersand spelling. */
	private static final Pattern FORMAT_CODES = Pattern.compile("(?i)[§&][0-9a-fk-or]");

	/**
	 * A money token: optional sign, optional {@code $}, grouped digits, optional
	 * decimal part, optional magnitude suffix.
	 */
	private static final Pattern AMOUNT = Pattern.compile(
			"(?i)(?<sign>[-+])?\\$?\\s*(?<num>\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(?<frac>\\d+))?\\s*(?<suffix>[kmbt])?\\b");

	private static final char[] SUFFIXES = {'k', 'm', 'b', 't'};

	private Money() {
	}

	/** Strips colour codes and non-breaking spaces so patterns see plain text. */
	public static String clean(String raw) {
		if (raw == null) {
			return "";
		}

		return FORMAT_CODES.matcher(raw).replaceAll("").replace('\u00A0', ' ').trim();
	}

	/**
	 * Reads the first money amount in {@code text}.
	 *
	 * <p>A bare number counts: menu lore often writes {@code Price: 4.2m} with no
	 * dollar sign. Returns empty when nothing in the string looks like an amount.
	 */
	public static Optional<Long> parse(String text) {
		List<Long> all = parseAll(text);
		return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
	}

	/** Reads every money amount in {@code text}, left to right. */
	public static List<Long> parseAll(String text) {
		List<Long> out = new ArrayList<>();

		if (text == null || text.isEmpty()) {
			return out;
		}

		Matcher m = AMOUNT.matcher(clean(text));

		while (m.find()) {
			out.add(toAmount(m));
		}

		return out;
	}

	private static long toAmount(Matcher m) {
		BigDecimal value = new BigDecimal(m.group("num").replace(",", ""));

		String frac = m.group("frac");

		if (frac != null && !frac.isEmpty()) {
			value = value.add(new BigDecimal("0." + frac));
		}

		String suffix = m.group("suffix");

		if (suffix != null && !suffix.isEmpty()) {
			value = value.multiply(BigDecimal.TEN.pow(3 * (indexOfSuffix(suffix.charAt(0)) + 1)));
		}

		if ("-".equals(m.group("sign"))) {
			value = value.negate();
		}

		return value.setScale(0, RoundingMode.HALF_UP).longValue();
	}

	private static int indexOfSuffix(char c) {
		char lower = Character.toLowerCase(c);

		for (int i = 0; i < SUFFIXES.length; i++) {
			if (SUFFIXES[i] == lower) {
				return i;
			}
		}

		return -1;
	}

	/**
	 * Short form for HUD and tooltip use: {@code $1.23m}, {@code $4.2b}, {@code $850}.
	 *
	 * <p>Keeps up to two decimals and drops trailing zeros, so amounts stay the same
	 * width as you earn without the text jittering on every coin.
	 */
	public static String compact(long amount) {
		if (amount == 0) {
			return "$0";
		}

		String sign = amount < 0 ? "-" : "";
		BigDecimal abs = BigDecimal.valueOf(Math.abs(amount));

		int tier = 0;

		while (abs.compareTo(BigDecimal.valueOf(1000)) >= 0 && tier < SUFFIXES.length) {
			abs = abs.divide(BigDecimal.valueOf(1000));
			tier++;
		}

		String digits = abs.setScale(tier == 0 ? 0 : 2, RoundingMode.DOWN)
				.stripTrailingZeros()
				.toPlainString();

		return sign + "$" + digits + (tier == 0 ? "" : String.valueOf(SUFFIXES[tier - 1]));
	}

	/** Long form with thousands separators, the way the server prints balances. */
	public static String full(long amount) {
		return (amount < 0 ? "-$" : "$") + String.format("%,d", Math.abs(amount));
	}

	/** Rate shown per hour, e.g. {@code $12.5m/h}. */
	public static String perHour(double amountPerHour) {
		return compact(Math.round(amountPerHour)) + "/h";
	}
}
