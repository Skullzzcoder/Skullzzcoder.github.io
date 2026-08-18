package dev.skullzz.donutflipper.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.skullzz.donutflipper.model.AuctionItem;
import dev.skullzz.donutflipper.model.Listing;
import dev.skullzz.donutflipper.model.Sale;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps raw API payloads into the internal model.
 *
 * <p>This is the only class that knows the DonutSMP wire format. Everything
 * downstream -- keying, valuation, scanning, UI -- speaks {@link Listing},
 * {@link Sale} and {@link AuctionItem}. When the API changes, or when the first
 * live probe confirms the real field names, this file is the only one that moves.
 *
 * <p>Records that cannot be mapped are skipped and counted rather than throwing.
 * A single malformed listing in a 5,000-row sweep must not abort the sweep; the
 * cost of losing one row is nothing, and the cost of losing the sweep is a hole
 * in the sale history that quietly weakens every valuation afterwards.
 */
public final class ApiMapper {

    // Alias lists, widest plausible set until the probe narrows them.
    private static final String[] ID_FIELDS = {"id", "uuid", "listingId", "listing_id", "auctionId"};
    private static final String[] SELLER_FIELDS = {"seller", "sellerName", "seller_name", "owner", "username", "player"};
    private static final String[] BUYER_FIELDS = {"buyer", "buyerName", "buyer_name", "purchaser", "boughtBy"};
    private static final String[] PRICE_FIELDS = {"price", "cost", "buyPrice", "amount", "value"};
    private static final String[] ITEM_FIELDS = {"item", "itemStack", "stack", "itemData"};
    private static final String[] TIME_FIELDS = {"timestamp", "time", "soldAt", "sold_at", "date", "createdAt", "listedAt"};

    private static final String[] MATERIAL_FIELDS = {"id", "material", "type", "itemId", "item_id", "itemType"};
    private static final String[] COUNT_FIELDS = {"count", "amount", "quantity", "stackSize", "size"};
    private static final String[] NAME_FIELDS = {"displayName", "display_name", "customName", "name", "title"};
    private static final String[] ENCHANT_FIELDS = {"enchantments", "enchants", "enchantment"};
    private static final String[] DAMAGE_FIELDS = {"damage", "durability", "damageValue"};
    private static final String[] MAX_DAMAGE_FIELDS = {"maxDamage", "max_damage", "maxDurability"};
    private static final String[] POTION_FIELDS = {"potion", "potionType", "effect"};
    private static final String[] CONTENTS_FIELDS = {"contents", "items", "inventory", "container"};

    /** Shulker-in-a-shulker is legal in Minecraft; anything deeper is malformed. */
    private static final int MAX_CONTAINER_DEPTH = 3;

    private ApiMapper() {
    }

    /** Outcome of mapping one page, including how much was unusable. */
    public record Result<T>(List<T> records, int skipped) {
        public boolean healthy() {
            // A page where most rows fail to map means our aliases are wrong,
            // not that the server sent junk. Callers surface this loudly.
            return skipped == 0 || skipped < records.size();
        }
    }

    public static Result<Listing> parseListings(JsonElement root, Instant observedAt) {
        List<Listing> out = new ArrayList<>();
        int skipped = 0;
        for (JsonElement el : Json.records(root)) {
            JsonObject o = Json.obj(el);
            if (o == null) {
                skipped++;
                continue;
            }
            String id = Json.str(o, null, ID_FIELDS);
            long price = Json.lng(o, -1, PRICE_FIELDS);
            AuctionItem item = parseItem(itemNode(o));
            if (id == null || price < 0 || item == null) {
                skipped++;
                continue;
            }
            Instant listedAt = Json.instant(o, observedAt, TIME_FIELDS);
            out.add(new Listing(
                    id,
                    Json.str(o, "unknown", SELLER_FIELDS),
                    price,
                    item,
                    // If the server tells us when it was listed, trust that over
                    // our own first sighting -- the poller may have started mid-life.
                    listedAt.isAfter(observedAt) ? observedAt : listedAt,
                    observedAt));
        }
        return new Result<>(out, skipped);
    }

    public static Result<Sale> parseSales(JsonElement root, Instant observedAt) {
        List<Sale> out = new ArrayList<>();
        int skipped = 0;
        for (JsonElement el : Json.records(root)) {
            JsonObject o = Json.obj(el);
            if (o == null) {
                skipped++;
                continue;
            }
            long price = Json.lng(o, -1, PRICE_FIELDS);
            AuctionItem item = parseItem(itemNode(o));
            if (price < 0 || item == null) {
                skipped++;
                continue;
            }
            Instant soldAt = Json.instant(o, observedAt, TIME_FIELDS);
            String id = Json.str(o, null, ID_FIELDS);
            if (id == null) {
                // Some transaction feeds have no stable id. Synthesise one that is
                // stable across sweeps so INSERT OR IGNORE still dedupes correctly;
                // using a random id here would duplicate every sale on every poll
                // and inflate the apparent sales-per-day of everything.
                id = "syn:" + Math.abs((Json.str(o, "?", SELLER_FIELDS) + price
                        + soldAt.getEpochSecond() + item.materialId()).hashCode());
            }
            out.add(new Sale(
                    id,
                    Json.str(o, "unknown", SELLER_FIELDS),
                    Json.str(o, null, BUYER_FIELDS),
                    price,
                    item,
                    soldAt));
        }
        return new Result<>(out, skipped);
    }

    /**
     * The item may be nested under an "item" key or flattened onto the record
     * itself. Both shapes appear in the wild, so try nested first and fall back.
     */
    private static JsonObject itemNode(JsonObject record) {
        JsonObject nested = Json.obj(Json.first(record, ITEM_FIELDS));
        return nested != null ? nested : record;
    }

    static AuctionItem parseItem(JsonObject o) {
        return parseItem(o, 0);
    }

    /**
     * @param depth container nesting level. Minecraft allows a shulker inside a
     *              shulker but not unbounded nesting; the cap stops a malformed
     *              or self-referential payload from recursing until the stack dies.
     */
    private static AuctionItem parseItem(JsonObject o, int depth) {
        if (o == null || depth > MAX_CONTAINER_DEPTH) {
            return null;
        }
        String material = Json.str(o, null, MATERIAL_FIELDS);
        if (material == null || material.isBlank()) {
            return null;
        }
        if (!material.contains(":")) {
            material = "minecraft:" + material.toLowerCase();
        }
        return new AuctionItem(
                material,
                Json.str(o, "", NAME_FIELDS),
                Math.max(1, Json.integer(o, 1, COUNT_FIELDS)),
                parseEnchantments(o),
                Json.str(o, null, POTION_FIELDS),
                Json.integer(o, 0, DAMAGE_FIELDS),
                Json.integer(o, 0, MAX_DAMAGE_FIELDS),
                parseContents(o, depth));
    }

    /**
     * Enchantments arrive either as an object ({@code {"sharpness": 5}}) or as an
     * array of records ({@code [{"id":"sharpness","level":5}]}). Both are handled
     * because which one you get appears to depend on the endpoint.
     */
    static Map<String, Integer> parseEnchantments(JsonObject o) {
        JsonElement el = Json.first(o, ENCHANT_FIELDS);
        if (el == null) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();

        if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    try {
                        result.put(e.getKey().toLowerCase(), e.getValue().getAsInt());
                    } catch (NumberFormatException ignored) {
                        // Non-numeric level: unusable for pricing, drop it.
                    }
                }
            }
        } else if (el.isJsonArray()) {
            for (JsonElement entry : el.getAsJsonArray()) {
                JsonObject eo = Json.obj(entry);
                if (eo == null) {
                    continue;
                }
                String name = Json.str(eo, null, "id", "name", "enchantment", "type");
                int level = Json.integer(eo, 1, "level", "lvl", "value");
                if (name != null && !name.isBlank()) {
                    result.put(name.toLowerCase(), level);
                }
            }
        }
        return result;
    }

    private static List<AuctionItem> parseContents(JsonObject o, int depth) {
        JsonArray arr = Json.array(o, CONTENTS_FIELDS);
        if (arr == null) {
            return List.of();
        }
        List<AuctionItem> out = new ArrayList<>();
        for (JsonElement el : arr) {
            AuctionItem child = parseItem(Json.obj(el), depth + 1);
            if (child != null) {
                out.add(child);
            }
        }
        return out;
    }
}
