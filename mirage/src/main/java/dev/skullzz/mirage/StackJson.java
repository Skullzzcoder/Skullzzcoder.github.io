package dev.skullzz.mirage;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;

/**
 * Item stacks carry data components, so they are saved through the vanilla codec rather
 * than by hand. Every mappings-sensitive line of the save format lives in this one file.
 */
public final class StackJson {
    private StackJson() {
    }

    public static JsonElement write(RegistryWrapper.WrapperLookup lookup, ItemStack stack) {
        return ItemStack.CODEC.encodeStart(RegistryOps.of(JsonOps.INSTANCE, lookup), stack)
                .resultOrPartial(error -> Mirage.LOGGER.warn("Mirage could not save an item stack: {}", error))
                .orElse(null);
    }

    public static ItemStack read(RegistryWrapper.WrapperLookup lookup, JsonElement json) {
        if (json == null || json.isJsonNull()) return ItemStack.EMPTY;

        return ItemStack.CODEC.parse(RegistryOps.of(JsonOps.INSTANCE, lookup), json)
                .resultOrPartial(error -> Mirage.LOGGER.warn("Mirage could not load an item stack: {}", error))
                .orElse(ItemStack.EMPTY);
    }
}
