package dev.skullzz.mirage.client;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * What a fake is made of. The rendered stack is built once and cached, and thrown away
 * whenever the price file changes.
 */
public final class FakeSpec {
    public final Item item;
    public final int count;
    /** Enchantment spec, e.g. {@code sharpness:5, unbreaking:3}. Empty for none. */
    public final String enchants;
    /** Per-item price for this fake alone. Null falls back to the price file. */
    public final Double price;
    /** Which map a filled_map shows. Null for anything else. */
    public final Integer mapId;
    /** Display name shown instead of the item's own. Empty for the default. */
    public final String name;

    private ItemStack built;

    public FakeSpec(Item item, int count, String enchants) {
        this(item, count, enchants, null, null);
    }

    public FakeSpec(Item item, int count, String enchants, Double price) {
        this(item, count, enchants, price, null);
    }

    public FakeSpec(Item item, int count, String enchants, Double price, Integer mapId) {
        this(item, count, enchants, price, mapId, "");
    }

    public FakeSpec(Item item, int count, String enchants, Double price, Integer mapId, String name) {
        this.item = item;
        this.count = Math.max(1, Math.min(count, 127));
        this.enchants = enchants == null ? "" : enchants.trim();
        this.price = price;
        this.mapId = mapId;
        this.name = name == null ? "" : name.trim();
    }

    public ItemStack stack() {
        if (this.built == null) {
            ItemStack stack = new ItemStack(this.item, this.count);
            FakeLore.applyMapId(stack, this.mapId);
            FakeLore.applyName(stack, this.name);
            this.built = FakeLore.applyTo(stack, this.enchants, this.price);
        }
        return this.built;
    }

    /** Whether two fakes are the same thing, and so would land in one stack. */
    public boolean stacksWith(FakeSpec other) {
        return other != null
                && this.item == other.item
                && this.name.equals(other.name)
                && this.enchants.equals(other.enchants)
                && java.util.Objects.equals(this.mapId, other.mapId)
                && java.util.Objects.equals(this.price, other.price);
    }

    /** The same fake with a different stack size. */
    public FakeSpec withCount(int newCount) {
        return new FakeSpec(this.item, newCount, this.enchants, this.price, this.mapId, this.name);
    }

    /** How this was typed, so it can be shown back and re-parsed. */
    public String describe() {
        String id = net.minecraft.registry.Registries.ITEM.getId(this.item).getPath();
        if (this.mapId != null) id = id + "#" + this.mapId;
        return this.name.isEmpty() ? id : id + " \"" + this.name + "\"";
    }

    /** What to call this in a list: the custom name if there is one. */
    public String label() {
        return this.name.isEmpty() ? stack().getName().getString() : this.name;
    }

    public void invalidate() {
        this.built = null;
    }
}
