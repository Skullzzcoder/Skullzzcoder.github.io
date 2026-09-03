package dev.skullzz.mirage.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.block.Block;
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
import net.minecraft.registry.Registries;
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
    /** An arrow keeps this much of its speed each tick. */
    private static final double ARROW_DRAG = 0.99;
    /** Ticks of flight per block of ground covered, which sets how high it arcs. */
    private static final double ARROW_TICKS_PER_BLOCK = 1.3;
    /** Ticks of not having moved before we accept vanilla is not going to move it. */
    private static final int ARROW_STALL_TICKS = 3;
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
    /** Where each machine's last placed answer is standing, so the next one replaces it. */
    private static final Map<BlockPos, BlockPos> standing = new HashMap<>();
    private static final List<PendingFire> pending = new ArrayList<>();
    private static final List<SpawnedItem> spawned = new ArrayList<>();
    private static final List<FlyingArrow> arrows = new ArrayList<>();

    private static final Map<String, RigProfile> profiles = new LinkedHashMap<>();
    private static String activeName = "";

    /** Slots in a dispenser, and the middle one that a ring is built around. */
    public static final int STOCK_SLOTS = 9;
    private static final int MIDDLE_SLOT = 4;
    /** How often the machines are re-checked for scenery painted over them. */
    private static final int GUARD_TICKS = 20;
    /** No dispenser can be opened from further away than this, squared. */
    private static final double REACH_SQUARED = 49.0;

    private static long tick;
    /** Prints what the watcher is seeing, for working out why nothing fired. */
    private static boolean debug;
    /** How long a complaint about an empty rig keeps quiet for. */
    /**
     * The last handful of fire attempts and what came of each.
     *
     * <p>Written on every attempt, never throttled and never conditional on debug being on.
     * The action-bar warning is one line every three seconds, which is right for playing and
     * useless for working out why nothing happens: firing five machines at once produced one
     * message about one of them. This is the record that answers, afterwards, whether the
     * watcher saw anything at all and what became of it.
     */
    private static final Deque<String> fireLog = new ArrayDeque<>();
    private static final int FIRE_LOG_SIZE = 12;

    /**
     * Everything the mod would have said, whether or not it said it.
     *
     * <p>Quiet mode takes the messages off the screen, and a message that is merely dropped
     * is the failure this whole thing has been fighting: three evenings went on a machine
     * that did nothing and would not say why. So they are written here first and shown
     * second, and the dashboard reads them back on the other screen where nobody is looking.
     */
    private static final Deque<String> notices = new ArrayDeque<>();
    private static final int NOTICE_SIZE = 40;

    private static final int WARN_GAP_TICKS = 60;
    private static long lastWarn = Long.MIN_VALUE / 2;
    private static String lastWarnText = "";

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
     * An arrow flown by vanilla, steered so that it happens to land where it was told to.
     *
     * <p>The earlier version placed the arrow itself every tick with its velocity zeroed,
     * which made vanilla decide it had already landed: it went into its stuck-in-a-block
     * wobble while being teleported along a path, which is what looked so wrong. Now the
     * arrow gets a real velocity and vanilla does the moving, so it rises, slows, tips over
     * and falls exactly like any other arrow, and turns to face its own flight.
     *
     * <p>Landing on the mark then only needs the velocity re-solved each tick from where the
     * arrow actually is. Each correction is a fraction of a block, so nothing shows, and it
     * absorbs whatever the real physics differ from the model by.
     */
    private static final class FlyingArrow {
        final ArrowEntity entity;
        final Vec3d to;
        final int flightTicks;
        final long startTick;
        final long removeAt;

        boolean landed;
        /** Set if vanilla turns out not to be ticking it, so we move it ourselves. */
        boolean selfDriven;
        int stalledTicks;
        Vec3d lastSeen;

        FlyingArrow(ArrowEntity entity, Vec3d from, Vec3d to, long startTick) {
            this.entity = entity;
            this.to = to;
            this.startTick = startTick;

            double dx = to.x - from.x;
            double dz = to.z - from.z;
            double flat = Math.sqrt(dx * dx + dz * dz);

            // Longer shots take proportionally longer, which is what makes them arc higher.
            this.flightTicks = (int) Math.max(20.0,
                    Math.min(60.0, flat * ARROW_TICKS_PER_BLOCK));
            this.removeAt = startTick + this.flightTicks + ARROW_LINGER_TICKS;
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
        RigProfile profile = active();
        // Nothing to load in a game with no chamber: the useful thing that key can mean
        // at a blackjack table is that the hand on it is over.
        if (profile.blackjack) {
            profile.newHand();
            return;
        }
        profile.armed = true;
    }

    public static boolean isArmed() {
        RigProfile profile = active();
        return profile.armed;
    }

    public static void disarm() {
        RigProfile profile = active();
        profile.armed = false;
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

        // A rig is the set of things it could fire, so the same item twice is not a second
        // option: it is one more press of the switch key that appears to do nothing, and one
        // more slot of the dispenser holding the same block.
        for (FakeSpec existing : profile.presets) {
            if (existing.stacksWith(spec)) return;
        }

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

    /**
     * Tells the builds which positions they may never paint over.
     *
     * <p>The machines themselves, and the cell each one fires into. A build block on a
     * dispenser switches every rig off at once, because the whole mod finds its machines by
     * reading the client's world and a painted one is not a dispenser any more. A build
     * block in front of one is milder and still wrong: what a machine puts down goes there,
     * and it cannot be put into a wall.
     *
     * <p>Worked out again as it goes rather than only when a machine is watched, because
     * which way a dispenser faces cannot be read until its chunk has arrived.
     */
    private static void guardMachines() {
        ClientWorld world = MinecraftClient.getInstance().world;
        Set<BlockPos> clear = new HashSet<>();

        for (BlockPos pos : watched) {
            clear.add(pos.toImmutable());
            if (world == null || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }
            // The real block, not the painted one: once something is covering the machine
            // the painted answer is the thing we are trying to get rid of.
            BlockState state = FakeBlocks.realAt(world, pos);
            if (state.getBlock() instanceof DispenserBlock) {
                clear.add(pos.offset(state.get(DispenserBlock.FACING)));
            }
        }
        FakeBlocks.keepClear(clear);
    }

    public static boolean watch(BlockPos pos) {
        boolean added = watched.add(pos.toImmutable());
        guardMachines();
        // Laid out every time, not only the first: watching a machine is the deliberate act
        // that joins it to whichever game is running, and a machine already watched for one
        // game has to be able to join another.
        fill(pos);
        return added;
    }

    public static boolean unwatch(BlockPos pos) {
        active().stock.remove(pos);
        // Across every rig, not just this one: a name or a floor left behind by a machine no
        // longer in play would keep the next one out of the game.
        for (RigProfile profile : profiles.values()) {
            profile.sides.remove(pos);
        }
        lastTriggered.remove(pos);
        lastPowered.remove(pos);
        lastFire.remove(pos);
        boolean removed = watched.remove(pos);
        // The wall it was holding back comes home now it is nobody's machine.
        guardMachines();
        return removed;
    }

    public static void unwatchAll() {
        watched.clear();
        lastTriggered.clear();
        lastPowered.clear();
        lastFire.clear();
        pending.clear();
        guardMachines();
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

        if (!SelfFakes.enabled()) {
            recall();
            return;
        }

        tidySpawned(client);
        flyArrows();
        // Once a second is plenty: it only changes when a machine is watched, dropped, or
        // its chunk finally arrives and says which way it faces.
        if (tick % GUARD_TICKS == 0) guardMachines();
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
                String why = whyNotDispensing(world, fire.pos());
                warn(why);
                logFire(fire.pos(), "STOPPED: " + why);
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
            } else if (profile.blackjack) {
                result = card(profile, fire.pos());
            } else {
                result = profile.resultFor(fire.pos());
            }
            if (result == null) {
                warn("Rig '" + profile.name + "' has nothing to fire.");
                logFire(fire.pos(), "STOPPED: rig '" + profile.name + "' had no answer for it");
            } else {
                // Only once something has actually come out. A machine with no room in
                // front of it used to take the item off its count anyway, so its stock
                // drained while nothing ever appeared.
                boolean out = profile.placeOutput
                        ? stand(world, fire.pos(), result)
                        : spawn(world, fire.pos(), result);

                // Take it out of what the dispenser looks like it is holding, so opening
                // the thing afterwards agrees with what everyone just watched come out.
                if (out) deplete(profile, fire.pos(), result);
                logFire(fire.pos(), out ? "fired " + describeSpec(result)
                        : "STOPPED: " + describeSpec(result) + " could not come out");
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
        // The side first: a round drawn before any machine had one has no winner to give
        // the high number to, so both machines take the low one.
        //
        // A machine going off during the game is the plainest sign there is that it belongs
        // to it, so this is one of the places allowed to hand a side out. What could not be
        // allowed was laying the rig out over every watched dispenser at once, which gave
        // the sides to whichever machines had been watched earliest.
        String side = profile.sideAt(pos, watched);
        if (side.isEmpty()) {
            warn("Both sides are taken, so " + text(pos) + " is not in the paper game.");
            return null;
        }

        // Added to the older side, never subtracted from the newer: the no-round-yet marker
        // is Long.MIN_VALUE, and subtracting that overflows to a huge negative, which reads
        // as "still the same round" forever and leaves every draw on its starting value.
        if (tick > profile.roundTick + ROUND_TICKS) profile.startRound(random, tick);

        boolean wins = !profile.roundWinner.isEmpty()
                && profile.roundWinner.equalsIgnoreCase(side);
        int number = wins ? profile.highRoll : profile.lowRoll;

        Item slip = SelfFakes.lookupItem(profile.slipItem);
        if (slip == null) return null;

        // Built to match the laid-out slip exactly, so it empties that slot on the way out.
        return new FakeSpec(slip, 1, "", null, null, profile.slipName(number, side));
    }

    /**
     * The card a deal produces, and whose hand it joins.
     *
     * <p>Which side is being dealt to decides everything, so it is worked out first: the
     * machine's own side when it has one, the side named by hand otherwise. A table with two
     * boxes needs nothing said; a table with one has the keys to say it.
     */
    private static FakeSpec card(RigProfile profile, BlockPos pos) {
        Item slip = SelfFakes.lookupItem(profile.slipItem);
        if (slip == null) return null;

        String side = profile.sideOf(pos);
        if (side.isEmpty()) side = profile.dealTo;
        if (side.isEmpty()) side = profile.sideAt(pos, watched);
        if (side.isEmpty()) {
            warn("Both boxes are taken, so " + text(pos) + " is not at this table."
                    + " Press [ or ] to say whose card it deals, or /fake dispenser parts.");
            return null;
        }

        int number = profile.chooseCard(side, random);
        profile.handFor(side).add(number);

        note("dealt " + RigProfile.cardName(number) + " to " + side + ", now on "
                + profile.totalFor(side));
        // Built to match the laid-out card exactly, so it empties that slot on the way out.
        return new FakeSpec(slip, 1, "", null, null, RigProfile.cardName(number));
    }

    /** Says whose card the next deal is, for a table played on one box. */
    public static String dealTo(String side) {
        active().dealTo = side;
        SelfFakes.save();
        return side;
    }

    /** Clears both hands, so the next card starts a new one. */
    public static void newHand() {
        active().newHand();
    }

    /** Steps who the paper game is rigged for, and says who that is now. */
    public static String cycleWinner() {
        return cycleWinner(1);
    }

    public static String cycleWinner(int delta) {
        String winner = active().cycleWinner(delta);
        SelfFakes.save();
        return winner;
    }

    /**
     * Why a position the mod thinks is a machine is not answering as one.
     *
     * <p>Three different problems used to share one sentence about the block having moved,
     * and only one of them was that. The one worth naming is a build painted over it: it is
     * the mod standing on its own foot, it takes every rig down at once, and the fix is one
     * key press.
     */
    private static String whyNotDispensing(ClientWorld world, BlockPos pos) {
        if (world == null || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return "Too far from the dispenser at " + text(pos) + " - go closer.";
        }
        if (FakeBlocks.fakeAt(pos) != null) {
            return "One of your builds is painted over the dispenser at " + text(pos)
                    + ". Look at it and press B to open a hole.";
        }
        return "No dispenser at " + text(pos) + " - moved, or broken.";
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
        logFire(key, "spotted by " + why);
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
        logFire(key, "fired by hand");
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
        return fill(pos, true);
    }

    /**
     * @param join whether a machine with no part in this game may be given one.
     *
     * <p>Watched dispensers belong to every rig at once, so laying a game out over all of
     * them would hand its floors and sides to whichever machines happened to be watched
     * first -- the roulette dropper and the coin flip included, leaving the machines the
     * game is actually played on with no part in it. Joining a machine to a game is
     * therefore something only a deliberate act does: watching it, or filling that one.
     */
    public static boolean fill(BlockPos pos, boolean join) {
        RigProfile profile = active();
        Map<Integer, FakeSpec> slots = new LinkedHashMap<>();

        if (profile.roulette) {
            // Loaded here rather than only on load: a rig that lays out nothing and fires
            // nothing reads as the whole game being broken, and there is no arrangement
            // where an empty roulette rig is what somebody meant.
            if (profile.bullet == null) profile.bullet = defaultSpec("end_crystal");
            if (profile.blank == null) profile.blank = defaultSpec("obsidian");

            for (int slot = 0; slot < STOCK_SLOTS; slot++) {
                FakeSpec spec = slot == MIDDLE_SLOT ? profile.bullet : profile.blank;
                // Each slot needs its own copy: they empty one at a time.
                if (spec != null) slots.put(slot, spec.withCount(1));
            }
        } else if (profile.blackjack) {
            // A shoe: one number per slot, two of each. That is what nine slots hold, and
            // what a shoe looks like through the glass. The box also takes a side here, the
            // way the paper game's machines do, since laying one out is a deliberate act.
            String side = join ? profile.sideAt(pos, watched) : profile.sideOf(pos);
            Item slip = side.isEmpty() ? null : SelfFakes.lookupItem(profile.slipItem);
            if (slip != null) {
                int count = Math.min(profile.cards, STOCK_SLOTS);
                for (int slot = 0; slot < count; slot++) {
                    slots.put(slot, new FakeSpec(slip, profile.cardEach, "", null, null,
                            RigProfile.cardName(slot + 1)));
                }
            }
        } else if (profile.paper) {
            // One slip per number, all named for the side this machine plays. A machine
            // with no side is one of the other games' and is left showing its real self.
            String side = join ? profile.sideAt(pos, watched) : profile.sideOf(pos);
            Item slip = side.isEmpty() ? null : SelfFakes.lookupItem(profile.slipItem);

            if (slip != null) {
                int count = Math.min(profile.numbers, STOCK_SLOTS);
                for (int slot = 0; slot < count; slot++) {
                    slots.put(slot, new FakeSpec(slip, 1, "", null, null,
                            profile.slipName(slot + 1, side)));
                }
            }
        } else if (profile.mix) {
            // The spread the game is played on, laid into the nine slots: four of one, four
            // of another, one of the prize. The rarest item takes the middle so the odds can
            // be read through the glass without counting, and the rest fill in round it.
            int rare = profile.rarestPreset();

            List<Integer> free = new ArrayList<>();
            for (int slot = 0; slot < STOCK_SLOTS; slot++) {
                if (rare < 0 || slot != MIDDLE_SLOT) free.add(slot);
            }

            int next = 0;
            for (int i = 0; i < profile.presets.size(); i++) {
                FakeSpec spec = profile.presets.get(i);
                for (int held = 0; held < profile.mixCount(i); held++) {
                    int slot;
                    if (i == rare && held == 0) {
                        slot = MIDDLE_SLOT;
                    } else if (next < free.size()) {
                        slot = free.get(next++);
                    } else {
                        // More items than the machine has room for. What is already in is
                        // the spread as far as it goes; inventing a tenth slot is not.
                        break;
                    }
                    // Each slot its own copy: they empty one at a time as it is played.
                    slots.put(slot, spec.withCount(1));
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

    /**
     * Why laying a machine out produced nothing.
     *
     * <p>"The rig has nothing to put in" was the answer to all of it, and for the two games
     * that hand out parts it is the wrong answer: the rig has plenty, this machine is simply
     * not in the game. That reads as the whole game being broken rather than as one machine
     * needing a side, which is a five-second fix nobody could find.
     */
    public static String whyNotFilled(BlockPos pos) {
        RigProfile profile = active();

        if (profile.paper) {
            if (profile.sideOf(pos).isEmpty()) {
                return "Both sides are already taken " + sideOwners()
                        + ". Look at this machine and run /fake paper side Player"
                        + " (or Host), or /fake dispenser parts to hand them out again.";
            }
            return "The paper game makes slips from '" + profile.slipItem
                    + "', which is not a real item. /fake paper item paper";
        }
        if (profile.blackjack) {
            if (profile.sideOf(pos).isEmpty()) {
                return "Both boxes are already taken " + sideOwners()
                        + ". /fake dispenser parts hands them out again.";
            }
            return "The shoe is made of '" + profile.slipItem
                    + "', which is not a real item. /fake blackjack item paper";
        }
        return "Rig '" + profile.name + "' has nothing to put in."
                + " Add one with /fake preset add <item>.";
    }

    /** Which machine holds each side of the paper game, for saying why a third cannot join. */
    private static String sideOwners() {
        RigProfile profile = active();
        StringBuilder text = new StringBuilder();

        for (Map.Entry<BlockPos, String> entry : profile.sides.entrySet()) {
            if (!watched.contains(entry.getKey())) continue;
            text.append(text.length() == 0 ? "(" : ", ")
                    .append(entry.getValue()).append(" at ").append(text(entry.getKey()));
        }
        return text.length() == 0 ? "(by nothing still watched)" : text.append(")").toString();
    }

    /** Fills every watched dispenser that has not been laid out yet. */
    public static int fillEmptyWatched() {
        RigProfile profile = active();
        int filled = 0;
        for (BlockPos pos : watched) {
            // Emptied counts as needing it: a dispenser played out to its last slot leaves
            // the key behind with nothing under it, and skipping that is why a rig switched
            // back to came up bare.
            Map<Integer, FakeSpec> slots = profile.stockAt(pos);
            if (slots != null && !slots.isEmpty()) continue;
            if (fill(pos, false)) filled++;
        }
        return filled;
    }

    /** Lays every watched dispenser out again, including ones already emptied. */
    public static int refillWatched() {
        int filled = 0;
        for (BlockPos pos : watched) {
            if (fill(pos, false)) filled++;
        }
        return filled;
    }

    /** @return how many machines are set up for the active game, or -1 if it has no parts. */
    public static int partsInGame() {
        RigProfile profile = active();
        if (profile.hasSides()) return profile.sideNames().size();
        return -1;
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

        if (!SelfFakes.enabled()) {
            lines.add("EVERYTHING IS OFF. Nothing is being faked.");
        }

        lines.add("Rig '" + profile.name + "'"
                + (profile.blackjack ? ", blackjack" : "")
                + (profile.mix ? ", 45/45/10" : "")
                + (profile.paper ? ", paper" : "")
                + (profile.roulette ? ", roulette" : "")
                + (profile.roulette && profile.manualTrigger ? ", manual" : "")
                + (profile.armed ? ", ARMED" : ""));
        // The one pair of keys whose meaning moves with the rig, so the answer to "what
        // does F do right now" is in the same place as everything else about the rig.
        lines.add("  F " + profile.forwardLabel() + ", R " + profile.backLabel());
        if (profile.blackjack) {
            lines.add("  a shoe of " + profile.cardEach + "x each of A-" + profile.cards
                    + ", rigged for "
                    + (profile.winner.isEmpty() ? "chance" : profile.winner));
            for (String side : profile.sideNames()) {
                lines.add("    " + side + " holds " + profile.handFor(side)
                        + " = " + profile.totalFor(side));
            }
        }
        if (profile.paper) {
            lines.add("  rigged for: "
                    + (profile.winner.isEmpty() ? "chance" : profile.winner)
                    + ", sides " + profile.sideNames());
            lines.add("  draws go to " + (profile.house.isEmpty() ? "nobody" : profile.house)
                    + ", drawn " + profile.tieChance + "% of the rounds it wins");
        }
        if (profile.mix) {
            StringBuilder spread = new StringBuilder("  holds ");
            for (int i = 0; i < profile.presets.size(); i++) {
                if (i > 0) spread.append(", ");
                spread.append(profile.mixCount(i)).append("x ")
                        .append(profile.presets.get(i).describe())
                        .append(" (").append(profile.mixChance(i)).append("%, pays ")
                        .append(profile.mixPayout(i)).append("x)")
                        // Which of the three is rigged, without having to turn the action
                        // bar readback on. Three items on one key is one too many to keep
                        // count of, and this is the answer that does not show on screen.
                        .append(i == profile.presetIndex() ? " <- RIGGED" : "");
            }
            lines.add(spread.toString());
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
                    line.append(FakeBlocks.fakeAt(pos) != null
                            ? "COVERED by one of your builds - press B on it"
                            : "not a dispenser any more - rewatch it");
                } else {
                    line.append("ok, ")
                            .append(world.isReceivingRedstonePower(pos)
                                    || world.isReceivingRedstonePower(pos.up())
                                    ? "powered now" : "unpowered");
                    if (profile.paper) {
                        String side = profile.sides.get(pos);
                        line.append(side == null ? ", not in this game" : ", plays " + side);
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

    /** What the open dispenser is faking in one slot, or null. */
    public static FakeSpec stockAt(ClientPlayerEntity player, int slot) {
        Map<Integer, FakeSpec> slots = openStock(player);
        return slots == null ? null : slots.get(slot);
    }

    /**
     * Puts a fake into one slot of the open dispenser, or clears it.
     *
     * <p>For moving things about by hand. A machine that has never been laid out gets a
     * layout the moment something is put in it, which is how a dispenser fills up.
     */
    public static boolean setStock(ClientPlayerEntity player, int slot, FakeSpec spec) {
        BlockPos pos = targetDispenser(player);
        if (pos == null) return false;

        Map<Integer, FakeSpec> slots = active().stock
                .computeIfAbsent(pos.toImmutable(), key -> new LinkedHashMap<>());

        if (spec == null) {
            slots.remove(slot);
        } else {
            slots.put(slot, spec);
        }

        SelfFakes.repaintContainer();
        SelfFakes.save();
        return true;
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

    /**
     * Whether a machine has an answer of its own rather than the rig's cycled one.
     *
     * <p>Worth being able to ask, because it is the only thing that turns a coin flip's
     * one-of-each layout into nine of a single block, and from in front of the dispenser
     * the two look like the same key having done something different each time.
     */
    /**
     * How long a block a machine put down should take to break, in ticks.
     *
     * <p>Zero for anything else, which leaves the block's own hardness to decide as it always
     * has. Only the answer standing in front of a machine gets a time of its own.
     */
    public static int placedBreakTicks(BlockPos pos) {
        if (!FakeBlocks.isPlaced(pos)) return 0;

        double seconds = active().breakSeconds;
        return seconds <= 0 ? 0 : Math.max(1, (int) Math.round(seconds * 20.0));
    }

    public static boolean hasFixedAnswer(BlockPos pos) {
        return active().perDispenser.containsKey(pos);
    }

    private static FakeSpec defaultSpec(String id) {
        Item item = SelfFakes.lookupItem(id);
        return item == null ? null : new FakeSpec(item, 1, "");
    }

    /**
     * Why the active rig can produce nothing at all, or null when it can.
     *
     * <p>Asked of the rig rather than of a machine. The games that hand out parts report per
     * machine, because there the fault is one machine's; roulette and the coin flip have no
     * parts, so when they fire nothing the fault is the rig's and there was nowhere that
     * said so -- the doctor's answer line only ever looked at presets, which roulette does
     * not use, and so called a roulette rig with no blank loaded fine.
     */
    public static String noAnswer() {
        RigProfile profile = active();

        if (profile.roulette) {
            if (profile.bullet == null && profile.blank == null) {
                return "NOTHING LOADED  <-- /fake roulette bullet end_crystal,"
                        + " /fake roulette blank obsidian";
            }
            if (profile.blank == null) {
                return "no blank loaded, so every shot but the armed one fires nothing"
                        + "  <-- /fake roulette blank obsidian";
            }
            return null;
        }
        // Their machines answer for themselves, one line each, further down.
        if (profile.hasSides()) return null;

        if (profile.presets.isEmpty()) return "NO ITEMS  <-- /fake preset add <item>";
        if (profile.selected() == null) return "nothing selected  <-- press F";
        return null;
    }

    /**
     * What a machine would fire if it went off right now, spending nothing.
     *
     * <p>The rigging is invisible until something comes out, so "is it rigged correctly" and
     * "does anything come out at all" have always been the same question asked twice. This
     * answers the first on its own: a machine that would fire the right thing and does not is
     * a different fault from one that has nothing to fire.
     */
    public static String preview(BlockPos pos) {
        RigProfile profile = active();

        if (profile.roulette) {
            if (profile.armed) return describeSpec(profile.bullet) + " - ARMED";
            if (profile.manualTrigger) {
                return describeSpec(profile.blank) + " (press ; for the loaded one)";
            }
            int next = profile.shot + 1 > profile.chambers ? 1 : profile.shot + 1;
            return describeSpec(next == profile.bulletAt ? profile.bullet : profile.blank)
                    + " (shot " + next + " of " + profile.chambers + ")";
        }
        if (profile.paper) {
            String side = profile.sideOf(pos);
            if (side.isEmpty()) {
                return "NOTHING - not in the paper game; sides held " + sideOwners()
                        + ". /fake dispenser parts hands them out again.";
            }
            return "a " + profile.slipItem + " for " + side + ", "
                    + (profile.winner.isEmpty() ? "winner left to chance"
                            : "rigged for " + profile.winner);
        }
        if (profile.blackjack) {
            String side = profile.sideOf(pos);
            if (side.isEmpty()) side = profile.dealTo;
            if (side.isEmpty()) return "NOTHING - this box is not at the table";

            return "a card for " + side + " (on " + profile.totalFor(side) + "), rigged for "
                    + (profile.winner.isEmpty() ? "chance" : profile.winner);
        }
        return describeSpec(profile.resultFor(pos));
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
    /** Records what happened to one fire attempt, for the doctor to read back. */
    private static void logFire(BlockPos pos, String outcome) {
        while (fireLog.size() >= FIRE_LOG_SIZE) fireLog.removeFirst();
        fireLog.addLast("t" + tick + "  " + text(pos) + "  " + outcome);
    }

    /** The fire log, oldest first. */
    public static List<String> fireLog() {
        return new ArrayList<>(fireLog);
    }

    /** Records something worth reading, shown on screen or not. */
    public static void notice(String message) {
        while (notices.size() >= NOTICE_SIZE) notices.removeFirst();
        notices.addLast("t" + tick + "  " + message);
    }

    /** What the mod has had to say lately, oldest first. */
    public static List<String> notices() {
        return new ArrayList<>(notices);
    }

    private static void warn(String message) {
        // Kept before anything that might swallow it. The throttle and the quiet switch both
        // decide whether it reaches the screen; neither may decide whether it happened.
        notice(message);
        if (SelfFakes.quiet()) return;

        // Throttled per message rather than outright. Firing five machines at once resolves
        // all five in one tick, and a flat throttle showed the first and threw the other
        // four away -- so four different faults could hide behind one line about a fifth.
        // A repeat of the same message is still held back; that is what the throttle is for.
        if (message.equals(lastWarnText) && tick - lastWarn < WARN_GAP_TICKS) return;
        lastWarn = tick;
        lastWarnText = message;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        client.player.sendMessage(Text.literal(message).formatted(Formatting.GRAY), true);
    }

    private static boolean spawn(ClientWorld world, BlockPos pos, FakeSpec result) {
        BlockState state = world.getBlockState(pos);
        // Was silent, and this is the last gate anything passes on its way out: a fire that
        // got all the way here and then produced nothing looked exactly like a mod that was
        // not loaded, which is the report that has come in three times now.
        if (!(state.getBlock() instanceof DispenserBlock)) {
            warn(whyNotDispensing(world, pos));
            return false;
        }

        Direction facing = state.get(DispenserBlock.FACING);
        double x = pos.getX() + 0.5 + facing.getOffsetX() * 0.7;
        double y = pos.getY() + 0.35 + facing.getOffsetY() * 0.7;
        double z = pos.getZ() + 0.5 + facing.getOffsetZ() * 0.7;

        ItemEntity entity = new ItemEntity(world, x, y, z, result.stack().copy());
        entity.setId(nextId());
        entity.setPickupDelayInfinite();
        // The renderer draws between where a thing was last tick and where it is now. A
        // freshly built entity has no last tick, so its first frame is drawn from wherever
        // those fields happened to start -- usually the world origin, as a streak across
        // the map. Setting both ends to the same point is what a spawn packet would do.
        entity.refreshPositionAndAngles(x, y, z, 0.0F, 0.0F);

        var random = world.getRandom();
        double spread = 0.06;
        entity.setVelocity(
                facing.getOffsetX() * 0.22 + (random.nextDouble() - 0.5) * spread,
                facing.getOffsetY() * 0.22 + 0.10 + (random.nextDouble() - 0.5) * spread,
                facing.getOffsetZ() * 0.22 + (random.nextDouble() - 0.5) * spread);
        entity.velocityDirty = true;

        world.addEntity(entity);
        spawned.add(new SpawnedItem(entity, result, tick));
        return true;
    }

    /**
     * Puts the answer down in front of the machine, the way a dispenser places a shulker box.
     *
     * <p>One per machine: the next round takes the last one away first, which is what
     * clearing the floor between rounds looks like.
     */
    private static boolean stand(ClientWorld world, BlockPos pos, FakeSpec result) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof DispenserBlock)) {
            warn(whyNotDispensing(world, pos));
            return false;
        }

        BlockPos was = standing.remove(pos);
        if (was != null) FakeBlocks.unplace(was);

        Block block = SelfFakes.lookupBlock(
                Registries.ITEM.getId(result.item).getPath());
        if (block == null) {
            warn("A " + result.describe() + " is not something a machine can place.");
            return false;
        }

        // Where a real dispenser would put it: the block it faces.
        BlockPos target = pos.offset(state.get(DispenserBlock.FACING));
        if (!FakeBlocks.place(target, block.getDefaultState())) {
            warn("No room in front of " + text(pos) + " to put it down.");
            return false;
        }
        standing.put(pos.toImmutable(), target);
        return true;
    }

    /** Clears the answers standing in front of the machines. */
    public static int clearStanding() {
        int gone = standing.size();
        for (BlockPos pos : standing.values()) FakeBlocks.unplace(pos);
        standing.clear();
        return gone;
    }

    private static void flyArrows() {
        Iterator<FlyingArrow> iterator = arrows.iterator();

        while (iterator.hasNext()) {
            FlyingArrow arrow = iterator.next();
            ArrowEntity entity = arrow.entity;

            if (entity.isRemoved()) {
                iterator.remove();
                continue;
            }
            if (tick >= arrow.removeAt) {
                entity.discard();
                iterator.remove();
                continue;
            }

            int remaining = arrow.flightTicks - (int) (tick - arrow.startTick);
            if (remaining <= 0) {
                if (!arrow.landed) land(arrow);
                continue;
            }
            steer(arrow, remaining);
        }
    }

    /** Re-aims the arrow from wherever it has got to, and moves it if nothing else will. */
    private static void steer(FlyingArrow arrow, int remaining) {
        ArrowEntity entity = arrow.entity;
        Vec3d here = new Vec3d(entity.getX(), entity.getY(), entity.getZ());

        if (arrow.lastSeen != null && here.squaredDistanceTo(arrow.lastSeen) < 1.0E-6) {
            if (++arrow.stalledTicks >= ARROW_STALL_TICKS) arrow.selfDriven = true;
        } else {
            arrow.stalledTicks = 0;
        }

        Vec3d velocity = launchVelocity(here, arrow.to, remaining);
        entity.setVelocity(velocity);
        entity.velocityDirty = true;

        if (arrow.selfDriven) {
            entity.setPosition(here.x + velocity.x, here.y + velocity.y, here.z + velocity.z);
        }
        arrow.lastSeen = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
    }

    /** Stops it dead on the mark, which is how a stuck arrow reads. */
    private static void land(FlyingArrow arrow) {
        arrow.landed = true;
        // Both ends of the interpolation, or the last frame is drawn as a jump to the mark.
        arrow.entity.refreshPositionAndAngles(arrow.to.x, arrow.to.y, arrow.to.z,
                arrow.entity.getYaw(), arrow.entity.getPitch());
        arrow.entity.setVelocity(Vec3d.ZERO);
        arrow.entity.setNoGravity(true);
        arrow.entity.velocityDirty = true;
    }

    /**
     * The velocity that reaches a point in a given number of ticks.
     *
     * <p>An arrow keeps 99% of its speed each tick and then has gravity taken off it, so what
     * it covers is a geometric sum rather than a straight line. Solving that sum gives exactly
     * one velocity per flight time, and that is what puts the arc on the mark.
     */
    private static Vec3d launchVelocity(Vec3d from, Vec3d to, int ticks) {
        double travel = ARROW_DRAG == 1.0 ? ticks
                : ARROW_DRAG * (1.0 - Math.pow(ARROW_DRAG, ticks)) / (1.0 - ARROW_DRAG);
        double fall = ARROW_GRAVITY / (1.0 - ARROW_DRAG);

        return new Vec3d(
                (to.x - from.x) / travel,
                (to.y - from.y + fall * ticks) / travel - fall,
                (to.z - from.z) / travel);
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
        arrow.refreshPositionAndAngles(from.x, from.y, from.z, 0.0F, 0.0F);
        // Straight through everything. It must not stick in the block it came out of, and a
        // client-side arrow catching a real player would show a hit that never happened.
        arrow.noClip = true;

        FlyingArrow flight = new FlyingArrow(arrow, from, target, tick);
        Vec3d launch = launchVelocity(from, target, flight.flightTicks);
        arrow.setVelocity(launch);
        arrow.velocityDirty = true;

        // Aim it for the first frame, using vanilla's own convention: yaw from atan2(x, z),
        // pitch from atan2(y, flat), neither negated. After this it turns itself.
        double flat = Math.sqrt(launch.x * launch.x + launch.z * launch.z);
        arrow.setYaw((float) (MathHelper.atan2(launch.x, launch.z) * 180.0 / Math.PI));
        arrow.setPitch((float) (MathHelper.atan2(launch.y, flat) * 180.0 / Math.PI));

        world.addEntity(arrow);
        arrows.add(flight);
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

                // From here we place it ourselves, so vanilla has to stop moving it. Left
                // as it was, gravity and the throw carried it one way each tick and we
                // snapped it back the other, which is a shudder all the way in.
                item.entity.setVelocity(Vec3d.ZERO);
                item.entity.setNoGravity(true);
                item.entity.noClip = true;
                item.entity.velocityDirty = true;
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

    /**
     * Takes back everything already in the air.
     *
     * <p>Switched off has to be immediate: an item still arcing out of a dispenser is the
     * one thing that would give it away a second after the switch. The edge memory goes too,
     * so that coming back on does not fire on a dispenser that went off while it was off.
     */
    private static void recall() {
        for (SpawnedItem item : spawned) item.entity.discard();
        spawned.clear();

        for (FlyingArrow arrow : arrows) arrow.entity.discard();
        arrows.clear();

        pending.clear();
        lastTriggered.clear();
        lastPowered.clear();
        clearStanding();
    }

    /** Leaving a world takes the client entities with it. */
    public static void reset() {
        arrows.clear();
        spawned.clear();
        pending.clear();
        lastTriggered.clear();
        lastPowered.clear();
        lastFire.clear();
        standing.clear();
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

            // Written for every rig. A coin flip played with shulker boxes wants its answer
            // standing on the ground as much as anything else does, and the setting once
            // lived inside one game's own block, which threw it away with that game.
            json.addProperty("place", profile.placeOutput);
            json.addProperty("breakSeconds", profile.breakSeconds);

            // Written whether it is on or off. Writing it only when on made a rig that
            // had been turned off look identical to one that had never heard of the game,
            // and there is no way to tell those apart on the way back in.
            if (profile.blackjack) {
                JsonObject cards = new JsonObject();
                cards.addProperty("cards", profile.cards);
                cards.addProperty("each", profile.cardEach);
                cards.addProperty("item", profile.slipItem);
                cards.addProperty("winner", profile.winner);

                JsonObject sides = new JsonObject();
                for (Map.Entry<BlockPos, String> entry : profile.sides.entrySet()) {
                    sides.addProperty(writePos(entry.getKey()), entry.getValue());
                }
                cards.add("sides", sides);
                json.add("blackjack", cards);
            }

            if (profile.paper || profile.name.equals("paper")) {
                JsonObject paper = new JsonObject();
                paper.addProperty("on", profile.paper);
                paper.addProperty("winner", profile.winner);
                paper.addProperty("item", profile.slipItem);
                paper.addProperty("numbers", profile.numbers);
                paper.addProperty("house", profile.house);
                paper.addProperty("ties", profile.tieChance);

                JsonObject sides = new JsonObject();
                for (Map.Entry<BlockPos, String> entry : profile.sides.entrySet()) {
                    sides.addProperty(writePos(entry.getKey()), entry.getValue());
                }
                paper.add("sides", sides);
                json.add("paper", paper);
            }

            if (profile.mix) {
                JsonObject mix = new JsonObject();
                JsonArray counts = new JsonArray();
                JsonArray payouts = new JsonArray();
                for (int i = 0; i < profile.presets.size(); i++) {
                    counts.add(profile.mixCount(i));
                    payouts.add(profile.mixPayout(i));
                }
                mix.add("counts", counts);
                mix.add("payouts", payouts);
                json.add("mix", mix);
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
        paperKnown = false;
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
        for (RigProfile profile : profiles.values()) repair(profile);
        if (!profiles.containsKey(activeName)) {
            activeName = profiles.keySet().iterator().next();
        }
        // The machines are back, so the builds have to be told to keep off them again.
        guardMachines();
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

        // A rig written before this existed carried the setting inside a game block that is
        // no longer read, so it comes back with placing off and one command away from having
        // it again.
        if (json.has("place")) profile.placeOutput = json.get("place").getAsBoolean();
        if (json.has("breakSeconds")) {
            profile.breakSeconds = json.get("breakSeconds").getAsDouble();
        }

        if (json.has("mix")) {
            JsonObject mix = json.getAsJsonObject("mix");
            profile.mix = true;
            if (mix.has("counts")) {
                for (JsonElement element : mix.getAsJsonArray("counts")) {
                    profile.mixCounts.add(element.getAsInt());
                }
            }
            if (mix.has("payouts")) {
                for (JsonElement element : mix.getAsJsonArray("payouts")) {
                    profile.mixPayouts.add(element.getAsInt());
                }
            }
        }

        if (json.has("blackjack")) {
            JsonObject cards = json.getAsJsonObject("blackjack");
            profile.blackjack = true;
            if (cards.has("cards")) profile.cards = cards.get("cards").getAsInt();
            if (cards.has("each")) profile.cardEach = cards.get("each").getAsInt();
            if (cards.has("item")) profile.slipItem = cards.get("item").getAsString();
            if (cards.has("winner")) profile.winner = cards.get("winner").getAsString();

            if (cards.has("sides")) {
                for (Map.Entry<String, JsonElement> entry
                        : cards.getAsJsonObject("sides").entrySet()) {
                    BlockPos pos = readPos(entry.getKey());
                    if (pos != null) profile.sides.put(pos, entry.getValue().getAsString());
                }
            }
            profile.tidyCards();
        }

        if (json.has("paper")) {
            if (profile.name.equals("paper")) paperKnown = true;
            JsonObject paper = json.getAsJsonObject("paper");
            // Older files only wrote this block when the game was on, so a block with no
            // word either way means on.
            profile.paper = !paper.has("on") || paper.get("on").getAsBoolean();
            if (paper.has("winner")) profile.winner = paper.get("winner").getAsString();
            if (paper.has("item")) profile.slipItem = paper.get("item").getAsString();
            if (paper.has("numbers")) profile.numbers = paper.get("numbers").getAsInt();
            if (paper.has("house")) profile.house = paper.get("house").getAsString();
            // Read from "ties", not the older "tieChance": levelling was on by default for
            // a while, and nobody chose that, so those files start again from off.
            if (paper.has("ties")) profile.tieChance = paper.get("ties").getAsInt();

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
    /** Whether the file said either way about the paper rig, rather than staying silent. */
    private static boolean paperKnown;

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
            // A file written before the paper game existed holds the rig's name and nothing
            // that says whether the game is on, so it gets turned on. The old test also
            // asked that the rig carry no presets, which meant a couple of items added to
            // it by accident quietly left it laying out coins instead of slips.
            RigProfile paper = profiles.get("paper");
            if (!paper.roulette && !paperKnown) paper.paper = true;
        }
        if (needsSeeding("blackjack")) {
            // A shoe of numbered slips: two of every number, one number per slot, which is
            // exactly what a nine-slot machine holds. What comes out is named or left to
            // chance, and the result keys walk the numbers.
            RigProfile cards = new RigProfile("blackjack");
            cards.blackjack = true;
            cards.tidyCards();
            profiles.put(cards.name, cards);
        }
        if (needsSeeding("454510")) {
            // 45/45/10: four diamonds, four emeralds and one crystal in the one machine.
            // Guess the kind and it pays double, guess the crystal and it pays four times.
            // No mode of its own beyond the layout -- what comes out is whichever item is
            // selected, so the result key is already the rig.
            RigProfile odds = new RigProfile("454510");
            odds.mix = true;

            String[] items = { "diamond", "emerald", "end_crystal" };
            int[] counts = { 4, 4, 1 };
            int[] payouts = { 2, 2, 4 };

            for (int i = 0; i < items.length; i++) {
                Item item = SelfFakes.lookupItem(items[i]);
                if (item == null) continue;
                odds.presets.add(new FakeSpec(item, 1, ""));
                odds.mixCounts.add(counts[i]);
                odds.mixPayouts.add(payouts[i]);
            }
            odds.setPresetIndex(odds.presets.isEmpty() ? -1 : 0);
            profiles.put(odds.name, odds);
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
     * Puts right the things a rig cannot work without.
     *
     * <p>A file can be older than the code that reads it, or carry the marks of a command
     * that has since changed, and every one of these leaves a rig that looks broken rather
     * than one that says what is wrong.
     */
    private static void repair(RigProfile profile) {
        // Duplicates read back as a rig with more options than it has: the switch key
        // appears to stick, and a coin flip lays out three of one side and one of the other.
        List<FakeSpec> unique = new ArrayList<>();
        for (FakeSpec spec : profile.presets) {
            boolean seen = false;
            for (FakeSpec kept : unique) {
                if (kept.stacksWith(spec)) seen = true;
            }
            if (!seen) unique.add(spec);
        }
        if (unique.size() != profile.presets.size()) {
            profile.presets.clear();
            profile.presets.addAll(unique);
        }
        if (profile.presetIndex() >= profile.presets.size()) {
            profile.setPresetIndex(profile.presets.isEmpty() ? -1 : 0);
        }

        // A rig named for a game, carrying no mode and no presets, can fire nothing at all:
        // the mode branch is skipped and the cycled branch it falls to has nothing to cycle.
        // Nobody sets that up on purpose, so it is put back -- the same repair the paper rig
        // has always had. A rig with presets has been given another job and is left as it is.
        if (profile.name.equals("roulette") && !profile.roulette && !profile.paper
                && !profile.blackjack && !profile.mix && profile.presets.isEmpty()) {
            profile.roulette = true;
            profile.manualTrigger = true;
            profile.chambers = 9;
        }

        // A roulette rig with nothing loaded fires nothing and lays out nothing, which is
        // indistinguishable from the game being broken.
        if (profile.roulette) {
            if (profile.bullet == null) {
                Item crystal = SelfFakes.lookupItem("end_crystal");
                if (crystal != null) profile.bullet = new FakeSpec(crystal, 1, "");
            }
            if (profile.blank == null) {
                Item obsidian = SelfFakes.lookupItem("obsidian");
                if (obsidian != null) profile.blank = new FakeSpec(obsidian, 1, "");
            }
            profile.tidyRoulette();
        }

        if (profile.blackjack) {
            if (SelfFakes.lookupItem(profile.slipItem) == null) profile.slipItem = "paper";
            profile.tidyCards();
            profile.pruneSides(watched);
            if (!profile.winner.isEmpty() && !profile.sideNames().isEmpty()
                    && !profile.hasSide(profile.winner)) {
                profile.winner = "";
            }
            // The numbers are the game; anything in the preset list belongs to another one.
            profile.presets.clear();
            profile.setPresetIndex(-1);
        }

        if (profile.paper) {
            if (SelfFakes.lookupItem(profile.slipItem) == null) profile.slipItem = "paper";
            profile.tieChance = Math.max(0, Math.min(100, profile.tieChance));
            // Presets belong to the coin-flip shape and mean nothing here. Dropping them
            // stops the switch keys cycling through items this game never fires.
            profile.presets.clear();
            profile.setPresetIndex(-1);
            // A winner nobody answers to leaves every round a draw, so drop it back to
            // chance -- but only where there are sides to check it against, since none are
            // known until the machines have been laid out.
            if (!profile.winner.isEmpty() && !profile.sideNames().isEmpty()
                    && !profile.hasSide(profile.winner)) {
                profile.winner = "";
            }
            // Sides held by machines that are gone push the real ones out of the game.
            profile.pruneSides(watched);
        }
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
