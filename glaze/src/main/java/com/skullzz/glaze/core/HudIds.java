package com.skullzz.glaze.core;

import java.util.List;

/** Stable identifiers for the HUD readouts, used as config keys. */
public final class HudIds {
	public static final String SESSION_TIME = "session_time";
	public static final String BALANCE = "balance";
	public static final String EARNED = "earned";
	public static final String MONEY_PER_HOUR = "money_per_hour";
	public static final String KILLS_DEATHS = "kills_deaths";
	public static final String COORDS = "coords";
	public static final String PING = "ping";
	public static final String COMBAT_TIMER = "combat_timer";
	public static final String INVENTORY_VALUE = "inventory_value";
	public static final String CONSUMABLES = "consumables";
	public static final String WAYPOINT = "waypoint";

	public static final List<String> ALL = List.of(
			SESSION_TIME, BALANCE, EARNED, MONEY_PER_HOUR, KILLS_DEATHS, COORDS,
			PING, COMBAT_TIMER, INVENTORY_VALUE, CONSUMABLES, WAYPOINT);

	private HudIds() {
	}

	/** Human-readable label for the config and HUD editor. */
	public static String label(String id) {
		return switch (id) {
			case SESSION_TIME -> "Session time";
			case BALANCE -> "Balance";
			case EARNED -> "Session earnings";
			case MONEY_PER_HOUR -> "Money per hour";
			case KILLS_DEATHS -> "Kills / deaths";
			case COORDS -> "Coordinates";
			case PING -> "Ping";
			case COMBAT_TIMER -> "Combat timer";
			case INVENTORY_VALUE -> "Inventory value";
			case CONSUMABLES -> "Consumable counts";
			case WAYPOINT -> "Nearest waypoint";
			default -> id;
		};
	}
}
