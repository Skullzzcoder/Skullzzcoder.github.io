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

    private ItemStack built;

    public FakeSpec(Item item, int count, String enchants) {
        this(item, count, enchants, null);
    }

    public FakeSpec(Item item, int count, String enchants, Double price) {
        this.item = item;
        this.count = Math.max(1, Math.min(count, 127));
        this.enchants = enchants == null ? "" : enchants.trim();
        this.price = price;
    }

    public ItemStack stack() {
        if (this.built == null) {
            this.built = FakeLore.applyTo(
                    new ItemStack(this.item, this.count), this.enchants, this.price);
        }
        return this.built;
    }

    public void invalidate() {
        this.built = null;
    }
}
