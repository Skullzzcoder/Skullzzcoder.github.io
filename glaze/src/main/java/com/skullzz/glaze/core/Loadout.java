package com.skullzz.glaze.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A kit you want to have on you, and the check that reports what is missing.
 *
 * <p>This deliberately only reports. It never moves an item or clicks a slot -
 * you glance at the list before a fight, top up yourself, and go.
 */
public final class Loadout {
	/** One shortfall: an item, how many you want, how many you actually have. */
	public record Shortfall(String item, int required, int held) {
		public int missing() {
			return Math.max(0, required - held);
		}
	}

	/** The outcome of comparing a loadout against your inventory. */
	public record Check(String loadoutName, List<Shortfall> shortfalls) {
		public boolean complete() {
			return shortfalls.isEmpty();
		}
	}

	private final String name;
	private final Map<String, Integer> required = new LinkedHashMap<>();

	public Loadout(String name) {
		this.name = name == null || name.isBlank() ? "unnamed" : name.trim();
	}

	public static Loadout of(String name, Map<String, Integer> required) {
		Loadout loadout = new Loadout(name);

		if (required != null) {
			required.forEach(loadout::require);
		}

		return loadout;
	}

	public String name() {
		return name;
	}

	/** Sets the wanted count for an item; a count of zero removes the entry. */
	public Loadout require(String item, int count) {
		String key = PriceBook.key(item);

		if (key.isEmpty()) {
			return this;
		}

		if (count <= 0) {
			required.remove(key);
		} else {
			required.put(key, count);
		}

		return this;
	}

	public Map<String, Integer> requirements() {
		return new LinkedHashMap<>(required);
	}

	public boolean isEmpty() {
		return required.isEmpty();
	}

	/**
	 * Compares this loadout against what you are carrying.
	 *
	 * @param held counts by item, keyed the same way as {@link PriceBook#key}
	 */
	public Check check(Map<String, Integer> held) {
		List<Shortfall> shortfalls = new ArrayList<>();

		required.forEach((item, want) -> {
			int have = held == null ? 0 : held.getOrDefault(item, 0);

			if (have < want) {
				shortfalls.add(new Shortfall(item, want, have));
			}
		});

		return new Check(name, List.copyOf(shortfalls));
	}
}
