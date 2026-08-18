package dev.skullzz.donutflipper.model;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Normalised description of an item on the auction house.
 *
 * <p>This is deliberately <em>our</em> shape, not the API's. The raw DonutSMP
 * payload is mapped into this by {@code api.ItemAdapter}, which gives us one
 * place to absorb API changes instead of threading them through valuation,
 * scanning and UI. Until the first live probe confirms the real field names,
 * that adapter is the only code that needs to change.
 *
 * @param materialId    canonical id, e.g. {@code minecraft:netherite_sword}
 * @param displayName   name as shown in game, colour codes included
 * @param count         stack size in this listing
 * @param enchantments  enchantment id to level; sorted so iteration is stable
 * @param potionType    potion/effect id, or null for non-potions
 * @param damage        durability used; 0 for fresh or non-damageable items
 * @param maxDamage     durability ceiling; 0 when the item cannot take damage
 * @param contents      items inside a shulker/container, empty for plain items
 */
public record AuctionItem(
        String materialId,
        String displayName,
        int count,
        Map<String, Integer> enchantments,
        String potionType,
        int damage,
        int maxDamage,
        List<AuctionItem> contents
) {

    public AuctionItem {
        materialId = materialId == null ? "minecraft:air" : materialId.toLowerCase().trim();
        displayName = displayName == null ? "" : displayName;
        count = Math.max(1, count);
        // TreeMap so two items with the same enchantments in different orders
        // produce byte-identical keys. Without this, fingerprinting is a coin flip.
        enchantments = enchantments == null ? Map.of() : new TreeMap<>(enchantments);
        contents = contents == null ? List.of() : List.copyOf(contents);
    }

    /**
     * Convenience for the common case: a plain, unenchanted, undamaged item.
     *
     * <p>Display name is left empty rather than echoing the material id. Passing
     * the id here would make {@code ItemKey} read the item as custom-named and
     * split it away from the very sale history it needs to be priced against.
     */
    public static AuctionItem simple(String materialId, int count) {
        return new AuctionItem(materialId, "", count, Map.of(), null, 0, 0, List.of());
    }

    public boolean isDamageable() {
        return maxDamage > 0;
    }

    /**
     * Wear as a fraction, 0.0 = pristine. Used for bucketing rather than exact
     * matching: buyers care about "nearly new" vs "half gone", not about the
     * difference between 3 and 4 durability points.
     */
    public double wearFraction() {
        if (!isDamageable()) {
            return 0.0;
        }
        return Math.min(1.0, Math.max(0.0, (double) damage / (double) maxDamage));
    }

    public boolean isContainer() {
        return !contents.isEmpty();
    }

    /** Total item count including anything nested inside a container. */
    public int totalUnits() {
        int total = count;
        for (AuctionItem child : contents) {
            total += child.totalUnits();
        }
        return total;
    }
}
