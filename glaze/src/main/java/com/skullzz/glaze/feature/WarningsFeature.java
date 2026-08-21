package com.skullzz.glaze.feature;

import com.skullzz.glaze.GlazeClient;
import com.skullzz.glaze.core.GlazeConfig;
import com.skullzz.glaze.core.PriceBook;
import com.skullzz.glaze.mc.Mc;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Formatting;

/**
 * Warns about gear about to break and consumables running low.
 *
 * <p>Both checks are throttled per subject, because a warning that fires every
 * tick is one you learn to ignore.
 */
public final class WarningsFeature {
	/** Checking every tick is wasteful; twice a second is well inside reaction time. */
	private static final int CHECK_INTERVAL_TICKS = 10;

	private static final Map<String, Long> lastWarned = new HashMap<>();
	private static int tickCounter;

	private WarningsFeature() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(WarningsFeature::tick);
	}

	/** Forgets throttle state, so a new session warns immediately. */
	public static void reset() {
		lastWarned.clear();
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || !GlazeClient.active()) {
			return;
		}

		if (++tickCounter < CHECK_INTERVAL_TICKS) {
			return;
		}

		tickCounter = 0;

		GlazeConfig config = GlazeClient.config();

		if (config.warnings.durability) {
			checkDurability(config);
		}

		if (config.warnings.lowConsumables) {
			checkConsumables(config);
		}
	}

	private static void checkDurability(GlazeConfig config) {
		for (ItemStack stack : Mc.inventoryStacks()) {
			if (!stack.isDamageable() || stack.getMaxDamage() <= 0) {
				continue;
			}

			int remaining = stack.getMaxDamage() - stack.getDamage();
			int percent = (int) Math.floor(100.0 * remaining / stack.getMaxDamage());

			if (percent > config.warnings.durabilityPercent) {
				continue;
			}

			String name = Mc.displayName(stack);

			if (shouldWarn("durability:" + name, config)) {
				warn(name + " is at " + percent + "% durability", Formatting.RED, config);
			}
		}
	}

	private static void checkConsumables(GlazeConfig config) {
		Map<String, Integer> counts = Mc.cachedInventoryCounts(false);

		config.warnings.consumableThresholds.forEach((item, threshold) -> {
			if (threshold == null || threshold <= 0) {
				return;
			}

			String key = PriceBook.key(item);
			int held = counts.getOrDefault(key, 0);

			// Only warn about something you were carrying and have run down. Warning
			// about every item you happen not to have would be constant noise.
			if (held == 0 || held >= threshold) {
				return;
			}

			if (shouldWarn("low:" + key, config)) {
				warn("Low on " + item + ": " + held + " left", Formatting.YELLOW, config);
			}
		});
	}

	private static boolean shouldWarn(String subject, GlazeConfig config) {
		long now = System.currentTimeMillis();
		long previous = lastWarned.getOrDefault(subject, 0L);

		if (now - previous < config.warnings.cooldownSeconds * 1000L) {
			return false;
		}

		lastWarned.put(subject, now);
		return true;
	}

	private static void warn(String message, Formatting colour, GlazeConfig config) {
		Mc.sendPrefixed(message, colour);

		if (config.warnings.playSound) {
			Mc.warnSound();
		}
	}
}
