package com.skullzz.glaze.hud;

import com.skullzz.glaze.Glaze;
import com.skullzz.glaze.GlazeClient;
import com.skullzz.glaze.core.GlazeConfig;
import com.skullzz.glaze.core.HudIds;
import com.skullzz.glaze.core.HudSpec;
import com.skullzz.glaze.core.Money;
import com.skullzz.glaze.core.PriceBook;
import com.skullzz.glaze.core.SessionStats;
import com.skullzz.glaze.core.Waypoint;
import com.skullzz.glaze.feature.CombatTracker;
import com.skullzz.glaze.feature.SessionTracker;
import com.skullzz.glaze.feature.WaypointFeature;
import com.skullzz.glaze.mc.Mc;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/** Draws the session readouts. */
public final class GlazeHud {
	private static final Identifier ID = Identifier.of(Glaze.MOD_ID, "hud");

	private static final int PADDING = 2;
	private static final int TEXT_COLOUR = 0xFFFFFFFF;

	/** Inventory value is a full scan plus a price lookup per stack; cache it. */
	private static final long VALUE_CACHE_MILLIS = 500;
	private static long inventoryValue;
	private static long inventoryValueAt;

	private GlazeHud() {
	}

	public static void register() {
		HudElementRegistry.addLast(ID, (context, tickCounter) -> render(context));
	}

	private static void render(DrawContext context) {
		MinecraftClient client = Mc.client();
		GlazeConfig config = GlazeClient.config();

		if (!config.hud.enabled || !GlazeClient.active() || client.player == null) {
			return;
		}

		// The HUD would sit on top of any open menu; the editor draws its own copy.
		if (client.currentScreen != null) {
			return;
		}

		draw(context, config, false);
	}

	/**
	 * Draws every enabled readout.
	 *
	 * @param editing when true, disabled readouts are shown too so they can be
	 *                positioned before being switched on
	 */
	public static void draw(DrawContext context, GlazeConfig config, boolean editing) {
		TextRenderer font = Mc.client().textRenderer;
		float scale = (float) config.hud.scale;

		context.getMatrices().pushMatrix();
		context.getMatrices().scale(scale, scale);

		int screenWidth = (int) (context.getScaledWindowWidth() / scale);
		int screenHeight = (int) (context.getScaledWindowHeight() / scale);

		for (HudSpec spec : config.hud.elements) {
			if (!spec.enabled && !editing) {
				continue;
			}

			Optional<Text> line = text(spec.id, config);

			if (line.isEmpty()) {
				continue;
			}

			drawElement(context, font, config, spec, line.get(), screenWidth, screenHeight, editing);
		}

		context.getMatrices().popMatrix();
	}

	private static void drawElement(DrawContext context, TextRenderer font, GlazeConfig config,
			HudSpec spec, Text line, int screenWidth, int screenHeight, boolean editing) {
		int width = font.getWidth(line) + PADDING * 2;
		int height = font.fontHeight + PADDING * 2;

		int x = spec.anchor.resolveX(spec.offsetX, width, screenWidth);
		int y = spec.anchor.resolveY(spec.offsetY, height, screenHeight);

		if (config.hud.background && config.hud.backgroundOpacity > 0) {
			int alpha = Math.min(255, config.hud.backgroundOpacity) << 24;
			context.fill(x, y, x + width, y + height, alpha);
		}

		if (editing) {
			// Outline every slot while editing so empty ones can still be grabbed.
			int outline = spec.enabled ? 0xFF55FF55 : 0xFF888888;
			context.fill(x, y, x + width, y + 1, outline);
			context.fill(x, y + height - 1, x + width, y + height, outline);
			context.fill(x, y, x + 1, y + height, outline);
			context.fill(x + width - 1, y, x + width, y + height, outline);
		}

		if (config.hud.textShadow) {
			context.drawTextWithShadow(font, line, x + PADDING, y + PADDING, TEXT_COLOUR);
		} else {
			context.drawText(font, line, x + PADDING, y + PADDING, TEXT_COLOUR, false);
		}
	}

	/** Measures a readout so the editor can hit-test it. */
	public static int[] measure(HudSpec spec, GlazeConfig config, int screenWidth, int screenHeight) {
		TextRenderer font = Mc.client().textRenderer;
		Text line = text(spec.id, config).orElse(Text.literal(HudIds.label(spec.id)));

		int width = font.getWidth(line) + PADDING * 2;
		int height = font.fontHeight + PADDING * 2;

		return new int[]{
				spec.anchor.resolveX(spec.offsetX, width, screenWidth),
				spec.anchor.resolveY(spec.offsetY, height, screenHeight),
				width,
				height
		};
	}

	/**
	 * The text for one readout, or empty when it has nothing to say right now.
	 *
	 * <p>An empty result hides the readout entirely rather than drawing a dash -
	 * a combat timer is only interesting while you are in combat.
	 */
	private static Optional<Text> text(String id, GlazeConfig config) {
		SessionStats session = GlazeClient.session();
		boolean compact = config.economy.compactMoney;

		return switch (id) {
			case HudIds.SESSION_TIME -> Optional.of(label("Time ",
					SessionTracker.describe(session), Formatting.WHITE));

			case HudIds.BALANCE -> session.balanceKnown()
					? Optional.of(label("Bal ", Mc.money(session.balance(), compact), Formatting.GOLD))
					: Optional.of(label("Bal ", "run /bal", Formatting.DARK_GRAY));

			case HudIds.EARNED -> {
				long net = session.netEarnings();
				Formatting colour = net >= 0 ? Formatting.GREEN : Formatting.RED;
				String prefix = net > 0 ? "+" : "";
				yield Optional.of(label("Session ", prefix + Mc.money(net, compact), colour));
			}

			case HudIds.MONEY_PER_HOUR -> {
				double rate = session.moneyPerHour();
				yield rate == 0
						? Optional.of(label("Rate ", "warming up", Formatting.DARK_GRAY))
						: Optional.of(label("Rate ", Money.perHour(rate),
								rate >= 0 ? Formatting.GREEN : Formatting.RED));
			}

			case HudIds.KILLS_DEATHS -> Optional.of(label("K/D ",
					session.kills() + "/" + session.deaths()
							+ String.format(" (%.2f)", session.killDeathRatio()),
					Formatting.WHITE));

			case HudIds.COORDS -> Optional.ofNullable(Mc.client().player)
					.map(p -> label("", (int) Math.floor(p.getX()) + ", "
							+ (int) Math.floor(p.getY()) + ", "
							+ (int) Math.floor(p.getZ()), Formatting.WHITE));

			case HudIds.PING -> {
				int ping = Mc.ping();
				yield ping < 0 ? Optional.empty()
						: Optional.of(label("Ping ", ping + "ms", pingColour(ping)));
			}

			case HudIds.COMBAT_TIMER -> CombatTracker.tagged()
					? Optional.of(label("COMBAT ", CombatTracker.remainingSeconds() + "s", Formatting.RED))
					: Optional.empty();

			case HudIds.INVENTORY_VALUE -> Optional.of(label("Inv ",
					Mc.money(inventoryValue(), compact), Formatting.GREEN));

			case HudIds.CONSUMABLES -> consumables(config);

			case HudIds.WAYPOINT -> waypoint();

			default -> Optional.empty();
		};
	}

	private static Text label(String prefix, String value, Formatting colour) {
		return Text.literal(prefix).formatted(Formatting.GRAY)
				.append(Text.literal(value).formatted(colour));
	}

	private static Formatting pingColour(int ping) {
		if (ping < 80) {
			return Formatting.GREEN;
		}

		return ping < 200 ? Formatting.YELLOW : Formatting.RED;
	}

	private static Optional<Text> consumables(GlazeConfig config) {
		Map<String, Integer> counts = Mc.cachedInventoryCounts(true);
		List<String> parts = new ArrayList<>();

		config.warnings.consumableThresholds.forEach((item, threshold) -> {
			int held = counts.getOrDefault(PriceBook.key(item), 0);
			parts.add(abbreviate(item) + " " + held);
		});

		return parts.isEmpty() ? Optional.empty()
				: Optional.of(Text.literal(String.join("  ", parts)).formatted(Formatting.AQUA));
	}

	/** "ender pearl" becomes "EP", so the row stays short. */
	private static String abbreviate(String item) {
		StringBuilder out = new StringBuilder();

		for (String word : item.split("\\s+")) {
			if (!word.isEmpty() && out.length() < 3) {
				out.append(Character.toUpperCase(word.charAt(0)));
			}
		}

		return out.isEmpty() ? item : out.toString();
	}

	private static Optional<Text> waypoint() {
		if (Mc.client().player == null) {
			return Optional.empty();
		}

		return WaypointFeature.nearest().map(w -> {
			var player = Mc.client().player;
			double distance = w.distanceTo(player.getX(), player.getZ());
			double bearing = w.bearingFrom(player.getX(), player.getZ(), player.getYaw());

			return label(Waypoint.arrow(bearing) + " ",
					w.name + " " + Math.round(distance) + "m", Formatting.LIGHT_PURPLE);
		});
	}

	private static long inventoryValue() {
		long now = System.currentTimeMillis();

		if (now - inventoryValueAt < VALUE_CACHE_MILLIS) {
			return inventoryValue;
		}

		long total = 0;

		for (Map.Entry<String, Integer> entry : Mc.cachedInventoryCounts(true).entrySet()) {
			total += GlazeClient.priceBook().stats(entry.getKey())
					.map(stats -> stats.median() * entry.getValue())
					.orElse(0L);
		}

		inventoryValue = total;
		inventoryValueAt = now;
		return total;
	}
}
