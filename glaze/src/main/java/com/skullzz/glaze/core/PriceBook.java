package com.skullzz.glaze.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A rolling record of prices seen for each item.
 *
 * <p>Samples come from auction pages you open and trades you make - the mod never
 * queries anything on its own. Old and surplus samples are dropped so a price that
 * moved six months ago stops dragging the median around.
 */
public final class PriceBook {
	/** One observed price, normalised to a single item. */
	public record Sample(long unitPrice, long observedAt, String source) {
	}

	public static final int DEFAULT_MAX_SAMPLES = 200;
	public static final long DEFAULT_MAX_AGE_MILLIS = 14L * 24 * 60 * 60 * 1000;

	private final Map<String, List<Sample>> samples = new HashMap<>();
	private int maxSamples = DEFAULT_MAX_SAMPLES;
	private long maxAgeMillis = DEFAULT_MAX_AGE_MILLIS;

	public void setRetention(int maxSamples, long maxAgeMillis) {
		this.maxSamples = Math.max(1, maxSamples);
		this.maxAgeMillis = Math.max(1, maxAgeMillis);
	}

	/** Normalises an item key so "Diamond Sword" and "diamond sword" agree. */
	public static String key(String rawName) {
		return rawName == null ? "" : rawName.trim().toLowerCase();
	}

	/**
	 * Records a price for {@code item}.
	 *
	 * @param totalPrice the asking price for the whole listing
	 * @param quantity   how many items that price covers, at least 1
	 */
	public void record(String item, long totalPrice, int quantity, long now, String source) {
		String k = key(item);

		if (k.isEmpty() || totalPrice <= 0 || quantity <= 0) {
			return;
		}

		List<Sample> list = samples.computeIfAbsent(k, unused -> new ArrayList<>());
		list.add(new Sample(totalPrice / quantity, now, source == null ? "" : source));
		prune(list, now);
	}

	private void prune(List<Sample> list, long now) {
		list.removeIf(s -> now - s.observedAt() > maxAgeMillis);

		if (list.size() > maxSamples) {
			// Keep the newest window; sorting by time avoids assuming insertion order.
			list.sort(Comparator.comparingLong(Sample::observedAt));
			list.subList(0, list.size() - maxSamples).clear();
		}
	}

	/** Drops aged-out samples across every item; call occasionally, not per frame. */
	public void pruneAll(long now) {
		samples.values().forEach(list -> prune(list, now));
		samples.entrySet().removeIf(e -> e.getValue().isEmpty());
	}

	public Optional<PriceStats> stats(String item) {
		List<Sample> list = samples.get(key(item));

		if (list == null || list.isEmpty()) {
			return Optional.empty();
		}

		long[] prices = list.stream().mapToLong(Sample::unitPrice).sorted().toArray();
		long newest = list.stream()
				.max(Comparator.comparingLong(Sample::observedAt))
				.map(Sample::unitPrice)
				.orElse(prices[0]);

		return Optional.of(new PriceStats(
				prices.length,
				prices[0],
				quantile(prices, 0.25),
				quantile(prices, 0.50),
				quantile(prices, 0.75),
				prices[prices.length - 1],
				newest));
	}

	/**
	 * Nearest-rank quantile over a sorted array. Nearest-rank rather than an
	 * interpolating variant so every figure shown is a price that was really seen.
	 */
	static long quantile(long[] sorted, double q) {
		if (sorted.length == 0) {
			return 0;
		}

		int rank = (int) Math.ceil(q * sorted.length) - 1;
		return sorted[Math.min(sorted.length - 1, Math.max(0, rank))];
	}

	public int itemCount() {
		return samples.size();
	}

	public int sampleCount() {
		return samples.values().stream().mapToInt(List::size).sum();
	}


	/** Replaces all contents, used when loading from disk. */
	public void loadFrom(Map<String, List<Sample>> stored) {
		samples.clear();

		if (stored != null) {
			stored.forEach((k, v) -> {
				if (k != null && v != null && !v.isEmpty()) {
					samples.put(key(k), new ArrayList<>(v));
				}
			});
		}
	}

	/** A snapshot for writing to disk. */
	public Map<String, List<Sample>> snapshot() {
		Map<String, List<Sample>> out = new HashMap<>();
		samples.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
		return out;
	}
}
