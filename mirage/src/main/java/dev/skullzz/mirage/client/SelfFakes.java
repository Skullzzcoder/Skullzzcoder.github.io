package dev.skullzz.mirage.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import dev.skullzz.mirage.Mirage;

/**
 * Fake items painted into the client's own copy of the player inventory.
 *
 * <p>Nothing here reaches the server. The client transmits actions, never item identities,
 * so the server's view of the inventory is untouched and nobody else sees any of this.
 *
 * <p>The real stack a fake covers is remembered, so clearing a fake puts the truth back
 * immediately rather than waiting for the server to next touch that slot.
 */
public final class SelfFakes {
    /** Inventory slot to the stack shown there. 0-8 is the hotbar, 9-35 the main inventory. */
    private static final Map<Integer, ItemStack> fakes = new LinkedHashMap<>();
    /** The real stack each fake is covering. */
    private static final Map<Integer, ItemStack> shadowed = new HashMap<>();
    /** The exact stack instance we last wrote, to spot the server overwriting it. */
    private static final Map<Integer, ItemStack> applied = new HashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int SLOT_COUNT = 36;

    private SelfFakes() {
    }

    // ------------------------------------------------------------------- slots

    /** @return the inventory index for a friendly name, or -1. */
    public static int slotIndex(String name) {
        String cleaned = name.toLowerCase(Locale.ROOT).trim();
        try {
            if (cleaned.startsWith("hotbar")) {
                int number = Integer.parseInt(cleaned.substring("hotbar".length()));
                if (number >= 1 && number <= 9) return number - 1;
            } else if (cleaned.startsWith("inv")) {
                int number = Integer.parseInt(cleaned.substring("inv".length()));
                if (number >= 1 && number <= 27) return 8 + number;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the failure below
        }
        return -1;
    }

    public static String slotName(int index) {
        if (index >= 0 && index <= 8) return "hotbar" + (index + 1);
        if (index >= 9 && index <= 35) return "inv" + (index - 8);
        return "slot" + index;
    }

    public static List<String> slotNames() {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 9; i++) names.add("hotbar" + i);
        for (int i = 1; i <= 27; i++) names.add("inv" + i);
        return names;
    }

    /** Looks up an item id, with or without the namespace. Isolated: one place to fix. */
    public static Item lookupItem(String id) {
        String cleaned = id.toLowerCase(Locale.ROOT).trim();
        if (cleaned.isEmpty()) return null;
        if (!cleaned.contains(":")) cleaned = "minecraft:" + cleaned;

        Identifier identifier = Identifier.tryParse(cleaned);
        if (identifier == null) return null;
        return Registries.ITEM.getOrEmpty(identifier).orElse(null);
    }

    // ------------------------------------------------------------------- state

    public static Map<Integer, ItemStack> all() {
        return fakes;
    }

    public static boolean has(int slot) {
        return fakes.containsKey(slot);
    }

    public static ItemStack get(int slot) {
        ItemStack stack = fakes.get(slot);
        return stack == null ? ItemStack.EMPTY : stack;
    }

    public static void set(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SLOT_COUNT || stack.isEmpty()) return;
        fakes.put(slot, stack.copy());
        save();
    }

    /** Clears one slot and puts the real stack back on screen straight away. */
    public static void clear(int slot, ClientPlayerEntity player) {
        if (fakes.remove(slot) == null) return;
        restore(slot, player);
        save();
    }

    public static void clearAll(ClientPlayerEntity player) {
        for (Integer slot : new ArrayList<>(fakes.keySet())) {
            fakes.remove(slot);
            restore(slot, player);
        }
        save();
    }

    private static void restore(int slot, ClientPlayerEntity player) {
        ItemStack real = shadowed.remove(slot);
        applied.remove(slot);
        if (player != null && real != null) {
            player.getInventory().setStack(slot, real);
        }
    }

    // ----------------------------------------------------------------- applying

    /** Repaints the fakes over the client's inventory. Called every client tick. */
    public static void apply(ClientPlayerEntity player) {
        if (fakes.isEmpty()) return;
        PlayerInventory inventory = player.getInventory();

        for (Map.Entry<Integer, ItemStack> entry : fakes.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= SLOT_COUNT) continue;

            ItemStack current = inventory.getStack(slot);
            // Identity, not equality: if this is not the very stack we wrote, the server
            // has since replaced it, so that is the real item now hiding underneath.
            if (current != applied.get(slot)) {
                shadowed.put(slot, current.copy());
                ItemStack copy = entry.getValue().copy();
                inventory.setStack(slot, copy);
                applied.put(slot, copy);
            }
        }
    }

    /** Forgets what was underneath, e.g. after leaving a world. */
    public static void forgetShadows() {
        shadowed.clear();
        applied.clear();
    }

    // -------------------------------------------------------------- persistence

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("mirage-client.json");
    }

    public static void load() {
        fakes.clear();
        Path file = file();
        if (!Files.exists(file)) return;

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            if (!parsed.isJsonObject()) return;

            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                int slot = Integer.parseInt(entry.getKey());
                JsonObject json = entry.getValue().getAsJsonObject();

                Item item = lookupItem(json.get("id").getAsString());
                if (item == null) continue;

                int count = json.has("count") ? json.get("count").getAsInt() : 1;
                fakes.put(slot, new ItemStack(item, Math.max(1, count)));
            }
        } catch (IOException | RuntimeException e) {
            Mirage.LOGGER.error("Mirage could not read {} -- starting empty", file, e);
            fakes.clear();
        }
    }

    public static void save() {
        JsonObject root = new JsonObject();
        for (Map.Entry<Integer, ItemStack> entry : fakes.entrySet()) {
            ItemStack stack = entry.getValue();
            Identifier id = Registries.ITEM.getId(stack.getItem());

            JsonObject json = new JsonObject();
            json.addProperty("id", id.toString());
            json.addProperty("count", stack.getCount());
            root.add(String.valueOf(entry.getKey()), json);
        }

        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException e) {
            Mirage.LOGGER.error("Mirage could not write {}", file, e);
        }
    }
}
