package com.skullzz.donutgambler;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.skullzz.donutgambler.advisor.Advice;
import com.skullzz.donutgambler.advisor.Advisor;
import com.skullzz.donutgambler.advisor.OpponentFlag;
import com.skullzz.donutgambler.data.Agg;
import com.skullzz.donutgambler.config.ConfigManager;
import com.skullzz.donutgambler.config.GamblerConfig;
import com.skullzz.donutgambler.data.BetLog;

import net.fabricmc.loader.api.FabricLoader;

/** Mod-wide state: config, bet history, and the cached advice the HUD reads every frame. */
public final class DonutGambler {
	public static final String MOD_ID = "donutgambler";
	public static final String NAME = "Donut Gambler";
	public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

	/** Advice is recomputed at most this often, since the HUD asks for it every frame. */
	private static final long ADVICE_MAX_AGE_MS = 1000;
	/** Roll-ups are a full pass over the history, so they are cached between frames too. */
	private static final long STATS_MAX_AGE_MS = 250;

	private static ConfigManager configManager;
	private static BetLog betLog;
	private static Stats cachedStats;
	private static long statsComputedAt;
	private static boolean statsStale = true;
	private static Advice cachedAdvice;
	private static long adviceComputedAt;
	private static boolean adviceStale = true;
	private static boolean configDirty;

	private DonutGambler() {
	}

	public static void init() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
		configManager = new ConfigManager(dir.resolve("config.json"));
		String configProblem = configManager.load();

		if (configProblem != null) {
			LOGGER.warn("[{}] {}", NAME, configProblem);
		}

		betLog = new BetLog(dir.resolve("history.json"));
		String logProblem = betLog.load();

		if (logProblem != null) {
			LOGGER.warn("[{}] {}", NAME, logProblem);
		}

		betLog.setHistoryLimit(config().historyLimit);
		LOGGER.info("[{}] loaded {} bets and {} games", NAME, betLog.size(), config().games.size());
	}

	public static Path dataDir() {
		return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
	}

	public static ConfigManager configManager() {
		return configManager;
	}

	public static GamblerConfig config() {
		return configManager.get();
	}

	public static BetLog log() {
		return betLog;
	}

	/** Every roll-up the UI needs, computed together in one pass over the history. */
	public record Stats(Agg session, Agg allTime, Map<String, Agg> byGame, List<OpponentFlag> opponents) {
	}

	/**
	 * Cached statistics. The HUD and the screens ask for these every frame, and each one is a
	 * full walk of the bet history, so they are recomputed only when something changed.
	 */
	public static Stats stats() {
		long now = System.currentTimeMillis();

		if (cachedStats == null || statsStale || now - statsComputedAt > STATS_MAX_AGE_MS) {
			cachedStats = new Stats(betLog.aggSession(), betLog.aggAll(), betLog.byGame(),
					Advisor.opponentFlags(config(), betLog));
			statsComputedAt = now;
			statsStale = false;
		}

		return cachedStats;
	}

	/** Current advice, recomputed when the history/config changed or the cache aged out. */
	public static Advice advice() {
		long now = System.currentTimeMillis();

		if (cachedAdvice == null || adviceStale || now - adviceComputedAt > ADVICE_MAX_AGE_MS) {
			cachedAdvice = Advisor.evaluate(config(), betLog);
			adviceComputedAt = now;
			adviceStale = false;
		}

		return cachedAdvice;
	}

	/** Call after anything that changes the history or the settings. */
	public static void invalidateAdvice() {
		adviceStale = true;
		statsStale = true;
	}

	/** Marks the config as needing a write; the actual save is batched on the client tick. */
	public static void markConfigDirty() {
		configDirty = true;
		adviceStale = true;
		statsStale = true;
	}

	public static void saveAll() {
		if (configDirty) {
			String problem = configManager.save();
			configDirty = false;

			if (problem != null) LOGGER.warn("[{}] {}", NAME, problem);
		}

		String problem = betLog.saveIfDirty();

		if (problem != null) LOGGER.warn("[{}] {}", NAME, problem);
	}
}
