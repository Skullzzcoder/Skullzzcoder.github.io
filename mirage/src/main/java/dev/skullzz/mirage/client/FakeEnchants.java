package dev.skullzz.mirage.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Enchantment lines for a fake item.
 *
 * <p>These are drawn as lore rather than real enchantment components. Building a real one
 * needs a lookup in the dynamic enchantment registry, whose shape has changed repeatedly
 * across versions; a vanilla tooltip renders enchantments as grey non-italic lines above the
 * lore, which is exactly what this produces. The glint is set separately.
 *
 * <p>Spec format: {@code sharpness:5, unbreaking:3} — level defaults to 1 if omitted.
 */
public final class FakeEnchants {
    private static final String[] ROMAN = {
            "", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"
    };

    private FakeEnchants() {
    }

    public static boolean hasAny(String spec) {
        return spec != null && !spec.trim().isEmpty();
    }

    /** @return one grey line per enchantment, in vanilla's order-of-appearance style. */
    public static List<String> lines(String spec) {
        List<String> lines = new ArrayList<>();
        if (!hasAny(spec)) return lines;

        for (String part : spec.split(",")) {
            String entry = part.trim();
            if (entry.isEmpty()) continue;

            String name = entry;
            int level = 1;

            int colon = entry.lastIndexOf(':');
            if (colon > 0) {
                name = entry.substring(0, colon).trim();
                try {
                    level = Integer.parseInt(entry.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                    level = 1;
                }
            }

            lines.add("§7" + prettify(name) + romanSuffix(level));
        }
        return lines;
    }

    /** minecraft:fire_aspect or fire_aspect becomes Fire Aspect. */
    private static String prettify(String id) {
        String name = id.toLowerCase(Locale.ROOT).trim();
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);

        StringBuilder pretty = new StringBuilder();
        for (String word : name.split("_")) {
            if (word.isEmpty()) continue;
            if (pretty.length() > 0) pretty.append(' ');
            pretty.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return pretty.length() == 0 ? name : pretty.toString();
    }

    /** Vanilla omits the numeral at level 1, and falls back to digits past ten. */
    private static String romanSuffix(int level) {
        if (level <= 1) return "";
        if (level < ROMAN.length) return " " + ROMAN[level];
        return " " + level;
    }
}
