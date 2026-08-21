package com.skullzz.glaze.mc;

import com.skullzz.glaze.Glaze;
import com.skullzz.glaze.GlazeClient;
import com.skullzz.glaze.config.ConfigScreen;
import com.skullzz.glaze.core.GlazeConfig;
import com.skullzz.glaze.core.Loadout;
import com.skullzz.glaze.feature.AuctionScanner;
import com.skullzz.glaze.feature.QuickStash;
import com.skullzz.glaze.hud.HudEditScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

/** Key bindings, all unbound by default so nothing is stolen from other mods. */
public final class Keybinds {
	/**
	 * Category object rather than a translation key string - the constructor
	 * changed shape in 1.21.11.
	 */
	private static final KeyBinding.Category CATEGORY =
			KeyBinding.Category.create(net.minecraft.util.Identifier.of(Glaze.MOD_ID, "main"));

	private static KeyBinding openConfig;
	private static KeyBinding editHud;
	private static KeyBinding checkLoadout;
	private static KeyBinding clearFilter;
	private static KeyBinding quickStash;

	private Keybinds() {
	}

	public static void register() {
		openConfig = bind("key.glaze.config", GLFW.GLFW_KEY_UNKNOWN);
		editHud = bind("key.glaze.edit_hud", GLFW.GLFW_KEY_UNKNOWN);
		checkLoadout = bind("key.glaze.check_loadout", GLFW.GLFW_KEY_UNKNOWN);
		clearFilter = bind("key.glaze.clear_filter", GLFW.GLFW_KEY_UNKNOWN);
		quickStash = bind("key.glaze.quick_stash", GLFW.GLFW_KEY_UNKNOWN);

		ClientTickEvents.END_CLIENT_TICK.register(Keybinds::tick);
	}

	private static KeyBinding bind(String translationKey, int code) {
		return KeyBindingHelper.registerKeyBinding(new KeyBinding(translationKey, code, CATEGORY));
	}

	private static void tick(MinecraftClient client) {
		// wasPressed drains a queue, so every binding must be polled every tick or
		// presses pile up and fire late.
		boolean config = consume(openConfig);
		boolean hud = consume(editHud);
		boolean loadout = consume(checkLoadout);
		boolean filter = consume(clearFilter);
		boolean stash = consume(quickStash);

		// Quick-stash needs a container menu open, so it is handled before the
		// screen check that the other bindings sit behind.
		if (stash) {
			QuickStash.start();
		}

		if (client.currentScreen != null || client.player == null) {
			return;
		}

		if (config) {
			client.setScreen(new ConfigScreen(null));
		} else if (hud) {
			client.setScreen(new HudEditScreen(null));
		}

		if (loadout) {
			reportLoadout();
		}

		if (filter) {
			AuctionScanner.setFilter("");
			Mc.sendPrefixed("Auction filter cleared");
		}
	}

	private static boolean consume(KeyBinding binding) {
		boolean pressed = false;

		while (binding != null && binding.wasPressed()) {
			pressed = true;
		}

		return pressed;
	}

	/** Prints what the active loadout is missing. Reports only; moves nothing. */
	public static void reportLoadout() {
		GlazeConfig config = GlazeClient.config();

		if (!config.inventory.loadoutChecker) {
			return;
		}

		Loadout loadout = config.activeLoadout();

		if (loadout.isEmpty()) {
			Mc.sendPrefixed("No loadout called '" + config.inventory.activeLoadout + "'",
					Formatting.RED);
			return;
		}

		Loadout.Check check = loadout.check(Mc.cachedInventoryCounts(true));

		if (check.complete()) {
			Mc.sendPrefixed("Loadout '" + loadout.name() + "' is complete", Formatting.GREEN);
			Mc.beep(1.6F);
			return;
		}

		Mc.sendPrefixed("Loadout '" + loadout.name() + "' is missing:", Formatting.YELLOW);

		for (Loadout.Shortfall shortfall : check.shortfalls()) {
			Mc.send(Text.literal("  " + shortfall.missing() + "x " + shortfall.item())
					.formatted(Formatting.GRAY)
					.append(Text.literal(" (have " + shortfall.held() + "/" + shortfall.required() + ")")
							.formatted(Formatting.DARK_GRAY)));
		}

		Mc.warnSound();
	}
}
