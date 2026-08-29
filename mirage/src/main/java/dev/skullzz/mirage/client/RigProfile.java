package dev.skullzz.mirage.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.Set;
import java.util.List;
import java.util.Map;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * One game's worth of rigging: the items it uses, and what each dispenser fires.
 *
 * <p>A coin-flip game wants two items cycled between. A game where two dispensers each fire
 * something and the higher one wins instead wants a fixed answer per dispenser, so both are
 * supported and the per-dispenser answer wins when there is one.
 */
public final class RigProfile {
    public final String name;

    /** Items cycled through with the keybinds. */
    public final List<FakeSpec> presets = new ArrayList<>();
    /** What a particular dispenser fires, overriding the cycled item. */
    public final Map<BlockPos, FakeSpec> perDispenser = new LinkedHashMap<>();

    /**
     * What each dispenser appears to hold: position to (slot to fake).
     *
     * <p>Kept per dispenser rather than per container size, because two dispensers in the
     * same game hold different things and both have to empty separately.
     */
    public final Map<BlockPos, Map<Integer, FakeSpec>> stock = new LinkedHashMap<>();

    /** Where this game's fake arrow lands, if it uses one. */
    public Vec3d arrowTarget;

    /** The sides a paper game is set up with, in the order they were watched. */
    public static final String[] DEFAULT_SIDES = { "Player", "Host" };

    /**
     * Paper mode: two dispensers each fire a numbered slip and the higher number wins.
     *
     * <p>Unlike the other games this one has no single answer, because what comes out of one
     * machine only means anything next to what came out of the other. So the pair is drawn
     * together and each dispenser takes its half.
     */
    public boolean paper;
    /** Which dispenser stands for whom, so a slip can be named for its side. */
    public final Map<BlockPos, String> sides = new LinkedHashMap<>();
    /** Whose slip comes out higher. Empty leaves it to chance. */
    public String winner = "";
    /** What the slips are made of, and how high they go. */
    public String slipItem = "paper";
    public int numbers = 9;

    /** Drawn once per round and shared by both machines. Never saved. */
    public int highRoll = 9;
    public int lowRoll = 1;
    public String roundWinner = "";
    public long roundTick = Long.MIN_VALUE;

    /**
     * Roulette mode: instead of one answer, the dispenser cycles through a fixed number of
     * shots and the loaded one lands on a chosen position in that cycle.
     */
    public boolean roulette;
    /** How many shots before the cycle starts over. */
    public int chambers = 6;
    /** Which shot in the cycle is the loaded one, counting from one. */
    public int bulletAt = 1;
    public FakeSpec bullet;
    /** What the other shots fire. Null means nothing comes out at all. */
    public FakeSpec blank;
    /** How many shots have gone in the current cycle. */
    public int shot;
    /**
     * Fire the loaded item only when armed, rather than on a counted position.
     *
     * <p>For a game where turns come in an order nobody decides in advance, counting chambers
     * is the wrong shape: you want to say "this one" as it happens.
     */
    public boolean manualTrigger;
    /** Set by arming; the next shot is the loaded one, then this clears itself. */
    public boolean armed;

    private int presetIndex = -1;

    public RigProfile(String name) {
        this.name = name;
    }

    public int presetIndex() {
        return this.presetIndex;
    }

    public void setPresetIndex(int index) {
        this.presetIndex = index;
    }

    public FakeSpec selected() {
        if (this.presetIndex < 0 || this.presetIndex >= this.presets.size()) return null;
        return this.presets.get(this.presetIndex);
    }

    /** Steps through the presets and returns the new one, or null if there are none. */
    public FakeSpec cycle(int delta) {
        if (this.presets.isEmpty()) return null;

        this.presetIndex = Math.floorMod(this.presetIndex + delta, this.presets.size());
        return this.presets.get(this.presetIndex);
    }

    /** What this dispenser should appear to fire: its own answer, else the cycled one. */
    public FakeSpec resultFor(BlockPos pos) {
        FakeSpec fixed = this.perDispenser.get(pos);
        return fixed != null ? fixed : selected();
    }

    /**
     * Advances the chamber and says what this shot fires.
     *
     * <p>Counts from one so that "the third shot" means the third, and wraps once the cycle is
     * spent.
     */
    public FakeSpec advanceRoulette() {
        this.shot++;
        if (this.shot > this.chambers) this.shot = 1;

        // Arming wins over both counting and manual mode, and is spent by this shot.
        if (this.armed) {
            this.armed = false;
            return this.bullet;
        }
        if (this.manualTrigger) return this.blank;

        return this.shot == this.bulletAt ? this.bullet : this.blank;
    }

    public void resetShots() {
        this.shot = 0;
    }

    /** Clamps the chamber settings to something coherent after an edit. */
    public void tidyRoulette() {
        this.chambers = Math.max(1, Math.min(this.chambers, 64));
        this.bulletAt = Math.max(1, Math.min(this.bulletAt, this.chambers));
        if (this.shot > this.chambers) this.shot = 0;
    }

    // ------------------------------------------------------------------- paper

    /** Which side this dispenser plays for, giving it one if it has none yet. */
    public String sideAt(BlockPos pos) {
        String side = this.sides.get(pos);
        if (side != null) return side;

        // First watched is the player, second the host, which is how they get set up.
        for (String candidate : DEFAULT_SIDES) {
            if (!this.sides.containsValue(candidate)) {
                this.sides.put(pos.toImmutable(), candidate);
                return candidate;
            }
        }
        side = "Side " + (this.sides.size() + 1);
        this.sides.put(pos.toImmutable(), side);
        return side;
    }

    public void setSide(BlockPos pos, String side) {
        this.sides.put(pos.toImmutable(), side);
    }

    /** The sides in play, without repeats, for cycling through and for listing. */
    public List<String> sideNames() {
        Set<String> unique = new LinkedHashSet<>(this.sides.values());
        return new ArrayList<>(unique);
    }

    /** What a slip in a given position reads, e.g. {@code 3 (Player)}. */
    public String slipName(int number, String side) {
        return side == null || side.isEmpty() ? String.valueOf(number)
                : number + " (" + side + ")";
    }

    /**
     * Draws the pair of numbers for a round, and settles who the high one goes to.
     *
     * <p>The high number is kept above the middle so the winning slip always looks like a
     * good draw rather than a two beating a one.
     */
    public void startRound(Random random, long tick) {
        this.roundTick = tick;

        int span = Math.max(1, this.numbers / 2);
        this.highRoll = this.numbers - random.nextInt(span);
        this.lowRoll = 1 + random.nextInt(Math.max(1, this.highRoll - 1));

        List<String> names = sideNames();
        this.roundWinner = !this.winner.isEmpty() ? this.winner
                : names.isEmpty() ? "" : names.get(random.nextInt(names.size()));
    }

    /** Steps the rigged winner on to the next side, then to chance, then round again. */
    public String cycleWinner() {
        List<String> names = sideNames();
        if (names.isEmpty()) return "";

        int index = names.indexOf(this.winner) + 1;
        this.winner = index >= names.size() ? "" : names.get(index);
        // Whatever was drawn belongs to the old setting.
        this.roundTick = Long.MIN_VALUE;
        return this.winner;
    }

    /** What a dispenser looks like it is holding, or null if nothing has been laid out. */
    public Map<Integer, FakeSpec> stockAt(BlockPos pos) {
        return this.stock.get(pos);
    }

    public boolean isEmpty() {
        return this.presets.isEmpty() && this.perDispenser.isEmpty()
                && this.arrowTarget == null && !this.roulette && !this.paper
                && this.stock.isEmpty();
    }
}
