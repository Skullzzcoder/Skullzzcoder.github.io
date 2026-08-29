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
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.Identifier;

import dev.skullzz.mirage.Mirage;

/**
 * Fake items painted into the client's own copies of the player inventory and of whatever
 * container is open.
 *
 * <p>Nothing here reaches the server. The client transmits actions, never item identities,
 * so the server's view is untouched and nobody else sees any of this.
 */
public final class SelfFakes {
    public static final int SLOT_COUNT = 36;
    /** Container sizes we keep separate sets of fakes for. */
    public static final int DISPENSER = 9;
    public static final int ENDER_CHEST = 27;
    /** Every container handler carries the player's 36 inventory slots after its own. */
    private static final int PLAYER_SLOTS = 36;

    private static final Map<Integer, FakeSpec> fakes = new LinkedHashMap<>();
    private static final Map<Integer, ItemStack> shadowed = new HashMap<>();
    /** The exact stack instance we last wrote, to spot the server overwriting it. */
    private static final Map<Integer, ItemStack> applied = new HashMap<>();

    /** Container size to (slot to fake). 9 is a dispenser or dropper, 27 an ender chest. */
    private static final Map<Integer, Map<Integer, FakeSpec>> containerFakes = new HashMap<>();
    private static final Map<Integer, ItemStack> containerShadowed = new HashMap<>();
    private static final Map<Integer, ItemStack> containerApplied = new HashMap<>();

    private static Map<Identifier, Item> itemsById;
    /** Whether cycling a preset prints anything. Off by default. */
    private static boolean announceSwitching = false;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SelfFakes() {
    }

    // ------------------------------------------------------------------- slots

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
            // fall through
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

    /**
     * Looks up an item id, with or without the namespace.
     *
     * <p>Built by walking the registry once rather than calling a lookup method, because the
     * shape of those has churned across versions while iteration and getId have not.
     */
    public static Item lookupItem(String id) {
        String cleaned = id.toLowerCase(Locale.ROOT).trim();
        if (cleaned.isEmpty()) return null;
        if (!cleaned.contains(":")) cleaned = "minecraft:" + cleaned;

        Identifier identifier = Identifier.tryParse(cleaned);
        if (identifier == null) return null;

        if (itemsById == null) {
            Map<Identifier, Item> index = new HashMap<>();
            for (Item candidate : Registries.ITEM) {
                index.put(Registries.ITEM.getId(candidate), candidate);
            }
            itemsById = index;
        }
        return itemsById.get(identifier);
    }

    // --------------------------------------------------------- inventory fakes

    public static Map<Integer, FakeSpec> all() {
        return fakes;
    }

    public static boolean has(int slot) {
        return fakes.containsKey(slot);
    }

    public static ItemStack get(int slot) {
        FakeSpec spec = fakes.get(slot);
        return spec == null ? ItemStack.EMPTY : spec.stack();
    }

    public static boolean announceSwitching() {
        return announceSwitching;
    }

    public static void setAnnounceSwitching(boolean announce) {
        announceSwitching = announce;
        save();
    }

    public static void set(int slot, FakeSpec spec) {
        if (slot < 0 || slot >= SLOT_COUNT) return;
        fakes.put(slot, spec);
        save();
    }

    public static void clear(int slot, ClientPlayerEntity player) {
        if (fakes.remove(slot) == null) return;
        ItemStack real = shadowed.remove(slot);
        applied.remove(slot);
        if (player != null && real != null) player.getInventory().setStack(slot, real);
        save();
    }

    public static void clearAll(ClientPlayerEntity player) {
        for (Integer slot : new ArrayList<>(fakes.keySet())) {
            fakes.remove(slot);
            ItemStack real = shadowed.remove(slot);
            applied.remove(slot);
            if (player != null && real != null) player.getInventory().setStack(slot, real);
        }
        save();
    }

    // --------------------------------------------------------- container fakes

    public static Map<Integer, FakeSpec> allContainer(int size) {
        return containerFakes.computeIfAbsent(size, key -> new LinkedHashMap<>());
    }

    public static boolean hasContainer(int size, int slot) {
        return allContainer(size).containsKey(slot);
    }

    public static ItemStack getContainer(int size, int slot) {
        FakeSpec spec = allContainer(size).get(slot);
        return spec == null ? ItemStack.EMPTY : spec.stack();
    }

    public static void setContainer(int size, int slot, FakeSpec spec) {
        if (slot < 0 || slot >= size) return;
        allContainer(size).put(slot, spec);
        save();
    }

    public static void clearContainer(int size, int slot, ClientPlayerEntity player) {
        if (allContainer(size).remove(slot) == null) return;
        containerShadowed.remove(slot);
        containerApplied.remove(slot);
        save();
    }

    public static void clearAllContainers(ClientPlayerEntity player) {
        containerFakes.clear();
        containerShadowed.clear();
        containerApplied.clear();
        save();
    }

    // ----------------------------------------------------------------- applying

    public static void apply(ClientPlayerEntity player) {
        applyInventory(player);
        applyContainer(player);
    }

    private static void applyInventory(ClientPlayerEntity player) {
        if (fakes.isEmpty()) return;
        PlayerInventory inventory = player.getInventory();

        for (Map.Entry<Integer, FakeSpec> entry : fakes.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= SLOT_COUNT) continue;

            ItemStack current = inventory.getStack(slot);
            // Identity, not equality: if this is not the very stack we wrote, the server has
            // since replaced it, so that is the real item now hiding underneath.
            if (current != applied.get(slot)) {
                shadowed.put(slot, current.copy());
                ItemStack copy = entry.getValue().stack().copy();
                inventory.setStack(slot, copy);
                applied.put(slot, copy);
            }
        }
    }

    /**
     * Paints the fakes for whatever container is open.
     *
     * <p>The open container's own slots come before the player's 36, so its size identifies
     * it well enough: nine is a dispenser or dropper, twenty-seven an ender chest. A normal
     * chest is also twenty-seven and gets the ender chest's fakes, which is a fair trade for
     * not having to guess at screen handler types.
     */
    private static void applyContainer(ClientPlayerEntity player) {
        ScreenHandler handler = player.currentScreenHandler;
        if (handler == player.playerScreenHandler) {
            // Nothing is open, so anything we painted went with the screen.
            containerApplied.clear();
            containerShadowed.clear();
            return;
        }

        int size = handler.slots.size() - PLAYER_SLOTS;
        if (size <= 0) return;

        Map<Integer, FakeSpec> target = containerFakes.get(size);
        if (target == null || target.isEmpty()) return;

        for (Map.Entry<Integer, FakeSpec> entry : target.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= size) continue;

            ItemStack current = handler.getSlot(slot).getStack();
            if (current != containerApplied.get(slot)) {
                containerShadowed.put(slot, current.copy());
                ItemStack copy = entry.getValue().stack().copy();
                handler.setStackInSlot(slot, handler.getRevision(), copy);
                containerApplied.put(slot, copy);
            }
        }
    }

    public static void forgetShadows() {
        shadowed.clear();
        applied.clear();
        containerShadowed.clear();
        containerApplied.clear();
    }

    /** Rebuilds every fake so a changed price file shows up without retyping anything. */
    public static void rebuildAll() {
        for (FakeSpec spec : fakes.values()) spec.invalidate();
        for (Map<Integer, FakeSpec> target : containerFakes.values()) {
            for (FakeSpec spec : target.values()) spec.invalidate();
        }
        ClientDispensers.invalidateResult();
        applied.clear();
        containerApplied.clear();
    }

    // -------------------------------------------------------------- persistence

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("mirage-client.json");
    }

    public static void load() {
        fakes.clear();
        containerFakes.clear();

        Path file = file();
        if (!Files.exists(file)) {
            WebDashboard.configure(new JsonObject());
            return;
        }

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            if (!parsed.isJsonObject()) return;
            JsonObject root = parsed.getAsJsonObject();

            if (root.has("inventory")) {
                readSection(root.getAsJsonObject("inventory"), fakes);
            } else if (!root.has("containers")) {
                // Files written before sections existed were a bare slot map.
                readSection(root, fakes);
            }

            if (root.has("containers")) {
                JsonObject containers = root.getAsJsonObject("containers");
                for (Map.Entry<String, JsonElement> entry : containers.entrySet()) {
                    int size = Integer.parseInt(entry.getKey());
                    readSection(entry.getValue().getAsJsonObject(), allContainer(size));
                }
            }
            announceSwitching = root.has("announceSwitching")
                    && root.get("announceSwitching").getAsBoolean();
            ClientDispensers.load(root);
            ClientDecor.load(root);
            WebDashboard.configure(root);
        } catch (IOException | RuntimeException e) {
            Mirage.LOGGER.error("Mirage could not read {} -- starting empty", file, e);
            fakes.clear();
            containerFakes.clear();
        }
    }

    private static void readSection(JsonObject section, Map<Integer, FakeSpec> target) {
        for (Map.Entry<String, JsonElement> entry : section.entrySet()) {
            try {
                int slot = Integer.parseInt(entry.getKey());
                JsonObject json = entry.getValue().getAsJsonObject();

                Item item = lookupItem(json.get("id").getAsString());
                if (item == null) continue;

                int count = json.has("count") ? json.get("count").getAsInt() : 1;
                String enchants = json.has("enchants") ? json.get("enchants").getAsString() : "";
                Double price = json.has("price") ? json.get("price").getAsDouble() : null;
                target.put(slot, new FakeSpec(item, count, enchants, price));
            } catch (RuntimeException ignored) {
                // one unreadable entry should not lose the rest
            }
        }
    }

    public static void save() {
        JsonObject root = new JsonObject();
        root.add("inventory", writeSection(fakes));

        JsonObject containers = new JsonObject();
        for (Map.Entry<Integer, Map<Integer, FakeSpec>> entry : containerFakes.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            containers.add(String.valueOf(entry.getKey()), writeSection(entry.getValue()));
        }
        root.add("containers", containers);
        root.addProperty("announceSwitching", announceSwitching);
        WebDashboard.writeConfig(root);
        ClientDecor.save(root);
        ClientDispensers.save(root);

        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException e) {
            Mirage.LOGGER.error("Mirage could not write {}", file, e);
        }
    }

    public static JsonObject writeSpec(FakeSpec spec) {
        JsonObject json = new JsonObject();
        json.addProperty("id", Registries.ITEM.getId(spec.item).toString());
        json.addProperty("count", spec.count);
        if (!spec.enchants.isEmpty()) json.addProperty("enchants", spec.enchants);
        if (spec.price != null) json.addProperty("price", spec.price);
        return json;
    }

    private static JsonObject writeSection(Map<Integer, FakeSpec> source) {
        JsonObject section = new JsonObject();
        for (Map.Entry<Integer, FakeSpec> entry : source.entrySet()) {
            section.add(String.valueOf(entry.getKey()), writeSpec(entry.getValue()));
        }
        return section;
    }
}
