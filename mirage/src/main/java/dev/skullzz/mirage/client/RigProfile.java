package dev.skullzz.mirage.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /** Where this game's fake arrow lands, if it uses one. */
    public Vec3d arrowTarget;

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

    public boolean isEmpty() {
        return this.presets.isEmpty() && this.perDispenser.isEmpty() && this.arrowTarget == null;
    }
}
