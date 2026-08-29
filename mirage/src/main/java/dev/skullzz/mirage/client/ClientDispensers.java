package dev.skullzz.mirage.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.block.BlockState;
import net.minecraft.block.DispenserBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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
    /** How long a fake waits to be walked over before coming to the player by itself. */
    private static final int COLLECT_AFTER_TICKS = 30;
    /** How long the flight into the player takes, matching vanilla's pickup. */
    private static final int COLLECT_TICKS = 4;
    /** Fires closer together than this belong to the same round of a paper game. */
    private static final int ROUND_TICKS = 40;
    /** How close the player has to get for it to come early. */
    private static final double COLLECT_RANGE_SQUARED = 2.5;

    /** One dispense can show up on more than one signal; ignore the echoes. */
    private static final int REFIRE_COOLDOWN_TICKS = 8;

    private static final Set<BlockPos> watched = new LinkedHashSet<>();
    private static final Map<BlockPos, Boolean> lastTriggered = new HashMap<>();
    private static final Map<BlockPos, Boolean> lastPowered = new HashMap<>();
    private static final Map<BlockPos, Long> lastFire = new HashMap<>();
    private static final List<PendingFire> pending = new ArrayList<>();
    private static final List<SpawnedItem> spawned = new ArrayList<>();
    private static final List<FlyingArrow> arrows = new ArrayList<>();

    private static final Map<String, RigProfile> profiles = new LinkedHashMap<>();
    private static String activeName = "";

    /** Slots in a dispenser, and the middle one that a ring is built around. */
    public static final int STOCK_SLOTS = 9;
    private static final int MIDDLE_SLOT = 4;
    /** No dispenser can be opened from further away than this, squared. */
    private static final double REACH_SQUARED = 49.0;

    private static long tick;
    /** Prints what the watcher is seeing, for working out why nothing fired. */
    private static boolean debug;
    /** How long a complaint about an empty rig keeps quiet for. */
    private static final int WARN_GAP_TICKS = 60;
    private static long lastWarn = Long.MIN_VALUE / 2;

    /** The dispenser last looked at, which is the one whose GUI is open if one is. */
    private static BlockPos openDispenser;
    /** Which of several matching slots empties, so a ring does not drain left to right. */
    private static final Random random = new Random();
    /** Client-only ids, from the top of the range so they miss the server's. */
    private static int nextEntityId = Integer.MAX_VALUE - 4096;

    private record PendingFire(BlockPos pos, long fireAt) {
    }

    /**
     * A fake that came out of a dispenser, and how it gets tidied away.
     *
     * <p>Left alone it simply vanishes. With collection on it flies to the player and lands in
     * the inventory instead, so the illusion finishes the way a real dispense would.
     */
    private static final class SpawnedItem {
        final ItemEntity entity;
        final FakeSpec spec;
        /** When it gives up waiting to be walked over and comes to the player anyway. */
        final long collectAt;
        final long removeAt;

        long collectStart = -1;
        Vec3d collectFrom;

        SpawnedItem(ItemEntity entity, FakeSpec spec, long now) {
            this.entity = entity;
            this.spec = spec;
            this.collectAt = now + COLLECT_AFTER_TICKS;
            this.removeAt = now + LIFETIME_TICKS;
        }
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
        // Each rig owns its own layouts, so switching game changes what the dispensers
        // appear to hold as well as what they fire.
        fillEmptyWatched();
        SelfFakes.repaintContainer();
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

    /** Makes the next shot from this rig the loaded one. */
    public static void armNext() {
        active().armed = true;
    }

    public static boolean isArmed() {
        return active().armed;
    }

    public static void disarm() {
        active().armed = false;
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
        // It now fires something different, so what it looks like it holds has to follow.
        fill(pos);
    }

    public static boolean clearDispenserResult(BlockPos pos) {
        return active().perDispenser.remove(pos) != null;
    }

    public static void invalidateResult() {
        invalidateResults();
    }

    // ----------------------------------------------------------------- watching

    public static boolean watch(BlockPos pos) {
        boolean added = watched.add(pos.toImmutable());
        // Lay it out straight away: a watched dispenser that opens empty gives the whole
        // thing away before it has fired once.
        if (added) fill(pos);
        return added;
    }

    public static boolean unwatch(BlockPos pos) {
        active().stock.remove(pos);
        lastTriggered.remove(pos);
        lastPowered.remove(pos);
        lastFire.remove(pos);
        return watched.remove(pos);
    }

    public static void unwatchAll() {
        watched.clear();
        lastTriggered.clear();
        lastPowered.clear();
        lastFire.clear();
        pending.clear();
    }

    public static boolean debug() {
        return debug;
    }

    public static void setDebug(boolean on) {
        debug = on;
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

        tidySpawned(client);
        flyArrows();
        watchTick(world);

        Iterator<PendingFire> iterator = pending.iterator();
        while (iterator.hasNext()) {
            PendingFire fire = iterator.next();
            if (tick < fire.fireAt()) continue;
            iterator.remove();

            // Resolve nothing until we know something can actually come out: in roulette
            // that call spends a chamber, and spending one on a dispenser that has been
            // broken or walked away from would quietly desync the count.
            if (!isDispenser(world, fire.pos())) {
                warn("No dispenser at " + text(fire.pos()) + " - moved, or too far away.");
                continue;
            }

            RigProfile profile = active();
            // In roulette the whole rig shares one chamber counter, so which dispenser fired
            // does not matter; otherwise a dispenser's own answer wins.
            FakeSpec result;
            if (profile.roulette) {
                result = profile.advanceRoulette();
            } else if (profile.paper) {
                result = paperSlip(profile, fire.pos());
            } else {
                result = profile.resultFor(fire.pos());
            }
            if (result == null) {
                warn("Rig '" + profile.name + "' has nothing to fire.");
            } else {
                spawn(world, fire.pos(), result);
                // Take it out of what the dispenser looks like it is holding, so opening
                // the thing afterwards agrees with what everyone just watched come out.
                deplete(profile, fire.pos(), result);
            }
            if (profile.arrowTarget != null) launchArrow(world, fire.pos(), profile.arrowTarget);
        }
    }

    /** Looks at every watched dispenser for a sign that it just went off. */
    private static void watchTick(ClientWorld world) {
        for (BlockPos pos : watched) {
            // Don't reach into chunks the client has not got.
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;

            BlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof DispenserBlock)) {
                lastTriggered.remove(pos);
                lastPowered.remove(pos);
                continue;
            }

            // TRIGGERED is set without telling anyone: the server writes it with the
            // no-redraw flag, which skips the packet, so on a real server this bit never
            // moves for us. It does in single player, where both sides share one world,
            // so it stays in as one signal rather than the only one.
            boolean triggered = state.get(DispenserBlock.TRIGGERED);
            Boolean wasTriggered = lastTriggered.put(pos, triggered);
            if (triggered && wasTriggered != null && !wasTriggered) spotFire(pos, "triggered");

            // What the client does get is the redstone around it. Levers, buttons, plates,
            // wire, repeaters and torches all sync their own state, so the power reaching
            // the dispenser can be worked out here from blocks we can actually see.
            boolean powered = world.isReceivingRedstonePower(pos)
                    || world.isReceivingRedstonePower(pos.up());
            Boolean wasPowered = lastPowered.put(pos, powered);
            if (powered && wasPowered != null && !wasPowered) spotFire(pos, "redstone");
        }
    }

    /**
     * The slip this machine fires, drawn as half of a pair.
     *
     * <p>Two dispensers going off together are one round: whichever fires first draws both
     * numbers, and the second takes the other half, so the two always disagree and the rigged
     * side always has the higher one.
     */
    private static FakeSpec paperSlip(RigProfile profile, BlockPos pos) {
        // Added to the older side, never subtracted from the newer: the no-round-yet marker
        // is Long.MIN_VALUE, and subtracting that overflows to a huge negative, which reads
        // as "still the same round" forever and leaves every draw on its starting value.
        if (tick > profile.roundTick + ROUND_TICKS) profile.startRound(random, tick);

        String side = profile.sideAt(pos);
        boolean wins = !profile.roundWinner.isEmpty() && profile.roundWinner.equals(side);
        int number = wins ? profile.highRoll : profile.lowRoll;

        Item slip = SelfFakes.lookupItem(profile.slipItem);
        if (slip == null) return null;

        // Built to match the laid-out slip exactly, so it empties that slot on the way out.
        return new FakeSpec(slip, 1, "", null, null, profile.slipName(number, side));
    }

    /** Steps who the paper game is rigged for, and says who that is now. */
    public static String cycleWinner() {
        String winner = active().cycleWinner();
        SelfFakes.save();
        return winner;
    }

    private static boolean isDispenser(ClientWorld world, BlockPos pos) {
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        return world.getBlockState(pos).getBlock() instanceof DispenserBlock;
    }

    /** Queues a dispense, unless the same one has already been spotted another way. */
    private static boolean spotFire(BlockPos pos, String why) {
        Long last = lastFire.get(pos);
        if (last != null && tick - last < REFIRE_COOLDOWN_TICKS) return false;

        BlockPos key = pos.toImmutable();
        lastFire.put(key, tick);
        pending.add(new PendingFire(key, tick + DISPENSE_DELAY_TICKS));
        note("fire spotted at " + text(key) + " (" + why + ")");
        return true;
    }

    /**
     * Fires a dispenser by hand.
     *
     * <p>The signals above cover the usual setups, but a dispenser worked by something the
     * client cannot see still needs to look like it went off, so this is always available.
     */
    public static boolean fireNow(BlockPos pos) {
        BlockPos key = pos.toImmutable();
        lastFire.put(key, tick);
        pending.add(new PendingFire(key, tick));
        return true;
    }

    /** Fires every watched dispenser, for when you are not looking at one. */
    public static int fireAllWatched() {
        for (BlockPos pos : watched) fireNow(pos);
        return watched.size();
    }

    // ------------------------------------------------------------------- stock

    /** Remembers the dispenser being looked at, so an open GUI can be tied to a position. */
    public static void setOpenDispenser(BlockPos pos) {
        openDispenser = pos == null ? null : pos.toImmutable();
    }

    /** @return what the open dispenser looks like it holds, or null to fall back. */
    public static Map<Integer, FakeSpec> openStock(ClientPlayerEntity player) {
        BlockPos pos = openDispenserPos(player);
        return pos == null ? null : active().stockAt(pos);
    }

    /**
     * Works out which dispenser an open screen belongs to.
     *
     * <p>Normally that is the one last looked at. When the look never landed -- opened from
     * an odd angle, or through a block -- the dispenser still has to be within reach to have
     * been opened at all, so the nearest one laid out in this rig is the answer. Without that
     * fallback a single missed raycast leaves the GUI showing the real, empty box.
     */
    public static BlockPos openDispenserPos(ClientPlayerEntity player) {
        RigProfile profile = active();
        if (openDispenser != null && profile.stock.containsKey(openDispenser)) {
            return openDispenser;
        }
        if (player == null) return null;

        BlockPos best = null;
        double bestDistance = REACH_SQUARED;

        for (BlockPos pos : profile.stock.keySet()) {
            double dx = player.getX() - (pos.getX() + 0.5);
            double dy = player.getY() - (pos.getY() + 0.5);
            double dz = player.getZ() - (pos.getZ() + 0.5);
            double distance = dx * dx + dy * dy + dz * dz;

            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    /**
     * Lays out what a dispenser appears to hold, from the rig it belongs to.
     *
     * <p>A roulette rig gets the shape of the real game: the loaded item in the middle with
     * blanks all round it. A dispenser with a fixed answer gets nine of that. Anything else
     * gets one of each of the rig's items, so a coin flip holds one of each side and looks
     * the same whichever way it is currently rigged.
     */
    public static boolean fill(BlockPos pos) {
        RigProfile profile = active();
        Map<Integer, FakeSpec> slots = new LinkedHashMap<>();

        if (profile.roulette) {
            for (int slot = 0; slot < STOCK_SLOTS; slot++) {
                FakeSpec spec = slot == MIDDLE_SLOT ? profile.bullet : profile.blank;
                // Each slot needs its own copy: they empty one at a time.
                if (spec != null) slots.put(slot, spec.withCount(1));
            }
        } else if (profile.paper) {
            // One slip per number, all named for the side this machine plays.
            String side = profile.sideAt(pos);
            Item slip = SelfFakes.lookupItem(profile.slipItem);

            if (slip != null) {
                int count = Math.min(profile.numbers, STOCK_SLOTS);
                for (int slot = 0; slot < count; slot++) {
                    slots.put(slot, new FakeSpec(slip, 1, "", null, null,
                            profile.slipName(slot + 1, side)));
                }
            }
        } else {
            FakeSpec fixed = profile.perDispenser.get(pos);
            if (fixed != null) {
                for (int slot = 0; slot < STOCK_SLOTS; slot++) {
                    slots.put(slot, fixed.withCount(fixed.count));
                }
            } else {
                // One of each, in order. A coin flip is the two things you could win sat
                // side by side, not a box full of them, and holding both means switching
                // which one is rigged never changes what the dispenser looks like.
                int count = Math.min(profile.presets.size(), STOCK_SLOTS);
                for (int slot = 0; slot < count; slot++) {
                    FakeSpec spec = profile.presets.get(slot);
                    slots.put(slot, spec.withCount(spec.count));
                }
            }
        }

        BlockPos key = pos.toImmutable();
        if (slots.isEmpty()) {
            profile.stock.remove(key);
            return false;
        }
        profile.stock.put(key, slots);
        SelfFakes.repaintContainer();
        return true;
    }

    /** Fills every watched dispenser that has not been laid out yet. */
    public static int fillEmptyWatched() {
        RigProfile profile = active();
        int filled = 0;
        for (BlockPos pos : watched) {
            if (profile.stock.containsKey(pos)) continue;
            if (fill(pos)) filled++;
        }
        return filled;
    }

    /** Lays every watched dispenser out again, including ones already emptied. */
    public static int refillWatched() {
        int filled = 0;
        for (BlockPos pos : watched) {
            if (fill(pos)) filled++;
        }
        return filled;
    }

    public static boolean unfill(BlockPos pos) {
        if (active().stock.remove(pos) == null) return false;
        SelfFakes.repaintContainer();
        return true;
    }

    /**
     * Takes one fired item back out of what the dispenser appears to hold.
     *
     * <p>Falls back to the one shared nine-slot set for dispensers laid out by hand, so this
     * works whether or not the rig owns the layout.
     */
    private static void deplete(RigProfile profile, BlockPos pos, FakeSpec fired) {
        Map<Integer, FakeSpec> slots = profile.stockAt(pos);
        if (slots == null) slots = SelfFakes.allContainer(SelfFakes.DISPENSER);
        if (slots.isEmpty()) return;

        List<Integer> matching = new ArrayList<>();
        for (Map.Entry<Integer, FakeSpec> entry : slots.entrySet()) {
            if (entry.getValue().stacksWith(fired)) matching.add(entry.getKey());
        }
        // Nothing of that kind in there. Taking some other item out would be a bigger
        // giveaway than the count simply not moving, so leave it alone.
        if (matching.isEmpty()) {
            note("nothing matching " + fired.describe() + " to take out of the dispenser");
            return;
        }

        int slot = matching.get(random.nextInt(matching.size()));
        FakeSpec held = slots.get(slot);
        int left = held.count - fired.count;

        if (left > 0) {
            slots.put(slot, held.withCount(left));
        } else {
            slots.remove(slot);
        }
        SelfFakes.repaintContainer();
    }

    /** What the watcher can see right now, so a dead setup can be told apart from a bug. */
    public static List<String> status(ClientWorld world) {
        List<String> lines = new ArrayList<>();
        RigProfile profile = active();

        lines.add("Rig '" + profile.name + "'"
                + (profile.paper ? ", paper" : "")
                + (profile.roulette ? ", roulette" : "")
                + (profile.roulette && profile.manualTrigger ? ", manual" : "")
                + (profile.armed ? ", ARMED" : ""));
        if (profile.paper) {
            lines.add("  rigged for: "
                    + (profile.winner.isEmpty() ? "chance" : profile.winner)
                    + ", sides " + profile.sideNames());
        }
        if (profile.roulette) {
            lines.add("  shot " + profile.shot + " of " + profile.chambers
                    + ", loaded one at " + profile.bulletAt
                    + ", bullet " + describeSpec(profile.bullet)
                    + ", blank " + describeSpec(profile.blank));
        } else {
            lines.add("  fires " + describeSpec(profile.selected()));
        }

        if (watched.isEmpty()) {
            lines.add("No dispensers watched. Look at one and run /fake dispenser watch.");
            return lines;
        }

        BlockPos open = openDispenserPos(MinecraftClient.getInstance().player);
        lines.add("  a dispenser GUI here would show: "
                + (open == null ? "its real contents - nothing laid out within reach"
                        : text(open)));

        for (BlockPos pos : watched) {
            StringBuilder line = new StringBuilder("  " + text(pos) + ": ");
            if (world == null) {
                line.append("no world");
            } else if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                line.append("chunk not loaded - go closer");
            } else {
                BlockState state = world.getBlockState(pos);
                if (!(state.getBlock() instanceof DispenserBlock)) {
                    line.append("not a dispenser any more - rewatch it");
                } else {
                    line.append("ok, ")
                            .append(world.isReceivingRedstonePower(pos)
                                    || world.isReceivingRedstonePower(pos.up())
                                    ? "powered now" : "unpowered");
                    if (profile.paper) {
                        line.append(", plays ").append(profile.sides.get(pos));
                    }
                    FakeSpec fixed = profile.perDispenser.get(pos);
                    if (fixed != null) line.append(", fires ").append(describeSpec(fixed));

                    Map<Integer, FakeSpec> slots = profile.stockAt(pos);
                    line.append(slots == null ? ", not laid out"
                            : ", holding " + slots.size() + "/" + STOCK_SLOTS);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * The dispenser a hand move applies to.
     *
     * <p>Falls back to the block last looked at even when it has no layout yet, so the first
     * item can be loaded into one that has never been filled.
     */
    private static BlockPos targetDispenser(ClientPlayerEntity player) {
        BlockPos pos = openDispenserPos(player);
        return pos != null ? pos : openDispenser;
    }

    /** @return whether the open dispenser is faking that slot, so a click on it is ours. */
    public static boolean stockHolds(ClientPlayerEntity player, int slot) {
        Map<Integer, FakeSpec> slots = openStock(player);
        return slots != null && slots.containsKey(slot);
    }

    /** Shift-clicking a laid-out slot takes it into the inventory, like emptying a dispenser. */
    public static boolean takeFromStock(ClientPlayerEntity player, int slot) {
        BlockPos pos = targetDispenser(player);
        if (pos == null) return false;

        Map<Integer, FakeSpec> slots = active().stockAt(pos);
        if (slots == null) return false;

        FakeSpec spec = slots.get(slot);
        if (spec == null || !SelfFakes.collect(spec, player)) return false;

        slots.remove(slot);
        SelfFakes.repaintContainer();
        SelfFakes.save();
        return true;
    }

    /** Shift-clicking a fake in the inventory loads it, the way a dispenser is filled by hand. */
    public static boolean putIntoStock(ClientPlayerEntity player, int inventorySlot) {
        BlockPos pos = targetDispenser(player);
        if (pos == null) return false;

        FakeSpec spec = SelfFakes.all().get(inventorySlot);
        if (spec == null) return false;

        Map<Integer, FakeSpec> slots = active().stock
                .computeIfAbsent(pos.toImmutable(), key -> new LinkedHashMap<>());

        // Onto a matching stack first, the way a real shift-click loads a dispenser.
        for (Map.Entry<Integer, FakeSpec> entry : slots.entrySet()) {
            FakeSpec held = entry.getValue();
            if (!held.stacksWith(spec) || held.count >= 64) continue;

            entry.setValue(held.withCount(Math.min(64, held.count + spec.count)));
            SelfFakes.clear(inventorySlot, player);
            SelfFakes.repaintContainer();
            return true;
        }

        for (int slot = 0; slot < STOCK_SLOTS; slot++) {
            if (slots.containsKey(slot)) continue;

            slots.put(slot, spec);
            SelfFakes.clear(inventorySlot, player);
            SelfFakes.repaintContainer();
            return true;
        }
        // Full. Leaving it in the inventory is what a real shift-click would do too.
        return false;
    }

    /** Counts a layout up by item, so "8x obsidian, 1x end crystal" can be read back. */
    public static String describeStock(BlockPos pos) {
        Map<Integer, FakeSpec> slots = active().stockAt(pos);
        if (slots == null || slots.isEmpty()) return "nothing";

        Map<String, Integer> totals = new LinkedHashMap<>();
        for (FakeSpec spec : slots.values()) {
            totals.merge(spec.describe(), spec.count, Integer::sum);
        }

        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            if (text.length() > 0) text.append(", ");
            text.append(entry.getValue()).append("x ").append(entry.getKey());
        }
        return text.toString();
    }

    private static String describeSpec(FakeSpec spec) {
        return spec == null ? "nothing" : spec.count + "x " + spec.describe();
    }

    private static String text(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** Debug chatter, in the action bar so it never lands in a screenshot of chat. */
    private static void note(String message) {
        if (!debug) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        client.player.sendMessage(Text.literal("[mirage] " + message)
                .formatted(Formatting.DARK_GRAY), false);
    }

    /**
     * Says why a dispense produced nothing, whether or not debug is on.
     *
     * <p>A fire that quietly does nothing is indistinguishable from the mod not being
     * loaded, which has now cost more than one evening. Throttled, since a jammed setup
     * can go off repeatedly.
     */
    private static void warn(String message) {
        if (tick - lastWarn < WARN_GAP_TICKS) return;
        lastWarn = tick;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        client.player.sendMessage(Text.literal(message).formatted(Formatting.GRAY), true);
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
        spawned.add(new SpawnedItem(entity, result, tick));
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

    private static void tidySpawned(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        Iterator<SpawnedItem> iterator = spawned.iterator();

        while (iterator.hasNext()) {
            SpawnedItem item = iterator.next();
            if (item.entity.isRemoved()) {
                iterator.remove();
                continue;
            }

            // Already on its way in.
            if (item.collectStart >= 0) {
                double progress = (tick - item.collectStart) / (double) COLLECT_TICKS;
                if (player == null || progress >= 1.0) {
                    finishCollect(player, item);
                    iterator.remove();
                } else {
                    // Built from the component accessors: Entity.getPos is gone in this
                    // version, while getX/getY/getZ have been there since 1.14.
                    Vec3d target = new Vec3d(player.getX(), player.getY() + 0.4, player.getZ());
                    item.entity.setPosition(
                            MathHelper.lerp(progress, item.collectFrom.x, target.x),
                            MathHelper.lerp(progress, item.collectFrom.y, target.y),
                            MathHelper.lerp(progress, item.collectFrom.z, target.z));
                }
                continue;
            }

            boolean nearby = player != null
                    && player.squaredDistanceTo(item.entity) < COLLECT_RANGE_SQUARED;
            if (SelfFakes.autoCollect() && player != null && (nearby || tick >= item.collectAt)) {
                item.collectStart = tick;
                item.collectFrom = new Vec3d(item.entity.getX(), item.entity.getY(),
                        item.entity.getZ());
                continue;
            }

            if (tick >= item.removeAt) {
                item.entity.discard();
                iterator.remove();
            }
        }
    }

    /** Ends the flight: the entity goes, the fake lands in the inventory, the sound plays. */
    private static void finishCollect(ClientPlayerEntity player, SpawnedItem item) {
        item.entity.discard();
        if (player == null) return;

        if (!SelfFakes.collect(item.spec, player)) return;

        // Vanilla's own pickup pitch, so it does not stand out against real ones.
        float pitch = ((player.getRandom().nextFloat() - player.getRandom().nextFloat()) * 0.7F
                + 1.0F) * 2.0F;
        player.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F, pitch);
    }

    /** Leaving a world takes the client entities with it. */
    public static void reset() {
        arrows.clear();
        spawned.clear();
        pending.clear();
        lastTriggered.clear();
        lastPowered.clear();
        lastFire.clear();
    }

    public static void invalidateResults() {
        for (RigProfile profile : profiles.values()) {
            for (FakeSpec spec : profile.presets) spec.invalidate();
            for (FakeSpec spec : profile.perDispenser.values()) spec.invalidate();
            for (Map<Integer, FakeSpec> slots : profile.stock.values()) {
                for (FakeSpec spec : slots.values()) spec.invalidate();
            }
            if (profile.bullet != null) profile.bullet.invalidate();
            if (profile.blank != null) profile.blank.invalidate();
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

            JsonObject stock = new JsonObject();
            for (Map.Entry<BlockPos, Map<Integer, FakeSpec>> entry : profile.stock.entrySet()) {
                JsonObject slots = new JsonObject();
                for (Map.Entry<Integer, FakeSpec> held : entry.getValue().entrySet()) {
                    slots.add(String.valueOf(held.getKey()), SelfFakes.writeSpec(held.getValue()));
                }
                stock.add(writePos(entry.getKey()), slots);
            }
            json.add("stock", stock);

            if (profile.arrowTarget != null) {
                json.addProperty("arrowTarget", profile.arrowTarget.x + ","
                        + profile.arrowTarget.y + "," + profile.arrowTarget.z);
            }

            if (profile.paper) {
                JsonObject paper = new JsonObject();
                paper.addProperty("winner", profile.winner);
                paper.addProperty("item", profile.slipItem);
                paper.addProperty("numbers", profile.numbers);

                JsonObject sides = new JsonObject();
                for (Map.Entry<BlockPos, String> entry : profile.sides.entrySet()) {
                    sides.addProperty(writePos(entry.getKey()), entry.getValue());
                }
                paper.add("sides", sides);
                json.add("paper", paper);
            }

            if (profile.roulette) {
                JsonObject roulette = new JsonObject();
                roulette.addProperty("chambers", profile.chambers);
                roulette.addProperty("bulletAt", profile.bulletAt);
                roulette.addProperty("shot", profile.shot);
                roulette.addProperty("manual", profile.manualTrigger);
                if (profile.bullet != null) roulette.add("bullet", SelfFakes.writeSpec(profile.bullet));
                if (profile.blank != null) roulette.add("blank", SelfFakes.writeSpec(profile.blank));
                json.add("roulette", roulette);
            }
            profileJson.add(json);
        }
        root.add("profiles", profileJson);
        root.addProperty("activeProfile", activeName);
    }

    public static void load(JsonObject root) {
        watched.clear();
        lastTriggered.clear();
        lastPowered.clear();
        lastFire.clear();
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

        seedDefaults();
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
        if (json.has("stock")) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("stock").entrySet()) {
                BlockPos pos = readPos(entry.getKey());
                if (pos == null) continue;

                Map<Integer, FakeSpec> slots = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> held
                        : entry.getValue().getAsJsonObject().entrySet()) {
                    FakeSpec spec = readSpec(held.getValue().getAsJsonObject());
                    if (spec == null) continue;
                    try {
                        slots.put(Integer.parseInt(held.getKey()), spec);
                    } catch (NumberFormatException ignored) {
                        // a slot key that is not a number is not a slot
                    }
                }
                if (!slots.isEmpty()) profile.stock.put(pos, slots);
            }
        }
        if (json.has("arrowTarget")) profile.arrowTarget = readVec(json.get("arrowTarget").getAsString());

        if (json.has("paper")) {
            JsonObject paper = json.getAsJsonObject("paper");
            profile.paper = true;
            if (paper.has("winner")) profile.winner = paper.get("winner").getAsString();
            if (paper.has("item")) profile.slipItem = paper.get("item").getAsString();
            if (paper.has("numbers")) profile.numbers = paper.get("numbers").getAsInt();

            if (paper.has("sides")) {
                for (Map.Entry<String, JsonElement> entry
                        : paper.getAsJsonObject("sides").entrySet()) {
                    BlockPos pos = readPos(entry.getKey());
                    if (pos != null) profile.sides.put(pos, entry.getValue().getAsString());
                }
            }
        }

        if (json.has("roulette")) {
            JsonObject roulette = json.getAsJsonObject("roulette");
            profile.roulette = true;
            if (roulette.has("chambers")) profile.chambers = roulette.get("chambers").getAsInt();
            if (roulette.has("bulletAt")) profile.bulletAt = roulette.get("bulletAt").getAsInt();
            if (roulette.has("shot")) profile.shot = roulette.get("shot").getAsInt();
            // Deliberately not persisted: a restart should never leave it armed.
            profile.manualTrigger = roulette.has("manual")
                    && roulette.get("manual").getAsBoolean();
            if (roulette.has("bullet")) profile.bullet = readSpec(roulette.getAsJsonObject("bullet"));
            if (roulette.has("blank")) profile.blank = readSpec(roulette.getAsJsonObject("blank"));
            profile.tidyRoulette();
        }

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

    /**
     * Puts the built-in rigs back if they are not in the file.
     *
     * <p>This runs on every load rather than only on a fresh one: a config written before a
     * rig existed has no trace of it, and the rig silently missing looks exactly like the
     * feature being broken. Rigs already in the file are left completely alone.
     */
    private static void seedDefaults() {
        if (needsSeeding("5050")) {
            RigProfile coinFlip = new RigProfile("5050");
            Item gold = SelfFakes.lookupItem("gold_block");
            Item diamond = SelfFakes.lookupItem("diamond_block");
            if (gold != null) coinFlip.presets.add(new FakeSpec(gold, 1, ""));
            if (diamond != null) coinFlip.presets.add(new FakeSpec(diamond, 1, ""));
            coinFlip.setPresetIndex(coinFlip.presets.isEmpty() ? -1 : 0);
            profiles.put(coinFlip.name, coinFlip);
        }
        if (needsSeeding("paper")) {
            // Two machines of numbered slips, the higher one winning. Sides are handed out
            // as the dispensers are watched: first the player's, then the host's.
            RigProfile paper = new RigProfile("paper");
            paper.paper = true;
            profiles.put(paper.name, paper);
        } else {
            // A file written before the paper game existed holds a rig with the name but
            // not the mode, which lays out nothing and looks like the game being broken.
            RigProfile paper = profiles.get("paper");
            if (!paper.roulette && paper.presets.isEmpty()) paper.paper = true;
        }
        if (needsSeeding("roulette")) {
            // Set up for the usual arrangement: eight obsidian round one crystal, and the
            // crystal appears only when you arm it, since turn order is decided as you go.
            RigProfile roulette = new RigProfile("roulette");
            roulette.roulette = true;
            roulette.manualTrigger = true;
            roulette.chambers = 9;

            Item crystal = SelfFakes.lookupItem("end_crystal");
            if (crystal != null) roulette.bullet = new FakeSpec(crystal, 1, "");
            Item obsidian = SelfFakes.lookupItem("obsidian");
            if (obsidian != null) roulette.blank = new FakeSpec(obsidian, 1, "");
            profiles.put(roulette.name, roulette);
        }
        if (activeName.isEmpty()) activeName = "5050";
    }

    /**
     * Whether a built-in rig has to be laid down again.
     *
     * <p>Missing is the obvious case. Present but empty is the one that bites: a config
     * written before a game existed carries the rig's name and nothing else, and a rig that
     * holds nothing behaves exactly like a broken feature. A rig with anything in it is
     * somebody's setup and is never touched.
     */
    private static boolean needsSeeding(String name) {
        RigProfile existing = profiles.get(name);
        return existing == null || existing.isEmpty();
    }

    private static FakeSpec readSpec(JsonObject json) {
        if (json == null || !json.has("id")) return null;

        Item item = SelfFakes.lookupItem(json.get("id").getAsString());
        if (item == null) return null;

        int count = json.has("count") ? json.get("count").getAsInt() : 1;
        String enchants = json.has("enchants") ? json.get("enchants").getAsString() : "";
        Double price = json.has("price") ? json.get("price").getAsDouble() : null;
        Integer mapId = json.has("mapId") ? json.get("mapId").getAsInt() : null;
        String name = json.has("name") ? json.get("name").getAsString() : "";
        return new FakeSpec(item, count, enchants, price, mapId, name);
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
