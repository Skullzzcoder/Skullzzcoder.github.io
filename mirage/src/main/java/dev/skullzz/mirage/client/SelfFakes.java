package dev.skullzz.mirage.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.Hand;
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
    /** Everything a player carries: 36 of inventory, then the armour, then the offhand. */
    public static final int SLOT_COUNT = 41;
    /** Where the carried part ends. A pickup never lands past here. */
    public static final int CARRIED_SLOTS = 36;
    /** Worn slots, in the order the inventory keeps them: feet upwards, then the offhand. */
    private static final String[] WORN = { "boots", "legs", "chest", "helmet", "offhand" };
    /**
     * The same slots as the model knows them.
     *
     * <p>What a player is wearing is drawn from what they have equipped, and where that is
     * read from has moved between versions. Writing both the slot and the equipment costs
     * nothing and means a fake set is actually on you either way.
     */
    private static final EquipmentSlot[] WORN_SLOTS = {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST,
            EquipmentSlot.HEAD, EquipmentSlot.OFFHAND };
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
    /** Set when a count changed, since the stack we painted is still the one in the slot. */
    private static boolean containerDirty;

    private static Map<Identifier, Item> itemsById;
    private static Map<Identifier, Block> blocksById;
    /** Whether cycling a preset prints anything. Off by default. */
    private static boolean announceSwitching = false;
    /** Whether a fake fired from a dispenser ends up in the inventory afterwards. */
    private static boolean autoCollect = true;
    /** The master switch. Off puts everything real back without forgetting any of it. */
    private static boolean enabled = true;
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

        // What is worn shows on the player model as well as in the screen, so these are
        // the slots that put a fake on you rather than merely in your bag.
        for (int i = 0; i < WORN.length; i++) {
            if (WORN[i].equals(cleaned)) return CARRIED_SLOTS + i;
        }
        if (cleaned.equals("leggings")) return CARRIED_SLOTS + 1;
        if (cleaned.equals("chestplate")) return CARRIED_SLOTS + 2;
        if (cleaned.equals("head")) return CARRIED_SLOTS + 3;
        return -1;
    }

    public static String slotName(int index) {
        if (index >= 0 && index <= 8) return "hotbar" + (index + 1);
        if (index >= 9 && index <= 35) return "inv" + (index - 8);
        if (index >= CARRIED_SLOTS && index < CARRIED_SLOTS + WORN.length) {
            return WORN[index - CARRIED_SLOTS];
        }
        return "slot" + index;
    }

    public static List<String> slotNames() {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 9; i++) names.add("hotbar" + i);
        for (int i = 1; i <= 27; i++) names.add("inv" + i);
        names.addAll(java.util.Arrays.asList(WORN));
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

        // Rebuilt if it came out empty: an index built before the registry was populated
        // would otherwise make every lookup fail for the rest of the session.
        if (itemsById == null || itemsById.isEmpty()) {
            Map<Identifier, Item> index = new HashMap<>();
            for (Item candidate : Registries.ITEM) {
                index.put(Registries.ITEM.getId(candidate), candidate);
            }
            itemsById = index;
        }
        return itemsById.get(identifier);
    }

    /**
     * Parses what someone typed into a fake.
     *
     * <p>Accepts {@code filled_map#42} to name a particular map, which is how a map art with a
     * number on it is picked out.
     *
     * @return null if the item is not recognised.
     */
    public static FakeSpec buildSpec(String itemText, int count, String enchants, Double price) {
        return buildSpec(itemText, count, enchants, price, "");
    }

    public static FakeSpec buildSpec(String itemText, int count, String enchants, Double price,
                                     String name) {
        String text = itemText == null ? "" : itemText.trim();
        Integer mapId = null;

        int hash = text.indexOf('#');
        if (hash > 0) {
            try {
                mapId = Integer.parseInt(text.substring(hash + 1).trim());
            } catch (NumberFormatException ignored) {
                // a bad suffix just means no map
            }
            text = text.substring(0, hash);
        }

        Item item = lookupItem(text);
        return item == null ? null : new FakeSpec(item, count, enchants, price, mapId, name);
    }

    /**
     * The block of the same name, for output a dispenser places rather than throws.
     *
     * <p>Built the same way as the item index and for the same reason: iterating the
     * registry and asking it for ids are the two things that have not moved between
     * versions, where the lookup methods have.
     */
    public static Block lookupBlock(String id) {
        String cleaned = id.toLowerCase(Locale.ROOT).trim();
        if (cleaned.isEmpty()) return null;
        if (!cleaned.contains(":")) cleaned = "minecraft:" + cleaned;

        Identifier identifier = Identifier.tryParse(cleaned);
        if (identifier == null) return null;

        if (blocksById == null || blocksById.isEmpty()) {
            Map<Identifier, Block> index = new HashMap<>();
            for (Block candidate : Registries.BLOCK) {
                index.put(Registries.BLOCK.getId(candidate), candidate);
            }
            blocksById = index;
        }
        return blocksById.get(identifier);
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

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        save();
    }

    public static boolean autoCollect() {
        return autoCollect;
    }

    public static void setAutoCollect(boolean collect) {
        autoCollect = collect;
        save();
    }

    public static boolean announceSwitching() {
        return announceSwitching;
    }

    public static void setAnnounceSwitching(boolean announce) {
        announceSwitching = announce;
        save();
    }

    /**
     * Which slot a hand is holding a fake in, or -1.
     *
     * <p>By identity against what we painted, not by what the stack looks like: a real item
     * of the same kind in the same hand is somebody else's and must be left alone.
     */
    public static int heldFakeSlot(ClientPlayerEntity player, Hand hand) {
        ItemStack held = player.getStackInHand(hand);

        for (Map.Entry<Integer, ItemStack> entry : applied.entrySet()) {
            if (held == entry.getValue() && fakes.containsKey(entry.getKey())) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /**
     * The four pieces of a set, from the name of what it is made of.
     *
     * <p>Named the way a player would say it rather than the way the registry does, since
     * "gold" is not what the items are called and nobody says "golden".
     */
    public static String[] armourSet(String material) {
        String cleaned = material.toLowerCase(Locale.ROOT).trim();
        if (cleaned.equals("gold")) cleaned = "golden";

        return new String[] {
                cleaned + "_boots", cleaned + "_leggings",
                cleaned + "_chestplate", cleaned + "_helmet" };
    }

    /** Takes one off a fake, the way placing a block does. @return what was taken. */
    public static FakeSpec takeOne(int slot) {
        FakeSpec spec = fakes.get(slot);
        if (spec == null) return null;

        if (spec.count > 1) {
            fakes.put(slot, spec.withCount(spec.count - 1));
        } else {
            fakes.remove(slot);
        }
        // The stack in the slot is still the one we wrote, so nothing would repaint it.
        applied.remove(slot);
        save();
        return spec.withCount(1);
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

        if (player != null && real != null) {
            player.getInventory().setStack(slot, real);
            wear(player, slot, real);
        }
        save();
    }

    /** Puts what is in a worn slot onto the model as well as into the screen. */
    private static void wear(ClientPlayerEntity player, int slot, ItemStack stack) {
        int worn = slot - CARRIED_SLOTS;
        if (worn < 0 || worn >= WORN_SLOTS.length) return;

        player.equipStack(WORN_SLOTS[worn], stack);
    }

    public static void clearAll(ClientPlayerEntity player) {
        for (Integer slot : new ArrayList<>(fakes.keySet())) {
            fakes.remove(slot);
            ItemStack real = shadowed.remove(slot);
            applied.remove(slot);

            if (player != null && real != null) {
                player.getInventory().setStack(slot, real);
                wear(player, slot, real);
            }
        }
        save();
    }

    /**
     * Puts a fake into the inventory the way a pickup would: onto a matching stack if there is
     * one, otherwise into the first slot that is genuinely empty.
     *
     * <p>Slots holding a real item are left alone, so nothing the player actually owns is
     * covered up by walking past a dispenser.
     *
     * @return false if there was nowhere to put it.
     */
    public static boolean collect(FakeSpec spec, ClientPlayerEntity player) {
        for (Map.Entry<Integer, FakeSpec> entry : fakes.entrySet()) {
            FakeSpec existing = entry.getValue();
            if (existing.stacksWith(spec) && existing.count < 64) {
                entry.setValue(existing.withCount(Math.min(64, existing.count + spec.count)));
                applied.remove(entry.getKey());
                save();
                return true;
            }
        }

        // Only the carried part: something walked over belongs in the bag, never worn.
        for (int slot = 0; slot < CARRIED_SLOTS; slot++) {
            if (fakes.containsKey(slot)) continue;
            if (player != null && !player.getInventory().getStack(slot).isEmpty()) continue;

            set(slot, spec);
            return true;
        }
        return false;
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

    /**
     * Forces the open container to be painted again next tick.
     *
     * <p>Needed when a count changes rather than a slot: the stack in the slot is still the
     * very one we wrote, so the identity check below would see nothing to do.
     */
    public static void repaintContainer() {
        containerDirty = true;
    }

    public static void clearAllContainers(ClientPlayerEntity player) {
        containerFakes.clear();
        containerShadowed.clear();
        containerApplied.clear();
        save();
    }

    // ----------------------------------------------------------------- applying

    public static void apply(ClientPlayerEntity player) {
        if (!enabled) {
            revert(player);
            return;
        }
        applyInventory(player);
        applyContainer(player);
    }

    /**
     * Puts the real contents back everywhere, while forgetting nothing.
     *
     * <p>Switched off has to leave a screen somebody else is looking over as honest as one
     * from a client with no mod on it, and switching back on has to cost nothing, so the
     * fakes stay in their maps and only what was painted into the world is undone.
     */
    private static void revert(ClientPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();

        for (Map.Entry<Integer, ItemStack> entry : applied.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= Math.min(SLOT_COUNT, inventory.size())) continue;
            // Only where it is still the very stack we wrote; anything else is the server's.
            if (inventory.getStack(slot) != entry.getValue()) continue;

            ItemStack real = shadowed.get(slot);
            ItemStack back = real == null ? ItemStack.EMPTY : real;
            inventory.setStack(slot, back);
            wear(player, slot, back);
        }
        applied.clear();
        shadowed.clear();

        // An open container is put right by painting nothing into it: the sweep at the end
        // of applyContainer gives back every slot that is no longer faked.
        applyContainer(player);
    }

    private static void applyInventory(ClientPlayerEntity player) {
        if (fakes.isEmpty()) return;
        PlayerInventory inventory = player.getInventory();

        for (Map.Entry<Integer, FakeSpec> entry : fakes.entrySet()) {
            int slot = entry.getKey();
            // Against the inventory's own size, not ours: how many slots a player carries
            // has moved between versions, and reaching past the end would throw.
            if (slot < 0 || slot >= Math.min(SLOT_COUNT, inventory.size())) continue;

            ItemStack current = inventory.getStack(slot);
            // Identity, not equality: if this is not the very stack we wrote, the server has
            // since replaced it, so that is the real item now hiding underneath.
            if (current != applied.get(slot)) {
                shadowed.put(slot, current.copy());
                ItemStack copy = entry.getValue().stack().copy();
                inventory.setStack(slot, copy);
                wear(player, slot, copy);
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
            containerDirty = false;
            return;
        }

        int size = handler.slots.size() - PLAYER_SLOTS;
        if (size <= 0) return;

        Map<Integer, FakeSpec> target = null;
        // A dispenser laid out by its rig knows which block it is, and so empties as that
        // one fires. Anything else falls back to the one set kept per container size.
        if (size == DISPENSER) target = ClientDispensers.openStock(player);
        if (target == null) target = containerFakes.get(size);
        // Switched off paints nothing, which hands every slot back on the sweep below.
        if (target == null || !enabled) target = Map.of();

        for (Map.Entry<Integer, FakeSpec> entry : target.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= size) continue;

            ItemStack current = handler.getSlot(slot).getStack();
            // Identity, not equality: if this is not the very stack we wrote, the server has
            // since replaced it, so that is the real item now hiding underneath.
            boolean ours = current == containerApplied.get(slot);
            if (!ours) containerShadowed.put(slot, current.copy());

            if (!ours || containerDirty) {
                ItemStack copy = entry.getValue().stack().copy();
                handler.setStackInSlot(slot, handler.getRevision(), copy);
                containerApplied.put(slot, copy);
            }
        }

        restoreUnpainted(handler, size, target);
        containerDirty = false;
    }

    /**
     * Puts back the real contents of a slot we no longer fake.
     *
     * <p>Without this a depleted slot would keep showing the item that just left, which is
     * the exact thing the depletion is there to avoid.
     */
    private static void restoreUnpainted(ScreenHandler handler, int size,
                                         Map<Integer, FakeSpec> target) {
        Iterator<Map.Entry<Integer, ItemStack>> painted = containerApplied.entrySet().iterator();

        while (painted.hasNext()) {
            Map.Entry<Integer, ItemStack> entry = painted.next();
            int slot = entry.getKey();
            if (target.containsKey(slot)) continue;

            if (slot >= 0 && slot < size && handler.getSlot(slot).getStack() == entry.getValue()) {
                ItemStack real = containerShadowed.get(slot);
                handler.setStackInSlot(slot, handler.getRevision(),
                        real == null ? ItemStack.EMPTY : real);
            }
            containerShadowed.remove(slot);
            painted.remove();
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
            autoCollect = !root.has("autoCollect") || root.get("autoCollect").getAsBoolean();
            enabled = !root.has("enabled") || root.get("enabled").getAsBoolean();
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
                Integer mapId = json.has("mapId") ? json.get("mapId").getAsInt() : null;
                String name = json.has("name") ? json.get("name").getAsString() : "";
                target.put(slot, new FakeSpec(item, count, enchants, price, mapId, name));
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
        root.addProperty("autoCollect", autoCollect);
        root.addProperty("enabled", enabled);
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
        if (spec.mapId != null) json.addProperty("mapId", spec.mapId);
        if (!spec.name.isEmpty()) json.addProperty("name", spec.name);
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
