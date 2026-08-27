package com.skullzz.donutgambler.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a regex from a real chat line, so you never have to hand-write one:
 * the money becomes an {@code amount} group, the player name becomes an {@code opponent}
 * group, and everything else stays literal but whitespace-tolerant.
 */
public final class PatternBuilder {
	private static final String OPPONENT_GROUP = "(?<opponent>[A-Za-z0-9_]{3,16})";
	private static final Pattern NAME_AFTER_PREPOSITION =
			Pattern.compile("(?i)\\b(?:from|to|against|vs\\.?|beat|beating|with)\\s+([A-Za-z0-9_]{3,16})");

	private PatternBuilder() {
	}

	/**
	 * @param line          the chat line to generalise
	 * @param opponentName  player name in the line to turn into a capture group, or null to auto-detect
	 */
	public static String fromLine(String line, String opponentName) {
		String text = BetMatcher.strip(line);
		if (text.isEmpty()) return "";

		String name = opponentName != null && !opponentName.isBlank() ? opponentName.trim() : autoDetectName(text);

		StringBuilder out = new StringBuilder("(?i)");
		Matcher money = MoneyParser.MONEY_TOKEN.matcher(text);
		int cursor = 0;
		boolean amountUsed = false;

		while (money.find()) {
			if (amountUsed) break;

			out.append(literal(text.substring(cursor, money.start()), name));
			out.append(MoneyParser.AMOUNT_GROUP);
			cursor = money.end();
			amountUsed = true;
		}

		out.append(literal(text.substring(cursor), name));

		if (!amountUsed) {
			// No money in the line: still useful as a trigger, but warn by leaving no amount group.
			return out.toString();
		}

		return out.toString();
	}

	/** The name this builder would capture from the line, or "" when it cannot find one. */
	public static String autoDetectName(String text) {
		Matcher m = NAME_AFTER_PREPOSITION.matcher(text);
		return m.find() ? m.group(1) : "";
	}

	/**
	 * Escapes a literal chunk, swapping the opponent name for its capture group,
	 * loosening whitespace, and generalising any leftover numbers.
	 */
	private static String literal(String chunk, String opponentName) {
		if (chunk.isEmpty()) return "";

		String work = chunk;
		StringBuilder sb = new StringBuilder();

		int nameAt = opponentName == null || opponentName.isEmpty()
				? -1
				: work.toLowerCase(java.util.Locale.ROOT).indexOf(opponentName.toLowerCase(java.util.Locale.ROOT));

		if (nameAt >= 0) {
			sb.append(escape(work.substring(0, nameAt)));
			sb.append(OPPONENT_GROUP);
			sb.append(escape(work.substring(nameAt + opponentName.length())));
			return sb.toString();
		}

		return escape(work);
	}

	private static String escape(String s) {
		StringBuilder sb = new StringBuilder();

		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);

			if (Character.isWhitespace(c)) {
				// Collapse a run of whitespace into a tolerant \s+
				while (i + 1 < s.length() && Character.isWhitespace(s.charAt(i + 1))) i++;
				sb.append("\\s+");
			} else if (Character.isDigit(c)) {
				while (i + 1 < s.length() && (Character.isDigit(s.charAt(i + 1)) || s.charAt(i + 1) == ',')) i++;
				sb.append("[\\d,]+");
			} else if ("\\.[]{}()<>*+-=!?^$|/".indexOf(c) >= 0) {
				sb.append('\\').append(c);
			} else {
				sb.append(c);
			}
		}

		return sb.toString();
	}
}
