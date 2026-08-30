package dev.skullzz.mirage.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import dev.skullzz.mirage.Mirage;

/**
 * Whole builds painted into the client's own copy of the world.
 *
 * <p>The same idea as the item fakes, one level up: the client is told a block is there, the
 * server is told nothing, and the server's world is untouched. A build can be copied out of
 * somewhere it really exists and shown anywhere, so a place can be stood up without a single
 * block being placed.
 *
 * <p>Two things make this safe to walk around in. Fakes are kept out of a small space around
 * the player, so nothing is ever stood on or walked into that the server does not agree is
 * there -- without that the client would think it was standing on air and be dragged back,
 * which looks exactly like flying. And the real state of every position is kept, so taking a
 * build away puts back what was underneath rather than a guess at it.
 */
public final class FakeBlocks {
    /** As many blocks as one build may hold, which is plenty for a gambling front. */
    public static final int MAX_BLOCKS = 30000;
    /** Positions re-checked per tick, so a large build costs a slice rather than a spike. */
    private static final int SWEEP_PER_TICK = 800;

    /** How far around the player fakes are held back, so nothing is ever collided with. */
    private static final int CLEAR_SIDE = 1;
    private static final int CLEAR_BELOW = 1;
    private static final int CLEAR_ABOVE = 2;

    private static final Map<String, Build> builds = new LinkedHashMap<>();
    /** Which builds are up, and where the corner of each one sits. */
    private static final Map<String, BlockPos> placed = new LinkedHashMap<>();

    /** What should be showing, and what was really there before it was. */
    private static final Map<BlockPos, BlockState> showing = new LinkedHashMap<>();
    private static final Map<BlockPos, BlockState> real = new HashMap<>();
    private static final List<BlockPos> order = new ArrayList<>();
    private static int cursor;

    private static final Gson GSON = new GsonBuilder().create();

    private static BlockPos cornerOne;
    private static BlockPos cornerTwo;

    /** One saved build: the states it uses, and where each block sits within it. */
    public static final class Build {
        final String name;
        final List<BlockState> palette;
        /** Four numbers per block: x, y, z from the corner, then which state. */
        final int[] blocks;
        final int width;
        final int height;
        final int depth;

        Build(String name, List<BlockState> palette, int[] blocks,
              int width, int height, int depth) {
            this.name = name;
            this.palette = palette;
            this.blocks = blocks;
            this.width = width;
            this.height = height;
            this.depth = depth;
        }

        public int count() {
            return this.blocks.length / 4;
        }

        public String size() {
            return this.width + "x" + this.height + "x" + this.depth;
        }
    }

    private FakeBlocks() {
    }

    // ----------------------------------------------------------------- corners

    /** @return which corner was set, 1 or 2. */
    public static int corner(BlockPos pos) {
        if (cornerOne == null || cornerTwo != null) {
            cornerOne = pos.toImmutable();
            cornerTwo = null;
            return 1;
        }
        cornerTwo = pos.toImmutable();
        return 2;
    }

    public static boolean hasRegion() {
        return cornerOne != null && cornerTwo != null;
    }

    public static int regionSize() {
        if (!hasRegion()) return 0;

        int width = Math.abs(cornerOne.getX() - cornerTwo.getX()) + 1;
        int height = Math.abs(cornerOne.getY() - cornerTwo.getY()) + 1;
        int depth = Math.abs(cornerOne.getZ() - cornerTwo.getZ()) + 1;
        return width * height * depth;
    }

    // ------------------------------------------------------------------ saving

    /**
     * Copies whatever really stands between the two corners.
     *
     * <p>Air is skipped, so only what is actually built is carried, and the corner it is
     * measured from is the lowest of the three axes rather than whichever was clicked first.
     *
     * @return the build, or null if the world could not supply it.
     */
    public static Build save(ClientWorld world, String name) {
        if (world == null || !hasRegion()) return null;

        int minX = Math.min(cornerOne.getX(), cornerTwo.getX());
        int minY = Math.min(cornerOne.getY(), cornerTwo.getY());
        int minZ = Math.min(cornerOne.getZ(), cornerTwo.getZ());
        int maxX = Math.max(cornerOne.getX(), cornerTwo.getX());
        int maxY = Math.max(cornerOne.getY(), cornerTwo.getY());
        int maxZ = Math.max(cornerOne.getZ(), cornerTwo.getZ());

        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        List<Integer> packed = new ArrayList<>();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) continue;

                    Integer slot = index.get(state);
                    if (slot == null) {
                        slot = palette.size();
                        index.put(state, slot);
                        palette.add(state);
                    }

                    packed.add(x - minX);
                    packed.add(y - minY);
                    packed.add(z - minZ);
                    packed.add(slot);
                    if (packed.size() / 4 >= MAX_BLOCKS) break;
                }
            }
        }
        if (packed.isEmpty()) return null;

        int[] blocks = new int[packed.size()];
        for (int i = 0; i < blocks.length; i++) blocks[i] = packed.get(i);

        Build build = new Build(name, palette, blocks,
                maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        builds.put(name, build);
        return build;
    }

    public static Map<String, Build> builds() {
        return builds;
    }

    public static Map<String, BlockPos> placed() {
        return placed;
    }

    public static boolean forget(String name) {
        take(name);
        return builds.remove(name) != null;
    }

    // ----------------------------------------------------------------- showing

    /**
     * Stands a build up with its corner at a position.
     *
     * @return how many blocks it will show, or -1 if there is no such build.
     */
    public static int put(String name, BlockPos corner) {
        Build build = builds.get(name);
        if (build == null) return -1;

        take(name);
        placed.put(name, corner.toImmutable());

        for (int i = 0; i < build.blocks.length; i += 4) {
            BlockPos pos = new BlockPos(
                    corner.getX() + build.blocks[i],
                    corner.getY() + build.blocks[i + 1],
                    corner.getZ() + build.blocks[i + 2]);
            showing.put(pos, build.palette.get(build.blocks[i + 3]));
        }

        reindex();
        return build.count();
    }

    /** Takes a build back down, putting the real blocks back as it goes. */
    public static boolean take(String name) {
        Build build = builds.get(name);
        BlockPos corner = placed.remove(name);
        if (build == null || corner == null) return false;

        ClientWorld world = MinecraftClient.getInstance().world;
        for (int i = 0; i < build.blocks.length; i += 4) {
            BlockPos pos = new BlockPos(
                    corner.getX() + build.blocks[i],
                    corner.getY() + build.blocks[i + 1],
                    corner.getZ() + build.blocks[i + 2]);

            showing.remove(pos);
            restore(world, pos);
        }

        reindex();
        return true;
    }

    public static void takeAll() {
        for (String name : new ArrayList<>(placed.keySet())) take(name);
    }

    public static int showingCount() {
        return showing.size();
    }

    private static void reindex() {
        order.clear();
        order.addAll(showing.keySet());
        cursor = 0;
    }

    // -------------------------------------------------------------------- tick

    /**
     * Keeps what should be showing showing, a slice at a time.
     *
     * <p>The client's own copy is only overwritten when the server sends word about that
     * position, which is rare, so a rolling sweep costs almost nothing and puts anything the
     * server has corrected back within a second.
     */
    public static void tick(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null || order.isEmpty()) return;

        if (!SelfFakes.enabled()) {
            hide(world);
            return;
        }

        ClientPlayerEntity player = client.player;
        int slice = Math.min(SWEEP_PER_TICK, order.size());

        for (int i = 0; i < slice; i++) {
            if (cursor >= order.size()) cursor = 0;
            BlockPos pos = order.get(cursor++);

            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;

            // Never where the player is standing or about to be: a block the server does not
            // have is one the server will not let them stand on.
            if (tooClose(player, pos)) {
                restore(world, pos);
                continue;
            }

            BlockState wanted = showing.get(pos);
            if (wanted == null || world.getBlockState(pos) == wanted) continue;
            paint(world, pos, wanted);
        }
    }

    /** Whether a position is inside the space kept clear around the player. */
    private static boolean tooClose(ClientPlayerEntity player, BlockPos pos) {
        if (player == null) return false;

        int feet = (int) Math.floor(player.getY());
        if (pos.getY() < feet - CLEAR_BELOW || pos.getY() > feet + CLEAR_ABOVE) return false;

        return Math.abs(pos.getX() - (int) Math.floor(player.getX())) <= CLEAR_SIDE
                && Math.abs(pos.getZ() - (int) Math.floor(player.getZ())) <= CLEAR_SIDE;
    }

    private static void paint(ClientWorld world, BlockPos pos, BlockState state) {
        try {
            // Remembered once and only once: painting over our own paint would lose it.
            if (!real.containsKey(pos)) real.put(pos.toImmutable(), world.getBlockState(pos));
            world.setBlockState(pos, state);
        } catch (RuntimeException e) {
            showing.remove(pos);
        }
    }

    /** Puts back whatever was really at a position, if we ever covered it. */
    private static void restore(ClientWorld world, BlockPos pos) {
        BlockState was = real.remove(pos);
        if (was == null || world == null) return;

        try {
            if (world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                world.setBlockState(pos, was);
            }
        } catch (RuntimeException ignored) {
            // The chunk went while we were holding it; there is nothing to put back into.
        }
    }

    /** Takes every build off the screen without forgetting where any of them stand. */
    public static void hide(ClientWorld world) {
        for (Iterator<Map.Entry<BlockPos, BlockState>> it = real.entrySet().iterator();
                it.hasNext(); ) {
            Map.Entry<BlockPos, BlockState> entry = it.next();
            BlockPos pos = entry.getKey();

            try {
                if (world != null && world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                    world.setBlockState(pos, entry.getValue());
                }
            } catch (RuntimeException ignored) {
                // Nothing to put it back into.
            }
            it.remove();
        }
    }

    /** Leaving a world takes the client's copy with it, so the shadows mean nothing. */
    public static void reset() {
        real.clear();
        cursor = 0;
    }

    // -------------------------------------------------------------- persistence

    /**
     * Its own file, and written only when a build actually changes.
     *
     * <p>A build of thirty thousand blocks is far too much to rewrite every time a preset is
     * switched, which is how often the main config is saved.
     */
    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("mirage-builds.json");
    }

    public static void persist() {
        JsonObject root = new JsonObject();
        writeInto(root);

        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(root));
        } catch (IOException e) {
            Mirage.LOGGER.error("Mirage could not write {}", file(), e);
        }
    }

    public static void load() {
        Path path = file();
        if (!Files.exists(path)) return;

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path));
            if (parsed.isJsonObject()) readFrom(parsed.getAsJsonObject());
            Mirage.LOGGER.info("Mirage loaded {} builds", builds.size());
        } catch (IOException | RuntimeException e) {
            Mirage.LOGGER.error("Mirage could not read {}", path, e);
        }
    }

    public static void writeInto(JsonObject root) {
        JsonArray saved = new JsonArray();

        for (Build build : builds.values()) {
            JsonObject json = new JsonObject();
            json.addProperty("name", build.name);
            json.addProperty("width", build.width);
            json.addProperty("height", build.height);
            json.addProperty("depth", build.depth);

            JsonArray palette = new JsonArray();
            for (BlockState state : build.palette) {
                Optional<JsonElement> encoded =
                        BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state).result();
                palette.add(encoded.orElseGet(() -> new JsonArray()));
            }
            json.add("palette", palette);

            JsonArray blocks = new JsonArray();
            for (int value : build.blocks) blocks.add(value);
            json.add("blocks", blocks);

            BlockPos corner = placed.get(build.name);
            if (corner != null) {
                json.addProperty("at", corner.getX() + "," + corner.getY() + "," + corner.getZ());
            }
            saved.add(json);
        }
        root.add("builds", saved);
    }

    private static void readFrom(JsonObject root) {
        builds.clear();
        placed.clear();
        showing.clear();
        real.clear();
        order.clear();

        if (!root.has("builds")) return;

        for (JsonElement element : root.getAsJsonArray("builds")) {
            JsonObject json = element.getAsJsonObject();
            if (!json.has("name") || !json.has("palette") || !json.has("blocks")) continue;

            List<BlockState> palette = new ArrayList<>();
            for (JsonElement entry : json.getAsJsonArray("palette")) {
                palette.add(BlockState.CODEC.parse(JsonOps.INSTANCE, entry)
                        .result().orElse(Blocks.AIR.getDefaultState()));
            }

            JsonArray packed = json.getAsJsonArray("blocks");
            int[] blocks = new int[packed.size() - packed.size() % 4];
            for (int i = 0; i < blocks.length; i++) blocks[i] = packed.get(i).getAsInt();

            Build build = new Build(json.get("name").getAsString(), palette, blocks,
                    json.has("width") ? json.get("width").getAsInt() : 0,
                    json.has("height") ? json.get("height").getAsInt() : 0,
                    json.has("depth") ? json.get("depth").getAsInt() : 0);
            builds.put(build.name, build);

            // Where it stood is remembered, but it is stood up again by the sweep rather
            // than here, since the world is not loaded yet at this point.
            if (json.has("at")) {
                BlockPos corner = readPos(json.get("at").getAsString());
                if (corner != null) placed.put(build.name, corner);
            }
        }

        // Everything that was up when the file was written goes back up.
        for (Map.Entry<String, BlockPos> entry : new ArrayList<>(placed.entrySet())) {
            put(entry.getKey(), entry.getValue());
        }
    }

    private static BlockPos readPos(String text) {
        String[] parts = text.split(",");
        if (parts.length != 3) return null;

        try {
            return new BlockPos(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
