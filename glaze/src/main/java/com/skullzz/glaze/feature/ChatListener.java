package com.skullzz.glaze.feature;

import com.skullzz.glaze.GlazeClient;
import com.skullzz.glaze.core.ChatSignal;
import com.skullzz.glaze.core.Money;
import com.skullzz.glaze.core.PriceBook;
import com.skullzz.glaze.mc.Mc;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Watches server messages for balance readings, trades, kills and combat tags.
 *
 * <p>Read-only: messages are observed and never modified or suppressed, so what
 * you see in chat is exactly what the server sent.
 */
public final class ChatListener {
	/** Set by {@code /glaze chatlog}, to help write patterns against real text. */
	private static boolean logging;

	private ChatListener() {
	}

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				handle(message);
			}
		});

		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) ->
				handle(message));
	}

	public static void setLogging(boolean enabled) {
		logging = enabled;
	}

	public static boolean logging() {
		return logging;
	}

	private static void handle(Text message) {
		if (!GlazeClient.active()) {
			return;
		}

		String line = Money.clean(message.getString());

		if (logging && !line.isBlank()) {
			Mc.send(Text.literal("[chatlog] ").formatted(Formatting.DARK_GRAY)
					.append(Text.literal(line).formatted(Formatting.GRAY)));
		}

		GlazeClient.chatParser().parse(line).ifPresent(ChatListener::apply);
	}

	private static void apply(ChatSignal signal) {
		var session = GlazeClient.session();
		long now = System.currentTimeMillis();

		switch (signal.kind()) {
			case BALANCE -> session.observeBalance(signal.amount());
			case MONEY_IN -> session.addIncome(signal.amount());
			case MONEY_OUT -> session.addSpend(signal.amount());
			case KILL -> session.addKill();
			case COMBAT_START -> CombatTracker.start(now);
			case COMBAT_END -> CombatTracker.clear();
			case PURCHASE -> {
				session.addSpend(signal.amount());
				recordTradePrice(signal, now, "bought");
			}
			case SALE -> {
				session.addIncome(signal.amount());
				recordTradePrice(signal, now, "sold");
			}
			default -> {
				// DEATH is detected from the player's health, not from chat.
			}
		}
	}

	/**
	 * Feeds a completed trade into the price book.
	 *
	 * <p>A price you actually paid is better evidence than an asking price, but it
	 * is stored the same way so both inform the median.
	 */
	private static void recordTradePrice(ChatSignal signal, long now, String source) {
		if (!GlazeClient.config().economy.recordPrices || signal.item().isEmpty()) {
			return;
		}

		int quantity = Math.max(1, signal.quantity());
		PriceBook book = GlazeClient.priceBook();
		book.record(signal.item(), signal.amount(), quantity, now, source);
	}
}
