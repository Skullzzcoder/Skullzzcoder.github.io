package dev.skullzz.mirage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

/** Everything we lie about for one particular dispenser. */
public class DispenserRig {
    /** Slot 0-8 to the stack the victim sees when they open this dispenser. */
    public final Map<Integer, ItemStack> display = new LinkedHashMap<>();

    /** What appears to fly out when the dispenser fires. Empty means don't fake the output. */
    public ItemStack result = ItemStack.EMPTY;

    /** null means prank everybody. */
    public UUID onlyPlayer;

    public boolean appliesTo(ServerPlayerEntity player) {
        return this.onlyPlayer == null || this.onlyPlayer.equals(player.getUuid());
    }

    public boolean isEmpty() {
        return this.display.isEmpty() && this.result.isEmpty();
    }
}
