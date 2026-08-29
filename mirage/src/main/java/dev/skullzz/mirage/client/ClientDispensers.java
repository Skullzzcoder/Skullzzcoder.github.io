package dev.skullzz.mirage.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
 * Makes a watched dispenser appear to fire something of your choosing.
 *
 * <p>The item is an entity added to the client world only — it is never sent anywhere and no
 * one else sees it. Firing is spotted from the vanilla TRIGGERED blockstate, which the client
 * already receives, so nothing here needs the server's cooperation.
 *
 * <p>Only dispensers you explicitly watch are polled, so this costs a handful of blockstate
 * reads per tick rather than a scan of everything around you.
 */
public final class ClientDispensers {
    /** Vanilla schedules the real dispense four ticks after TRIGGERED flips. */
    private static final int DISPENSE_DELAY_TICKS = 4;
    /** How long the fake item hangs around before vanishing. */
    private static final int LIFETIME_TICKS = 60;

    private static final Set<BlockPos> watched = new LinkedHashSet<>();
    private static final Map<BlockPos, Boolean> lastTriggered = new HashMap<>();
    private static final List<PendingFire> pending = new ArrayList<>();
    private static final List<ExpiringItem> spawned = new ArrayList<>();

    /** Downward acceleration per tick squared, matching a vanilla arrow. */
    private static final double ARROW_GRAVITY = 0.05;
    /** How long it stays stuck in the target afterwards. */
    private static final int ARROW_LINGER_TICKS = 100;

    /** Results a keybind can flip between, so the choice needs no menu. */
    private static final List<FakeSpec> presets = new ArrayList<>();
    private static int presetIndex = -1;

    private static FakeSpec result;
    /** Where a fake arrow always lands. Null means don't fire one. */
    private static Vec3d arrowTarget;
    private static long tick;

    /** Client-only ids, taken from the top of the range so they miss the server's. */
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

    private static final List<FlyingArrow> arrows = new ArrayList<>();

    private ClientDispensers() {
    }

    // ------------------------------------------------------------------ config

    public static FakeSpec result() {
        return result;
    }

    public static void setResult(FakeSpec spec) {
        result = spec;
    }

    public static List<FakeSpec> presets() {
        return presets;
    }

    public static int presetIndex() {
        return presetIndex;
    }

    public static void addPreset(FakeSpec spec) {
        presets.add(spec);
    }

    public static void clearPresets() {
        presets.clear();
        presetIndex = -1;
    }

    /**
     * Steps to the next or previous preset and makes it the dispenser's result.
     *
     * @return the newly selected spec, or null if there are no presets.
     */
    public static FakeSpec cyclePreset(int delta) {
        if (presets.isEmpty()) return null;

        presetIndex = Math.floorMod(presetIndex + delta, presets.size());
        result = presets.get(presetIndex);
        return result;
    }

    public static Vec3d arrowTarget() {
        return arrowTarget;
    }

    public static void setArrowTarget(Vec3d target) {
        arrowTarget = target;
    }

    public static void invalidateResult() {
        if (result != null) result.invalidate();
    }

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

    // -------------------------------------------------------------------- tick

    public static void tick(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null) return;
        tick++;

        expire();
        flyArrows();
        if ((result == null && arrowTarget == null) || watched.isEmpty()) return;

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
            if (result != null) spawn(world, fire.pos());
            if (arrowTarget != null) launchArrow(world, fire.pos());
        }
    }

    /**
     * Flies each arrow along its arc and parks it on the target.
     *
     * <p>The path is interpolated rather than launched ballistically: solving for a velocity
     * that lands on an exact point through Minecraft's drag is fiddly and approximate, and
     * the whole point is that it never misses.
     */
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

    private static void launchArrow(ClientWorld world, BlockPos pos) {
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
        arrows.add(new FlyingArrow(arrow, from, arrowTarget, tick));
    }

    private static void spawn(ClientWorld world, BlockPos pos) {
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

    // -------------------------------------------------------------- persistence

    public static void save(JsonObject root) {
        JsonArray positions = new JsonArray();
        for (BlockPos pos : watched) {
            positions.add(pos.getX() + "," + pos.getY() + "," + pos.getZ());
        }
        root.add("watchedDispensers", positions);

        JsonArray presetJson = new JsonArray();
        for (FakeSpec spec : presets) presetJson.add(SelfFakes.writeSpec(spec));
        root.add("dispenserPresets", presetJson);
        if (result != null) root.add("dispenserResult", SelfFakes.writeSpec(result));
        if (arrowTarget != null) {
            root.addProperty("arrowTarget",
                    arrowTarget.x + "," + arrowTarget.y + "," + arrowTarget.z);
        }
    }

    public static void load(JsonObject root) {
        watched.clear();
        lastTriggered.clear();
        result = null;

        if (root.has("watchedDispensers")) {
            for (JsonElement element : root.getAsJsonArray("watchedDispensers")) {
                String[] parts = element.getAsString().split(",");
                if (parts.length != 3) continue;
                try {
                    watched.add(new BlockPos(Integer.parseInt(parts[0]),
                            Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                } catch (NumberFormatException ignored) {
                    // skip an unreadable position
                }
            }
        }

        presets.clear();
        presetIndex = -1;
        if (root.has("dispenserPresets")) {
            for (JsonElement element : root.getAsJsonArray("dispenserPresets")) {
                FakeSpec spec = readSpec(element.getAsJsonObject());
                if (spec != null) presets.add(spec);
            }
        }
        if (presets.isEmpty()) {
            // Sensible defaults for the usual two-way gamble.
            Item gold = SelfFakes.lookupItem("gold_ingot");
            Item diamond = SelfFakes.lookupItem("diamond");
            if (gold != null) presets.add(new FakeSpec(gold, 1, ""));
            if (diamond != null) presets.add(new FakeSpec(diamond, 1, ""));
        }

        arrowTarget = null;
        if (root.has("arrowTarget")) {
            String[] parts = root.get("arrowTarget").getAsString().split(",");
            if (parts.length == 3) {
                try {
                    arrowTarget = new Vec3d(Double.parseDouble(parts[0]),
                            Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
                } catch (NumberFormatException ignored) {
                    // skip an unreadable target
                }
            }
        }

        if (root.has("dispenserResult")) {
            result = readSpec(root.getAsJsonObject("dispenserResult"));
        }
    }

    private static FakeSpec readSpec(JsonObject json) {
        if (json == null || !json.has("id")) return null;

        Item item = SelfFakes.lookupItem(json.get("id").getAsString());
        if (item == null) return null;

        int count = json.has("count") ? json.get("count").getAsInt() : 1;
        String enchants = json.has("enchants") ? json.get("enchants").getAsString() : "";
        Double price = json.has("price") ? json.get("price").getAsDouble() : null;
        return new FakeSpec(item, count, enchants, price);
    }
}
