package com.skullzz.donutgambler.gui;

import java.util.List;
import java.util.Locale;

import com.skullzz.donutgambler.DonutGambler;
import com.skullzz.donutgambler.advisor.Advisor;
import com.skullzz.donutgambler.advisor.MathUtil;
import com.skullzz.donutgambler.chat.BetMatcher;
import com.skullzz.donutgambler.chat.ChatWatcher;
import com.skullzz.donutgambler.chat.MoneyParser;
import com.skullzz.donutgambler.config.GameDef;
import com.skullzz.donutgambler.data.Agg;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Editor for one game: its chat patterns, its odds, and what those odds imply. */
public class GameEditScreen extends Screen {
	private final Screen parent;
	private final GameDef game;
	private String testResult = "";

	public GameEditScreen(Screen parent, GameDef game) {
		super(Component.literal("Edit game"));
		this.parent = parent;
		this.game = game;
	}

	@Override
	protected void init() {
		int margin = 20;
		int wide = width - margin * 2;
		int y = 44;

		addRenderableWidget(Fields.text(font, margin, y, 180, 18, "Name", game.name, 40, v -> {
			game.name = v;
			DonutGambler.markConfigDirty();
		}));
		addRenderableWidget(Fields.toggle(margin + 190, y, 110, 18, "Enabled",
				() -> game.enabled, v -> game.enabled = v));
		addRenderableWidget(Fields.toggle(margin + 306, y, 190, 18, "Amount in chat is winnings",
				() -> game.amountIsProfit, v -> game.amountIsProfit = v));

		y += 34;
		addRenderableWidget(Fields.text(font, margin, y, wide - 96, 18, "Win pattern", game.winPattern, 500, v -> {
			game.winPattern = v;
			game.invalidate();
			DonutGambler.markConfigDirty();
		}));
		addRenderableWidget(Fields.button(margin + wide - 92, y, 92, 18, "Build win", () ->
				Minecraft.getInstance().setScreen(new PatternBuilderScreen(this, game, true))));

		y += 26;
		addRenderableWidget(Fields.text(font, margin, y, wide - 96, 18, "Loss pattern", game.lossPattern, 500, v -> {
			game.lossPattern = v;
			game.invalidate();
			DonutGambler.markConfigDirty();
		}));
		addRenderableWidget(Fields.button(margin + wide - 92, y, 92, 18, "Build loss", () ->
				Minecraft.getInstance().setScreen(new PatternBuilderScreen(this, game, false))));

		y += 26;
		addRenderableWidget(Fields.text(font, margin, y, wide - 96, 18, "Push pattern (optional)",
				game.pushPattern, 500, v -> {
					game.pushPattern = v;
					game.invalidate();
					DonutGambler.markConfigDirty();
				}));
		addRenderableWidget(Fields.button(margin + wide - 92, y, 92, 18, "Test", this::runTest));

		y += 40;
		int boxW = 76;
		int gap = 96;
		addRenderableWidget(Fields.number(font, margin, y, boxW, 18, "Win chance",
				game.winChance * 100, 0, 100, v -> {
					game.winChance = v / 100;
					DonutGambler.markConfigDirty();
				}));
		addRenderableWidget(Fields.number(font, margin + gap, y, boxW, 18, "Payout",
				game.payout, 0, 1000, v -> {
					game.payout = v;
					DonutGambler.markConfigDirty();
				}));
		addRenderableWidget(Fields.number(font, margin + gap * 2, y, boxW, 18, "House tax",
				game.houseTaxPercent, 0, 100, v -> {
					game.houseTaxPercent = v;
					DonutGambler.markConfigDirty();
				}));
		addRenderableWidget(Fields.number(font, margin + gap * 3, y, boxW, 18, "Fallback stake",
				game.defaultStake, 0, 1e15, v -> {
					game.defaultStake = v;
					DonutGambler.markConfigDirty();
				}));

		addRenderableWidget(Fields.button(margin, height - 28, 110, 20, "Save and close", this::onClose));
		addRenderableWidget(Fields.button(margin + 114, height - 28, 110, 20, "Delete game", () -> {
			DonutGambler.config().games.remove(game);
			DonutGambler.markConfigDirty();
			onClose();
		}));
	}

	private void runTest() {
		List<String> lines = ChatWatcher.recentLines();

		if (lines.isEmpty()) {
			testResult = "No chat captured yet - play a few rounds, then test.";
			return;
		}

		int wins = game.winPattern.isBlank() ? 0 : BetMatcher.countMatches(game.winPattern, lines);
		int losses = game.lossPattern.isBlank() ? 0 : BetMatcher.countMatches(game.lossPattern, lines);
		testResult = String.format(Locale.ROOT,
				"Against the last %d chat lines: win pattern hits %s, loss pattern hits %s.",
				lines.size(), wins < 0 ? "INVALID REGEX" : String.valueOf(wins),
				losses < 0 ? "INVALID REGEX" : String.valueOf(losses));
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);

		int margin = 20;
		graphics.drawString(font, "Edit game: " + game.name, margin, 16, Theme.ACCENT);
		graphics.drawString(font, "Named groups: (?<amount>...) and (?<opponent>...)",
				margin, 28, Theme.DIM);

		int labelY = 44 + 34 + 26 + 26 + 40 - 11;
		graphics.drawString(font, "Win chance %", margin, labelY, Theme.MUTED);
		graphics.drawString(font, "Payout x", margin + 96, labelY, Theme.MUTED);
		graphics.drawString(font, "House tax %", margin + 192, labelY, Theme.MUTED);
		graphics.drawString(font, "Fallback stake", margin + 288, labelY, Theme.MUTED);

		int y = labelY + 40;
		double edge = game.edgePercent();
		graphics.drawString(font, String.format(Locale.ROOT,
				"Effective payout %.3fx  |  edge %+.2f%%  |  full Kelly %.2f%% of bankroll",
				game.effectivePayout(), edge, game.kellyFraction() * 100),
				margin, y, edge < 0 ? Theme.RED : Theme.GREEN);

		y += 12;
		double bet = Advisor.recommendedBet(DonutGambler.config(), game);
		graphics.drawString(font, bet > 0
				? "Suggested bet at your settings: " + MoneyParser.format(bet)
				: "Suggested bet: none - this game is -EV, so no stake is correct.",
				margin, y, bet > 0 ? Theme.TEXT : Theme.YELLOW);

		y += 12;
		Agg measured = DonutGambler.log().byGame().get(game.id);

		if (measured != null) {
			graphics.drawString(font, String.format(Locale.ROOT,
					"Measured: %d-%d (%s), net %s, 95%% CI %.0f-%.0f%%, needs ~%d bets to be sure",
					measured.wins, measured.losses, MoneyParser.percent(measured.winRate()),
					MoneyParser.formatSigned(measured.net), measured.winRateLower95() * 100,
					measured.winRateUpper95() * 100,
					MathUtil.samplesForPrecision(game.clampedChance(), 0.05)),
					margin, y, Theme.MUTED);
		} else {
			graphics.drawString(font, "Measured: nothing logged for this game yet.", margin, y, Theme.DIM);
		}

		y += 16;
		String problem = game.problem();

		if (problem != null) {
			graphics.drawString(font, "Problem: " + problem, margin, y, Theme.YELLOW);
			y += 12;
		}

		if (!testResult.isEmpty()) {
			for (String line : Theme.wrap(font, testResult, width - margin * 2, 3)) {
				graphics.drawString(font, line, margin, y, Theme.TEXT);
				y += 11;
			}
		}

		if (!game.notes.isBlank()) {
			for (String line : Theme.wrap(font, game.notes, width - margin * 2, 3)) {
				graphics.drawString(font, line, margin, y, Theme.DIM);
				y += 11;
			}
		}
	}

	@Override
	public void onClose() {
		game.id = game.id == null || game.id.isBlank() ? GameDef.slug(game.name) : game.id;
		game.invalidate();
		DonutGambler.markConfigDirty();
		DonutGambler.invalidateAdvice();
		DonutGambler.saveAll();
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
