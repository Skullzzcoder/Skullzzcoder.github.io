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
 *
 * <p>The real stack a fake covers is remembered, so clearing a fake puts the truth back
 * immediately rather than waiting for the server to next touch that slot.
 */
public final class SelfFakes {
    /** Inventory slot to the stack shown there. 0-8 is the hotbar, 9-35 the main inventory. */
    private static final Map<Integer, ItemStack> fakes = new LinkedHashMap<>();
    private static final Map<Integer, ItemStack> shadowed = new HashMap<>();
    /** The exact stack instance we last wrote, to spot the server overwriting it. */
    private static final Map<Integer, ItemStack> applied = new HashMap<>();

    /** Slot 0-8 of whatever container is open: a dispenser or dropper's nine slots. */
    private static final Map<Integer, ItemStack> containerFakes = new LinkedHashMap<>();
    private static final Map<Integer, ItemStack> containerShadowed = new HashMap<>();
    private static final Map<Integer, ItemStack> containerApplied = new HashMap<>();

    /** Item id index, built on first use. The registry is fixed once the game is running. */
    private static Map<Identifier, Item> itemsById;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final int SLOT_COUNT = 36;
    public static final int CONTAINER_SLOT_COUNT = 9;

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

    /**
     * Looks up an item id, with or without the namespace.
     *
     * <p>Built by walking the registry once rather than calling a lookup method, because the
     * shape of those has churned across versions while iteration and getId have not. The
     * scan happens once and only on the first lookup, which is a menu click.
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

    /** Builds the stack as it will be shown, price lore included. */
    public static ItemStack buildStack(Item item, int count) {
        return FakeLore.applyTo(new ItemStack(item, Math.max(1, Math.min(count, 127))));
    }

    // ------------------------------------------------------- inventory fakes

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

        ItemStack real = shadowed.remove(slot);
        applied.remove(slot);
        if (player != null && real != null) player.getInventory().setStack(slot, real);
        save();
    }

    public static void clearAll(ClientPlayerEntity player) {
        for (Integer slot : new ArrayList<>(fakes.keySet())) {
            clearWithoutSaving(slot, player);
        }
        save();
    }

    private static void clearWithoutSaving(int slot, ClientPlayerEntity player) {
        fakes.remove(slot);
        ItemStack real = shadowed.remove(slot);
        applied.remove(slot);
        if (player != null && real != null) player.getInventory().setStack(slot, real);
    }

    // ------------------------------------------------------- container fakes

    public static Map<Integer, ItemStack> allContainer() {
        return containerFakes;
    }

    public static boolean hasContainer(int slot) {
        return containerFakes.containsKey(slot);
    }

    public static ItemStack getContainer(int slot) {
        ItemStack stack = containerFakes.get(slot);
        return stack == null ? ItemStack.EMPTY : stack;
    }

    public static void setContainer(int slot, ItemStack stack) {
        if (slot < 0 || slot >= CONTAINER_SLOT_COUNT || stack.isEmpty()) return;
        containerFakes.put(slot, stack.copy());
        save();
    }

    public static void clearContainer(int slot, ClientPlayerEntity player) {
        if (containerFakes.remove(slot) == null) return;
        restoreContainerSlot(slot, player);
        save();
    }

    public static void clearAllContainer(ClientPlayerEntity player) {
        for (Integer slot : new ArrayList<>(containerFakes.keySet())) {
            containerFakes.remove(slot);
            restoreContainerSlot(slot, player);
        }
        save();
    }

    private static void restoreContainerSlot(int slot, ClientPlayerEntity player) {
        ItemStack real = containerShadowed.remove(slot);
        containerApplied.remove(slot);
        if (player == null || real == null) return;

        ScreenHandler handler = player.currentScreenHandler;
        if (handler != player.playerScreenHandler && slot < handler.slots.size()) {
            handler.setStackInSlot(slot, handler.getRevision(), real);
        }
    }

    // ----------------------------------------------------------------- applying

    /** Repaints the fakes over the client's own state. Called every client tick. */
    public static void apply(ClientPlayerEntity player) {
        applyInventory(player);
        applyContainer(player);
    }

    private static void applyInventory(ClientPlayerEntity player) {
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

    /**
     * Paints the container fakes into an open dispenser or dropper.
     *
     * <p>The first nine slots of any non-player screen handler are the container's own, which
     * is exactly a dispenser or dropper's grid. Bigger containers get their top row faked;
     * that is harmless and keeps this free of screen-type guessing.
     */
    private static void applyContainer(ClientPlayerEntity player) {
        ScreenHandler handler = player.currentScreenHandler;
        if (handler == player.playerScreenHandler) {
            // Nothing is open, so anything we painted is gone with the screen.
            containerApplied.clear();
            containerShadowed.clear();
            return;
        }
        if (containerFakes.isEmpty() || handler.slots.size() < CONTAINER_SLOT_COUNT) return;

        for (Map.Entry<Integer, ItemStack> entry : containerFakes.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= CONTAINER_SLOT_COUNT) continue;

            ItemStack current = handler.getSlot(slot).getStack();
            if (current != containerApplied.get(slot)) {
                containerShadowed.put(slot, current.copy());
                ItemStack copy = entry.getValue().copy();
                handler.setStackInSlot(slot, handler.getRevision(), copy);
                containerApplied.put(slot, copy);
            }
        }
    }

    /** Forgets what was underneath, e.g. after leaving a world. */
    public static void forgetShadows() {
        shadowed.clear();
        applied.clear();
        containerShadowed.clear();
        containerApplied.clear();
    }

    /** Rebuilds every fake so a changed price file shows up without retyping anything. */
    public static void rebakeLore(ClientPlayerEntity player) {
        rebake(fakes);
        rebake(containerFakes);
        // Force a repaint on the next tick.
        applied.clear();
        containerApplied.clear();
        save();
    }

    private static void rebake(Map<Integer, ItemStack> target) {
        for (Map.Entry<Integer, ItemStack> entry : target.entrySet()) {
            ItemStack old = entry.getValue();
            entry.setValue(buildStack(old.getItem(), old.getCount()));
        }
    }

    // -------------------------------------------------------------- persistence

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("mirage-client.json");
    }

    public static void load() {
        fakes.clear();
        containerFakes.clear();

        Path file = file();
        if (!Files.exists(file)) return;

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            if (!parsed.isJsonObject()) return;
            JsonObject root = parsed.getAsJsonObject();

            if (root.has("inventory") || root.has("container")) {
                if (root.has("inventory")) readSection(root.getAsJsonObject("inventory"), fakes);
                if (root.has("container")) readSection(root.getAsJsonObject("container"), containerFakes);
            } else {
                // Files written before container fakes existed were a bare slot map.
                readSection(root, fakes);
            }
        } catch (IOException | RuntimeException e) {
            Mirage.LOGGER.error("Mirage could not read {} -- starting empty", file, e);
            fakes.clear();
            containerFakes.clear();
        }
    }

    private static void readSection(JsonObject section, Map<Integer, ItemStack> target) {
        for (Map.Entry<String, JsonElement> entry : section.entrySet()) {
            try {
                int slot = Integer.parseInt(entry.getKey());
                JsonObject json = entry.getValue().getAsJsonObject();

                Item item = lookupItem(json.get("id").getAsString());
                if (item == null) continue;

                int count = json.has("count") ? json.get("count").getAsInt() : 1;
                target.put(slot, buildStack(item, count));
            } catch (RuntimeException ignored) {
                // one unreadable entry should not lose the rest
            }
        }
    }

    public static void save() {
        JsonObject root = new JsonObject();
        root.add("inventory", writeSection(fakes));
        root.add("container", writeSection(containerFakes));

        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException e) {
            Mirage.LOGGER.error("Mirage could not write {}", file, e);
        }
    }

    private static JsonObject writeSection(Map<Integer, ItemStack> source) {
        JsonObject section = new JsonObject();
        for (Map.Entry<Integer, ItemStack> entry : source.entrySet()) {
            ItemStack stack = entry.getValue();

            JsonObject json = new JsonObject();
            json.addProperty("id", Registries.ITEM.getId(stack.getItem()).toString());
            json.addProperty("count", stack.getCount());
            section.add(String.valueOf(entry.getKey()), json);
        }
        return section;
    }
}
