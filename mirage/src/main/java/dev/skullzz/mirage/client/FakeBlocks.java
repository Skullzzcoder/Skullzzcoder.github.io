package dev.skullzz.mirage.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
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
    /** As many blocks as one build may hold. A whole base fits well inside this. */
    public static final int MAX_BLOCKS = 500000;
    /** Above this, saving says how big it got, since it is enough to be worth knowing. */
    public static final int LARGE_BLOCKS = 120000;

    /**
     * How long a full sweep of a build takes, in ticks.
     *
     * <p>Positions are re-checked a slice at a time so that a large build costs a little
     * every tick rather than everything at once. Fixing the time rather than the slice means
     * a build twice the size is still fully painted just as quickly, and the first paint --
     * the only pass where most positions actually change -- is spread over the same window.
     */
    private static final int SWEEP_TICKS = 60;
    private static final int MIN_SLICE = 400;
    private static final int MAX_SLICE = 6000;

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

    /** @return how many positions the box covers, in long arithmetic so it cannot wrap. */
    public static long regionSize() {
        if (!hasRegion()) return 0L;

        long width = Math.abs(cornerOne.getX() - cornerTwo.getX()) + 1L;
        long height = Math.abs(cornerOne.getY() - cornerTwo.getY()) + 1L;
        long depth = Math.abs(cornerOne.getZ() - cornerTwo.getZ()) + 1L;
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

        capture:
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
                    // Out of all three loops: breaking the inner one only skipped the
                    // rest of that row and carried on filling past the limit.
                    if (packed.size() / 4 >= MAX_BLOCKS) break capture;
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
        int slice = Math.min(order.size(),
                Math.max(MIN_SLICE, Math.min(MAX_SLICE, order.size() / SWEEP_TICKS)));

        for (int i = 0; i < slice; i++) {
            if (cursor >= order.size()) cursor = 0;
            BlockPos pos = order.get(cursor++);

            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;

            BlockState wanted = showing.get(pos);
            if (wanted == null || world.getBlockState(pos) == wanted) continue;

            // Held back only where the server has nothing. Over a real block the paint is
            // just a change of skin: both sides agree something solid is there, so it can
            // be stood on and walked into exactly as it looks. Over air it cannot, so near
            // the player it comes off rather than have them stand on nothing.
            if (tooClose(player, pos) && beneath(world, pos).isAir()) {
                restore(world, pos);
                continue;
            }
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

    /**
     * What the server actually has at a position, painted over or not.
     *
     * <p>Once something is painted the world's own answer is ours, so the remembered state
     * is the honest one wherever there is one.
     */
    private static BlockState beneath(ClientWorld world, BlockPos pos) {
        BlockState was = real.get(pos);
        return was != null ? was : world.getBlockState(pos);
    }

    private static void paint(ClientWorld world, BlockPos pos, BlockState state) {
        try {
            // Only ever reached when what is there is not what we painted, so what is there
            // is the server's word on it -- including a block just placed by hand under a
            // fake, which is how a real floor comes to hold up a painted one.
            real.put(pos.toImmutable(), world.getBlockState(pos));
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

            // One string rather than a few million JsonPrimitives: at this size the
            // object churn of an array costs more than the file does.
            json.addProperty("packed", pack(build.blocks));

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
            if (!json.has("name") || !json.has("palette")) continue;
            if (!json.has("packed") && !json.has("blocks")) continue;

            List<BlockState> palette = new ArrayList<>();
            for (JsonElement entry : json.getAsJsonArray("palette")) {
                palette.add(BlockState.CODEC.parse(JsonOps.INSTANCE, entry)
                        .result().orElse(Blocks.AIR.getDefaultState()));
            }

            int[] blocks = json.has("packed")
                    ? unpack(json.get("packed").getAsString())
                    : readLoose(json.getAsJsonArray("blocks"));

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

    private static String pack(int[] blocks) {
        ByteBuffer buffer = ByteBuffer.allocate(blocks.length * 4);
        buffer.asIntBuffer().put(blocks);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private static int[] unpack(String text) {
        byte[] bytes = Base64.getDecoder().decode(text);
        int[] blocks = new int[bytes.length / 4];
        ByteBuffer.wrap(bytes).asIntBuffer().get(blocks);
        return blocks;
    }

    /** Files written before the packed form, kept readable so nothing has to be recopied. */
    private static int[] readLoose(JsonArray packed) {
        int[] blocks = new int[packed.size() - packed.size() % 4];
        for (int i = 0; i < blocks.length; i++) blocks[i] = packed.get(i).getAsInt();
        return blocks;
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
