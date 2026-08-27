package com.skullzz.donutgambler.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

import com.skullzz.donutgambler.DonutGambler;
import com.skullzz.donutgambler.chat.MoneyParser;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Builders for the handful of controls the settings pages need. */
public final class Fields {
	private Fields() {
	}

	public static Button button(int x, int y, int w, int h, String label, Runnable action) {
		return Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, h).build();
	}

	/** ON/OFF button that writes straight back to the config. */
	public static Button toggle(int x, int y, int w, int h, String label, BooleanSupplier getter, Consumer<Boolean> setter) {
		Button[] holder = new Button[1];
		holder[0] = Button.builder(text(label, getter.getAsBoolean()), b -> {
			boolean next = !getter.getAsBoolean();
			setter.accept(next);
			b.setMessage(text(label, next));
			DonutGambler.markConfigDirty();
		}).bounds(x, y, w, h).build();
		return holder[0];
	}

	private static Component text(String label, boolean on) {
		return Component.literal(label + ": " + (on ? "ON" : "OFF"));
	}

	/** Button that cycles through values, e.g. the HUD anchor. */
	public static Button cycle(int x, int y, int w, int h, String label, Supplier<String> value, Runnable next) {
		Button[] holder = new Button[1];
		holder[0] = Button.builder(Component.literal(label + ": " + value.get()), b -> {
			next.run();
			b.setMessage(Component.literal(label + ": " + value.get()));
			DonutGambler.markConfigDirty();
		}).bounds(x, y, w, h).build();
		return holder[0];
	}

	/**
	 * Number field that applies every valid keystroke and quietly ignores half-typed input.
	 * Accepts {@code 2.5m} style shorthand as well as plain numbers.
	 */
	public static EditBox number(Font font, int x, int y, int w, int h, String label,
			double initial, double min, double max, DoubleConsumer apply) {
		EditBox box = new EditBox(font, x, y, w, h, Component.literal(label));
		box.setMaxLength(24);
		box.setValue(trimNumber(initial));
		box.setResponder(text -> {
			double parsed = MoneyParser.parse(text);

			if (text.isBlank() || Double.isNaN(parsed)) return;
			if (parsed < min || parsed > max) return;

			apply.accept(parsed);
			DonutGambler.markConfigDirty();
		});
		return box;
	}

	/** Free-text field applied on every keystroke. */
	public static EditBox text(Font font, int x, int y, int w, int h, String label, String initial,
			int maxLength, Consumer<String> apply) {
		EditBox box = new EditBox(font, x, y, w, h, Component.literal(label));
		box.setMaxLength(maxLength);
		box.setValue(initial == null ? "" : initial);
		box.setResponder(apply);
		return box;
	}

	/** Drops a trailing ".0" so fields read "5" rather than "5.0". */
	public static String trimNumber(double value) {
		if (value == Math.rint(value) && Math.abs(value) < 1e15) {
			return String.valueOf((long) value);
		}

		return String.valueOf(Math.round(value * 1000.0) / 1000.0);
	}
}
