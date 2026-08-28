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
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

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

    private static FakeSpec result;
    private static long tick;

    /** Client-only ids, taken from the top of the range so they miss the server's. */
    private static int nextEntityId = Integer.MAX_VALUE - 4096;

    private record PendingFire(BlockPos pos, long fireAt) {
    }

    private record ExpiringItem(ItemEntity entity, long removeAt) {
    }

    private ClientDispensers() {
    }

    // ------------------------------------------------------------------ config

    public static FakeSpec result() {
        return result;
    }

    public static void setResult(FakeSpec spec) {
        result = spec;
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
        if (result == null || watched.isEmpty()) return;

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
            spawn(world, fire.pos());
        }
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
        if (result != null) root.add("dispenserResult", SelfFakes.writeSpec(result));
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

        if (root.has("dispenserResult")) {
            JsonObject json = root.getAsJsonObject("dispenserResult");
            Item item = SelfFakes.lookupItem(json.get("id").getAsString());
            if (item != null) {
                int count = json.has("count") ? json.get("count").getAsInt() : 1;
                String enchants = json.has("enchants") ? json.get("enchants").getAsString() : "";
                result = new FakeSpec(item, count, enchants);
            }
        }
    }
}
