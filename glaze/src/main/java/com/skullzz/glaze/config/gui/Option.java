package com.skullzz.glaze.config.gui;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * One row in the settings screen.
 *
 * <p>Rows draw themselves and handle their own clicks and drags. Each kind knows
 * how to read and write the config field it stands for, so the screen never has
 * to know what any particular setting means.
 */
public abstract class Option {
	protected static final int ROW_HEIGHT = 26;
	protected static final int CONTROL_WIDTH = 90;

	private final String title;
	private final String description;

	protected Option(String title, String description) {
		this.title = title;
		this.description = description == null ? "" : description;
	}

	public String title() {
		return title;
	}

	public String description() {
		return description;
	}

	public int height() {
		return description.isEmpty() ? ROW_HEIGHT : ROW_HEIGHT + 9;
	}

	/** Whether this row should survive the search filter. */
	public boolean matches(String query) {
		if (query.isEmpty()) {
			return true;
		}

		String q = query.toLowerCase(Locale.ROOT);
		return title.toLowerCase(Locale.ROOT).contains(q)
				|| description.toLowerCase(Locale.ROOT).contains(q);
	}

	public void render(DrawContext context, TextRenderer font, int x, int y, int width,
			int mouseX, int mouseY) {
		boolean hovered = Theme.within(mouseX, mouseY, x, y, width, height());
		Theme.card(context, x, y, width, height(), hovered);

		context.drawTextWithShadow(font, Text.literal(title), x + 8, y + 8, Theme.TEXT);

		if (!description.isEmpty()) {
			context.drawText(font, Text.literal(description), x + 8, y + 20, Theme.TEXT_FAINT, false);
		}

		renderControl(context, font, x, y, width, mouseX, mouseY, hovered);
	}

	protected abstract void renderControl(DrawContext context, TextRenderer font, int x, int y,
			int width, int mouseX, int mouseY, boolean hovered);

	/** @return true when the click was consumed */
	public boolean click(int x, int y, int width, int mouseX, int mouseY, int button) {
		return false;
	}

	/** Called while the mouse is held after this row consumed a click. */
	public void drag(int x, int y, int width, int mouseX) {
	}

	/** Whether this row wants drag events after being clicked. */
	public boolean draggable() {
		return false;
	}

	protected int controlX(int x, int width) {
		return x + width - CONTROL_WIDTH - 8;
	}

	/** A boolean setting, shown as a switch. */
	public static final class Toggle extends Option {
		private static final int SWITCH_WIDTH = 28;
		private static final int SWITCH_HEIGHT = 14;

		private final BooleanSupplier getter;
		private final Consumer<Boolean> setter;
		private final Runnable onChange;

		public Toggle(String title, String description, BooleanSupplier getter,
				Consumer<Boolean> setter, Runnable onChange) {
			super(title, description);
			this.getter = getter;
			this.setter = setter;
			this.onChange = onChange;
		}

		private int switchX(int x, int width) {
			return x + width - SWITCH_WIDTH - 10;
		}

		private int switchY(int y) {
			return y + (ROW_HEIGHT - SWITCH_HEIGHT) / 2;
		}

		@Override
		protected void renderControl(DrawContext context, TextRenderer font, int x, int y,
				int width, int mouseX, int mouseY, boolean hovered) {
			boolean on = getter.getAsBoolean();
			int sx = switchX(x, width);
			int sy = switchY(y);

			Theme.toggle(context, sx, sy, SWITCH_WIDTH, SWITCH_HEIGHT, on,
					Theme.within(mouseX, mouseY, sx, sy, SWITCH_WIDTH, SWITCH_HEIGHT));

			String label = on ? "ON" : "OFF";
			context.drawText(font, Text.literal(label),
					sx - font.getWidth(label) - 6, y + 9, on ? Theme.ON : Theme.TEXT_FAINT, false);
		}

		@Override
		public boolean click(int x, int y, int width, int mouseX, int mouseY, int button) {
			if (!Theme.within(mouseX, mouseY, x, y, width, height())) {
				return false;
			}

			setter.accept(!getter.getAsBoolean());
			onChange.run();
			return true;
		}
	}

	/** A numeric setting, shown as a slider with its current value. */
	public static final class Slider extends Option {
		private static final int TRACK_WIDTH = 76;
		private static final int TRACK_HEIGHT = 12;

		private final DoubleSupplier getter;
		private final Consumer<Double> setter;
		private final Runnable onChange;
		private final double min;
		private final double max;
		private final double step;
		private final Formatter formatter;

		/** How a slider's current value is written out next to it. */
		public interface Formatter {
			String format(double value);
		}

		public Slider(String title, String description, double min, double max, double step,
				DoubleSupplier getter, Consumer<Double> setter, Formatter formatter,
				Runnable onChange) {
			super(title, description);
			this.min = min;
			this.max = max;
			this.step = step;
			this.getter = getter;
			this.setter = setter;
			this.formatter = formatter;
			this.onChange = onChange;
		}

		/** Convenience for whole-number settings. */
		public static Slider ofInt(String title, String description, int min, int max,
				IntSupplier getter, Consumer<Integer> setter, String suffix, Runnable onChange) {
			return new Slider(title, description, min, max, 1,
					() -> getter.getAsInt(),
					value -> setter.accept((int) Math.round(value)),
					value -> (long) value + suffix,
					onChange);
		}

		private int trackX(int x, int width) {
			return x + width - TRACK_WIDTH - 10;
		}

		private int trackY(int y) {
			return y + (ROW_HEIGHT - TRACK_HEIGHT) / 2;
		}

		private double fraction() {
			return max <= min ? 0 : (getter.getAsDouble() - min) / (max - min);
		}

		@Override
		protected void renderControl(DrawContext context, TextRenderer font, int x, int y,
				int width, int mouseX, int mouseY, boolean hovered) {
			int tx = trackX(x, width);
			int ty = trackY(y);

			Theme.slider(context, tx, ty, TRACK_WIDTH, TRACK_HEIGHT, fraction(),
					Theme.within(mouseX, mouseY, tx, ty, TRACK_WIDTH, TRACK_HEIGHT));

			String label = formatter.format(getter.getAsDouble());
			context.drawText(font, Text.literal(label),
					tx - font.getWidth(label) - 8, y + 9, Theme.ACCENT, false);
		}

		@Override
		public boolean click(int x, int y, int width, int mouseX, int mouseY, int button) {
			int tx = trackX(x, width);
			int ty = trackY(y);

			// Only the track takes the click, so clicking the row's label does not
			// jump the value to wherever the cursor happened to be.
			if (!Theme.within(mouseX, mouseY, tx - 2, ty - 3, TRACK_WIDTH + 4, TRACK_HEIGHT + 6)) {
				return false;
			}

			applyFromMouse(tx, mouseX);
			return true;
		}

		@Override
		public boolean draggable() {
			return true;
		}

		@Override
		public void drag(int x, int y, int width, int mouseX) {
			applyFromMouse(trackX(x, width), mouseX);
		}

		private void applyFromMouse(int trackX, int mouseX) {
			double fraction = Theme.clamp01((mouseX - trackX) / (double) TRACK_WIDTH);
			double raw = min + fraction * (max - min);
			double snapped = step <= 0 ? raw : Math.round(raw / step) * step;
			setter.accept(Math.max(min, Math.min(max, snapped)));
			onChange.run();
		}
	}

	/** A setting that cycles through a fixed set of values. */
	public static final class Cycle extends Option {
		private final Supplier<List<String>> values;
		private final Supplier<String> getter;
		private final Consumer<String> setter;
		private final Runnable onChange;

		public Cycle(String title, String description, Supplier<List<String>> values,
				Supplier<String> getter, Consumer<String> setter, Runnable onChange) {
			super(title, description);
			this.values = values;
			this.getter = getter;
			this.setter = setter;
			this.onChange = onChange;
		}

		@Override
		protected void renderControl(DrawContext context, TextRenderer font, int x, int y,
				int width, int mouseX, int mouseY, boolean hovered) {
			int bx = controlX(x, width);
			int by = y + 5;

			Theme.rounded(context, bx, by, CONTROL_WIDTH, 16, Theme.PANEL_RAISED);
			Theme.outline(context, bx, by, CONTROL_WIDTH, 16,
					Theme.within(mouseX, mouseY, bx, by, CONTROL_WIDTH, 16)
							? Theme.ACCENT : Theme.BORDER);

			String current = getter.get();
			context.drawText(font, Text.literal(current),
					bx + CONTROL_WIDTH / 2 - font.getWidth(current) / 2, by + 4, Theme.TEXT, false);
		}

		@Override
		public boolean click(int x, int y, int width, int mouseX, int mouseY, int button) {
			int bx = controlX(x, width);
			int by = y + 5;

			if (!Theme.within(mouseX, mouseY, bx, by, CONTROL_WIDTH, 16)) {
				return false;
			}

			List<String> options = values.get();

			if (options.isEmpty()) {
				return true;
			}

			int index = options.indexOf(getter.get());
			// Right-click steps backwards, which saves a lap round a long list.
			int delta = button == 1 ? -1 : 1;
			int next = Math.floorMod(index + delta, options.size());
			setter.accept(options.get(next));
			onChange.run();
			return true;
		}
	}

	/** A button that does something rather than storing a value. */
	public static final class Action extends Option {
		private final String label;
		private final Runnable action;
		private final int colour;

		public Action(String title, String description, String label, int colour, Runnable action) {
			super(title, description);
			this.label = label;
			this.colour = colour;
			this.action = action;
		}

		@Override
		protected void renderControl(DrawContext context, TextRenderer font, int x, int y,
				int width, int mouseX, int mouseY, boolean hovered) {
			int bx = controlX(x, width);
			int by = y + 5;
			boolean over = Theme.within(mouseX, mouseY, bx, by, CONTROL_WIDTH, 16);

			Theme.rounded(context, bx, by, CONTROL_WIDTH, 16, over ? Theme.PANEL_RAISED : Theme.PANEL);
			Theme.outline(context, bx, by, CONTROL_WIDTH, 16, over ? colour : Theme.BORDER);
			context.drawText(font, Text.literal(label),
					bx + CONTROL_WIDTH / 2 - font.getWidth(label) / 2, by + 4, colour, false);
		}

		@Override
		public boolean click(int x, int y, int width, int mouseX, int mouseY, int button) {
			int bx = controlX(x, width);
			int by = y + 5;

			if (!Theme.within(mouseX, mouseY, bx, by, CONTROL_WIDTH, 16)) {
				return false;
			}

			action.run();
			return true;
		}
	}

	/** Static text, for notes and warnings between rows. */
	public static final class Note extends Option {
		private final int colour;

		public Note(String text, int colour) {
			super(text, "");
			this.colour = colour;
		}

		@Override
		public int height() {
			return 18;
		}

		@Override
		public void render(DrawContext context, TextRenderer font, int x, int y, int width,
				int mouseX, int mouseY) {
			context.drawText(font, Text.literal(title()), x + 4, y + 5, colour, false);
		}

		@Override
		protected void renderControl(DrawContext context, TextRenderer font, int x, int y,
				int width, int mouseX, int mouseY, boolean hovered) {
		}
	}

	/**
	 * An editable list of entries, each removable with the cross beside it.
	 *
	 * <p>Adding is handled by the screen's shared text box rather than a per-row
	 * field, so only one text input ever exists and focus never has to be juggled
	 * between a dozen of them.
	 */
	public static final class ListEdit extends Option {
		private static final int ENTRY_HEIGHT = 14;
		private static final int REMOVE_SIZE = 11;

		private final Supplier<List<String>> entries;
		private final Consumer<String> remove;

		public ListEdit(String title, String description, Supplier<List<String>> entries,
				Consumer<String> remove) {
			super(title, description);
			this.entries = entries;
			this.remove = remove;
		}

		@Override
		public int height() {
			int rows = Math.max(1, entries.get().size());
			return 22 + rows * ENTRY_HEIGHT + 4;
		}

		@Override
		public void render(DrawContext context, TextRenderer font, int x, int y, int width,
				int mouseX, int mouseY) {
			Theme.card(context, x, y, width, height(), false);
			context.drawTextWithShadow(font, Text.literal(title()), x + 8, y + 7, Theme.TEXT);

			List<String> values = entries.get();

			if (values.isEmpty()) {
				context.drawText(font, Text.literal("empty"), x + 12, y + 24, Theme.TEXT_FAINT, false);
				return;
			}

			int row = y + 22;

			for (String entry : values) {
				int removeX = x + width - REMOVE_SIZE - 10;
				boolean over = Theme.within(mouseX, mouseY, removeX, row, REMOVE_SIZE, REMOVE_SIZE);

				Theme.rounded(context, removeX, row, REMOVE_SIZE, REMOVE_SIZE,
						over ? Theme.DANGER : Theme.PANEL_RAISED);
				context.drawText(font, Text.literal("x"), removeX + 4, row + 2,
						over ? 0xFF1B1B21 : Theme.TEXT_DIM, false);

				context.drawText(font, Text.literal(entry), x + 12, row + 2, Theme.TEXT_DIM, false);
				row += ENTRY_HEIGHT;
			}
		}

		@Override
		protected void renderControl(DrawContext context, TextRenderer font, int x, int y,
				int width, int mouseX, int mouseY, boolean hovered) {
		}

		@Override
		public boolean click(int x, int y, int width, int mouseX, int mouseY, int button) {
			int row = y + 22;

			for (String entry : List.copyOf(entries.get())) {
				int removeX = x + width - REMOVE_SIZE - 10;

				if (Theme.within(mouseX, mouseY, removeX, row, REMOVE_SIZE, REMOVE_SIZE)) {
					remove.accept(entry);
					return true;
				}

				row += ENTRY_HEIGHT;
			}

			return false;
		}
	}
}
