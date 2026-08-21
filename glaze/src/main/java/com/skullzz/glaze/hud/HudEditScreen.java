package com.skullzz.glaze.hud;

import com.skullzz.glaze.GlazeClient;
import com.skullzz.glaze.core.Anchor;
import com.skullzz.glaze.core.GlazeConfig;
import com.skullzz.glaze.core.HudIds;
import com.skullzz.glaze.core.HudSpec;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Drag-to-position editor for the HUD.
 *
 * <p>Left-drag moves a readout, right-click switches it on or off. On release the
 * readout is re-anchored to whichever corner it was dropped nearest, so it keeps
 * its place when the window is resized.
 *
 * <p>Mouse coordinates are taken from {@code render} rather than from the click
 * event, which keeps this working regardless of how the event record exposes its
 * position.
 */
public final class HudEditScreen extends Screen {
	private static final int LEFT_BUTTON = 0;
	private static final int RIGHT_BUTTON = 1;

	private final Screen parent;

	private int mouseX;
	private int mouseY;

	private HudSpec dragging;
	private int dragOffsetX;
	private int dragOffsetY;

	public HudEditScreen(Screen parent) {
		super(Text.literal("Glaze HUD layout"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
				.dimensions(width / 2 - 100, height - 28, 200, 20)
				.build());

		addDrawableChild(ButtonWidget.builder(Text.literal("Reset layout"), button -> resetLayout())
				.dimensions(width / 2 - 100, height - 52, 200, 20)
				.build());
	}

	private void resetLayout() {
		GlazeConfig config = GlazeClient.config();
		config.hud.elements = null;
		config.sanitised();
		GlazeClient.saveConfig();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		this.mouseX = mouseX;
		this.mouseY = mouseY;

		super.render(context, mouseX, mouseY, delta);

		GlazeConfig config = GlazeClient.config();

		if (dragging != null) {
			moveTo(config, dragging, hudX(config) - dragOffsetX, hudY(config) - dragOffsetY);
		}

		GlazeHud.draw(context, config, true);

		context.drawCenteredTextWithShadow(textRenderer,
				Text.literal("Drag to move, right-click to toggle").formatted(Formatting.GRAY),
				width / 2, 12, 0xFFFFFFFF);

		HudSpec hovered = specAt(config, hudX(config), hudY(config));

		if (hovered != null) {
			context.drawCenteredTextWithShadow(textRenderer,
					Text.literal(HudIds.label(hovered.id)
									+ (hovered.enabled ? "" : " (hidden)"))
							.formatted(hovered.enabled ? Formatting.GREEN : Formatting.GRAY),
					width / 2, 24, 0xFFFFFFFF);
		}
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (super.mouseClicked(click, doubled)) {
			return true;
		}

		GlazeConfig config = GlazeClient.config();
		HudSpec spec = specAt(config, hudX(config), hudY(config));

		if (spec == null) {
			return false;
		}

		if (click.button() == RIGHT_BUTTON) {
			spec.enabled = !spec.enabled;
			GlazeClient.saveConfig();
			return true;
		}

		if (click.button() == LEFT_BUTTON) {
			int[] box = GlazeHud.measure(spec, config, scaledWidth(config), scaledHeight(config));
			dragging = spec;
			dragOffsetX = hudX(config) - box[0];
			dragOffsetY = hudY(config) - box[1];
			return true;
		}

		return false;
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (dragging != null) {
			dragging = null;
			GlazeClient.saveConfig();
			return true;
		}

		return super.mouseReleased(click);
	}

	/**
	 * Re-anchors a readout to the corner it now sits nearest and stores the offset
	 * from that corner.
	 */
	private void moveTo(GlazeConfig config, HudSpec spec, int x, int y) {
		int screenWidth = scaledWidth(config);
		int screenHeight = scaledHeight(config);
		int[] box = GlazeHud.measure(spec, config, screenWidth, screenHeight);

		Anchor anchor = Anchor.nearest(x + box[2] / 2, y + box[3] / 2, screenWidth, screenHeight);
		spec.anchor = anchor;
		spec.offsetX = anchor.toOffsetX(x, box[2], screenWidth);
		spec.offsetY = anchor.toOffsetY(y, box[3], screenHeight);
	}

	private HudSpec specAt(GlazeConfig config, int x, int y) {
		int screenWidth = scaledWidth(config);
		int screenHeight = scaledHeight(config);

		// Walk backwards so the readout drawn last, and so on top, is picked first.
		for (int i = config.hud.elements.size() - 1; i >= 0; i--) {
			HudSpec spec = config.hud.elements.get(i);
			int[] box = GlazeHud.measure(spec, config, screenWidth, screenHeight);

			if (x >= box[0] && x < box[0] + box[2] && y >= box[1] && y < box[1] + box[3]) {
				return spec;
			}
		}

		return null;
	}

	/**
	 * The HUD is drawn under a scale transform, so screen-space mouse coordinates
	 * have to be divided by that scale before they can be compared with element
	 * positions. Getting this wrong makes dragging drift at any scale but 1.
	 */
	private int hudX(GlazeConfig config) {
		return (int) (mouseX / config.hud.scale);
	}

	private int hudY(GlazeConfig config) {
		return (int) (mouseY / config.hud.scale);
	}

	private int scaledWidth(GlazeConfig config) {
		return (int) (width / config.hud.scale);
	}

	private int scaledHeight(GlazeConfig config) {
		return (int) (height / config.hud.scale);
	}

	@Override
	public void close() {
		GlazeClient.saveConfig();
		client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
