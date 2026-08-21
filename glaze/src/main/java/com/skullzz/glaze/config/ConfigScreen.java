package com.skullzz.glaze.config;

import com.skullzz.glaze.GlazeClient;
import com.skullzz.glaze.core.GlazeConfig;
import com.skullzz.glaze.hud.HudEditScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Toggles for the features people switch on and off day to day.
 *
 * <p>The fiddly settings - chat patterns, watchlist ceilings, per-item thresholds -
 * live in {@code config/glaze/config.json}, which is a far better editor for a map
 * of regexes than a grid of buttons would be.
 */
public final class ConfigScreen extends Screen {
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int SPACING = 24;
	private static final int COLUMNS = 2;

	private final Screen parent;
	private final List<Toggle> toggles = new ArrayList<>();

	/** One boolean setting: how to read it, how to flip it, what to call it. */
	private record Toggle(String label, BooleanSupplier get, Consumer<Boolean> set) {
	}

	public ConfigScreen(Screen parent) {
		super(Text.literal("Glaze"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		GlazeConfig config = GlazeClient.config();
		toggles.clear();

		toggles.add(new Toggle("HUD", () -> config.hud.enabled, v -> config.hud.enabled = v));
		toggles.add(new Toggle("HUD background", () -> config.hud.background,
				v -> config.hud.background = v));
		toggles.add(new Toggle("Price tooltips", () -> config.economy.priceTooltips,
				v -> config.economy.priceTooltips = v));
		toggles.add(new Toggle("Record AH prices", () -> config.economy.recordPrices,
				v -> config.economy.recordPrices = v));
		toggles.add(new Toggle("Highlight deals", () -> config.economy.highlightDeals,
				v -> config.economy.highlightDeals = v));
		toggles.add(new Toggle("Watchlist alerts", () -> config.economy.watchlistAlerts,
				v -> config.economy.watchlistAlerts = v));
		toggles.add(new Toggle("Compact money", () -> config.economy.compactMoney,
				v -> config.economy.compactMoney = v));
		toggles.add(new Toggle("Shulker preview", () -> config.inventory.shulkerPreview,
				v -> config.inventory.shulkerPreview = v));
		toggles.add(new Toggle("Carried totals", () -> config.inventory.totalCounts,
				v -> config.inventory.totalCounts = v));
		toggles.add(new Toggle("Durability warnings", () -> config.warnings.durability,
				v -> config.warnings.durability = v));
		toggles.add(new Toggle("Low item warnings", () -> config.warnings.lowConsumables,
				v -> config.warnings.lowConsumables = v));
		toggles.add(new Toggle("Warning sounds", () -> config.warnings.playSound,
				v -> config.warnings.playSound = v));
		toggles.add(new Toggle("DonutSMP only", () -> config.donutOnly,
				v -> config.donutOnly = v));

		int top = 44;
		int totalWidth = COLUMNS * BUTTON_WIDTH + (COLUMNS - 1) * 8;
		int left = (width - totalWidth) / 2;

		for (int i = 0; i < toggles.size(); i++) {
			Toggle toggle = toggles.get(i);
			int column = i % COLUMNS;
			int row = i / COLUMNS;
			int x = left + column * (BUTTON_WIDTH + 8);
			int y = top + row * SPACING;

			addDrawableChild(ButtonWidget.builder(labelFor(toggle), button -> {
				toggle.set().accept(!toggle.get().getAsBoolean());
				GlazeClient.saveConfig();
				button.setMessage(labelFor(toggle));
			}).dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
		}

		int bottom = top + ((toggles.size() + COLUMNS - 1) / COLUMNS) * SPACING + 8;

		addDrawableChild(ButtonWidget.builder(Text.literal("Edit HUD layout"),
						button -> client.setScreen(new HudEditScreen(this)))
				.dimensions(width / 2 - BUTTON_WIDTH / 2, bottom, BUTTON_WIDTH, BUTTON_HEIGHT)
				.build());

		addDrawableChild(ButtonWidget.builder(Text.literal("Reload config from disk"),
						button -> {
							GlazeClient.reloadConfig();
							client.setScreen(new ConfigScreen(parent));
						})
				.dimensions(width / 2 - BUTTON_WIDTH / 2, bottom + SPACING, BUTTON_WIDTH, BUTTON_HEIGHT)
				.build());

		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
				.dimensions(width / 2 - BUTTON_WIDTH / 2, height - 28, BUTTON_WIDTH, BUTTON_HEIGHT)
				.build());
	}

	private static Text labelFor(Toggle toggle) {
		boolean on = toggle.get().getAsBoolean();

		return Text.literal(toggle.label() + ": ")
				.append(Text.literal(on ? "ON" : "OFF")
						.formatted(on ? Formatting.GREEN : Formatting.RED));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		context.drawCenteredTextWithShadow(textRenderer,
				Text.literal("Glaze").formatted(Formatting.GOLD), width / 2, 14, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(textRenderer,
				Text.literal("Patterns, thresholds and watchlists: config/glaze/config.json")
						.formatted(Formatting.DARK_GRAY),
				width / 2, 28, 0xFFFFFFFF);
	}

	@Override
	public void close() {
		GlazeClient.saveConfig();
		client.setScreen(parent);
	}
}
