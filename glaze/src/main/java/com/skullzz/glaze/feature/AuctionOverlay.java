package com.skullzz.glaze.feature;

import com.skullzz.glaze.GlazeClient;
import com.skullzz.glaze.core.AuctionListing;
import com.skullzz.glaze.core.GlazeConfig;
import com.skullzz.glaze.core.PriceStats;
import com.skullzz.glaze.mc.Mc;
import com.skullzz.glaze.mixin.HandledScreenAccessor;
import java.util.Optional;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Draws the auction overlay: cheap listings tinted green, filter matches picked
 * out, everything else dimmed while a filter is active.
 *
 * <p>Drawing only. Nothing here can click a slot.
 */
public final class AuctionOverlay {
	private static final int SLOT_SIZE = 16;

	private static final int DEAL_TINT = 0x6033FF66;
	private static final int MATCH_TINT = 0x6033AAFF;
	private static final int DIM_TINT = 0xB0101010;

	private AuctionOverlay() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof HandledScreen<?>)) {
				return;
			}

			ScreenEvents.afterRender(screen).register(AuctionOverlay::render);
			ScreenEvents.remove(screen).register(ignored -> AuctionScanner.reset());
		});
	}

	private static void render(Screen screen, DrawContext context, int mouseX, int mouseY, float delta) {
		if (!GlazeClient.active() || !(screen instanceof HandledScreen<?> handled)) {
			return;
		}

		GlazeConfig config = GlazeClient.config();

		if (!config.economy.recordPrices && !config.economy.highlightDeals
				&& !AuctionScanner.filtering()) {
			return;
		}

		AuctionScanner.scan(handled);

		if (AuctionScanner.listingCount() == 0 && !AuctionScanner.filtering()) {
			return;
		}

		HandledScreenAccessor accessor = (HandledScreenAccessor) handled;
		int originX = accessor.glaze$getX();
		int originY = accessor.glaze$getY();

		for (Slot slot : handled.getScreenHandler().slots) {
			drawSlotTint(context, slot, originX, originY);
		}

		drawStatusLine(context, accessor);
		drawHoveredPrice(context, handled, mouseX, mouseY);
	}

	private static void drawSlotTint(DrawContext context, Slot slot, int originX, int originY) {
		int x = originX + slot.x;
		int y = originY + slot.y;

		if (AuctionScanner.filtering()) {
			if (AuctionScanner.matchesFilter(slot.id)) {
				fillSlot(context, x, y, MATCH_TINT);
			} else if (slot.hasStack()) {
				fillSlot(context, x, y, DIM_TINT);
			}

			return;
		}

		if (AuctionScanner.isDeal(slot.id)) {
			fillSlot(context, x, y, DEAL_TINT);
		}
	}

	private static void fillSlot(DrawContext context, int x, int y, int colour) {
		context.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, colour);
	}

	/** A line under the menu saying what the overlay is currently doing. */
	private static void drawStatusLine(DrawContext context, HandledScreenAccessor accessor) {
		String status;

		if (AuctionScanner.filtering()) {
			status = "filter: " + AuctionScanner.filter();
		} else if (AuctionScanner.listingCount() > 0) {
			status = AuctionScanner.listingCount() + " listings read";
		} else {
			return;
		}

		int x = accessor.glaze$getX();
		int y = accessor.glaze$getY() + accessor.glaze$getBackgroundHeight() + 4;

		context.drawTextWithShadow(Mc.client().textRenderer,
				Text.literal("[Glaze] " + status).formatted(Formatting.GOLD), x, y, 0xFFFFFFFF);
	}

	/**
	 * Shows how the hovered listing compares to its median.
	 *
	 * <p>Drawn beside the cursor rather than added to the item tooltip so it stays
	 * readable over the vanilla tooltip the game draws for the same slot.
	 */
	private static void drawHoveredPrice(DrawContext context, HandledScreen<?> handled,
			int mouseX, int mouseY) {
		Slot focused = handled.focusedSlot;

		if (focused == null) {
			return;
		}

		Optional<AuctionListing> listing = AuctionScanner.listingAt(focused.id);

		if (listing.isEmpty()) {
			return;
		}

		AuctionListing entry = listing.get();
		Optional<PriceStats> stats = GlazeClient.priceBook().stats(entry.itemName());

		if (stats.isEmpty()) {
			return;
		}

		double margin = stats.get().marginAgainstMedian(entry.unitPrice());
		int percent = (int) Math.round(Math.abs(margin) * 100);
		boolean cheap = margin > 0;

		String label = cheap
				? percent + "% under median"
				: percent + "% over median";

		context.drawTextWithShadow(Mc.client().textRenderer,
				Text.literal(label).formatted(cheap ? Formatting.GREEN : Formatting.RED),
				mouseX + 10, mouseY - 12, 0xFFFFFFFF);
	}
}
