package dev.skullzz.mirage.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /** The most that may be taken out in one go, so a slip does not gut a build. */
    public static final int MAX_CUT_RADIUS = 5;

    private static final Map<String, Build> builds = new LinkedHashMap<>();
    /** Which builds are up, and where the corner of each one sits. */
    private static final Map<String, BlockPos> placed = new LinkedHashMap<>();

    /** What should be showing, and what was really there before it was. */
    /**
     * Positions held back only from directly underfoot, rather than from the whole space
     * around the player.
     *
     * <p>A build wants the wider rule so you can walk through your own walls. Something a
     * machine has just put on the ground wants the opposite: it is meant to be looked at
     * from a step away, and a hole where it should be defeats the point. Only what could
     * hold the player up is unsafe -- a full block at their own level cannot be stepped onto
     * without jumping, and one that merely blocks the way never puts them anywhere the
     * server disagrees with.
     */
    private static final Set<BlockPos> underfootOnly = new HashSet<>();

    private static final Map<BlockPos, BlockState> showing = new LinkedHashMap<>();
    private static final Map<BlockPos, BlockState> real = new HashMap<>();
    private static final List<BlockPos> order = new ArrayList<>();
    private static int cursor;

    /**
     * The one position being broken by hand, if any.
     *
     * <p>The sweep comes round to a position about once a second, which is fine for the
     * server correcting something out at the edge of a build and far too slow for a block
     * being hit: vanilla takes its own copy out from under us the moment it decides the
     * block is gone, and a second of nothing is exactly the block disappearing. So the one
     * being broken is put back every tick instead, before a frame is ever drawn without it.
     */
    private static BlockPos pinned;

    /**
     * Positions no build may ever cover.
     *
     * <p>The machines the games are played on. Everything this mod does with a dispenser it
     * does by reading the client's own copy of the world -- whether one just went off, which
     * way it faces, where to put what comes out, which machine an open screen belongs to.
     * That copy is the same one builds are painted into, so a build block landing on a
     * dispenser does not hide the machine: it deletes it, as far as the rest of the mod can
     * tell. Every rig stops at once and nothing says why.
     */
    private static final Set<BlockPos> keepClear = new HashSet<>();

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
        /**
         * Positions cut out of it, relative to its corner.
         *
         * <p>Held against the build rather than against the world, so a hole belongs to the
         * design: something real goes in it, and it stays a hole wherever the build is
         * stood up.
         */
        final Set<BlockPos> cuts = new HashSet<>();

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
            if (build.cuts.contains(new BlockPos(build.blocks[i], build.blocks[i + 1],
                    build.blocks[i + 2]))) {
                continue;
            }

            BlockPos pos = new BlockPos(
                    corner.getX() + build.blocks[i],
                    corner.getY() + build.blocks[i + 1],
                    corner.getZ() + build.blocks[i + 2]);
            showing.put(pos, build.palette.get(build.blocks[i + 3]));
        }

        reindex();
        return showing.size();
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

    // ----------------------------------------------------------------- cutting

    /** Which standing build covers a position, or null if none does. */
    private static String owner(BlockPos pos) {
        for (Map.Entry<String, BlockPos> entry : placed.entrySet()) {
            Build build = builds.get(entry.getKey());
            if (build == null) continue;

            BlockPos corner = entry.getValue();
            int dx = pos.getX() - corner.getX();
            int dy = pos.getY() - corner.getY();
            int dz = pos.getZ() - corner.getZ();

            if (dx < 0 || dy < 0 || dz < 0) continue;
            if (dx >= build.width || dy >= build.height || dz >= build.depth) continue;
            return build.name;
        }
        return null;
    }

    /**
     * Takes a hole out of whatever build covers a spot, and puts the real world back there.
     *
     * <p>What it is for is making room: a dispenser, a chest, a sign, anything that has to
     * actually be there rather than only look it. The hole is remembered against the build,
     * so it survives standing the build up again and is still there next time.
     *
     * @return how many blocks were taken out.
     */
    public static int cut(BlockPos centre, int radius) {
        ClientWorld world = MinecraftClient.getInstance().world;
        int taken = 0;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = centre.add(x, y, z);

                    String name = owner(pos);
                    if (name == null) continue;

                    BlockPos corner = placed.get(name);
                    BlockPos offset = new BlockPos(pos.getX() - corner.getX(),
                            pos.getY() - corner.getY(), pos.getZ() - corner.getZ());
                    if (!builds.get(name).cuts.add(offset)) continue;

                    if (showing.remove(pos) != null) {
                        restore(world, pos);
                        taken++;
                    }
                }
            }
        }

        if (taken > 0) reindex();
        return taken;
    }

    /** Fills a hole back in. */
    public static int uncut(BlockPos centre, int radius) {
        Map<String, Set<BlockPos>> back = new LinkedHashMap<>();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = centre.add(x, y, z);

                    String name = owner(pos);
                    if (name == null) continue;

                    BlockPos corner = placed.get(name);
                    BlockPos offset = new BlockPos(pos.getX() - corner.getX(),
                            pos.getY() - corner.getY(), pos.getZ() - corner.getZ());

                    if (!builds.get(name).cuts.remove(offset)) continue;
                    back.computeIfAbsent(name, key -> new HashSet<>()).add(offset);
                }
            }
        }
        return refill(back);
    }

    /** Fills every hole in a build back in. */
    public static int uncutAll(String name) {
        Build build = builds.get(name);
        if (build == null || build.cuts.isEmpty()) return 0;

        Map<String, Set<BlockPos>> back = new LinkedHashMap<>();
        back.put(name, new HashSet<>(build.cuts));
        build.cuts.clear();
        return refill(back);
    }

    /**
     * Puts uncut positions back on the board.
     *
     * <p>One pass over the build rather than standing the whole thing up again, which would
     * take everything down and repaint it over several seconds for the sake of a few blocks.
     */
    private static int refill(Map<String, Set<BlockPos>> back) {
        int filled = 0;

        for (Map.Entry<String, Set<BlockPos>> entry : back.entrySet()) {
            Build build = builds.get(entry.getKey());
            BlockPos corner = placed.get(entry.getKey());
            if (build == null || corner == null) continue;

            for (int i = 0; i < build.blocks.length; i += 4) {
                BlockPos offset = new BlockPos(build.blocks[i], build.blocks[i + 1],
                        build.blocks[i + 2]);
                if (!entry.getValue().contains(offset)) continue;

                showing.put(new BlockPos(corner.getX() + offset.getX(),
                                corner.getY() + offset.getY(), corner.getZ() + offset.getZ()),
                        build.palette.get(build.blocks[i + 3]));
                filled++;
            }
        }

        if (filled > 0) reindex();
        return filled;
    }

    /** @return how many holes are cut in a standing build, or -1 if it is not standing. */
    public static int cutCount(String name) {
        Build build = builds.get(name);
        return build == null ? -1 : build.cuts.size();
    }

    /** The build covering a spot, for telling someone what they just cut into. */
    public static String buildAt(BlockPos pos) {
        return owner(pos);
    }

    private static void reindex() {
        order.clear();
        order.addAll(showing.keySet());
        // Kept where it was rather than sent back to the start. Every box a machine puts
        // down rebuilds this list, and starting over each time meant a large build never
        // reached its far side, so anything the server corrected out there stayed corrected.
        if (cursor > order.size()) cursor = 0;
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

        if (pinned != null) refresh(world, player, pinned);

        for (int i = 0; i < slice; i++) {
            if (cursor >= order.size()) cursor = 0;
            refresh(world, player, order.get(cursor++));
        }
    }

    /**
     * The positions builds must keep off, replacing whatever was set before.
     *
     * <p>Anything already painted there comes off at once rather than waiting for the sweep,
     * because until it does the machine underneath does not exist.
     */
    public static void keepClear(Set<BlockPos> positions) {
        keepClear.clear();
        for (BlockPos pos : positions) keepClear.add(pos.toImmutable());

        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) return;

        for (BlockPos pos : keepClear) {
            // The entry in showing stays: this is a position held back, not one taken out
            // of the build, so unwatching the machine brings the wall back.
            if (showing.containsKey(pos)) restore(world, pos);
        }
    }

    /** Whether a position is one the paint is kept off. */
    public static boolean isKeptClear(BlockPos pos) {
        return keepClear.contains(pos);
    }

    /** What the server really has at a position, painted over or not. */
    public static BlockState realAt(ClientWorld world, BlockPos pos) {
        return beneath(world, pos);
    }

    /** Puts one position back to what it should be showing, if it has drifted. */
    private static void refresh(ClientWorld world, ClientPlayerEntity player, BlockPos pos) {
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return;

        // Before the has-it-drifted test, not after: a machine already covered is showing
        // exactly what was asked of it, so that test would call it settled and leave it.
        if (keepClear.contains(pos)) {
            restore(world, pos);
            return;
        }

        BlockState wanted = showing.get(pos);
        if (wanted == null || world.getBlockState(pos) == wanted) return;

        // Held back only where the server has nothing. Over a real block the paint is
        // just a change of skin: both sides agree something solid is there, so it can
        // be stood on and walked into exactly as it looks. Over air it cannot, so near
        // the player it comes off rather than have them stand on nothing.
        if (tooClose(player, pos) && beneath(world, pos).isAir()) {
            restore(world, pos);
            return;
        }
        paint(world, pos, wanted);
    }

    /**
     * Marks the position being broken, or clears it with null.
     *
     * <p>Two things follow from a position being pinned: it is repainted every tick rather
     * than on the sweep's turn, and what vanilla leaves there is not mistaken for the
     * server's word on it.
     */
    public static void pin(BlockPos pos) {
        pinned = pos == null ? null : pos.toImmutable();
    }

    /** Whether a position is inside the space kept clear around the player. */
    private static boolean tooClose(ClientPlayerEntity player, BlockPos pos) {
        if (player == null) return false;
        if (underfootOnly.contains(pos)) return underfoot(player, pos);

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

    /** Whether a position is the one holding the player up. */
    private static boolean underfoot(ClientPlayerEntity player, BlockPos pos) {
        if (pos.getY() != (int) Math.floor(player.getY()) - 1) return false;

        // Half the player's width plus half a block: the columns their feet are over.
        double dx = player.getX() - (pos.getX() + 0.5);
        double dz = player.getZ() - (pos.getZ() + 0.5);
        return Math.abs(dx) < 0.8 && Math.abs(dz) < 0.8;
    }

    /**
     * Puts a single block on the board, the way a machine placing one would.
     *
     * <p>Kept apart from the builds: it is not part of any of them, it is held back only
     * from underfoot, and it goes away again when the next one takes its place.
     */
    public static boolean place(BlockPos pos, BlockState state) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null || !beneath(world, pos).isAir()) return false;

        BlockPos key = pos.toImmutable();
        showing.put(key, state);
        underfootOnly.add(key);
        reindex();
        return true;
    }

    /** What is being faked at a position, or null if nothing is. */
    public static BlockState fakeAt(BlockPos pos) {
        return showing.get(pos);
    }

    /**
     * What is being faked at a position and actually on the screen there.
     *
     * <p>The difference matters to a hand. A fake held back from underfoot, or one the
     * master switch has taken down, is not on the screen: the block being hit there is the
     * real one, and it is vanilla's to break in the ordinary way. Only paint that is
     * showing is ours to intercept.
     */
    public static BlockState paintedAt(BlockPos pos) {
        BlockState wanted = showing.get(pos);
        if (wanted == null) return null;

        ClientWorld world = MinecraftClient.getInstance().world;
        return world != null && world.getBlockState(pos) == wanted ? wanted : null;
    }

    /**
     * Takes a block away because it was broken.
     *
     * <p>One a machine put down simply goes. One belonging to a build is cut out of it, so
     * the hole stays where it was put rather than coming back the next time the build is
     * stood up -- which is what breaking a block means.
     *
     * @return what was there, or null if nothing of ours was.
     */
    public static BlockState broke(BlockPos pos) {
        BlockState was = showing.get(pos);
        if (was == null) return null;

        if (underfootOnly.contains(pos)) {
            unplace(pos);
        } else {
            cut(pos, 0);
        }
        return was;
    }

    /** Takes one placed block away, putting the real world back. */
    public static boolean unplace(BlockPos pos) {
        if (!underfootOnly.remove(pos)) return false;

        showing.remove(pos);
        restore(MinecraftClient.getInstance().world, pos);
        reindex();
        return true;
    }

    private static void paint(ClientWorld world, BlockPos pos, BlockState state) {
        try {
            // Normally only reached when what is there is not what we painted, so what is
            // there is the server's word on it -- including a block just placed by hand
            // under a fake, which is how a real floor comes to hold up a painted one.
            //
            // While a position is being broken it is not. Vanilla mines its own copy of the
            // block and leaves air, and taking that for the server's word would put air
            // back over a real wall the moment the build came down. So the first answer is
            // kept and everything vanilla does to the position afterwards is ignored.
            BlockPos key = pos.toImmutable();
            BlockState there = world.getBlockState(pos);
            if (key.equals(pinned)) real.putIfAbsent(key, there);
            else real.put(key, there);
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
        pinned = null;
        keepClear.clear();
        cursor = 0;
    }

    /** Takes away every block a machine has placed, leaving the builds standing. */
    public static int unplaceAll() {
        int gone = underfootOnly.size();
        for (BlockPos pos : new ArrayList<>(underfootOnly)) unplace(pos);
        return gone;
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

            if (!build.cuts.isEmpty()) {
                JsonArray cuts = new JsonArray();
                for (BlockPos cut : build.cuts) {
                    cuts.add(cut.getX());
                    cuts.add(cut.getY());
                    cuts.add(cut.getZ());
                }
                json.add("cuts", cuts);
            }

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
            if (json.has("cuts")) {
                JsonArray cuts = json.getAsJsonArray("cuts");
                for (int i = 0; i + 2 < cuts.size(); i += 3) {
                    build.cuts.add(new BlockPos(cuts.get(i).getAsInt(),
                            cuts.get(i + 1).getAsInt(), cuts.get(i + 2).getAsInt()));
                }
            }
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
