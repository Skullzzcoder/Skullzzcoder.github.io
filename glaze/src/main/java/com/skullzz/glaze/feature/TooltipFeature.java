package com.skullzz.glaze.feature;

import com.skullzz.glaze.GlazeClient;
import com.skullzz.glaze.core.GlazeConfig;
import com.skullzz.glaze.core.PriceBook;
import com.skullzz.glaze.core.PriceStats;
import com.skullzz.glaze.mc.Mc;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Adds price, value and contents information to item tooltips.
 *
 * <p>Everything shown comes from prices this client has already seen. When an item
 * has no history the tooltip says so rather than inventing a number.
 */
public final class TooltipFeature {
	/** Contents lines to list before collapsing the rest into a summary. */
	private static final int MAX_CONTENT_LINES = 8;

	private TooltipFeature() {
	}

	public static void register() {
		ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
			if (!GlazeClient.active() || stack.isEmpty()) {
				return;
			}

			GlazeConfig config = GlazeClient.config();

			if (config.economy.priceTooltips) {
				appendPrice(stack, lines, config);
			}

			if (config.inventory.shulkerPreview && Mc.isContainer(stack)) {
				appendContents(stack, lines, config);
			}

			if (config.inventory.totalCounts) {
				appendHeldCount(stack, lines);
			}
		});
	}

	private static void appendPrice(ItemStack stack, List<Text> lines, GlazeConfig config) {
		String name = Mc.displayName(stack);
		Optional<PriceStats> stats = GlazeClient.priceBook().stats(name);

		if (stats.isEmpty()) {
			lines.add(Text.literal("No price data yet").formatted(Formatting.DARK_GRAY));
			return;
		}

		PriceStats s = stats.get();
		boolean compact = config.economy.compactMoney;

		lines.add(Text.literal("AH median ").formatted(Formatting.GRAY)
				.append(Text.literal(Mc.money(s.median(), compact)).formatted(Formatting.GOLD))
				.append(Text.literal(" each").formatted(Formatting.DARK_GRAY)));

		lines.add(Text.literal("low " + Mc.money(s.low(), compact)
						+ "  high " + Mc.money(s.high(), compact)
						+ "  (" + s.samples() + " seen)")
				.formatted(Formatting.DARK_GRAY));

		if (config.economy.stackValueTooltip && stack.getCount() > 1) {
			long stackValue = s.median() * stack.getCount();
			lines.add(Text.literal("this stack ").formatted(Formatting.GRAY)
					.append(Text.literal(Mc.money(stackValue, compact)).formatted(Formatting.GREEN)));
		}
	}

	private static void appendContents(ItemStack stack, List<Text> lines, GlazeConfig config) {
		List<ItemStack> contents = Mc.containerContents(stack);

		if (contents.isEmpty()) {
			lines.add(Text.literal("Empty").formatted(Formatting.DARK_GRAY));
			return;
		}

		// Merge duplicate stacks so a box of 27 diamond stacks reads as one line.
		Map<String, Integer> merged = new LinkedHashMap<>();
		long value = 0;

		for (ItemStack inner : contents) {
			String name = Mc.displayName(inner);
			merged.merge(name, inner.getCount(), Integer::sum);
			value += GlazeClient.priceBook().stats(name)
					.map(s -> s.median() * inner.getCount())
					.orElse(0L);
		}

		lines.add(Text.literal("Contents").formatted(Formatting.AQUA));

		int shown = 0;

		for (Map.Entry<String, Integer> entry : merged.entrySet()) {
			if (shown++ >= MAX_CONTENT_LINES) {
				lines.add(Text.literal("  ... and " + (merged.size() - MAX_CONTENT_LINES) + " more")
						.formatted(Formatting.DARK_GRAY));
				break;
			}

			lines.add(Text.literal("  " + entry.getValue() + "x " + entry.getKey())
					.formatted(Formatting.GRAY));
		}

		if (config.inventory.shulkerValue && value > 0) {
			lines.add(Text.literal("Contents worth ").formatted(Formatting.GRAY)
					.append(Text.literal(Mc.money(value, config.economy.compactMoney))
							.formatted(Formatting.GREEN)));
		}
	}

	private static void appendHeldCount(ItemStack stack, List<Text> lines) {
		String key = PriceBook.key(Mc.displayName(stack));
		int held = Mc.cachedInventoryCounts(true).getOrDefault(key, 0);

		// Only worth saying when there is more of it elsewhere than in this stack.
		if (held > stack.getCount()) {
			lines.add(Text.literal("You are carrying " + held + " in total")
					.formatted(Formatting.DARK_GRAY));
		}
	}
}
