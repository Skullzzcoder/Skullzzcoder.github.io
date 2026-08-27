package com.skullzz.donutgambler.data;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

/** The bet history: storage, the current session window, and every roll-up the UI asks for. */
public class BetLog {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** On-disk shape, versioned so the format can move later. */
	public static class Save {
		public int version = 1;
		@SerializedName("bets")
		public List<BetRecord> bets = new ArrayList<>();
	}

	private final Path file;
	private final List<BetRecord> bets = new ArrayList<>();
	private long sessionStart = System.currentTimeMillis();
	private int historyLimit = 20000;
	private boolean dirty;

	public BetLog(Path file) {
		this.file = file;
	}

	public void setHistoryLimit(int limit) {
		this.historyLimit = Math.max(100, limit);
		trim();
	}

	public String load() {
		bets.clear();

		if (!Files.isRegularFile(file)) return null;

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Save save = GSON.fromJson(reader, Save.class);

			if (save != null && save.bets != null) {
				for (BetRecord r : save.bets) {
					if (r != null && r.outcome != null) bets.add(r);
				}
			}

			bets.sort(Comparator.comparingLong(r -> r.time));
			return null;
		} catch (IOException | JsonParseException e) {
			return "could not read history.json (" + e.getMessage() + ")";
		}
	}

	public String save() {
		try {
			Path parent = file.getParent();
			if (parent != null) Files.createDirectories(parent);

			Save save = new Save();
			save.bets = bets;

			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");

			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(save, writer);
			}

			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			dirty = false;
			return null;
		} catch (IOException e) {
			return "could not write history.json (" + e.getMessage() + ")";
		}
	}

	public String saveIfDirty() {
		return dirty ? save() : null;
	}

	public boolean isDirty() {
		return dirty;
	}

	public void add(BetRecord record) {
		bets.add(record);
		trim();
		dirty = true;
	}

	/** Removes and returns the newest bet, or null when the log is empty. */
	public BetRecord removeLast() {
		if (bets.isEmpty()) return null;
		dirty = true;
		return bets.remove(bets.size() - 1);
	}

	public void clear() {
		bets.clear();
		dirty = true;
	}

	private void trim() {
		while (bets.size() > historyLimit) {
			bets.remove(0);
		}
	}

	public List<BetRecord> all() {
		return bets;
	}

	public int size() {
		return bets.size();
	}

	public long sessionStart() {
		return sessionStart;
	}

	public void startNewSession() {
		sessionStart = System.currentTimeMillis();
	}

	public List<BetRecord> sessionBets() {
		List<BetRecord> out = new ArrayList<>();

		for (BetRecord r : bets) {
			if (r.time >= sessionStart) out.add(r);
		}

		return out;
	}

	/** Newest first. */
	public List<BetRecord> recent(int count) {
		List<BetRecord> out = new ArrayList<>();

		for (int i = bets.size() - 1; i >= 0 && out.size() < count; i--) {
			out.add(bets.get(i));
		}

		return out;
	}

	public BetRecord last() {
		return bets.isEmpty() ? null : bets.get(bets.size() - 1);
	}

	public int betsSince(long since) {
		int n = 0;

		for (int i = bets.size() - 1; i >= 0; i--) {
			if (bets.get(i).time < since) break;
			n++;
		}

		return n;
	}

	public Agg aggAll() {
		return Agg.of("All time", bets);
	}

	public Agg aggSession() {
		return Agg.of("Session", sessionBets());
	}

	/** Per-game roll-ups, biggest absolute money moved first. */
	public Map<String, Agg> byGame() {
		Map<String, Agg> map = new LinkedHashMap<>();

		for (BetRecord r : bets) {
			Agg agg = map.computeIfAbsent(r.gameId, k -> {
				Agg a = new Agg();
				a.label = r.gameName == null || r.gameName.isBlank() ? r.gameId : r.gameName;
				return a;
			});
			agg.accept(r);
		}

		return sortByAbsNet(map);
	}

	/** Per-opponent roll-ups. Bets with no opponent (house games) are skipped. */
	public Map<String, Agg> byOpponent() {
		Map<String, Agg> map = new LinkedHashMap<>();

		for (BetRecord r : bets) {
			if (!r.hasOpponent()) continue;

			String key = r.opponent.toLowerCase(Locale.ROOT);
			Agg agg = map.computeIfAbsent(key, k -> {
				Agg a = new Agg();
				a.label = r.opponent;
				return a;
			});
			agg.accept(r);
		}

		return sortByAbsNet(map);
	}

	private static Map<String, Agg> sortByAbsNet(Map<String, Agg> map) {
		List<Map.Entry<String, Agg>> entries = new ArrayList<>(map.entrySet());
		entries.sort((a, b) -> Double.compare(Math.abs(b.getValue().net), Math.abs(a.getValue().net)));

		Map<String, Agg> sorted = new LinkedHashMap<>();

		for (Map.Entry<String, Agg> e : entries) {
			sorted.put(e.getKey(), e.getValue());
		}

		return sorted;
	}

	/**
	 * Cumulative profit over the last {@code count} bets, oldest first, starting at 0.
	 * Used by the dashboard chart.
	 */
	public double[] cumulative(int count) {
		int from = Math.max(0, bets.size() - count);
		double[] out = new double[bets.size() - from + 1];
		double running = 0;
		out[0] = 0;

		for (int i = from; i < bets.size(); i++) {
			running += bets.get(i).net;
			out[i - from + 1] = running;
		}

		return out;
	}

	/** CSV of the whole history, for spreadsheets. */
	public String toCsv() {
		StringBuilder sb = new StringBuilder("time_ms,iso_time,game,opponent,outcome,stake,net,source\n");

		for (BetRecord r : bets) {
			sb.append(r.time).append(',')
					.append(java.time.Instant.ofEpochMilli(r.time)).append(',')
					.append(csv(r.gameName)).append(',')
					.append(csv(r.opponent)).append(',')
					.append(r.outcome).append(',')
					.append(String.format(Locale.ROOT, "%.2f", r.stake)).append(',')
					.append(String.format(Locale.ROOT, "%.2f", r.net)).append(',')
					.append(csv(r.source)).append('\n');
		}

		return sb.toString();
	}

	private static String csv(String s) {
		if (s == null) return "";
		if (s.indexOf(',') < 0 && s.indexOf('"') < 0) return s;
		return '"' + s.replace("\"", "\"\"") + '"';
	}
}
