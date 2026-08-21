package com.skullzz.glaze.core;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reads price and seller out of the lore lines under an auction house item.
 *
 * <p>Like {@link ChatPatterns}, the patterns are configurable, because menu lore
 * is exactly the kind of thing a server reskins without warning.
 */
public final class ListingParser {
	public static final List<String> DEFAULT_PRICE_PATTERNS = List.of(
			"(?i)^\\s*(?:price|cost|buy(?: it)? now|bin)\\s*:?\\s*" + ChatPatterns.AMOUNT,
			"(?i)\\bfor\\s+" + ChatPatterns.AMOUNT + "\\s*$");

	public static final List<String> DEFAULT_SELLER_PATTERNS = List.of(
			"(?i)^\\s*(?:seller|sold by|owner|listed by)\\s*:?\\s*(?<player>\\w{3,16})");

	private final List<Pattern> pricePatterns;
	private final List<Pattern> sellerPatterns;

	public ListingParser(List<String> pricePatterns, List<String> sellerPatterns) {
		this.pricePatterns = compile(pricePatterns, DEFAULT_PRICE_PATTERNS);
		this.sellerPatterns = compile(sellerPatterns, DEFAULT_SELLER_PATTERNS);
	}

	public static ListingParser withDefaults() {
		return new ListingParser(DEFAULT_PRICE_PATTERNS, DEFAULT_SELLER_PATTERNS);
	}

	private static List<Pattern> compile(List<String> sources, List<String> fallback) {
		List<String> use = sources == null || sources.isEmpty() ? fallback : sources;

		return use.stream()
				.map(ListingParser::compileOrNull)
				.filter(java.util.Objects::nonNull)
				.toList();
	}

	private static Pattern compileOrNull(String source) {
		try {
			return Pattern.compile(source);
		} catch (PatternSyntaxException e) {
			return null;
		}
	}

	/**
	 * Builds a listing from a slot's item.
	 *
	 * @return empty when no price could be found, which is the normal outcome for
	 *         decorative slots like page arrows and filler glass
	 */
	public Optional<AuctionListing> parse(String itemName, int quantity, List<String> lore, int slot) {
		if (itemName == null || itemName.isBlank() || lore == null) {
			return Optional.empty();
		}

		long price = 0;
		String seller = "";

		for (String rawLine : lore) {
			String line = Money.clean(rawLine);

			if (price <= 0) {
				price = firstMatch(pricePatterns, line, "amount")
						.flatMap(Money::parse)
						.orElse(0L);
			}

			if (seller.isEmpty()) {
				seller = firstMatch(sellerPatterns, line, "player").orElse("");
			}
		}

		if (price <= 0) {
			return Optional.empty();
		}

		return Optional.of(new AuctionListing(
				itemName.trim(), Math.max(1, quantity), price, seller, slot));
	}

	private static Optional<String> firstMatch(List<Pattern> patterns, String line, String group) {
		for (Pattern pattern : patterns) {
			Matcher m = pattern.matcher(line);

			if (m.find()) {
				try {
					String value = m.group(group);

					if (value != null && !value.isBlank()) {
						return Optional.of(value.trim());
					}
				} catch (IllegalArgumentException ignored) {
					// Pattern does not declare that group; try the next one.
				}
			}
		}

		return Optional.empty();
	}
}
