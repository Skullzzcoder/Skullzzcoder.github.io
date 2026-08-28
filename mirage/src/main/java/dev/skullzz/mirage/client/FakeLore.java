package dev.skullzz.mirage.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
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
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##");

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
        lore.add("&7Sell Price: &a$%price%");
        root.add("lore", lore);

        JsonObject starter = new JsonObject();
        starter.addProperty("minecraft:diamond_block", 4500);
        starter.addProperty("minecraft:netherite_ingot", 32000);
        starter.addProperty("minecraft:diamond", 500);
        root.add("prices", starter);

        root.addProperty("_comment", "Placeholder values -- replace with the real ones. "
                + "An item with no price here gets no lore line.");

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
            Mirage.LOGGER.info("Mirage wrote a starter price file to {}", file);
        } catch (IOException e) {
            Mirage.LOGGER.error("Mirage could not write {}", file, e);
        }
    }

    public static boolean hasPriceFor(ItemStack stack) {
        return priceOf(stack) != null;
    }

    private static Double priceOf(ItemStack stack) {
        return prices.get(Registries.ITEM.getId(stack.getItem()).toString());
    }

    /** Adds the price lore to a stack, if there is a price for it. Returns the same stack. */
    public static ItemStack applyTo(ItemStack stack) {
        Double unitPrice = priceOf(stack);
        if (unitPrice == null || loreLines.isEmpty()) return stack;

        // Price scales with the stack, the way a sell-all total would.
        String formatted = MONEY.format(unitPrice * stack.getCount());

        List<Text> lines = new ArrayList<>();
        for (String line : loreLines) {
            String text = line.replace("%price%", formatted)
                    .replace("%unit%", MONEY.format(unitPrice))
                    .replace('&', '§');
            // Lore is italic by default; server-formatted items normally are not.
            lines.add(Text.literal(text).setStyle(Style.EMPTY.withItalic(false)));
        }

        stack.set(DataComponentTypes.LORE, new LoreComponent(lines));
        return stack;
    }
}
