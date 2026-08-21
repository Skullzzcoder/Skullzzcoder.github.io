package com.skullzz.glaze.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the player can change, as a plain data object.
 *
 * <p>Deliberately free of Minecraft types so it can be serialised, defaulted and
 * tested on its own. Fields are public because this is a config record read by a
 * JSON serialiser, not an encapsulated service.
 */
public final class GlazeConfig {
	/** Bumped when a migration is needed; written to the file for future use. */
	public int configVersion = 1;

	/**
	 * Keep the mod dormant unless the server address contains {@link #serverMatch}.
	 *
	 * <p>The price book and chat patterns are DonutSMP-shaped, so leaving this on
	 * avoids polluting them with prices from unrelated servers.
	 */
	public boolean donutOnly = true;
	public String serverMatch = "donutsmp";

	public Economy economy = new Economy();
	public Inventory inventory = new Inventory();
	public Warnings warnings = new Warnings();
	public Hud hud = new Hud();
	public Automation automation = new Automation();

	/** Chat patterns, editable so server wording changes do not need a new build. */
	public Map<String, List<String>> chatPatterns = ChatPatterns.mutableDefaults();
	public List<String> listingPricePatterns = new ArrayList<>(ListingParser.DEFAULT_PRICE_PATTERNS);
	public List<String> listingSellerPatterns = new ArrayList<>(ListingParser.DEFAULT_SELLER_PATTERNS);

	/** Your own saved places, including automatic death points. */
	public List<Waypoint> waypoints = new ArrayList<>();

	/** Named kits, checked with a keypress. Item names are lower-cased. */
	public Map<String, Map<String, Integer>> loadouts = defaultLoadouts();

	public static final class Economy {
		public boolean priceTooltips = true;
		public boolean stackValueTooltip = true;
		/** Record prices from auction menus you open. */
		public boolean recordPrices = true;
		/** Tint slots priced well under the median. */
		public boolean highlightDeals = true;
		/** A listing at or below this fraction of median counts as a deal. */
		public double dealThreshold = 0.70;
		/** Minimum samples before deals are called at all. */
		public int dealMinSamples = 5;
		/** Alert when a watched item shows up on a page you open. */
		public boolean watchlistAlerts = true;
		public List<String> watchlist = new ArrayList<>(List.of("elytra", "totem of undying"));
		/** Ceiling per watched item; zero means alert at any price. */
		public Map<String, Long> watchlistMaxPrice = new LinkedHashMap<>();
		public int priceRetentionDays = 14;
		public int maxSamplesPerItem = PriceBook.DEFAULT_MAX_SAMPLES;
		/** Use compact money ($1.2b) rather than grouped digits ($1,200,000,000). */
		public boolean compactMoney = true;
	}

	public static final class Inventory {
		public boolean shulkerPreview = true;
		public boolean shulkerValue = true;
		/** Show how many of the hovered item you hold in total. */
		public boolean totalCounts = true;
		public boolean loadoutChecker = true;
		public String activeLoadout = "pvp";
	}

	public static final class Warnings {
		public boolean durability = true;
		/** Warn once an item drops to this percentage of its maximum durability. */
		public int durabilityPercent = 15;
		public boolean lowConsumables = true;
		/** Item name to the count below which you want warning. */
		public Map<String, Integer> consumableThresholds = defaultConsumables();
		public boolean playSound = true;
		/** Seconds before the same warning may fire again. */
		public int cooldownSeconds = 30;
	}

	public static final class Hud {
		public boolean enabled = true;
		public double scale = 1.0;
		public boolean textShadow = true;
		public boolean background = true;
		public int backgroundOpacity = 100;
		/** Combat tag length in seconds, for the countdown readout. */
		public int combatTagSeconds = 15;
		public List<HudSpec> elements = defaultHudLayout();
	}

	/**
	 * The one feature that clicks for you.
	 *
	 * <p>Off by default and kept in its own group on purpose: everything else in
	 * this mod only reads and draws, while this sends real interactions to the
	 * server. See the rules note in the README before turning it on.
	 *
	 * <p>Quick-stash is deliberately built from shift-click moves only - one
	 * self-contained operation per slot, so an interrupted run can never leave
	 * items stranded on the cursor.
	 */
	public static final class Automation {
		public boolean quickStash = false;
		/** Minimum gap between synthetic clicks, in milliseconds. */
		public int clickDelayMillis = 120;
		/** Extra random jitter added to each gap. */
		public int clickJitterMillis = 40;
		/** Refuse to run if it would need more than this many clicks. */
		public int maxClicksPerAction = 64;
	}

	private static Map<String, Integer> defaultConsumables() {
		Map<String, Integer> out = new LinkedHashMap<>();
		out.put("ender pearl", 8);
		out.put("totem of undying", 2);
		out.put("enchanted golden apple", 4);
		out.put("end crystal", 8);
		return out;
	}

	private static Map<String, Map<String, Integer>> defaultLoadouts() {
		Map<String, Integer> pvp = new LinkedHashMap<>();
		pvp.put("totem of undying", 8);
		pvp.put("ender pearl", 16);
		pvp.put("enchanted golden apple", 16);
		pvp.put("end crystal", 32);
		pvp.put("obsidian", 64);

		Map<String, Integer> mining = new LinkedHashMap<>();
		mining.put("ender pearl", 4);
		mining.put("cooked beef", 32);
		mining.put("torch", 128);

		Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
		out.put("pvp", pvp);
		out.put("mining", mining);
		return out;
	}

	private static List<HudSpec> defaultHudLayout() {
		List<HudSpec> out = new ArrayList<>();
		int row = 4;

		// Left column: the session readouts you glance at constantly.
		for (String id : List.of(HudIds.SESSION_TIME, HudIds.BALANCE, HudIds.EARNED,
				HudIds.MONEY_PER_HOUR, HudIds.KILLS_DEATHS)) {
			out.add(new HudSpec(id, true, Anchor.TOP_LEFT, 4, row));
			row += 11;
		}

		// Right column: connection and position, on by default only where it is cheap.
		out.add(new HudSpec(HudIds.COORDS, true, Anchor.BOTTOM_LEFT, 4, 26));
		out.add(new HudSpec(HudIds.PING, true, Anchor.TOP_RIGHT, 4, 4));
		out.add(new HudSpec(HudIds.INVENTORY_VALUE, false, Anchor.TOP_RIGHT, 4, 15));
		out.add(new HudSpec(HudIds.WAYPOINT, false, Anchor.TOP_RIGHT, 4, 26));

		// Centre: things that need to catch your eye when they appear.
		out.add(new HudSpec(HudIds.COMBAT_TIMER, true, Anchor.TOP_CENTER, 0, 20));
		out.add(new HudSpec(HudIds.CONSUMABLES, true, Anchor.BOTTOM_CENTER, 0, 60));
		return out;
	}

	/**
	 * Repairs a config loaded from disk.
	 *
	 * <p>Hand editing and version upgrades both produce configs with missing or
	 * nonsensical values; every read path assumes this has run.
	 */
	public GlazeConfig sanitised() {
		if (economy == null) {
			economy = new Economy();
		}

		if (inventory == null) {
			inventory = new Inventory();
		}

		if (warnings == null) {
			warnings = new Warnings();
		}

		if (hud == null) {
			hud = new Hud();
		}

		if (automation == null) {
			automation = new Automation();
		}

		if (chatPatterns == null || chatPatterns.isEmpty()) {
			chatPatterns = ChatPatterns.mutableDefaults();
		}

		if (listingPricePatterns == null || listingPricePatterns.isEmpty()) {
			listingPricePatterns = new ArrayList<>(ListingParser.DEFAULT_PRICE_PATTERNS);
		}

		if (listingSellerPatterns == null || listingSellerPatterns.isEmpty()) {
			listingSellerPatterns = new ArrayList<>(ListingParser.DEFAULT_SELLER_PATTERNS);
		}

		if (loadouts == null) {
			loadouts = defaultLoadouts();
		}

		if (waypoints == null) {
			waypoints = new ArrayList<>();
		} else {
			waypoints.removeIf(w -> w == null || w.name == null || w.name.isBlank());
		}

		if (warnings.consumableThresholds == null) {
			warnings.consumableThresholds = defaultConsumables();
		}

		if (economy.watchlist == null) {
			economy.watchlist = new ArrayList<>();
		}

		if (economy.watchlistMaxPrice == null) {
			economy.watchlistMaxPrice = new LinkedHashMap<>();
		}

		economy.dealThreshold = clamp(economy.dealThreshold, 0.05, 1.0);
		economy.dealMinSamples = (int) clamp(economy.dealMinSamples, 1, 1_000);
		economy.priceRetentionDays = (int) clamp(economy.priceRetentionDays, 1, 365);
		economy.maxSamplesPerItem = (int) clamp(economy.maxSamplesPerItem, 5, 10_000);

		warnings.durabilityPercent = (int) clamp(warnings.durabilityPercent, 1, 99);
		warnings.cooldownSeconds = (int) clamp(warnings.cooldownSeconds, 1, 3_600);

		hud.scale = clamp(hud.scale, 0.5, 3.0);
		hud.backgroundOpacity = (int) clamp(hud.backgroundOpacity, 0, 255);
		hud.combatTagSeconds = (int) clamp(hud.combatTagSeconds, 1, 300);

		automation.clickDelayMillis = (int) clamp(automation.clickDelayMillis, 50, 2_000);
		automation.clickJitterMillis = (int) clamp(automation.clickJitterMillis, 0, 1_000);
		automation.maxClicksPerAction = (int) clamp(automation.maxClicksPerAction, 1, 500);

		if (hud.elements == null || hud.elements.isEmpty()) {
			hud.elements = defaultHudLayout();
		} else {
			hud.elements.removeIf(e -> e == null || e.id == null || e.id.isBlank());
			hud.elements.forEach(HudSpec::sanitised);

			// A config written by an older version will not know about newer readouts.
			List<String> known = hud.elements.stream().map(e -> e.id).toList();

			for (HudSpec fresh : defaultHudLayout()) {
				if (!known.contains(fresh.id)) {
					hud.elements.add(fresh);
				}
			}
		}

		if (serverMatch == null || serverMatch.isBlank()) {
			serverMatch = "donutsmp";
		}

		return this;
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	/** The spec for a readout, or null when the config does not mention it. */
	public HudSpec spec(String id) {
		for (HudSpec spec : hud.elements) {
			if (spec.id.equals(id)) {
				return spec;
			}
		}

		return null;
	}

	public long priceRetentionMillis() {
		return economy.priceRetentionDays * 24L * 60 * 60 * 1000;
	}

	/** The loadout the inventory checker compares against, empty if unset. */
	public Loadout activeLoadout() {
		Map<String, Integer> spec = loadouts.get(inventory.activeLoadout);
		return spec == null ? new Loadout(inventory.activeLoadout)
				: Loadout.of(inventory.activeLoadout, spec);
	}
}
