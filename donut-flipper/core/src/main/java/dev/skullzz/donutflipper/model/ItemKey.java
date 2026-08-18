package dev.skullzz.donutflipper.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Canonical fingerprint identifying "the same tradeable thing".
 *
 * <p>This is the hardest and most important piece in the project. A
 * Sharpness&nbsp;V + Mending netherite sword and a bare netherite sword are not
 * the same asset, and pricing them together produces exactly the failure mode
 * that loses money: the tool sees enchanted copies selling for 400k, spots a
 * bare one at 60k, and screams "flip!" at something nobody wants.
 *
 * <p>Two levels of granularity are produced:
 * <ul>
 *   <li>{@link #exact()} -- material, enchantments, wear, potion type, container
 *       contents. This is what valuation prefers.</li>
 *   <li>{@link #family()} -- material alone. A fallback for when the exact key
 *       has too little sale history, always at reduced confidence.</li>
 * </ul>
 *
 * <p>Deliberate design choices, each of which was a trap:
 * <ul>
 *   <li><b>Display name is never part of the identity.</b> The oldest auction
 *       scam is worthless junk renamed to look valuable. Identity comes from
 *       material and NBT; the name is display metadata only.</li>
 *   <li><b>Custom naming is recorded as a flag, not as text.</b> Including the
 *       literal name would shatter the key space -- every "xX_Sword_Xx" would
 *       become its own unpriceable item. Including nothing would let a renamed
 *       item be priced against plain ones. A boolean splits the difference.</li>
 *   <li><b>Wear is bucketed, not exact.</b> Buyers care about "nearly new" vs
 *       "half gone"; nobody prices the difference between 3 and 4 durability.</li>
 * </ul>
 */
public record ItemKey(String exact, String family) {

    /** Matches both section-sign and ampersand colour codes. */
    private static final Pattern COLOUR_CODES = Pattern.compile("[§&][0-9a-fk-orA-FK-OR]");
    private static final String MC = "minecraft:";

    /**
     * Durability bands. Coarse on purpose -- fine-grained buckets would starve
     * each band of sale history without telling a buyer anything they care about.
     */
    public enum Wear {
        PRISTINE, LIGHT, USED, WORN;

        static Wear of(double fraction) {
            if (fraction <= 0.0) return PRISTINE;
            if (fraction < 0.15) return LIGHT;
            if (fraction < 0.50) return USED;
            return WORN;
        }
    }

    public static ItemKey of(AuctionItem item) {
        String family = shorten(item.materialId());
        StringBuilder sb = new StringBuilder(family);

        if (!item.enchantments().isEmpty()) {
            sb.append("|e:").append(encodeEnchantments(item.enchantments()));
        }
        if (item.potionType() != null && !item.potionType().isBlank()) {
            sb.append("|p:").append(shorten(item.potionType()));
        }
        if (item.isDamageable()) {
            sb.append("|w:").append(Wear.of(item.wearFraction()));
        }
        if (hasCustomName(item)) {
            sb.append("|n:custom");
        }
        if (item.isContainer()) {
            // Contents are digested rather than inlined: a full shulker would
            // otherwise produce a key hundreds of characters long.
            sb.append("|c:").append(digestContents(item.contents()));
        }
        return new ItemKey(sb.toString(), family);
    }

    /**
     * True when the item carries a player-applied name, as opposed to the
     * material's own name. Compared with colour codes and separators stripped,
     * because a purely cosmetic recolour is not a different item.
     */
    static boolean hasCustomName(AuctionItem item) {
        String name = normaliseName(item.displayName());
        if (name.isEmpty()) {
            return false;
        }
        // A payload that echoes the namespaced id ("minecraft:diamond") as the
        // display name is describing a plain item, not a renamed one. Treating
        // that as custom would split every ordinary item away from its own
        // sale history and leave it permanently unpriceable.
        if (name.startsWith("minecraft ")) {
            name = name.substring("minecraft ".length());
        }
        String defaultName = normaliseName(shorten(item.materialId()).replace('_', ' '));
        return !name.equals(defaultName);
    }

    static String normaliseName(String raw) {
        if (raw == null) {
            return "";
        }
        return COLOUR_CODES.matcher(raw)
                .replaceAll("")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    /** Sorted {@code name=level} pairs, so ordering in the payload cannot change the key. */
    private static String encodeEnchantments(Map<String, Integer> enchantments) {
        List<String> parts = new ArrayList<>(enchantments.size());
        for (Map.Entry<String, Integer> e : enchantments.entrySet()) {
            parts.add(shorten(e.getKey()) + "=" + e.getValue());
        }
        Collections.sort(parts);
        return String.join(",", parts);
    }

    /**
     * Order-independent digest of container contents. Child keys are sorted
     * before hashing so two shulkers holding the same goods in different slots
     * fingerprint identically -- slot order is not part of what a buyer is buying.
     */
    private static String digestContents(List<AuctionItem> contents) {
        List<String> childKeys = new ArrayList<>(contents.size());
        for (AuctionItem child : contents) {
            childKeys.add(of(child).exact() + "x" + child.count());
        }
        Collections.sort(childKeys);
        return sha256Short(String.join(";", childKeys));
    }

    private static String shorten(String id) {
        String lower = id == null ? "" : id.toLowerCase().trim();
        return lower.startsWith(MC) ? lower.substring(MC.length()) : lower;
    }

    static String sha256Short(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            // 8 bytes is ample: collisions across a few hundred thousand distinct
            // container layouts are vanishingly unlikely, and short keys keep the
            // SQLite indexes small and the debug output readable.
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from JRE", e);
        }
    }

    @Override
    public String toString() {
        return exact;
    }
}
