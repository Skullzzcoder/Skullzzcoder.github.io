package com.skullzz.glaze.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GlazeConfigTest {
	@Test
	void defaultsAreUsable() {
		GlazeConfig config = new GlazeConfig().sanitised();

		assertTrue(config.donutOnly);
		assertTrue(config.hud.enabled);
		assertFalse(config.automation.quickStash, "click automation must be opt-in");
		// DEATH is the one signal read from the client rather than from chat, so it
		// is the only kind without a pattern set.
		for (ChatSignal.Kind kind : ChatSignal.Kind.values()) {
			if (kind == ChatSignal.Kind.DEATH) {
				continue;
			}

			assertTrue(config.chatPatterns.containsKey(kind.name()),
					() -> "no default chat patterns for " + kind);
		}
	}

	@Test
	void everyHudReadoutHasASpecByDefault() {
		GlazeConfig config = new GlazeConfig().sanitised();

		for (String id : HudIds.ALL) {
			assertNotNull(config.spec(id), () -> "no default spec for " + id);
		}
	}

	@Test
	void nulledSectionsAreRebuilt() {
		GlazeConfig config = new GlazeConfig();
		config.economy = null;
		config.hud = null;
		config.warnings = null;
		config.inventory = null;
		config.automation = null;
		config.chatPatterns = null;
		config.loadouts = null;
		config.sanitised();

		assertNotNull(config.economy);
		assertNotNull(config.hud);
		assertNotNull(config.warnings);
		assertNotNull(config.inventory);
		assertNotNull(config.automation);
		assertNotNull(config.chatPatterns);
		assertNotNull(config.loadouts);
	}

	@Test
	void outOfRangeValuesAreClamped() {
		GlazeConfig config = new GlazeConfig();
		config.economy.dealThreshold = 40.0;
		config.warnings.durabilityPercent = -5;
		config.hud.scale = 99.0;
		config.automation.clickDelayMillis = 1;
		config.sanitised();

		assertEquals(1.0, config.economy.dealThreshold, 1e-9);
		assertEquals(1, config.warnings.durabilityPercent);
		assertEquals(3.0, config.hud.scale, 1e-9);
		assertEquals(50, config.automation.clickDelayMillis,
				"a delay below 50ms would be a click macro, not a throttle");
	}

	@Test
	void readoutsAddedInLaterVersionsAreAppendedToAnOldLayout() {
		GlazeConfig config = new GlazeConfig();
		config.hud.elements = new ArrayList<>(List.of(
				new HudSpec(HudIds.BALANCE, true, Anchor.TOP_LEFT, 4, 4)));
		config.sanitised();

		assertNotNull(config.spec(HudIds.COMBAT_TIMER),
				"a config from an older build should gain new readouts");
		assertEquals(1, config.hud.elements.stream()
				.filter(e -> e.id.equals(HudIds.BALANCE)).count(),
				"existing readouts must not be duplicated");
	}

	@Test
	void brokenHudEntriesAreDiscarded() {
		GlazeConfig config = new GlazeConfig();
		config.hud.elements = new ArrayList<>();
		config.hud.elements.add(null);
		config.hud.elements.add(new HudSpec("", true, null, -5, -5));
		config.sanitised();

		assertTrue(config.hud.elements.stream().noneMatch(e -> e == null || e.id.isBlank()));
		assertTrue(config.hud.elements.stream().allMatch(e -> e.anchor != null));
	}

	@Test
	void activeLoadoutResolvesFromTheNamedKit() {
		GlazeConfig config = new GlazeConfig().sanitised();
		config.inventory.activeLoadout = "pvp";

		Loadout loadout = config.activeLoadout();
		assertEquals("pvp", loadout.name());
		assertTrue(loadout.requirements().containsKey("totem of undying"));
	}

	@Test
	void unknownLoadoutNameYieldsAnEmptyKitRatherThanFailing() {
		GlazeConfig config = new GlazeConfig().sanitised();
		config.inventory.activeLoadout = "does-not-exist";

		assertTrue(config.activeLoadout().isEmpty());
	}

	@Test
	void defaultChatPatternsStillCompileAfterSanitising() {
		GlazeConfig config = new GlazeConfig().sanitised();
		assertEquals(List.of(), new ChatParser(config.chatPatterns).errors());
	}

	@Test
	void retentionConvertsDaysToMillis() {
		GlazeConfig config = new GlazeConfig().sanitised();
		config.economy.priceRetentionDays = 2;

		assertEquals(172_800_000L, config.priceRetentionMillis());
	}
}
