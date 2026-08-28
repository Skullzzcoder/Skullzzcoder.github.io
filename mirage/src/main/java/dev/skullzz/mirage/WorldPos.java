package dev.skullzz.mirage;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** A block position plus the dimension it lives in, usable as a map key and as a save-file key. */
public record WorldPos(Identifier dimension, BlockPos pos) {
    public static WorldPos of(World world, BlockPos pos) {
        return new WorldPos(world.getRegistryKey().getValue(), pos.toImmutable());
    }

    public RegistryKey<World> worldKey() {
        return RegistryKey.of(RegistryKeys.WORLD, this.dimension);
    }

    public String toKeyString() {
        return this.dimension + "|" + this.pos.getX() + "," + this.pos.getY() + "," + this.pos.getZ();
    }

    /** @return the parsed key, or null if the string is not one we wrote. */
    public static WorldPos parse(String key) {
        int bar = key.lastIndexOf('|');
        if (bar < 0) return null;

        Identifier dimension = Identifier.tryParse(key.substring(0, bar));
        if (dimension == null) return null;

        String[] parts = key.substring(bar + 1).split(",");
        if (parts.length != 3) return null;

        try {
            return new WorldPos(dimension, new BlockPos(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return this.pos.getX() + " " + this.pos.getY() + " " + this.pos.getZ() + " (" + this.dimension + ")";
    }
}
