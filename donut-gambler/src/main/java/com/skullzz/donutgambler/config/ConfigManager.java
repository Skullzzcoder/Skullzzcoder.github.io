package com.skullzz.donutgambler.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/** Loads and saves {@code config.json}. Kept free of Minecraft types so it can be unit-tested. */
public class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path file;
	private GamblerConfig config = GamblerConfig.createDefault();

	public ConfigManager(Path file) {
		this.file = file;
	}

	public GamblerConfig get() {
		return config;
	}

	public Path file() {
		return file;
	}

	/** Returns null on success, or a message describing why defaults were used instead. */
	public String load() {
		if (!Files.isRegularFile(file)) {
			config = GamblerConfig.createDefault();
			save();
			return null;
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			GamblerConfig loaded = GSON.fromJson(reader, GamblerConfig.class);

			if (loaded == null) {
				config = GamblerConfig.createDefault();
				return "config.json was empty; defaults restored";
			}

			config = repair(loaded);
			return null;
		} catch (IOException | JsonParseException e) {
			config = GamblerConfig.createDefault();
			return "could not read config.json (" + e.getMessage() + "); defaults used";
		}
	}

	/** Fills in anything a hand-edited or older config left null/absurd. */
	private static GamblerConfig repair(GamblerConfig c) {
		if (c.games == null) c.games = GamblerConfig.createDefault().games;
		if (c.hudAnchor == null) c.hudAnchor = HudAnchor.TOP_LEFT;
		if (c.balancePattern == null) c.balancePattern = new GamblerConfig().balancePattern;

		for (GameDef g : c.games) {
			if (g.id == null || g.id.isBlank()) g.id = GameDef.slug(g.name);
			if (g.name == null) g.name = g.id;
			if (g.winPattern == null) g.winPattern = "";
			if (g.lossPattern == null) g.lossPattern = "";
			if (g.pushPattern == null) g.pushPattern = "";
			if (g.notes == null) g.notes = "";
			g.invalidate();
		}

		c.kellyFraction = clamp(c.kellyFraction, 0.01, 1.0, 0.25);
		c.maxBetPercent = clamp(c.maxBetPercent, 0.01, 100.0, 5.0);
		c.hudScale = clamp(c.hudScale, 0.5, 3.0, 1.0);
		c.historyLimit = (int) clamp(c.historyLimit, 100, 500000, 20000);
		c.hudBackgroundAlpha = (int) clamp(c.hudBackgroundAlpha, 0, 255, 130);
		return c;
	}

	private static double clamp(double v, double min, double max, double fallback) {
		if (Double.isNaN(v) || v < min || v > max) return fallback;
		return v;
	}

	/** Returns null on success or an error message. Writes via a temp file so a crash cannot truncate it. */
	public String save() {
		try {
			Path parent = file.getParent();
			if (parent != null) Files.createDirectories(parent);

			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");

			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}

			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			return null;
		} catch (IOException e) {
			return "could not write config.json (" + e.getMessage() + ")";
		}
	}

	public void replace(GamblerConfig newConfig) {
		this.config = repair(newConfig);
		save();
	}
}
