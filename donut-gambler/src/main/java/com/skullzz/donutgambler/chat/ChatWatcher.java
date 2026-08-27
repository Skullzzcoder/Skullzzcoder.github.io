package com.skullzz.donutgambler.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import com.skullzz.donutgambler.ClientChat;
import com.skullzz.donutgambler.DonutGambler;
import com.skullzz.donutgambler.advisor.Advice;
import com.skullzz.donutgambler.config.GamblerConfig;
import com.skullzz.donutgambler.data.Agg;
import com.skullzz.donutgambler.data.BetRecord;
import com.skullzz.donutgambler.data.Outcome;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/**
 * Watches incoming chat, logs the bets it recognises, and keeps a buffer of raw lines so the
 * pattern builder has real messages to work from.
 *
 * <p>Read-only by design: this never sends a command, clicks a GUI, or automates a bet.
 */
public final class ChatWatcher {
	private static final int BUFFER_SIZE = 200;
	private static final long DUPLICATE_WINDOW_MS = 400;

	private static final Deque<String> RECENT = new ArrayDeque<>();
	private static String lastLine = "";
	private static long lastLineAt;

	private ChatWatcher() {
	}

	public static void register() {
		// Server/system messages: where gambling results almost always arrive.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) handle(message.getString());
		});

		// Player chat: only parsed when explicitly enabled, otherwise just buffered.
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, timestamp) -> {
			String text = message.getString();
			remember(text);

			if (DonutGambler.config().parsePlayerChat) {
				process(text);
			}
		});
	}

	private static void handle(String raw) {
		remember(raw);
		process(raw);
	}

	private static void remember(String raw) {
		String text = BetMatcher.strip(raw);
		if (text.isEmpty()) return;

		RECENT.addLast(text);

		while (RECENT.size() > BUFFER_SIZE) {
			RECENT.removeFirst();
		}
	}

	private static void process(String raw) {
		GamblerConfig config = DonutGambler.config();
		if (!config.parseChat) return;

		String text = BetMatcher.strip(raw);
		if (text.isEmpty()) return;

		long now = System.currentTimeMillis();

		// Servers often echo the same line twice (action bar + chat). Count it once.
		if (text.equals(lastLine) && now - lastLineAt < DUPLICATE_WINDOW_MS) return;
		lastLine = text;
		lastLineAt = now;

		double balance = BetMatcher.matchBalance(config, text);

		if (!Double.isNaN(balance) && balance > 0) {
			config.bankroll = balance;

			if (config.sessionStartBankroll <= 0) {
				config.sessionStartBankroll = balance;
			}

			DonutGambler.markConfigDirty();
		}

		BetRecord record = BetMatcher.match(config, text, now);
		if (record == null) return;

		recordBet(record);
	}

	/** Adds a bet, updates the running bankroll, and fires the notifications. */
	public static void recordBet(BetRecord record) {
		GamblerConfig config = DonutGambler.config();
		DonutGambler.log().add(record);

		if (config.bankroll > 0) {
			config.bankroll = Math.max(0, config.bankroll + record.net);
			DonutGambler.markConfigDirty();
		}

		DonutGambler.invalidateAdvice();
		notify(record);
	}

	private static void notify(BetRecord record) {
		GamblerConfig config = DonutGambler.config();
		Advice advice = DonutGambler.advice();

		if (config.chatNotifyOnBet) {
			Agg session = DonutGambler.log().aggSession();
			String vs = record.hasOpponent() ? " vs " + record.opponent : "";
			String line = String.format(Locale.ROOT, "%s %s %s%s  |  session %s (%d-%d)",
					record.gameName,
					record.outcome == Outcome.WIN ? "WIN" : record.outcome == Outcome.LOSS ? "LOSS" : "PUSH",
					MoneyParser.formatSigned(record.net), vs,
					MoneyParser.formatSigned(session.net), session.wins, session.losses);

			if (record.outcome == Outcome.WIN) {
				ClientChat.good(line);
			} else if (record.outcome == Outcome.LOSS) {
				ClientChat.bad(line);
			} else {
				ClientChat.info(line);
			}
		}

		if (config.chatNotifyOnAlert && advice.verdict != Advice.Verdict.GREEN) {
			String reason = advice.primaryReason();

			if (advice.verdict == Advice.Verdict.RED) {
				ClientChat.bad("STOP: " + reason);
			} else {
				ClientChat.warn("Caution: " + reason);
			}
		}
	}

	/** Buffered chat lines, newest first. Used by the pattern builder and the regex tester. */
	public static List<String> recentLines() {
		List<String> out = new ArrayList<>(RECENT);
		java.util.Collections.reverse(out);
		return out;
	}

	public static void clearBuffer() {
		RECENT.clear();
	}
}
