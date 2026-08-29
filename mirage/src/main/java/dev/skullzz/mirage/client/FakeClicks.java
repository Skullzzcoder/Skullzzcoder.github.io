package dev.skullzz.mirage.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

/**
 * Lets fakes be moved about by hand, and stops a click on one reaching the server.
 *
 * <p>Shift-clicking a fake in the inventory loads it into the open dispenser, and shift-clicking
 * one in the dispenser takes it back, so a dispenser can be filled and emptied the way a real
 * one is rather than by typing a command.
 *
 * <p>Every click on a faked slot is cancelled, whether or not it moves anything. Vanilla would
 * otherwise send the click on to the server, which sees the real contents of that slot, and a
 * click meant for something that is not there would move something that is.
 */
public final class FakeClicks {
    /** The container background every 3x3 and inventory screen is drawn on. */
    private static final int BACKGROUND_WIDTH = 176;
    private static final int BACKGROUND_HEIGHT = 166;
    private static final int SLOT_SIZE = 16;
    /** Every container handler carries the player's 36 slots after its own. */
    private static final int PLAYER_SLOTS = 36;
    /** Rows of the main inventory, which come before the hotbar in a container. */
    private static final int MAIN_SLOTS = 27;

    private FakeClicks() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof HandledScreen<?> handled)) return;

            ScreenMouseEvents.allowMouseClick(screen).register((target, mouseX, mouseY, button) ->
                    !handle(handled, mouseX, mouseY));
        });
    }

    /** @return true if this click was ours, and so must not reach vanilla or the server. */
    private static boolean handle(HandledScreen<?> screen, double mouseX, double mouseY) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return false;

        ScreenHandler handler = screen.getScreenHandler();
        int size = handler.slots.size() - PLAYER_SLOTS;
        // Only a dispenser or dropper. Anything else is left entirely alone.
        if (size != ClientDispensers.STOCK_SLOTS) return false;

        Slot slot = slotAt(screen, handler, mouseX, mouseY);
        if (slot == null) return false;

        if (slot.id < size) {
            if (!ClientDispensers.stockHolds(player, slot.id)) return false;
            if (Screen.hasShiftDown()) ClientDispensers.takeFromStock(player, slot.id);
            return true;
        }

        int inventorySlot = inventorySlot(slot.id, size);
        if (inventorySlot < 0 || !SelfFakes.has(inventorySlot)) return false;

        if (Screen.hasShiftDown()) ClientDispensers.putIntoStock(player, inventorySlot);
        return true;
    }

    /**
     * Which slot the cursor is over.
     *
     * <p>The screen's own hit test is not reachable from outside it, so this walks the slots,
     * whose positions are relative to the top left of the background. If the background is not
     * where we think it is, no slot matches and the click is simply left to vanilla.
     */
    private static Slot slotAt(HandledScreen<?> screen, ScreenHandler handler,
                               double mouseX, double mouseY) {
        double originX = (screen.width - BACKGROUND_WIDTH) / 2.0;
        double originY = (screen.height - BACKGROUND_HEIGHT) / 2.0;

        for (Slot slot : handler.slots) {
            double x = mouseX - originX - slot.x;
            double y = mouseY - originY - slot.y;
            if (x >= 0.0 && x < SLOT_SIZE && y >= 0.0 && y < SLOT_SIZE) return slot;
        }
        return null;
    }

    /**
     * Turns a container slot id into a player inventory index.
     *
     * <p>A container lays the player's slots out as the three main rows first and the hotbar
     * last, which is the opposite way round from the inventory itself.
     */
    private static int inventorySlot(int id, int size) {
        int offset = id - size;
        if (offset < 0 || offset >= PLAYER_SLOTS) return -1;
        return offset < MAIN_SLOTS ? 9 + offset : offset - MAIN_SLOTS;
    }
}
