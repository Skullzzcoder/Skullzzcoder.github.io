package dev.skullzz.mirage.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.skullzz.mirage.Mirage;

/**
 * Where the click GUI's panels were left.
 *
 * <p>Its own small file. Dragging a panel is the kind of thing you do once and expect to
 * stay done, and a menu that forgets between sessions is one you stop rearranging.
 *
 * <p>Saved by panel id, so adding or removing a panel later moves nothing that was
 * already placed -- and a panel the file has never heard of simply keeps the position it
 * was built with.
 */
public final class RyneLayout {

    private RyneLayout() {
    }

    private static Path file() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("mirage-layout.json");
    }

    public static void save(RyneGui gui) {
        JsonObject root = new JsonObject();
        for (RyneGui.Panel panel : gui.panels()) {
            JsonObject one = new JsonObject();
            one.addProperty("x", panel.x);
            one.addProperty("y", panel.y);
            one.addProperty("open", panel.open);
            root.add(panel.id, one);
        }

        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), root.toString());
        } catch (IOException failure) {
            Mirage.LOGGER.warn("Mirage could not write the menu layout", failure);
        }
    }

    public static void load(RyneGui gui) {
        if (!Files.exists(file())) return;

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file()));
            if (!parsed.isJsonObject()) return;

            for (RyneGui.Panel panel : gui.panels()) {
                JsonElement saved = parsed.getAsJsonObject().get(panel.id);
                if (saved == null || !saved.isJsonObject()) continue;

                JsonObject one = saved.getAsJsonObject();
                if (one.has("x")) panel.x = one.get("x").getAsInt();
                if (one.has("y")) panel.y = one.get("y").getAsInt();
                if (one.has("open")) {
                    panel.open = one.get("open").getAsBoolean();
                    // Start where it belongs rather than sliding open on every launch.
                    panel.openness = panel.open ? 1f : 0f;
                }
            }
        } catch (IOException | RuntimeException failure) {
            Mirage.LOGGER.warn("Mirage could not read the menu layout", failure);
        }
    }
}
