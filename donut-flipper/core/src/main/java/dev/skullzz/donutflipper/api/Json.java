package dev.skullzz.donutflipper.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Tolerant JSON accessors.
 *
 * <p>The exact field names in the DonutSMP payloads are not confirmed until the
 * first live probe run, and community wrappers disagree about them. Rather than
 * hard-committing to one guess and getting a {@code NullPointerException} at 3am
 * three days into a collection run, every read here accepts a list of plausible
 * names and takes the first that is present.
 *
 * <p>Once {@code Probe} has dumped a real payload, the alias lists in
 * {@link ApiMapper} can be trimmed to the confirmed names. Until then this keeps
 * the collector running against whatever shape the server actually returns.
 */
final class Json {

    private Json() {
    }

    static JsonObject obj(JsonElement el) {
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    /** First present, non-null field among the candidates. */
    static JsonElement first(JsonObject o, String... names) {
        if (o == null) {
            return null;
        }
        for (String name : names) {
            JsonElement el = o.get(name);
            if (el != null && !el.isJsonNull()) {
                return el;
            }
        }
        return null;
    }

    static String str(JsonObject o, String fallback, String... names) {
        JsonElement el = first(o, names);
        if (el == null) {
            return fallback;
        }
        if (el.isJsonPrimitive()) {
            return el.getAsString();
        }
        // Some APIs wrap a display name as {"text": "..."} chat-component style.
        JsonObject nested = obj(el);
        if (nested != null) {
            JsonElement text = first(nested, "text", "value", "name");
            if (text != null && text.isJsonPrimitive()) {
                return text.getAsString();
            }
        }
        return fallback;
    }

    static long lng(JsonObject o, long fallback, String... names) {
        JsonElement el = first(o, names);
        if (el == null || !el.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return el.getAsLong();
        } catch (NumberFormatException e) {
            // Prices sometimes arrive as formatted strings such as "1,250,000".
            try {
                return Long.parseLong(el.getAsString().replaceAll("[^0-9-]", ""));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }

    static int integer(JsonObject o, int fallback, String... names) {
        return (int) lng(o, fallback, names);
    }

    static JsonArray array(JsonObject o, String... names) {
        JsonElement el = first(o, names);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : null;
    }

    /**
     * Parses a timestamp in any of the forms these APIs tend to use: epoch
     * seconds, epoch milliseconds, or an ISO-8601 string.
     *
     * <p>Seconds and milliseconds are told apart by magnitude. Anything past
     * ~2001 in milliseconds exceeds 1e12, while epoch seconds do not reach that
     * until the year 33658 -- so the threshold is unambiguous in practice.
     */
    static Instant instant(JsonObject o, Instant fallback, String... names) {
        JsonElement el = first(o, names);
        if (el == null || !el.isJsonPrimitive()) {
            return fallback;
        }
        try {
            long raw = el.getAsLong();
            if (raw <= 0) {
                return fallback;
            }
            return raw > 1_000_000_000_000L
                    ? Instant.ofEpochMilli(raw)
                    : Instant.ofEpochSecond(raw);
        } catch (NumberFormatException notANumber) {
            try {
                return Instant.parse(el.getAsString());
            } catch (DateTimeParseException ignored) {
                return fallback;
            }
        }
    }

    /**
     * Finds the list of records in a response envelope. Handles a bare array, and
     * the common wrapper shapes where the payload sits under result/data/items.
     */
    static List<JsonElement> records(JsonElement root) {
        if (root == null) {
            return List.of();
        }
        if (root.isJsonArray()) {
            return root.getAsJsonArray().asList();
        }
        JsonObject o = obj(root);
        if (o == null) {
            return List.of();
        }
        JsonElement inner = first(o, "result", "results", "data", "items", "listings",
                "transactions", "auctions", "entries");
        if (inner == null) {
            return List.of();
        }
        if (inner.isJsonArray()) {
            return inner.getAsJsonArray().asList();
        }
        // A single-object result is still one record.
        return inner.isJsonObject() ? List.of(inner) : List.of();
    }
}
