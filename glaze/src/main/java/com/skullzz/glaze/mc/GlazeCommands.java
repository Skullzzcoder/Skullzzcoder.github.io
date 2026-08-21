package com.skullzz.glaze.mc;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.skullzz.glaze.GlazeClient;
import com.skullzz.glaze.core.GlazeConfig;
import com.skullzz.glaze.core.PriceStats;
import com.skullzz.glaze.core.Waypoint;
import com.skullzz.glaze.feature.AuctionScanner;
import com.skullzz.glaze.feature.ChatListener;
import com.skullzz.glaze.feature.WaypointFeature;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The {@code /glaze} client command.
 *
 * <p>Registered as a client command, so nothing here is ever sent to the server.
 */
public final class GlazeCommands {
	private GlazeCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommandManager.literal("glaze")
						.executes(context -> {
							help();
							return 1;
						})
						.then(ClientCommandManager.literal("stats").executes(context -> {
							stats();
							return 1;
						}))
						.then(ClientCommandManager.literal("loadout").executes(context -> {
							Keybinds.reportLoadout();
							return 1;
						}))
						.then(ClientCommandManager.literal("reload").executes(context -> {
							GlazeClient.reloadConfig();
							Mc.sendPrefixed("Config reloaded", Formatting.GREEN);
							return 1;
						}))
						.then(ClientCommandManager.literal("chatlog").executes(context -> {
							ChatListener.setLogging(!ChatListener.logging());
							Mc.sendPrefixed("Chat logging "
									+ (ChatListener.logging() ? "on" : "off")
									+ " - use it to write chat patterns against real messages");
							return 1;
						}))
						.then(ClientCommandManager.literal("filter")
								.executes(context -> {
									AuctionScanner.setFilter("");
									Mc.sendPrefixed("Auction filter cleared");
									return 1;
								})
								.then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
										.executes(context -> {
											String text = StringArgumentType.getString(context, "text");
											AuctionScanner.setFilter(text);
											Mc.sendPrefixed("Auction filter: " + text);
											return 1;
										})))
						.then(ClientCommandManager.literal("price")
								.then(ClientCommandManager.argument("item", StringArgumentType.greedyString())
										.executes(context -> {
											price(StringArgumentType.getString(context, "item"));
											return 1;
										})))
						.then(ClientCommandManager.literal("waypoint")
								.then(ClientCommandManager.literal("add")
										.then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
												.executes(context -> {
													addWaypoint(StringArgumentType.getString(context, "name"));
													return 1;
												})))
								.then(ClientCommandManager.literal("remove")
										.then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
												.executes(context -> {
													String name = StringArgumentType.getString(context, "name");
													Mc.sendPrefixed(WaypointFeature.remove(name)
															? "Removed waypoint " + name
															: "No waypoint called " + name);
													return 1;
												})))
								.then(ClientCommandManager.literal("list").executes(context -> {
									listWaypoints();
									return 1;
								})))));
	}

	private static void help() {
		Mc.sendPrefixed("Commands:", Formatting.GOLD);
		line("/glaze stats", "session summary");
		line("/glaze loadout", "what your kit is missing");
		line("/glaze price <item>", "price history for an item");
		line("/glaze filter <text>", "highlight matching auction slots");
		line("/glaze waypoint add|remove|list", "your own saved places");
		line("/glaze chatlog", "print raw chat, for writing patterns");
		line("/glaze reload", "re-read config.json from disk");
	}

	private static void line(String command, String description) {
		Mc.send(Text.literal("  " + command + " ").formatted(Formatting.YELLOW)
				.append(Text.literal("- " + description).formatted(Formatting.GRAY)));
	}

	private static void stats() {
		var session = GlazeClient.session();
		GlazeConfig config = GlazeClient.config();
		boolean compact = config.economy.compactMoney;

		Mc.sendPrefixed("Session", Formatting.GOLD);
		line("active", com.skullzz.glaze.core.SessionStats.formatDuration(session.activeMillis()));
		line("kills / deaths", session.kills() + " / " + session.deaths()
				+ String.format(" (%.2f)", session.killDeathRatio()));

		if (session.balanceKnown()) {
			line("balance", Mc.money(session.balance(), compact));
			line("net this session", Mc.money(session.netEarnings(), compact));
			line("rate", com.skullzz.glaze.core.Money.perHour(session.moneyPerHour()));
		} else {
			line("balance", "unknown - run /bal once so Glaze can read it");
		}

		line("received", Mc.money(session.grossIncome(), compact));
		line("spent", Mc.money(session.grossSpending(), compact));
		line("prices known", GlazeClient.priceBook().itemCount() + " items, "
				+ GlazeClient.priceBook().sampleCount() + " samples");
	}

	private static void price(String item) {
		GlazeConfig config = GlazeClient.config();
		boolean compact = config.economy.compactMoney;

		GlazeClient.priceBook().stats(item).ifPresentOrElse(stats -> {
			Mc.sendPrefixed(item, Formatting.GOLD);
			line("median", Mc.money(stats.median(), compact) + " each");
			line("range", Mc.money(stats.low(), compact) + " - " + Mc.money(stats.high(), compact));
			line("quartiles", Mc.money(stats.p25(), compact) + " / " + Mc.money(stats.p75(), compact));
			line("last seen", Mc.money(stats.newest(), compact));
			line("samples", String.valueOf(stats.samples()));
			warnIfThin(stats);
		}, () -> Mc.sendPrefixed("No prices recorded for '" + item
				+ "' yet - open an auction page containing it", Formatting.GRAY));
	}

	private static void warnIfThin(PriceStats stats) {
		if (stats.samples() < GlazeClient.config().economy.dealMinSamples) {
			Mc.send(Text.literal("  too few samples to judge deals yet")
					.formatted(Formatting.DARK_GRAY));
		}
	}

	private static void addWaypoint(String name) {
		WaypointFeature.add(name).ifPresentOrElse(
				w -> Mc.sendPrefixed("Saved " + w.name + " at " + w.coords(), Formatting.GREEN),
				() -> Mc.sendPrefixed("Could not save that waypoint", Formatting.RED));
	}

	private static void listWaypoints() {
		List<Waypoint> waypoints = WaypointFeature.all();

		if (waypoints.isEmpty()) {
			Mc.sendPrefixed("No waypoints saved");
			return;
		}

		Mc.sendPrefixed("Waypoints", Formatting.GOLD);

		for (Waypoint waypoint : waypoints) {
			line(waypoint.name, waypoint.coords());
		}
	}
}
