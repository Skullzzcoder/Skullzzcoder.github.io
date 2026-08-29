package dev.skullzz.mirage.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.block.BlockState;
import net.minecraft.block.DispenserBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Makes watched dispensers appear to fire something of your choosing, and optionally shoot a
 * fake arrow at a fixed point.
 *
 * <p>What comes out is decided by the active {@link RigProfile}, so different games can be set
 * up separately and switched between. Firing is spotted from the vanilla TRIGGERED blockstate,
 * which the client already receives, and everything spawned is a client-only entity.
 */
public final class ClientDispensers {
    private static final double ARROW_GRAVITY = 0.05;
    private static final int ARROW_LINGER_TICKS = 100;
    /** Vanilla schedules the real dispense four ticks after TRIGGERED flips. */
    private static final int DISPENSE_DELAY_TICKS = 4;
    private static final int LIFETIME_TICKS = 60;

    private static final Set<BlockPos> watched = new LinkedHashSet<>();
    private static final Map<BlockPos, Boolean> lastTriggered = new HashMap<>();
    private static final List<PendingFire> pending = new ArrayList<>();
    private static final List<ExpiringItem> spawned = new ArrayList<>();
    private static final List<FlyingArrow> arrows = new ArrayList<>();

    private static final Map<String, RigProfile> profiles = new LinkedHashMap<>();
    private static String activeName = "";

    private static long tick;
    /** Client-only ids, from the top of the range so they miss the server's. */
    private static int nextEntityId = Integer.MAX_VALUE - 4096;

    private record PendingFire(BlockPos pos, long fireAt) {
    }

    private record ExpiringItem(ItemEntity entity, long removeAt) {
    }

    /**
     * An arrow on a real ballistic path that happens to land where it was told to.
     *
     * <p>Rather than interpolating a shape, the launch velocity is solved for: given a flight
     * time, there is exactly one velocity that reaches the target under constant gravity. The
     * arrow then simply falls, so it rises, slows, tips over and comes down like any arrow.
     */
    private static final class FlyingArrow {
        final ArrowEntity entity;
        final Vec3d from;
        final Vec3d to;
        final double velocityX;
        final double velocityY;
        final double velocityZ;
        final int flightTicks;
        final long startTick;
        final long removeAt;

        FlyingArrow(ArrowEntity entity, Vec3d from, Vec3d to, long startTick) {
            this.entity = entity;
            this.from = from;
            this.to = to;
            this.startTick = startTick;

            double dx = to.x - from.x;
            double dy = to.y - from.y;
            double dz = to.z - from.z;

            // Longer flights arc higher. A short hop still gets enough time to look lobbed
            // rather than flat, and a long shot goes properly up into the sky.
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            this.flightTicks = (int) Math.max(20.0, Math.min(60.0, horizontal * 1.6));

            // Constant-gravity solution: horizontal is linear, vertical carries the arc.
            this.velocityX = dx / this.flightTicks;
            this.velocityZ = dz / this.flightTicks;
            this.velocityY = (dy + 0.5 * ARROW_GRAVITY * this.flightTicks * this.flightTicks)
                    / this.flightTicks;

            this.removeAt = startTick + this.flightTicks + ARROW_LINGER_TICKS;
        }

        Vec3d positionAt(double elapsed) {
            return new Vec3d(
                    this.from.x + this.velocityX * elapsed,
                    this.from.y + this.velocityY * elapsed - 0.5 * ARROW_GRAVITY * elapsed * elapsed,
                    this.from.z + this.velocityZ * elapsed);
        }

        /** Velocity at a moment, which is what the arrow should be pointing along. */
        Vec3d velocityAt(double elapsed) {
            return new Vec3d(this.velocityX, this.velocityY - ARROW_GRAVITY * elapsed, this.velocityZ);
        }
    }

    private ClientDispensers() {
    }

    // ---------------------------------------------------------------- profiles

    public static Map<String, RigProfile> profiles() {
        return profiles;
    }

    public static String activeName() {
        return activeName;
    }

    /** @return the active rig, creating a default one if none exists yet. */
    public static RigProfile active() {
        if (profiles.isEmpty()) {
            RigProfile fallback = new RigProfile("default");
            profiles.put(fallback.name, fallback);
            activeName = fallback.name;
        }
        RigProfile profile = profiles.get(activeName);
        if (profile == null) {
            profile = profiles.values().iterator().next();
            activeName = profile.name;
        }
        return profile;
    }

    public static RigProfile create(String name) {
        RigProfile profile = new RigProfile(name);
        profiles.put(name, profile);
        return profile;
    }

    public static boolean use(String name) {
        if (!profiles.containsKey(name)) return false;
        activeName = name;
        return true;
    }

    public static boolean delete(String name) {
        if (profiles.remove(name) == null) return false;
        if (activeName.equals(name) && !profiles.isEmpty()) {
            activeName = profiles.keySet().iterator().next();
        }
        return true;
    }

    /** Steps to the next or previous rig, for switching games without a menu. */
    public static RigProfile cycleProfile(int delta) {
        if (profiles.size() < 2) return null;

        List<String> names = new ArrayList<>(profiles.keySet());
        int index = Math.max(0, names.indexOf(activeName));
        activeName = names.get(Math.floorMod(index + delta, names.size()));
        return profiles.get(activeName);
    }

    public static FakeSpec cyclePreset(int delta) {
        return active().cycle(delta);
    }

    // Convenience over the active rig, so callers that only care about "the current game"
    // do not each have to reach through active().

    public static List<FakeSpec> presets() {
        return active().presets;
    }

    public static int presetIndex() {
        return active().presetIndex();
    }

    public static FakeSpec result() {
        return active().selected();
    }

    public static void addPreset(FakeSpec spec) {
        RigProfile profile = active();
        profile.presets.add(spec);
        if (profile.presetIndex() < 0) profile.setPresetIndex(0);
    }

    public static void clearPresets() {
        RigProfile profile = active();
        profile.presets.clear();
        profile.setPresetIndex(-1);
    }

    /** Replaces whatever is selected, or adds one if nothing is. */
    public static void setSelected(FakeSpec spec) {
        RigProfile profile = active();
        int index = profile.presetIndex();

        if (index >= 0 && index < profile.presets.size()) {
            profile.presets.set(index, spec);
        } else {
            profile.presets.add(spec);
            profile.setPresetIndex(profile.presets.size() - 1);
        }
    }

    public static void setResult(FakeSpec spec) {
        setSelected(spec);
    }

    public static Vec3d arrowTarget() {
        return active().arrowTarget;
    }

    public static void setArrowTarget(Vec3d target) {
        active().arrowTarget = target;
    }

    /** Fixes what one particular dispenser fires, regardless of the cycled item. */
    public static void setDispenserResult(BlockPos pos, FakeSpec spec) {
        active().perDispenser.put(pos.toImmutable(), spec);
        watched.add(pos.toImmutable());
    }

    public static boolean clearDispenserResult(BlockPos pos) {
        return active().perDispenser.remove(pos) != null;
    }

    public static void invalidateResult() {
        invalidateResults();
    }

    // ----------------------------------------------------------------- watching

    public static boolean watch(BlockPos pos) {
        return watched.add(pos.toImmutable());
    }

    public static boolean unwatch(BlockPos pos) {
        lastTriggered.remove(pos);
        return watched.remove(pos);
    }

    public static void unwatchAll() {
        watched.clear();
        lastTriggered.clear();
        pending.clear();
    }

    public static int watchedCount() {
        return watched.size();
    }

    public static Set<BlockPos> watchedPositions() {
        return watched;
    }

    // -------------------------------------------------------------------- tick

    public static void tick(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null) return;
        tick++;

        expire();
        flyArrows();
        if (watched.isEmpty()) return;

        for (BlockPos pos : watched) {
            // Don't reach into chunks the client has not got.
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;

            BlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof DispenserBlock)) {
                lastTriggered.remove(pos);
                continue;
            }

            boolean triggered = state.get(DispenserBlock.TRIGGERED);
            Boolean previous = lastTriggered.put(pos, triggered);
            // Only the rising edge counts, and never the very first observation.
            if (triggered && previous != null && !previous) {
                pending.add(new PendingFire(pos, tick + DISPENSE_DELAY_TICKS));
            }
        }

        Iterator<PendingFire> iterator = pending.iterator();
        while (iterator.hasNext()) {
            PendingFire fire = iterator.next();
            if (tick < fire.fireAt()) continue;
            iterator.remove();

            RigProfile profile = active();
            FakeSpec result = profile.resultFor(fire.pos());
            if (result != null) spawn(world, fire.pos(), result);
            if (profile.arrowTarget != null) launchArrow(world, fire.pos(), profile.arrowTarget);
        }
    }

    private static void spawn(ClientWorld world, BlockPos pos, FakeSpec result) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof DispenserBlock)) return;

        Direction facing = state.get(DispenserBlock.FACING);
        double x = pos.getX() + 0.5 + facing.getOffsetX() * 0.7;
        double y = pos.getY() + 0.35 + facing.getOffsetY() * 0.7;
        double z = pos.getZ() + 0.5 + facing.getOffsetZ() * 0.7;

        ItemEntity entity = new ItemEntity(world, x, y, z, result.stack().copy());
        entity.setId(nextId());
        entity.setPickupDelayInfinite();

        var random = world.getRandom();
        double spread = 0.06;
        entity.setVelocity(
                facing.getOffsetX() * 0.22 + (random.nextDouble() - 0.5) * spread,
                facing.getOffsetY() * 0.22 + 0.10 + (random.nextDouble() - 0.5) * spread,
                facing.getOffsetZ() * 0.22 + (random.nextDouble() - 0.5) * spread);
        entity.velocityDirty = true;

        world.addEntity(entity);
        spawned.add(new ExpiringItem(entity, tick + LIFETIME_TICKS));
    }

    private static void flyArrows() {
        Iterator<FlyingArrow> iterator = arrows.iterator();
        while (iterator.hasNext()) {
            FlyingArrow arrow = iterator.next();

            if (arrow.entity.isRemoved()) {
                iterator.remove();
                continue;
            }
            if (tick >= arrow.removeAt) {
                arrow.entity.discard();
                iterator.remove();
                continue;
            }

            double elapsed = tick - arrow.startTick;
            boolean landed = elapsed >= arrow.flightTicks;

            Vec3d position = landed ? arrow.to : arrow.positionAt(elapsed);
            if (!landed) {
                // Point along the current velocity, using vanilla's own arrow convention:
                // yaw from atan2(x, z), pitch from atan2(y, horizontal), neither negated.
                Vec3d velocity = arrow.velocityAt(elapsed);
                double flat = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
                arrow.entity.setYaw((float) (MathHelper.atan2(velocity.x, velocity.z) * 180.0 / Math.PI));
                arrow.entity.setPitch((float) (MathHelper.atan2(velocity.y, flat) * 180.0 / Math.PI));
            }

            arrow.entity.setPosition(position.x, position.y, position.z);
        }
    }

    private static void launchArrow(ClientWorld world, BlockPos pos, Vec3d target) {
        BlockState state = world.getBlockState(pos);
        Direction facing = state.getBlock() instanceof DispenserBlock
                ? state.get(DispenserBlock.FACING) : Direction.UP;

        Vec3d from = new Vec3d(
                pos.getX() + 0.5 + facing.getOffsetX() * 0.6,
                pos.getY() + 0.5 + facing.getOffsetY() * 0.6,
                pos.getZ() + 0.5 + facing.getOffsetZ() * 0.6);

        ArrowEntity arrow = new ArrowEntity(world, from.x, from.y, from.z,
                new ItemStack(Items.ARROW), null);
        arrow.setId(nextId());
        // We drive the position ourselves, so keep vanilla physics out of it entirely.
        arrow.setNoGravity(true);
        arrow.noClip = true;
        arrow.setVelocity(Vec3d.ZERO);
        arrow.setPosition(from.x, from.y, from.z);

        world.addEntity(arrow);
        arrows.add(new FlyingArrow(arrow, from, target, tick));
    }

    private static int nextId() {
        if (nextEntityId <= Integer.MAX_VALUE - 8192) nextEntityId = Integer.MAX_VALUE - 4096;
        return nextEntityId--;
    }

    private static void expire() {
        Iterator<ExpiringItem> iterator = spawned.iterator();
        while (iterator.hasNext()) {
            ExpiringItem item = iterator.next();
            if (item.entity().isRemoved()) {
                iterator.remove();
            } else if (tick >= item.removeAt()) {
                item.entity().discard();
                iterator.remove();
            }
        }
    }

    /** Leaving a world takes the client entities with it. */
    public static void reset() {
        arrows.clear();
        spawned.clear();
        pending.clear();
        lastTriggered.clear();
    }

    public static void invalidateResults() {
        for (RigProfile profile : profiles.values()) {
            for (FakeSpec spec : profile.presets) spec.invalidate();
            for (FakeSpec spec : profile.perDispenser.values()) spec.invalidate();
        }
    }

    // -------------------------------------------------------------- persistence

    public static void save(JsonObject root) {
        JsonArray positions = new JsonArray();
        for (BlockPos pos : watched) positions.add(writePos(pos));
        root.add("watchedDispensers", positions);

        JsonArray profileJson = new JsonArray();
        for (RigProfile profile : profiles.values()) {
            JsonObject json = new JsonObject();
            json.addProperty("name", profile.name);
            json.addProperty("presetIndex", profile.presetIndex());

            JsonArray presets = new JsonArray();
            for (FakeSpec spec : profile.presets) presets.add(SelfFakes.writeSpec(spec));
            json.add("presets", presets);

            JsonObject perDispenser = new JsonObject();
            for (Map.Entry<BlockPos, FakeSpec> entry : profile.perDispenser.entrySet()) {
                perDispenser.add(writePos(entry.getKey()), SelfFakes.writeSpec(entry.getValue()));
            }
            json.add("perDispenser", perDispenser);

            if (profile.arrowTarget != null) {
                json.addProperty("arrowTarget", profile.arrowTarget.x + ","
                        + profile.arrowTarget.y + "," + profile.arrowTarget.z);
            }
            profileJson.add(json);
        }
        root.add("profiles", profileJson);
        root.addProperty("activeProfile", activeName);
    }

    public static void load(JsonObject root) {
        watched.clear();
        lastTriggered.clear();
        profiles.clear();
        activeName = "";

        if (root.has("watchedDispensers")) {
            for (JsonElement element : root.getAsJsonArray("watchedDispensers")) {
                BlockPos pos = readPos(element.getAsString());
                if (pos != null) watched.add(pos);
            }
        }

        if (root.has("profiles")) {
            for (JsonElement element : root.getAsJsonArray("profiles")) {
                readProfile(element.getAsJsonObject());
            }
            if (root.has("activeProfile")) activeName = root.get("activeProfile").getAsString();
        } else {
            migrateSingleRig(root);
        }

        if (profiles.isEmpty()) seedDefaults();
        if (!profiles.containsKey(activeName)) {
            activeName = profiles.keySet().iterator().next();
        }
    }

    private static void readProfile(JsonObject json) {
        RigProfile profile = new RigProfile(json.get("name").getAsString());

        if (json.has("presets")) {
            for (JsonElement element : json.getAsJsonArray("presets")) {
                FakeSpec spec = readSpec(element.getAsJsonObject());
                if (spec != null) profile.presets.add(spec);
            }
        }
        if (json.has("presetIndex")) profile.setPresetIndex(json.get("presetIndex").getAsInt());

        if (json.has("perDispenser")) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("perDispenser").entrySet()) {
                BlockPos pos = readPos(entry.getKey());
                FakeSpec spec = readSpec(entry.getValue().getAsJsonObject());
                if (pos != null && spec != null) profile.perDispenser.put(pos, spec);
            }
        }
        if (json.has("arrowTarget")) profile.arrowTarget = readVec(json.get("arrowTarget").getAsString());

        profiles.put(profile.name, profile);
    }

    /** Files written before rigs existed held one set of presets and one result. */
    private static void migrateSingleRig(JsonObject root) {
        RigProfile profile = new RigProfile("default");

        if (root.has("dispenserPresets")) {
            for (JsonElement element : root.getAsJsonArray("dispenserPresets")) {
                FakeSpec spec = readSpec(element.getAsJsonObject());
                if (spec != null) profile.presets.add(spec);
            }
        }
        if (root.has("dispenserResult")) {
            FakeSpec spec = readSpec(root.getAsJsonObject("dispenserResult"));
            if (spec != null && profile.presets.isEmpty()) profile.presets.add(spec);
        }
        if (root.has("arrowTarget")) profile.arrowTarget = readVec(root.get("arrowTarget").getAsString());

        if (!profile.isEmpty()) {
            profiles.put(profile.name, profile);
            activeName = profile.name;
        }
    }

    /** A coin-flip rig ready to go, since that is what most of these games are. */
    private static void seedDefaults() {
        RigProfile coinFlip = new RigProfile("5050");
        Item gold = SelfFakes.lookupItem("gold_block");
        Item diamond = SelfFakes.lookupItem("diamond_block");
        if (gold != null) coinFlip.presets.add(new FakeSpec(gold, 1, ""));
        if (diamond != null) coinFlip.presets.add(new FakeSpec(diamond, 1, ""));

        profiles.put(coinFlip.name, coinFlip);
        profiles.put("paper", new RigProfile("paper"));
        activeName = coinFlip.name;
    }

    private static FakeSpec readSpec(JsonObject json) {
        if (json == null || !json.has("id")) return null;

        Item item = SelfFakes.lookupItem(json.get("id").getAsString());
        if (item == null) return null;

        int count = json.has("count") ? json.get("count").getAsInt() : 1;
        String enchants = json.has("enchants") ? json.get("enchants").getAsString() : "";
        Double price = json.has("price") ? json.get("price").getAsDouble() : null;
        Integer mapId = json.has("mapId") ? json.get("mapId").getAsInt() : null;
        return new FakeSpec(item, count, enchants, price, mapId);
    }

    private static String writePos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
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

    private static Vec3d readVec(String text) {
        String[] parts = text.split(",");
        if (parts.length != 3) return null;
        try {
            return new Vec3d(Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
