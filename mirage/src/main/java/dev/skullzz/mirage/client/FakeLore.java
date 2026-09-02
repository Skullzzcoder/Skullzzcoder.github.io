package dev.skullzz.mirage.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import dev.skullzz.mirage.Mirage;

/**
 * Puts a price line on a fake item so it reads like a server-formatted item rather than a
 * bare vanilla one.
 *
 * <p>Prices come from {@code config/mirage-prices.json}, which you edit. Nothing is fetched:
 * the values are whatever you put there. Every mappings-sensitive call lives in
 * {@link #applyTo(ItemStack)}.
 */
public final class FakeLore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    // Locale.ROOT on purpose: a European default locale would render 19,1K.
    private static final DecimalFormat MONEY =
            new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final DecimalFormat SHORT =
            new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));

    /** Item id to price. */
    private static final Map<String, Double> prices = new HashMap<>();
    /** Lore lines; %price% is substituted, and &-codes become colours. */
    private static final List<String> loreLines = new ArrayList<>();

    private FakeLore() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("mirage-prices.json");
    }

    public static void load() {
        prices.clear();
        loreLines.clear();

        Path file = file();
        if (!Files.exists(file)) {
            writeStarterFile(file);
        }

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            if (!parsed.isJsonObject()) return;
            JsonObject root = parsed.getAsJsonObject();

            if (root.has("lore")) {
                JsonArray array = root.getAsJsonArray("lore");
                for (JsonElement line : array) loreLines.add(line.getAsString());
            }
            if (root.has("api")) PriceApi.configure(root.getAsJsonObject("api"));
            if (root.has("prices")) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("prices").entrySet()) {
                    String id = entry.getKey().contains(":") ? entry.getKey() : "minecraft:" + entry.getKey();
                    prices.put(id, entry.getValue().getAsDouble());
                }
            }
            Mirage.LOGGER.info("Mirage loaded {} prices", prices.size());
        } catch (IOException | RuntimeException e) {
            Mirage.LOGGER.error("Mirage could not read {}", file, e);
        }
    }

    /**
     * The shipped values are PLACEHOLDERS, not real server prices. Edit them to whatever the
     * server actually pays, or the lore will read convincingly and be wrong.
     */
    private static void writeStarterFile(Path file) {
        JsonObject root = new JsonObject();

        JsonArray lore = new JsonArray();
        lore.add("&7~ &a$ %short%");
        root.add("lore", lore);

        JsonObject starter = new JsonObject();
        starter.addProperty("minecraft:gold_block", 19100);
        starter.addProperty("minecraft:diamond_block", 44600);
        root.add("prices", starter);

        root.addProperty("_comment", "An item with no price here gets no lore line. "
                + "%short% is the stack total as 19.1K, %unit_short% is per item, "
                + "%price% and %unit% are the same numbers written out in full.");

        // Optional live lookup. Off by default, and deliberately generic: fill in the URL,
        // the headers and the path to the number, whatever shape the API returns.
        JsonObject api = new JsonObject();
        api.addProperty("enabled", false);
        api.addProperty("url", "https://example.invalid/price/%item_short%");
        api.addProperty("path", "result.price");
        api.addProperty("cacheMinutes", 30);
        JsonObject headers = new JsonObject();
        headers.addProperty("Authorization", "Bearer PUT_YOUR_KEY_HERE");
        api.add("headers", headers);
        api.addProperty("_comment", "Your key lives in this file. Do not share or upload it.");
        root.add("api", api);

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
            Mirage.LOGGER.info("Mirage wrote a starter price file to {}", file);
        } catch (IOException e) {
            Mirage.LOGGER.error("Mirage could not write {}", file, e);
        }
    }

    /**
     * Money the way a server writes it: 19100 becomes 19.1K, 1234567 becomes 1.2M.
     *
     * <p>A whole number keeps no decimal, so 20000 is 20K rather than 20.0K.
     */
    static String abbreviate(double value) {
        double magnitude = Math.abs(value);

        if (magnitude >= 1_000_000_000.0) return SHORT.format(value / 1_000_000_000.0) + "B";
        if (magnitude >= 1_000_000.0) return SHORT.format(value / 1_000_000.0) + "M";
        if (magnitude >= 1_000.0) return SHORT.format(value / 1_000.0) + "K";
        return MONEY.format(value);
    }

    public static boolean hasPriceFor(ItemStack stack) {
        return priceOf(stack) != null;
    }

    /** What a price would render as, for showing back in the menu. */
    public static String preview(double price) {
        return abbreviate(price);
    }

    /** @return something like {@code $19.1K}, or empty if this item has no price. */
    public static String priceLabel(ItemStack stack, Double override) {
        Double price = override != null ? override : priceOf(stack);
        return price == null ? "" : "$" + abbreviate(price * stack.getCount());
    }

    private static Double priceOf(ItemStack stack) {
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        // The local table wins, so a value you set by hand is never overwritten by the API.
        Double local = prices.get(id);
        return local != null ? local : PriceApi.lookup(id);
    }

    /**
     * Points a filled map at a particular map.
     *
     * <p>A map's picture belongs to the server, so this only shows something if the client has
     * already been sent that map. Ids seen in an earlier round will render; a made-up one
     * stays blank.
     */
    public static void applyMapId(ItemStack stack, Integer mapId) {
        if (mapId == null) return;

        try {
            stack.set(DataComponentTypes.MAP_ID, new MapIdComponent(mapId));
        } catch (RuntimeException e) {
            Mirage.LOGGER.warn("Mirage could not set map id {}: {}", mapId, e.toString());
        }
    }

    /** Names an item. Non-italic, since a server-named item usually is. */
    public static void applyName(ItemStack stack, String name) {
        if (name == null || name.isEmpty()) return;

        try {
            stack.set(DataComponentTypes.CUSTOM_NAME,
                    Text.literal(name.replace('&', '§')).setStyle(Style.EMPTY.withItalic(false)));
        } catch (RuntimeException e) {
            Mirage.LOGGER.warn("Mirage could not name an item: {}", e.toString());
        }
    }

    public static ItemStack applyTo(ItemStack stack) {
        return applyTo(stack, null, null);
    }

    public static ItemStack applyTo(ItemStack stack, String enchantSpec) {
        return applyTo(stack, enchantSpec, null);
    }

    /**
     * Writes the enchantment lines and the price lines into the stack's lore, and turns on
     * the glint if there are enchantments.
     *
     * <p>Both kinds of line share the one lore component, so they are composed here and
     * written once — setting it twice would erase the first set.
     */
    public static ItemStack applyTo(ItemStack stack, String enchantSpec, Double priceOverride) {
        List<Text> lines = new ArrayList<>();

        for (String enchant : FakeEnchants.lines(enchantSpec)) {
            lines.add(Text.literal(enchant).setStyle(Style.EMPTY.withItalic(false)));
        }

        // A price set on the fake itself beats the file, which beats the API -- but the
        // switch beats all three. Asked here rather than at each caller so that a price set
        // on one fake by hand cannot slip a line back onto an item that is meant to be bare.
        Double unitPrice = priceOverride != null ? priceOverride : priceOf(stack);
        if (SelfFakes.showPrices() && unitPrice != null && !loreLines.isEmpty()) {
            // Price scales with the stack, the way a sell-all total would.
            double total = unitPrice * stack.getCount();
            for (String line : loreLines) {
                String text = line.replace("%short%", abbreviate(total))
                        .replace("%unit_short%", abbreviate(unitPrice))
                        .replace("%price%", MONEY.format(total))
                        .replace("%unit%", MONEY.format(unitPrice))
                        .replace('&', '§');
                // Lore is italic by default; server-formatted items normally are not.
                lines.add(Text.literal(text).setStyle(Style.EMPTY.withItalic(false)));
            }
        }

        if (!lines.isEmpty()) {
            stack.set(DataComponentTypes.LORE, new LoreComponent(lines));
        }
        if (FakeEnchants.hasAny(enchantSpec)) {
            stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
        }
        return stack;
    }
}
