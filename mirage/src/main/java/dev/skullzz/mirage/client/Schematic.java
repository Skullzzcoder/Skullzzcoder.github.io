package dev.skullzz.mirage.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

import dev.skullzz.mirage.Mirage;

/**
 * Litematica files, turned into builds this mod already knows how to stand up.
 *
 * <p>The whole point of the shape here: a schematic becomes an ordinary build, so putting
 * it up, taking it down, nudging it, cutting holes in it, tying it to the world it was
 * placed in and remembering it across a restart are not written again. They already work.
 *
 * <p>Reading the file is {@link Litematic}'s job and knows nothing about Minecraft, which
 * is what lets it be run against real files here. This half is the part that cannot be:
 * turning "minecraft:oak_stairs, facing=north" into a block state. It goes through the
 * game's own block-state codec, the same one builds are already saved and loaded with,
 * rather than any method looked up by name.
 */
public final class Schematic {

    /** What a Litematica file is called. Others are read by other mods, not this one. */
    public static final List<String> KINDS = List.of(".litematic");

    private static String lastReason = "";

    private Schematic() {
    }

    public static String lastReason() {
        return lastReason;
    }

    /** Where schematics are looked for first. Made at startup so it is there to drop into. */
    public static Path folder() {
        return Disk.folder("mirage-schematics");
    }

    /** Every folder a schematic is looked for in, written out. */
    public static String describePlaces() {
        return Disk.describe(folder());
    }

    /** What schematics are sitting where one can be loaded from. */
    public static List<String> files() {
        return Disk.list(folder(), KINDS);
    }

    private static List<String> cached = List.of();
    private static long cachedAt = 0L;

    /** How long a folder listing is trusted, in milliseconds. */
    private static final long CACHE_MS = 3000L;

    /**
     * The same listing, but safe to ask for constantly.
     *
     * <p>The dashboard rebuilds twenty times a second, and walking five folders on disk
     * that often is a stutter you can feel. Three seconds stale is not stale for a folder
     * a person drops files into by hand.
     */
    public static List<String> filesCached() {
        long now = System.currentTimeMillis();
        if (now - cachedAt > CACHE_MS) {
            cached = files();
            cachedAt = now;
        }
        return cached;
    }

    /**
     * Reads a file and keeps it as a build under the given name.
     *
     * <p>Nothing is stood up here: loading and placing are separate on purpose, the same
     * way Litematica separates loading a schematic from placing it.
     *
     * @return how many blocks it holds, or -1 with a reason in {@link #lastReason()}
     */
    public static int load(String fileName, String name) {
        Path path = Disk.find(folder(), fileName);
        if (path == null) {
            lastReason = "no schematic called " + fileName + ". Looked in: " + describePlaces()
                    + ". Drop it in one of those, or give the whole path in quotes.";
            return -1;
        }

        try {
            Map<String, Object> root = Nbt.read(path);
            Litematic read = Litematic.parse(root, FakeBlocks.MAX_BLOCKS);

            List<BlockState> palette = new ArrayList<>();
            int unknown = 0;
            for (Litematic.Entry entry : read.palette) {
                BlockState state = resolve(entry);
                if (state == null) {
                    unknown++;
                    // A block this version does not have becomes stone rather than a hole:
                    // a gap in a wall is harder to see than a wrong block in one, and the
                    // count is reported either way.
                    state = Blocks.STONE.getDefaultState();
                }
                palette.add(state);
            }

            FakeBlocks.adopt(name, palette, read.blocks, read.width, read.height, read.depth);
            FakeBlocks.persist();

            lastReason = "loaded " + read.count() + " blocks, " + read.size()
                    + (read.regions > 1 ? ", " + read.regions + " regions" : "")
                    + (unknown > 0 ? ", " + unknown + " block kinds this version does not "
                            + "have (shown as stone)" : "");
            return read.count();
        } catch (IOException | RuntimeException failure) {
            // The reader refuses a file it cannot trust rather than half-reading it, and
            // that refusal names what was wrong with it. Passing it through is the point.
            String said = failure.getMessage();
            lastReason = said == null || said.isEmpty()
                    ? fileName + " could not be read: " + failure
                    : said;
            Mirage.LOGGER.warn("Mirage could not read the schematic {}", path, failure);
            return -1;
        }
    }

    /**
     * One palette entry as a block state, or null if this version has no such block.
     *
     * <p>Built as the JSON the game's own codec reads -- {@code {"Name": ..., "Properties":
     * {...}}} -- because that codec is already what saves and loads every build here. A
     * property the block does not have makes the whole parse fail, so it is tried again
     * with none: a stair facing a direction this version dropped is still a stair.
     */
    static BlockState resolve(Litematic.Entry entry) {
        BlockState exact = parse(entry.name, entry.properties);
        if (exact != null) return exact;
        return parse(entry.name, Map.of());
    }

    private static BlockState parse(String name, Map<String, String> properties) {
        JsonObject json = new JsonObject();
        json.addProperty("Name", name);

        if (!properties.isEmpty()) {
            JsonObject written = new JsonObject();
            for (Map.Entry<String, String> property : properties.entrySet()) {
                written.addProperty(property.getKey(), property.getValue());
            }
            json.add("Properties", written);
        }

        try {
            return BlockState.CODEC.parse(JsonOps.INSTANCE, json).result().orElse(null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
