package com.skullzz.donutgambler.command;

import java.util.Locale;
import java.util.Map;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import com.skullzz.donutgambler.DonutGambler;
import com.skullzz.donutgambler.advisor.Advice;
import com.skullzz.donutgambler.chat.ChatWatcher;
import com.skullzz.donutgambler.chat.MoneyParser;
import com.skullzz.donutgambler.config.GameDef;
import com.skullzz.donutgambler.data.Agg;
import com.skullzz.donutgambler.data.BetRecord;
import com.skullzz.donutgambler.data.Outcome;
import com.skullzz.donutgambler.gui.GamblerScreen;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** {@code /gambler ...} - all client-side; nothing is ever sent to the server. */
public final class GamblerCommands {
	private static final SuggestionProvider<FabricClientCommandSource> GAME_IDS = (context, builder) -> {
		for (GameDef game : DonutGambler.config().games) {
			builder.suggest(game.id);
		}

		return builder.buildFuture();
	};

	private static final SuggestionProvider<FabricClientCommandSource> RESULTS = (context, builder) -> {
		builder.suggest("win");
		builder.suggest("loss");
		builder.suggest("push");
		return builder.buildFuture();
	};

	private GamblerCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommandManager.literal("gambler")
						.executes(context -> openScreen())
						.then(ClientCommandManager.literal("stats").executes(context -> stats()))
						.then(ClientCommandManager.literal("advice").executes(context -> advice()))
						.then(ClientCommandManager.literal("hud").executes(context -> toggleHud()))
						.then(ClientCommandManager.literal("undo").executes(context -> undo()))
						.then(ClientCommandManager.literal("export").executes(context -> export()))
						.then(ClientCommandManager.literal("session")
								.then(ClientCommandManager.literal("reset").executes(context -> resetSession())))
						.then(ClientCommandManager.literal("balance")
								.then(ClientCommandManager.argument("amount", StringArgumentType.word())
										.executes(context -> setBalance(
												StringArgumentType.getString(context, "amount")))))
						.then(ClientCommandManager.literal("log")
								.then(ClientCommandManager.argument("game", StringArgumentType.word())
										.suggests(GAME_IDS)
										.then(ClientCommandManager.argument("result", StringArgumentType.word())
												.suggests(RESULTS)
												.then(ClientCommandManager.argument("amount", StringArgumentType.word())
														.executes(context -> logBet(context.getSource(),
																StringArgumentType.getString(context, "game"),
																StringArgumentType.getString(context, "result"),
																StringArgumentType.getString(context, "amount"),
																""))
														.then(ClientCommandManager.argument("opponent",
																		StringArgumentType.word())
																.executes(context -> logBet(context.getSource(),
																		StringArgumentType.getString(context, "game"),
																		StringArgumentType.getString(context, "result"),
																		StringArgumentType.getString(context, "amount"),
																		StringArgumentType.getString(context,
																				"opponent"))))))))));
	}

	private static int openScreen() {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> client.setScreen(new GamblerScreen()));
		return Command.SINGLE_SUCCESS;
	}

	private static int stats() {
		DonutGambler.Stats stats = DonutGambler.stats();
		Agg session = stats.session();
		Agg all = stats.allTime();

		feedback(String.format(Locale.ROOT, "Session %s over %d bets (%d-%d)",
				MoneyParser.formatSigned(session.net), session.bets, session.wins, session.losses),
				session.net >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED);
		feedback(String.format(Locale.ROOT, "All time %s over %d bets (%d-%d, ROI %s)",
				MoneyParser.formatSigned(all.net), all.bets, all.wins, all.losses,
				MoneyParser.signedPercent(all.roi())),
				all.net >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED);

		for (Map.Entry<String, Agg> entry : stats.byGame().entrySet()) {
			Agg agg = entry.getValue();
			feedback(String.format(Locale.ROOT, "  %s: %s (%d-%d)", agg.label,
					MoneyParser.formatSigned(agg.net), agg.wins, agg.losses), ChatFormatting.GRAY);
		}

		return Command.SINGLE_SUCCESS;
	}

	private static int advice() {
		Advice advice = DonutGambler.advice();
		feedback("[" + advice.verdict.label + "] " + advice.headline, switch (advice.verdict) {
		case RED -> ChatFormatting.RED;
		case YELLOW -> ChatFormatting.YELLOW;
		case GREEN -> ChatFormatting.GREEN;
		});

		for (Advice.Line line : advice.lines) {
			feedback("  " + line.text(), switch (line.severity()) {
			case BAD -> ChatFormatting.RED;
			case WARN -> ChatFormatting.YELLOW;
			case GOOD -> ChatFormatting.GREEN;
			case INFO -> ChatFormatting.GRAY;
			});
		}

		return Command.SINGLE_SUCCESS;
	}

	private static int toggleHud() {
		DonutGambler.config().hudEnabled = !DonutGambler.config().hudEnabled;
		DonutGambler.markConfigDirty();
		DonutGambler.saveAll();
		feedback("HUD " + (DonutGambler.config().hudEnabled ? "shown" : "hidden"), ChatFormatting.GRAY);
		return Command.SINGLE_SUCCESS;
	}

	private static int undo() {
		BetRecord removed = DonutGambler.log().removeLast();

		if (removed == null) {
			feedback("Nothing to undo.", ChatFormatting.GRAY);
			return 0;
		}

		if (DonutGambler.config().bankroll > 0) {
			DonutGambler.config().bankroll = Math.max(0, DonutGambler.config().bankroll - removed.net);
			DonutGambler.markConfigDirty();
		}

		DonutGambler.invalidateAdvice();
		DonutGambler.saveAll();
		feedback("Removed " + removed.gameName + " " + MoneyParser.formatSigned(removed.net), ChatFormatting.GRAY);
		return Command.SINGLE_SUCCESS;
	}

	private static int export() {
		try {
			java.nio.file.Path out = DonutGambler.dataDir().resolve("export-" + System.currentTimeMillis() + ".csv");
			java.nio.file.Files.createDirectories(out.getParent());
			java.nio.file.Files.writeString(out, DonutGambler.log().toCsv());
			feedback("Exported " + DonutGambler.log().size() + " bets to " + out, ChatFormatting.GREEN);
			return Command.SINGLE_SUCCESS;
		} catch (Exception e) {
			feedback("Export failed: " + e.getMessage(), ChatFormatting.RED);
			return 0;
		}
	}

	private static int resetSession() {
		DonutGambler.log().startNewSession();
		DonutGambler.config().sessionStartBankroll = DonutGambler.config().bankroll;
		DonutGambler.markConfigDirty();
		DonutGambler.invalidateAdvice();
		feedback("Session reset.", ChatFormatting.GRAY);
		return Command.SINGLE_SUCCESS;
	}

	private static int setBalance(String raw) {
		double amount = MoneyParser.parse(raw);

		if (Double.isNaN(amount) || amount < 0) {
			feedback("Could not read '" + raw + "'. Try /gambler balance 2.5m", ChatFormatting.RED);
			return 0;
		}

		DonutGambler.config().bankroll = amount;

		if (DonutGambler.config().sessionStartBankroll <= 0) {
			DonutGambler.config().sessionStartBankroll = amount;
		}

		DonutGambler.markConfigDirty();
		DonutGambler.saveAll();
		feedback("Bankroll set to " + MoneyParser.format(amount), ChatFormatting.GREEN);
		return Command.SINGLE_SUCCESS;
	}

	private static int logBet(FabricClientCommandSource source, String gameId, String result,
			String amountText, String opponent) {
		GameDef game = DonutGambler.config().gameById(gameId);

		if (game == null) {
			feedback("No game with id '" + gameId + "'. Ids: " + ids(), ChatFormatting.RED);
			return 0;
		}

		double amount = MoneyParser.parse(amountText);

		if (Double.isNaN(amount) || amount <= 0) {
			feedback("Could not read amount '" + amountText + "'.", ChatFormatting.RED);
			return 0;
		}

		Outcome outcome = Outcome.fromString(result.startsWith("w") ? "WIN"
				: result.startsWith("p") ? "PUSH" : "LOSS");
		double stake;
		double net;

		switch (outcome) {
		case WIN -> {
			if (game.amountIsProfit) {
				net = amount;
				stake = game.stakeForProfit(amount);
			} else {
				stake = amount;
				net = game.profitForStake(amount);
			}
		}
		case LOSS -> {
			stake = amount;
			net = -amount;
		}
		default -> {
			stake = amount;
			net = 0;
		}
		}

		ChatWatcher.recordBet(new BetRecord(System.currentTimeMillis(), game.id, game.name, opponent,
				stake, net, outcome, "manual", ""));
		DonutGambler.saveAll();
		return Command.SINGLE_SUCCESS;
	}

	private static String ids() {
		StringBuilder sb = new StringBuilder();

		for (GameDef game : DonutGambler.config().games) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(game.id);
		}

		return sb.toString();
	}

	private static void feedback(String text, ChatFormatting color) {
		Minecraft client = Minecraft.getInstance();

		if (client.player != null) {
			client.player.displayClientMessage(
					Component.literal("[Gambler] ").withStyle(ChatFormatting.GOLD)
							.append(Component.literal(text).withStyle(color)), false);
		}
	}
}
