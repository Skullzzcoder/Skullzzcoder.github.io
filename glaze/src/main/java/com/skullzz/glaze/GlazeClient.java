package com.skullzz.glaze;

import com.skullzz.glaze.config.ConfigIO;
import com.skullzz.glaze.config.PriceBookIO;
import com.skullzz.glaze.core.ChatParser;
import com.skullzz.glaze.core.GlazeConfig;
import com.skullzz.glaze.core.ListingParser;
import com.skullzz.glaze.core.PriceBook;
import com.skullzz.glaze.core.SessionStats;
import com.skullzz.glaze.feature.AuctionOverlay;
import com.skullzz.glaze.feature.AuctionScanner;
import com.skullzz.glaze.feature.ChatListener;
import com.skullzz.glaze.feature.QuickStash;
import com.skullzz.glaze.feature.SessionTracker;
import com.skullzz.glaze.feature.TooltipFeature;
import com.skullzz.glaze.feature.WarningsFeature;
import com.skullzz.glaze.hud.GlazeHud;
import com.skullzz.glaze.mc.GlazeCommands;
import com.skullzz.glaze.mc.Keybinds;
import com.skullzz.glaze.mc.Mc;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.util.Formatting;

/**
 * Entry point and shared state.
 *
 * <p>The mod is client-side and read-only by design: it watches chat, reads menus
 * you open, and draws what it learned. It does not send anything to the server on
 * your behalf unless you deliberately turn on the automation options, which are
 * off by default.
 */
public final class GlazeClient implements ClientModInitializer {
	/** Prices are written back this often, so a crash costs at most this much. */
	private static final int SAVE_INTERVAL_TICKS = 20 * 300;

	private static GlazeConfig config = new GlazeConfig().sanitised();
	private static final PriceBook PRICE_BOOK = new PriceBook();
	private static final SessionStats SESSION = new SessionStats();

	private static ChatParser chatParser = ChatParser.withDefaults();
	private static ListingParser listingParser = ListingParser.withDefaults();

	private static boolean onSupportedServer;
	private static int saveCounter;

	@Override
	public void onInitializeClient() {
		config = ConfigIO.load();
		PriceBookIO.load(PRICE_BOOK);
		rebuildParsers();
		applyRetention();

		Keybinds.register();
		GlazeCommands.register();
		ChatListener.register();
		SessionTracker.register();
		WarningsFeature.register();
		QuickStash.register();
		TooltipFeature.register();
		AuctionOverlay.register();
		GlazeHud.register();

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> saveAll());
		ClientTickEvents.END_CLIENT_TICK.register(client -> periodicSave());

		Glaze.LOG.info("Glaze ready");
	}

	private void onJoin() {
		long now = System.currentTimeMillis();
		onSupportedServer = !config.donutOnly
				|| Mc.serverAddress().contains(config.serverMatch.toLowerCase());

		SessionTracker.onJoin(now);
		WarningsFeature.reset();
		AuctionScanner.reset();

		if (onSupportedServer) {
			Mc.sendPrefixed("Ready. /glaze for commands. Run /bal once so the "
					+ "session tracker has a starting balance.", Formatting.GRAY);
		}
	}

	private void onDisconnect() {
		saveAll();
		onSupportedServer = false;
		AuctionScanner.reset();
		QuickStash.cancel();
	}

	private void periodicSave() {
		if (++saveCounter >= SAVE_INTERVAL_TICKS) {
			saveCounter = 0;
			PRICE_BOOK.pruneAll(System.currentTimeMillis());
			PriceBookIO.save(PRICE_BOOK);
		}
	}

	/**
	 * Whether the mod should be doing anything right now.
	 *
	 * <p>Every feature checks this so that a single server address decides the lot,
	 * rather than each feature growing its own idea of when it applies.
	 */
	public static boolean active() {
		return onSupportedServer && Mc.inGame();
	}

	public static GlazeConfig config() {
		return config;
	}

	public static PriceBook priceBook() {
		return PRICE_BOOK;
	}

	public static SessionStats session() {
		return SESSION;
	}

	public static ChatParser chatParser() {
		return chatParser;
	}

	public static ListingParser listingParser() {
		return listingParser;
	}

	public static void saveConfig() {
		ConfigIO.save(config);
	}

	/** Re-reads config.json and rebuilds anything derived from it. */
	public static void reloadConfig() {
		config = ConfigIO.load();
		rebuildParsers();
		applyRetention();
	}

	private static void saveAll() {
		ConfigIO.save(config);
		PriceBookIO.save(PRICE_BOOK);
	}

	/**
	 * Rebuilds the parsers from config and reports any regex that would not
	 * compile, since a silently dead pattern is very hard to notice.
	 */
	private static void rebuildParsers() {
		chatParser = new ChatParser(config.chatPatterns);
		listingParser = new ListingParser(config.listingPricePatterns, config.listingSellerPatterns);

		for (String error : chatParser.errors()) {
			Glaze.LOG.warn("Ignoring bad chat pattern - {}", error);
			Mc.sendPrefixed("Bad chat pattern: " + error, Formatting.RED);
		}
	}

	private static void applyRetention() {
		PRICE_BOOK.setRetention(config.economy.maxSamplesPerItem, config.priceRetentionMillis());
	}
}
