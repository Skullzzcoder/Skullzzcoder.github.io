package com.skullzz.glaze.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChatParserTest {
	private final ChatParser parser = ChatParser.withDefaults();

	private ChatSignal parse(String line) {
		Optional<ChatSignal> signal = parser.parse(line);
		assertTrue(signal.isPresent(), () -> "no signal parsed from: " + line);
		return signal.get();
	}

	@Test
	void defaultPatternsAllCompile() {
		assertEquals(List.of(), parser.errors());
	}

	@Test
	void readsBalanceLines() {
		ChatSignal s = parse("Your balance: $1,234,567");
		assertEquals(ChatSignal.Kind.BALANCE, s.kind());
		assertEquals(1_234_567L, s.amount());
	}

	@Test
	void readsIncomingPayment() {
		ChatSignal s = parse("Notch sent you $2.5m");
		assertEquals(ChatSignal.Kind.MONEY_IN, s.kind());
		assertEquals(2_500_000L, s.amount());
		assertEquals("Notch", s.player());
	}

	@Test
	void readsOutgoingPayment() {
		ChatSignal s = parse("You sent $500k to Herobrine");
		assertEquals(ChatSignal.Kind.MONEY_OUT, s.kind());
		assertEquals(500_000L, s.amount());
		assertEquals("Herobrine", s.player());
	}

	@Test
	void doesNotConfusePaymentDirection() {
		assertEquals(ChatSignal.Kind.MONEY_OUT, parse("You paid $1k to Steve").kind());
		assertEquals(ChatSignal.Kind.MONEY_IN, parse("Steve paid you $1k").kind());
	}

	@Test
	void readsPurchasesWithQuantity() {
		ChatSignal s = parse("You purchased 64x Diamond for $320,000");
		assertEquals(ChatSignal.Kind.PURCHASE, s.kind());
		assertEquals(320_000L, s.amount());
		assertEquals(64, s.quantity());
		assertEquals("Diamond", s.item());
		assertEquals(Optional.of(5_000L), s.unitPrice());
	}

	@Test
	void readsSales() {
		ChatSignal s = parse("You sold 16x Netherite Ingot for $8m");
		assertEquals(ChatSignal.Kind.SALE, s.kind());
		assertEquals(8_000_000L, s.amount());
		assertEquals(16, s.quantity());
		assertEquals("Netherite Ingot", s.item());
	}

	@Test
	void readsCombatTagBoundaries() {
		assertEquals(ChatSignal.Kind.COMBAT_START, parse("You are now in combat!").kind());
		assertEquals(ChatSignal.Kind.COMBAT_END, parse("You are no longer in combat.").kind());
	}

	@Test
	void readsKills() {
		assertEquals("Steve", parse("You killed Steve").player());
	}

	@Test
	void toleratesColourCodes() {
		ChatSignal s = parse("§aYour balance: §e$42,000");
		assertEquals(42_000L, s.amount());
	}

	@Test
	void ignoresOrdinaryChatter() {
		assertEquals(Optional.empty(), parser.parse("<Steve> anyone selling elytra"));
		assertEquals(Optional.empty(), parser.parse("Welcome to DonutSMP!"));
	}

	@Test
	void collectsBrokenPatternsInsteadOfThrowing() {
		ChatParser broken = new ChatParser(Map.of(
				ChatSignal.Kind.BALANCE.name(), List.of("(unclosed"),
				ChatSignal.Kind.KILL.name(), List.of("(?i)^you killed (?<player>\\w+)")));

		assertEquals(1, broken.errors().size());
		assertTrue(broken.errors().get(0).startsWith("BALANCE"));
		// The valid pattern in the same set still works.
		assertEquals("Steve", broken.parse("You killed Steve").orElseThrow().player());
	}
}
