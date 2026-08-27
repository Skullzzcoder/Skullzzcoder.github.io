package com.skullzz.donutgambler.gui;

import java.util.List;

import com.skullzz.donutgambler.ClientChat;
import com.skullzz.donutgambler.DonutGambler;
import com.skullzz.donutgambler.chat.ChatWatcher;
import com.skullzz.donutgambler.chat.PatternBuilder;
import com.skullzz.donutgambler.config.GameDef;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Pick a real chat line, and the mod writes the regex for you: the money becomes an
 * {@code amount} group, the player name an {@code opponent} group.
 */
public class PatternBuilderScreen extends Screen {
	private static final int ROW_HEIGHT = 11;

	private final Screen parent;
	private final GameDef target;
	private final boolean preferWin;

	private List<String> lines = List.of();
	private int selected = -1;
	private double scroll;
	private String opponentName = "";
	private EditBox opponentBox;

	public PatternBuilderScreen(Screen parent, GameDef target) {
		this(parent, target, true);
	}

	public PatternBuilderScreen(Screen parent, GameDef target, boolean preferWin) {
		super(Component.literal("Pattern builder"));
		this.parent = parent;
		this.target = target;
		this.preferWin = preferWin;
	}

	@Override
	protected void init() {
		lines = ChatWatcher.recentLines();

		int margin = 20;
		opponentBox = Fields.text(font, margin + 120, 30, 140, 18, "Opponent", opponentName, 16,
				v -> opponentName = v);
		addRenderableWidget(opponentBox);

		int y = height - 28;
		Button useWin = Fields.button(margin, y, 130, 20, "Use as WIN pattern", () -> apply(true));
		Button useLoss = Fields.button(margin + 134, y, 130, 20, "Use as LOSS pattern", () -> apply(false));
		useWin.active = target != null;
		useLoss.active = target != null;
		addRenderableWidget(useWin);
		addRenderableWidget(useLoss);

		addRenderableWidget(Fields.button(margin + 268, y, 100, 20, "Copy regex", () -> {
			String regex = currentRegex();

			if (regex.isEmpty()) {
				ClientChat.info("Select a chat line first.");
				return;
			}

			Minecraft.getInstance().keyboardHandler.setClipboard(regex);
			ClientChat.info("Pattern copied to clipboard.");
		}));

		addRenderableWidget(Fields.button(width - margin - 60, y, 60, 20, "Back", this::onClose));
	}

	private void apply(boolean asWin) {
		String regex = currentRegex();

		if (target == null || regex.isEmpty()) {
			ClientChat.info("Select a chat line first.");
			return;
		}

		if (asWin) {
			target.winPattern = regex;
		} else {
			target.lossPattern = regex;
		}

		target.invalidate();
		DonutGambler.markConfigDirty();
		DonutGambler.saveAll();
		ClientChat.good("Set the " + (asWin ? "win" : "loss") + " pattern for " + target.name + ".");
		onClose();
	}

	private String currentRegex() {
		if (selected < 0 || selected >= lines.size()) return "";
		return PatternBuilder.fromLine(lines.get(selected), opponentName);
	}

	private int listTop() {
		return 54;
	}

	private int listHeight() {
		return height - 54 - 84;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);

		int margin = 20;
		graphics.drawString(font, "Pattern builder" + (target == null ? " (browse only)" : " for " + target.name),
				margin, 14, Theme.ACCENT);
		graphics.drawString(font, "Opponent in line:", margin, 36, Theme.MUTED);
		graphics.drawString(font, preferWin ? "Click the line the server prints when you WIN."
				: "Click the line the server prints when you LOSE.", margin + 270, 36, Theme.DIM);

		int top = listTop();
		int listH = listHeight();
		Theme.panel(graphics, margin, top, width - margin * 2, listH);

		graphics.enableScissor(margin + 1, top + 1, width - margin - 1, top + listH - 1);

		for (int i = 0; i < lines.size(); i++) {
			int y = top + 4 + i * ROW_HEIGHT - (int) scroll;
			if (y + ROW_HEIGHT < top || y > top + listH) continue;

			boolean hover = mouseY >= y && mouseY < y + ROW_HEIGHT && mouseX >= margin && mouseX <= width - margin;

			if (i == selected) {
				graphics.fill(margin + 2, y - 1, width - margin - 2, y + ROW_HEIGHT - 2, Theme.ROW_HOVER);
			} else if (hover) {
				graphics.fill(margin + 2, y - 1, width - margin - 2, y + ROW_HEIGHT - 2, Theme.ROW_ALT);
			}

			graphics.drawString(font, Theme.fit(font, lines.get(i), width - margin * 2 - 12), margin + 6, y,
					i == selected ? Theme.TEXT : Theme.MUTED);
		}

		graphics.disableScissor();

		if (lines.isEmpty()) {
			graphics.drawString(font, "No chat captured yet. Gamble once with the mod running, then come back.",
					margin + 6, top + 8, Theme.DIM);
		}

		int previewY = top + listH + 6;
		String regex = currentRegex();
		graphics.drawString(font, "Generated pattern:", margin, previewY, Theme.MUTED);

		if (regex.isEmpty()) {
			graphics.drawString(font, "(select a line above)", margin + 110, previewY, Theme.DIM);
		} else {
			int y = previewY + 11;

			for (String part : Theme.wrap(font, regex, width - margin * 2, 2)) {
				graphics.drawString(font, part, margin, y, Theme.GREEN);
				y += 10;
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;

		double mouseY = event.y();
		int top = listTop();

		if (mouseY >= top && mouseY <= top + listHeight()) {
			int index = (int) ((mouseY - top - 4 + scroll) / ROW_HEIGHT);

			if (index >= 0 && index < lines.size()) {
				selected = index;
				String detected = PatternBuilder.autoDetectName(lines.get(index));

				if (!detected.isEmpty()) {
					opponentName = detected;
					opponentBox.setValue(detected);
				}

				return true;
			}
		}

		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		double max = Math.max(0, lines.size() * ROW_HEIGHT + 8 - listHeight());
		scroll = Math.max(0, Math.min(max, scroll - amountY * 16));
		return true;
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
