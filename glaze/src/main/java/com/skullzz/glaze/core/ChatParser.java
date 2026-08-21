package com.skullzz.glaze.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Turns raw server messages into {@link ChatSignal}s using a configurable pattern set.
 *
 * <p>A bad regex in config must never take the mod down with it, so patterns that
 * fail to compile are collected in {@link #errors()} and skipped rather than thrown.
 */
public final class ChatParser {
	/**
	 * Kinds are tried in this order. The money directions come first because
	 * "you paid X to Y" and "Y paid you X" are easy to confuse with looser patterns.
	 */
	private static final List<ChatSignal.Kind> ORDER = List.of(
			ChatSignal.Kind.MONEY_OUT,
			ChatSignal.Kind.MONEY_IN,
			ChatSignal.Kind.PURCHASE,
			ChatSignal.Kind.SALE,
			ChatSignal.Kind.BALANCE,
			ChatSignal.Kind.KILL,
			ChatSignal.Kind.COMBAT_START,
			ChatSignal.Kind.COMBAT_END);

	private final Map<ChatSignal.Kind, List<Pattern>> compiled = new EnumMap<>(ChatSignal.Kind.class);
	private final List<String> errors = new ArrayList<>();

	public ChatParser(Map<String, List<String>> raw) {
		for (ChatSignal.Kind kind : ChatSignal.Kind.values()) {
			List<String> sources = raw.getOrDefault(kind.name(), List.of());
			List<Pattern> patterns = new ArrayList<>(sources.size());

			for (String source : sources) {
				try {
					patterns.add(Pattern.compile(source));
				} catch (PatternSyntaxException e) {
					errors.add(kind.name() + ": " + e.getDescription() + " in /" + source + "/");
				}
			}

			compiled.put(kind, patterns);
		}
	}

	public static ChatParser withDefaults() {
		return new ChatParser(ChatPatterns.defaults());
	}

	/** Config patterns that would not compile, for surfacing to the player. */
	public List<String> errors() {
		return List.copyOf(errors);
	}

	/**
	 * Reads {@code rawLine}, returning the first signal any pattern recognises.
	 *
	 * <p>Colour codes are stripped first so patterns can be written against the
	 * plain text a player sees.
	 */
	public Optional<ChatSignal> parse(String rawLine) {
		String line = Money.clean(rawLine);

		if (line.isEmpty()) {
			return Optional.empty();
		}

		for (ChatSignal.Kind kind : ORDER) {
			for (Pattern pattern : compiled.getOrDefault(kind, List.of())) {
				Matcher m = pattern.matcher(line);

				if (m.find()) {
					return Optional.of(build(kind, m));
				}
			}
		}

		return Optional.empty();
	}

	private static ChatSignal build(ChatSignal.Kind kind, Matcher m) {
		return new ChatSignal(kind,
				group(m, "amount").flatMap(Money::parse).orElse(0L),
				group(m, "player").orElse(""),
				group(m, "item").map(String::trim).orElse(""),
				group(m, "qty").map(ChatParser::toInt).orElse(0));
	}

	/**
	 * Reads a named group that a given pattern may not declare at all.
	 * {@link Matcher#group(String)} throws for undeclared names, so probe first.
	 */
	private static Optional<String> group(Matcher m, String name) {
		try {
			String value = m.group(name);
			return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	private static int toInt(String s) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
