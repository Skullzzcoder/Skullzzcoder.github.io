package com.skullzz.glaze.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The regexes that turn server messages into {@link ChatSignal}s.
 *
 * <p>These live in config rather than in code on purpose. Server message wording
 * changes whenever DonutSMP reworks a feature, and when it does you want to fix a
 * line in a JSON file, not wait for a mod update. {@code /glaze chatlog} prints the
 * raw text of incoming messages so a broken pattern can be rewritten against the
 * real thing.
 *
 * <p>Patterns may use these named groups:
 * <ul>
 *   <li>{@code amount} - any money-ish token, run through {@link Money#parse}</li>
 *   <li>{@code player} - a player name</li>
 *   <li>{@code item} - an item name</li>
 *   <li>{@code qty} - a stack size</li>
 * </ul>
 */
public final class ChatPatterns {
	/** Matches a money token including suffixed and grouped forms. */
	public static final String AMOUNT = "(?<amount>[-+]?\\$?[\\d,.]+\\s*[kmbtKMBT]?)";
	private static final String PLAYER = "(?<player>\\w{3,16})";
	private static final String ITEM = "(?<item>[\\w' ]+?)";
	private static final String QTY = "(?<qty>\\d+)";

	private ChatPatterns() {
	}

	/**
	 * Best-effort starting patterns for DonutSMP.
	 *
	 * <p>They are deliberately loose - anchored on the distinctive verb rather than
	 * the whole line - so cosmetic prefixes and rank tags do not break them.
	 */
	public static Map<String, List<String>> defaults() {
		Map<String, List<String>> out = new LinkedHashMap<>();

		out.put(ChatSignal.Kind.BALANCE.name(), List.of(
				"(?i)^(?:your )?balance(?: is)?:?\\s*" + AMOUNT,
				"(?i)^you (?:currently )?have\\s*" + AMOUNT + "(?:\\s|$)",
				"(?i)\\bbalance:\\s*" + AMOUNT));

		out.put(ChatSignal.Kind.MONEY_IN.name(), List.of(
				"(?i)^" + PLAYER + " (?:has )?(?:sent|paid|given) you " + AMOUNT,
				"(?i)^you (?:have )?received " + AMOUNT + " from " + PLAYER));

		out.put(ChatSignal.Kind.MONEY_OUT.name(), List.of(
				"(?i)^you (?:have )?(?:sent|paid) " + AMOUNT + " to " + PLAYER,
				"(?i)^you (?:have )?(?:sent|paid) " + PLAYER + " " + AMOUNT));

		out.put(ChatSignal.Kind.PURCHASE.name(), List.of(
				"(?i)^you (?:have )?(?:bought|purchased) " + QTY + "x? " + ITEM + " for " + AMOUNT,
				"(?i)^you (?:have )?(?:bought|purchased) " + ITEM + " for " + AMOUNT));

		out.put(ChatSignal.Kind.SALE.name(), List.of(
				"(?i)^you (?:have )?sold " + QTY + "x? " + ITEM + " for " + AMOUNT,
				"(?i)^you (?:have )?sold " + ITEM + " for " + AMOUNT));

		out.put(ChatSignal.Kind.KILL.name(), List.of(
				"(?i)^you (?:killed|slain) " + PLAYER,
				"(?i)^" + PLAYER + " was (?:slain|killed) by you"));

		out.put(ChatSignal.Kind.COMBAT_START.name(), List.of(
				"(?i)you are now in combat",
				"(?i)combat (?:tag|logging) (?:started|active)"));

		out.put(ChatSignal.Kind.COMBAT_END.name(), List.of(
				"(?i)you are no longer in combat",
				"(?i)(?:combat|you) (?:tag )?(?:has )?(?:expired|ended)"));

		return out;
	}

	/** A mutable copy of {@link #defaults()}, suitable for storing in config. */
	public static Map<String, List<String>> mutableDefaults() {
		Map<String, List<String>> out = new LinkedHashMap<>();
		defaults().forEach((k, v) -> out.put(k, new ArrayList<>(v)));
		return out;
	}
}
