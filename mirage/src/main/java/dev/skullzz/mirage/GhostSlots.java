package dev.skullzz.mirage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Friendly names for the slots of a player's own inventory screen (sync id 0), so that
 * nobody has to remember that the hotbar actually lives at raw indices 36-44.
 */
public final class GhostSlots {
    private static final Map<String, Integer> NAMES = new LinkedHashMap<>();

    static {
        for (int i = 1; i <= 9; i++) NAMES.put("hotbar" + i, 35 + i); // 36..44
        for (int i = 1; i <= 27; i++) NAMES.put("inv" + i, 8 + i);    // 9..35, top-left first
        NAMES.put("offhand", 45);
        NAMES.put("head", 5);
        NAMES.put("chest", 6);
        NAMES.put("legs", 7);
        NAMES.put("feet", 8);
    }

    private GhostSlots() {
    }

    public static List<String> names() {
        return new ArrayList<>(NAMES.keySet());
    }

    /** @return the raw screen-handler index, or -1 if the name is not a slot. */
    public static int index(String name) {
        Integer raw = NAMES.get(name.toLowerCase(Locale.ROOT));
        return raw == null ? -1 : raw;
    }

    public static String nameOf(int index) {
        for (Map.Entry<String, Integer> entry : NAMES.entrySet()) {
            if (entry.getValue() == index) return entry.getKey();
        }
        return "slot" + index;
    }
}
