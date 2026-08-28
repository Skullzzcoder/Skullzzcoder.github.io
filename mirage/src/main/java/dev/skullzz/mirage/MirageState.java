package dev.skullzz.mirage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

/**
 * Every lie the mod is currently telling, persisted to {@code mirage.json} in the world
 * folder so pranks survive a restart.
 */
public class MirageState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Player UUID to (raw inventory slot index to the stack they see there). */
    public final Map<UUID, Map<Integer, ItemStack>> ghosts = new HashMap<>();

    /** Rigged dispensers, by dimension and position. */
    public final Map<WorldPos, DispenserRig> rigs = new HashMap<>();

    private static Path fileFor(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve("mirage.json");
    }

    public void load(MinecraftServer server) {
        this.ghosts.clear();
        this.rigs.clear();

        Path file = fileFor(server);
        if (!Files.exists(file)) return;

        RegistryWrapper.WrapperLookup lookup = server.getRegistryManager();
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            if (!parsed.isJsonObject()) return;
            JsonObject root = parsed.getAsJsonObject();

            if (root.has("ghosts")) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("ghosts").entrySet()) {
                    UUID player;
                    try {
                        player = UUID.fromString(entry.getKey());
                    } catch (IllegalArgumentException e) {
                        continue;
                    }

                    Map<Integer, ItemStack> slots = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> slot : entry.getValue().getAsJsonObject().entrySet()) {
                        ItemStack stack = StackJson.read(lookup, slot.getValue());
                        if (!stack.isEmpty()) slots.put(Integer.parseInt(slot.getKey()), stack);
                    }
                    if (!slots.isEmpty()) this.ghosts.put(player, slots);
                }
            }

            if (root.has("rigs")) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("rigs").entrySet()) {
                    WorldPos pos = WorldPos.parse(entry.getKey());
                    if (pos == null) continue;

                    JsonObject json = entry.getValue().getAsJsonObject();
                    DispenserRig rig = new DispenserRig();

                    if (json.has("display")) {
                        for (Map.Entry<String, JsonElement> slot : json.getAsJsonObject("display").entrySet()) {
                            ItemStack stack = StackJson.read(lookup, slot.getValue());
                            if (!stack.isEmpty()) rig.display.put(Integer.parseInt(slot.getKey()), stack);
                        }
                    }
                    if (json.has("result")) {
                        rig.result = StackJson.read(lookup, json.get("result"));
                    }
                    if (json.has("only")) {
                        try {
                            rig.onlyPlayer = UUID.fromString(json.get("only").getAsString());
                        } catch (IllegalArgumentException ignored) {
                            // fall back to pranking everybody
                        }
                    }

                    if (!rig.isEmpty()) this.rigs.put(pos, rig);
                }
            }

            Mirage.LOGGER.info("Mirage loaded {} ghosted inventories and {} rigged dispensers",
                    this.ghosts.size(), this.rigs.size());
        } catch (IOException | RuntimeException e) {
            Mirage.LOGGER.error("Mirage could not read {} -- starting empty", file, e);
            this.ghosts.clear();
            this.rigs.clear();
        }
    }

    public void save(MinecraftServer server) {
        RegistryWrapper.WrapperLookup lookup = server.getRegistryManager();
        JsonObject root = new JsonObject();

        JsonObject ghostsJson = new JsonObject();
        for (Map.Entry<UUID, Map<Integer, ItemStack>> entry : this.ghosts.entrySet()) {
            if (entry.getValue().isEmpty()) continue;

            JsonObject slots = new JsonObject();
            for (Map.Entry<Integer, ItemStack> slot : entry.getValue().entrySet()) {
                JsonElement stack = StackJson.write(lookup, slot.getValue());
                if (stack != null) slots.add(String.valueOf(slot.getKey()), stack);
            }
            if (slots.size() > 0) ghostsJson.add(entry.getKey().toString(), slots);
        }
        root.add("ghosts", ghostsJson);

        JsonObject rigsJson = new JsonObject();
        for (Map.Entry<WorldPos, DispenserRig> entry : this.rigs.entrySet()) {
            DispenserRig rig = entry.getValue();
            if (rig.isEmpty()) continue;

            JsonObject json = new JsonObject();
            JsonObject display = new JsonObject();
            for (Map.Entry<Integer, ItemStack> slot : rig.display.entrySet()) {
                JsonElement stack = StackJson.write(lookup, slot.getValue());
                if (stack != null) display.add(String.valueOf(slot.getKey()), stack);
            }
            json.add("display", display);

            if (!rig.result.isEmpty()) {
                JsonElement result = StackJson.write(lookup, rig.result);
                if (result != null) json.add("result", result);
            }
            if (rig.onlyPlayer != null) {
                json.addProperty("only", rig.onlyPlayer.toString());
            }

            rigsJson.add(entry.getKey().toKeyString(), json);
        }
        root.add("rigs", rigsJson);

        Path file = fileFor(server);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException e) {
            Mirage.LOGGER.error("Mirage could not write {}", file, e);
        }
    }
}
