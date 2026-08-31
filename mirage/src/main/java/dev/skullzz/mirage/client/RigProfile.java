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
    /** The side a draw belongs to. Named rather than fixed, since the sides are. */
    public String house = DEFAULT_SIDES[1];
    /** How often a round the house takes is drawn level instead, in percent. Off by default. */
    public int tieChance;

    /** Drawn once per round and shared by both machines. Never saved. */
    public int highRoll = 9;
    public int lowRoll = 1;
    public String roundWinner = "";
    public long roundTick = Long.MIN_VALUE;

    /**
     * Tower mode: a row of machines, one per floor, each holding two of each colour.
     *
     * <p>The player calls a colour before each floor and climbs while they are right, so
     * what a machine should fire is not a fixed answer but the answer to their call: the
     * colour they said to let them through, the other one to end the run.
     */
    public boolean tower;
    /** How many machines make a run. */
    public int floors = 5;
    /** Which floor of the tower each machine is, in the order they were watched. */
    public final Map<BlockPos, Integer> towerFloors = new LinkedHashMap<>();
    /** The two colours in play. */
    public String towerA = "white_shulker_box";
    public String towerB = "black_shulker_box";
    /** How many of each colour a machine appears to hold. */
    public int towerEach = 2;
    /** What the player just called, so the machine knows which answer is theirs. */
    public String call = "";
    /** The floor a run always ends on, or zero to leave it to the arm key. */
    public int bustAt;
    /** Set by arming: the next floor to fire ends the run, whatever floor it is. */
    public boolean bustNext;

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

    // ------------------------------------------------------------------- tower

    /**
     * Which floor a machine is, giving it the next one if it has none.
     *
     * <p>Floors go out in the order the machines are watched, and only as many as the run
     * has. Watched dispensers are shared by every rig, so anything past the last floor is
     * one of the other games' and is left out rather than made into a sixth floor.
     *
     * @return the floor, or zero for a machine that is not in this game.
     */
    public int floorAt(BlockPos pos, Set<BlockPos> live) {
        Integer floor = this.towerFloors.get(pos);
        if (floor != null) return floor;

        Set<Integer> taken = new LinkedHashSet<>();
        for (Map.Entry<BlockPos, Integer> entry : this.towerFloors.entrySet()) {
            if (live.contains(entry.getKey())) taken.add(entry.getValue());
        }

        for (int candidate = 1; candidate <= this.floors; candidate++) {
            if (taken.contains(candidate)) continue;
            this.towerFloors.put(pos.toImmutable(), candidate);
            return candidate;
        }
        return 0;
    }

    public void setFloor(BlockPos pos, int floor) {
        this.towerFloors.put(pos.toImmutable(), floor);
    }

    /** Forgets floors belonging to machines that are no longer watched. */
    public void pruneFloors(Set<BlockPos> live) {
        this.towerFloors.keySet().retainAll(live);
    }

    /** The colour that is not the one named, so a wrong call has something to be. */
    public String otherColour(String colour) {
        return colour.equalsIgnoreCase(this.towerA) ? this.towerB : this.towerA;
    }

    /** What the player called, falling back to the first colour if they said nothing. */
    public String called() {
        return this.call.isEmpty() ? this.towerA : this.call;
    }

    /**
     * Whether the run ends on a floor.
     *
     * <p>Arming beats the counted floor and is spent by the floor it lands on, so a run can
     * be ended by hand at any point without disturbing where it was set to end.
     */
    public boolean bustsOn(int floor) {
        if (this.bustNext) {
            this.bustNext = false;
            return true;
        }
        return this.bustAt > 0 && floor == this.bustAt;
    }

    // ------------------------------------------------------------------- paper

    /**
     * Which side this dispenser plays for, giving it one if it has none yet.
     *
     * <p>Watched dispensers are shared by every rig, so switching to the paper game reaches
     * machines belonging to the other games too. Only the two sides are ever handed out, and
     * only against machines still in play: a third one is not a third player, it is the
     * roulette dropper standing nearby, and it is left out rather than called "Side 3".
     *
     * @param live the dispensers currently being watched.
     * @return the side, or empty for a machine that is not part of this game.
     */
    public String sideAt(BlockPos pos, Set<BlockPos> live) {
        String side = this.sides.get(pos);
        if (side != null) return side;

        Set<String> taken = new LinkedHashSet<>();
        for (Map.Entry<BlockPos, String> entry : this.sides.entrySet()) {
            if (live.contains(entry.getKey())) taken.add(entry.getValue());
        }

        for (String candidate : DEFAULT_SIDES) {
            if (taken.contains(candidate)) continue;
            this.sides.put(pos.toImmutable(), candidate);
            return candidate;
        }
        return "";
    }

    /** Forgets sides belonging to machines that are no longer watched. */
    public void pruneSides(Set<BlockPos> live) {
        this.sides.keySet().retainAll(live);
    }

    public void setSide(BlockPos pos, String side) {
        this.sides.put(pos.toImmutable(), side);
    }

    /**
     * The sides in play, without repeats.
     *
     * <p>Ordered by the built-in sides rather than by which machine was watched first, so
     * that a key meaning "the player wins" keeps meaning that however the game was set up.
     */
    public List<String> sideNames() {
        Set<String> unique = new LinkedHashSet<>(this.sides.values());
        List<String> ordered = new ArrayList<>();

        for (String name : DEFAULT_SIDES) {
            if (unique.remove(name)) ordered.add(name);
        }
        ordered.addAll(unique);
        return ordered;
    }

    /** What a slip in a given position reads, e.g. {@code 3 (Player)}. */
    public String slipName(int number, String side) {
        return side == null || side.isEmpty() ? String.valueOf(number)
                : number + " (" + side + ")";
    }

    /** Whether a side is the one a draw goes to. */
    public boolean isHouse(String side) {
        return !this.house.isEmpty() && this.house.equalsIgnoreCase(side);
    }

    /** Whether a side name still belongs to a machine in the game. */
    public boolean hasSide(String name) {
        for (String side : sideNames()) {
            if (side.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * Draws the pair of numbers for a round, and settles who the high one goes to.
     *
     * <p>The high number is kept above the middle so the winning slip always looks like a
     * good draw rather than a two beating a one.
     *
     * <p>A draw belongs to the house, so a round the house is meant to take may come out
     * level: the machines agreeing now and then is what a fair pair of them would do, and
     * it costs nothing. A round the player is meant to take never can, because a draw
     * would hand them the loss the rigging is there to avoid.
     */
    public void startRound(Random random, long tick) {
        this.roundTick = tick;

        List<String> names = sideNames();

        // A name can go stale: a machine renamed, unwatched, or carried over from a file
        // written before the sides were worked out. A winner nobody answers to is the worst
        // possible outcome, because then no machine draws the high number and both take the
        // low one -- the same slip on both sides, every single round.
        //
        // Only ever for this round, and only against sides that are actually known. Nothing
        // is known before the machines have been laid out, and taking that for a stale name
        // threw away a winner that had just been set by hand.
        String wanted = this.winner;
        if (!wanted.isEmpty() && !names.isEmpty() && !hasSide(wanted)) wanted = "";

        this.roundWinner = !wanted.isEmpty() ? wanted
                : names.isEmpty() ? "" : names.get(random.nextInt(names.size()));

        int span = Math.max(1, this.numbers / 2);
        this.highRoll = this.numbers - random.nextInt(span);

        // Level only when the house is taking the round and only when asked for. Anything
        // else must come out apart, or the machines agree and the rigging means nothing.
        boolean level = this.tieChance > 0
                && isHouse(this.roundWinner)
                && random.nextInt(100) < this.tieChance;
        this.lowRoll = level ? this.highRoll
                : 1 + random.nextInt(Math.max(1, this.highRoll - 1));
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
                && this.arrowTarget == null && !this.roulette && !this.paper && !this.tower
                && this.stock.isEmpty();
    }
}
