package dev.skullzz.donutflipper.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Keying is the piece the whole tool's accuracy rests on, so these tests are
 * written as statements about money rather than about strings.
 */
class ItemKeyTest {

    private static AuctionItem sword(Map<String, Integer> enchants) {
        return new AuctionItem("minecraft:netherite_sword", "Netherite Sword", 1,
                enchants, null, 0, 2031, List.of());
    }

    @Test
    @DisplayName("an enchanted sword is not the same asset as a bare one")
    void enchantedDoesNotCollideWithBare() {
        // The expensive failure: valuing bare gear against enchanted sale history
        // and reporting a 'bargain' that nobody wants to buy.
        ItemKey bare = ItemKey.of(sword(Map.of()));
        ItemKey enchanted = ItemKey.of(sword(Map.of("sharpness", 5, "mending", 1)));

        assertNotEquals(bare.exact(), enchanted.exact());
        // They must still share a family, so the fallback tier can group them.
        assertEquals(bare.family(), enchanted.family());
    }

    @Test
    @DisplayName("enchantment order in the payload cannot change the key")
    void enchantmentOrderIsIrrelevant() {
        Map<String, Integer> a = new LinkedHashMap<>();
        a.put("sharpness", 5);
        a.put("mending", 1);
        a.put("unbreaking", 3);

        Map<String, Integer> b = new LinkedHashMap<>();
        b.put("unbreaking", 3);
        b.put("mending", 1);
        b.put("sharpness", 5);

        assertEquals(ItemKey.of(sword(a)).exact(), ItemKey.of(sword(b)).exact(),
                "same enchantments listed in a different order must fingerprint identically");
    }

    @Test
    @DisplayName("enchantment level is part of the identity")
    void levelMatters() {
        assertNotEquals(
                ItemKey.of(sword(Map.of("sharpness", 5))).exact(),
                ItemKey.of(sword(Map.of("sharpness", 1))).exact());
    }

    @Test
    @DisplayName("a deceptive display name cannot impersonate a valuable item")
    void displayNameIsNotIdentity() {
        // The oldest auction scam: dirt renamed to look like something precious.
        AuctionItem junk = new AuctionItem("minecraft:dirt",
                "§6Netherite Sword", 1, Map.of(), null, 0, 0, List.of());

        ItemKey junkKey = ItemKey.of(junk);
        assertTrue(junkKey.exact().startsWith("dirt"),
                "identity must come from the material, not the name: " + junkKey.exact());
        assertNotEquals(ItemKey.of(sword(Map.of())).exact(), junkKey.exact());
    }

    @Test
    @DisplayName("custom naming is recorded as a flag, not as free text")
    void customNameCollapsesToAFlag() {
        AuctionItem named1 = new AuctionItem("minecraft:netherite_sword",
                "xX_Slayer_Xx", 1, Map.of(), null, 0, 2031, List.of());
        AuctionItem named2 = new AuctionItem("minecraft:netherite_sword",
                "Bob's Blade", 1, Map.of(), null, 0, 2031, List.of());

        // Two differently-named swords share a key -- otherwise every renamed item
        // becomes its own unpriceable island with no sale history.
        assertEquals(ItemKey.of(named1).exact(), ItemKey.of(named2).exact());
        // But a renamed sword is still distinguished from a plain one.
        assertNotEquals(ItemKey.of(named1).exact(), ItemKey.of(sword(Map.of())).exact());
    }

    @Test
    @DisplayName("colour codes alone are cosmetic, not a different item")
    void colourCodesAreStripped() {
        AuctionItem plain = new AuctionItem("minecraft:diamond", "Diamond", 1,
                Map.of(), null, 0, 0, List.of());
        AuctionItem coloured = new AuctionItem("minecraft:diamond", "§bDiamond", 1,
                Map.of(), null, 0, 0, List.of());

        assertEquals(ItemKey.of(plain).exact(), ItemKey.of(coloured).exact());
    }

    @Test
    @DisplayName("wear is bucketed so near-identical durability shares history")
    void wearIsBucketed() {
        AuctionItem fresh = new AuctionItem("minecraft:diamond_pickaxe", "", 1,
                Map.of(), null, 0, 1561, List.of());
        AuctionItem barelyUsed = new AuctionItem("minecraft:diamond_pickaxe", "", 1,
                Map.of(), null, 30, 1561, List.of());
        AuctionItem halfGone = new AuctionItem("minecraft:diamond_pickaxe", "", 1,
                Map.of(), null, 900, 1561, List.of());

        assertEquals(ItemKey.of(barelyUsed).exact(), ItemKey.of(
                        new AuctionItem("minecraft:diamond_pickaxe", "", 1,
                                Map.of(), null, 40, 1561, List.of())).exact(),
                "two lightly-used picks should price together");
        assertNotEquals(ItemKey.of(fresh).exact(), ItemKey.of(halfGone).exact(),
                "pristine and half-spent are genuinely different goods");
    }

    @Test
    @DisplayName("shulker contents identify the bundle regardless of slot order")
    void containerContentsAreOrderIndependent() {
        AuctionItem diamonds = AuctionItem.simple("minecraft:diamond", 64);
        AuctionItem gold = AuctionItem.simple("minecraft:gold_ingot", 32);

        AuctionItem boxA = new AuctionItem("minecraft:shulker_box", "", 1,
                Map.of(), null, 0, 0, List.of(diamonds, gold));
        AuctionItem boxB = new AuctionItem("minecraft:shulker_box", "", 1,
                Map.of(), null, 0, 0, List.of(gold, diamonds));

        assertEquals(ItemKey.of(boxA).exact(), ItemKey.of(boxB).exact());
    }

    @Test
    @DisplayName("shulkers with different contents are different assets")
    void containerContentsAffectIdentity() {
        AuctionItem full = new AuctionItem("minecraft:shulker_box", "", 1,
                Map.of(), null, 0, 0, List.of(AuctionItem.simple("minecraft:diamond", 64)));
        AuctionItem empty = new AuctionItem("minecraft:shulker_box", "", 1,
                Map.of(), null, 0, 0, List.of());

        assertNotEquals(ItemKey.of(full).exact(), ItemKey.of(empty).exact());
    }
}
