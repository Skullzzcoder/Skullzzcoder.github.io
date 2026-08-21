package com.skullzz.glaze.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LoadoutTest {
	@Test
	void reportsWhatIsMissing() {
		Loadout pvp = Loadout.of("pvp", Map.of("ender pearl", 16, "totem of undying", 4));
		Loadout.Check check = pvp.check(Map.of("ender pearl", 9, "totem of undying", 4));

		assertFalse(check.complete());
		assertEquals(1, check.shortfalls().size());

		Loadout.Shortfall missing = check.shortfalls().get(0);
		assertEquals("ender pearl", missing.item());
		assertEquals(7, missing.missing());
	}

	@Test
	void aFullKitReportsComplete() {
		Loadout pvp = Loadout.of("pvp", Map.of("ender pearl", 16));
		assertTrue(pvp.check(Map.of("ender pearl", 32)).complete());
	}

	@Test
	void missingItemEntirelyCountsAsShortfall() {
		Loadout pvp = Loadout.of("pvp", Map.of("golden apple", 8));
		Loadout.Check check = pvp.check(Map.of());

		assertEquals(8, check.shortfalls().get(0).missing());
	}

	@Test
	void requirementKeysAreNormalised() {
		Loadout pvp = new Loadout("pvp").require("  Ender Pearl  ", 4);
		assertTrue(pvp.requirements().containsKey("ender pearl"));
	}

	@Test
	void requiringZeroRemovesTheEntry() {
		Loadout pvp = new Loadout("pvp").require("ender pearl", 4).require("ender pearl", 0);
		assertTrue(pvp.isEmpty());
	}
}
