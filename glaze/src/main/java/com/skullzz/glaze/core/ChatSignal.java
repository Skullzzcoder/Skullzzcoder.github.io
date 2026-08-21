package com.skullzz.glaze.core;

import java.util.Optional;

/**
 * Something interesting the mod recognised in a server message.
 *
 * <p>Fields that do not apply to a given {@link Kind} are left at their empty
 * value: a combat tag has no amount, a balance line has no player.
 */
public record ChatSignal(Kind kind, long amount, String player, String item, int quantity) {
	public enum Kind {
		/** An authoritative statement of your current balance. */
		BALANCE,
		/** Money arrived from another player. */
		MONEY_IN,
		/** Money left your account towards another player. */
		MONEY_OUT,
		/** You bought something from a shop or the auction house. */
		PURCHASE,
		/** You sold something. */
		SALE,
		/** You killed another player. */
		KILL,
		/** You died. */
		DEATH,
		/** The server put you in combat. */
		COMBAT_START,
		/** The combat tag expired. */
		COMBAT_END
	}

	public static ChatSignal of(Kind kind, long amount) {
		return new ChatSignal(kind, amount, "", "", 0);
	}

	public static ChatSignal of(Kind kind, long amount, String player) {
		return new ChatSignal(kind, amount, player == null ? "" : player, "", 0);
	}


	/** Price for a single unit, for trade signals that carried a quantity. */
	public Optional<Long> unitPrice() {
		if (quantity <= 0 || amount <= 0) {
			return Optional.empty();
		}

		return Optional.of(amount / quantity);
	}
}
