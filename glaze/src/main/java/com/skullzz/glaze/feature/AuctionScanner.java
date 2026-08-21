package com.skullzz.glaze.feature;

import com.skullzz.glaze.GlazeClient;
import com.skullzz.glaze.core.AuctionListing;
import com.skullzz.glaze.core.GlazeConfig;
import com.skullzz.glaze.core.PriceBook;
import com.skullzz.glaze.core.PriceStats;
import com.skullzz.glaze.mc.Mc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Formatting;

/**
 * Reads the auction menu that is currently open.
 *
 * <p>This only ever looks at a menu the player opened themselves - it does not
 * page, click, buy, or ask the server for anything. Every listing it sees is one
 * already sitting on the player's screen.
 */
public final class AuctionScanner {
	/** Listings on the page being viewed, keyed by slot index. */
	private static final Map<Integer, AuctionListing> current = new HashMap<>();
	/** Slots flagged as unusually cheap. */
	private static final Set<Integer> deals = new HashSet<>();
	/** Slots matching the active filter, empty when no filter is set. */
	private static final Set<Integer> filterMatches = new HashSet<>();
	/** Watchlist hits already announced, so a page does not re-alert every frame. */
	private static final Set<String> alerted = new HashSet<>();

	private static String filter = "";
	private static int lastScannedSlotCount;

	private AuctionScanner() {
	}

	public static void setFilter(String text) {
		filter = text == null ? "" : text.trim().toLowerCase();
	}

	public static String filter() {
		return filter;
	}

	public static boolean filtering() {
		return !filter.isEmpty();
	}

	public static Optional<AuctionListing> listingAt(int slot) {
		return Optional.ofNullable(current.get(slot));
	}

	public static boolean isDeal(int slot) {
		return deals.contains(slot);
	}

	public static boolean matchesFilter(int slot) {
		return filterMatches.contains(slot);
	}

	public static int listingCount() {
		return current.size();
	}

	/** Clears page state when a menu closes. */
	public static void reset() {
		current.clear();
		deals.clear();
		filterMatches.clear();
		alerted.clear();
		lastScannedSlotCount = 0;
	}

	/**
	 * Re-reads the open menu.
	 *
	 * <p>Called from the render pass, so it bails out early unless the menu's
	 * contents actually changed - the server sends a page as a burst of slot
	 * updates and re-parsing every frame would be wasted work.
	 */
	public static void scan(HandledScreen<?> screen) {
		GlazeConfig config = GlazeClient.config();
		List<Slot> slots = screen.getScreenHandler().slots;

		int signature = contentSignature(slots);

		if (signature == lastScannedSlotCount) {
			return;
		}

		lastScannedSlotCount = signature;
		current.clear();
		deals.clear();
		filterMatches.clear();

		List<AuctionListing> found = new ArrayList<>();

		for (Slot slot : slots) {
			ItemStack stack = slot.getStack();

			if (stack.isEmpty()) {
				continue;
			}

			String name = Mc.displayName(stack);

			if (filtering() && name.toLowerCase().contains(filter)) {
				filterMatches.add(slot.id);
			}

			GlazeClient.listingParser()
					.parse(name, stack.getCount(), Mc.lore(stack), slot.id)
					.ifPresent(listing -> {
						current.put(slot.id, listing);
						found.add(listing);
					});
		}

		found.forEach(listing -> classify(listing, config));
	}

	/**
	 * A cheap fingerprint of the menu's contents.
	 *
	 * <p>Slot count alone is not enough, since paging keeps the count identical.
	 * Mixing in item identity and stack sizes catches a page turn.
	 */
	private static int contentSignature(List<Slot> slots) {
		int hash = 17;

		for (Slot slot : slots) {
			ItemStack stack = slot.getStack();
			hash = hash * 31 + (stack.isEmpty() ? 0 : stack.getItem().hashCode());
			hash = hash * 31 + stack.getCount();
		}

		return hash;
	}

	private static void classify(AuctionListing listing, GlazeConfig config) {
		long unit = listing.unitPrice();
		Optional<PriceStats> stats = GlazeClient.priceBook().stats(listing.itemName());

		if (config.economy.highlightDeals && stats.isPresent()
				&& stats.get().isDeal(unit, config.economy.dealThreshold, config.economy.dealMinSamples)) {
			deals.add(listing.slot());
		}

		// Record after judging, so a page of listings cannot talk itself into being
		// a bargain by dragging its own median down first.
		if (config.economy.recordPrices) {
			GlazeClient.priceBook().record(listing.itemName(), listing.price(),
					listing.quantity(), System.currentTimeMillis(), "ah");
		}

		if (config.economy.watchlistAlerts) {
			checkWatchlist(listing, unit, config);
		}
	}

	private static void checkWatchlist(AuctionListing listing, long unit, GlazeConfig config) {
		String key = PriceBook.key(listing.itemName());

		for (String watched : config.economy.watchlist) {
			String watchedKey = PriceBook.key(watched);

			if (watchedKey.isEmpty() || !key.contains(watchedKey)) {
				continue;
			}

			Long ceiling = config.economy.watchlistMaxPrice.get(watchedKey);

			if (ceiling != null && ceiling > 0 && unit > ceiling) {
				continue;
			}

			String token = watchedKey + "@" + listing.price() + "#" + listing.slot();

			if (alerted.add(token)) {
				Mc.sendPrefixed("Watchlist: " + listing.itemName() + " at "
						+ Mc.money(listing.price(), config.economy.compactMoney)
						+ " (" + Mc.money(unit, config.economy.compactMoney) + " each)",
						Formatting.AQUA);
				Mc.beep(1.4F);
			}

			return;
		}
	}
}
