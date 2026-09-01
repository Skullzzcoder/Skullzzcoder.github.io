package dev.skullzz.mirage.client;

import java.lang.reflect.Proxy;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.Event;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.Window;
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
    /** A click reported twice, as a double click is, must not move the item twice. */
    private static final long REPEAT_MILLIS = 200L;

    private static long lastHandled;
    /** What is being carried on the pointer, or null. Fakes only; real items are vanilla's. */
    private static FakeSpec cursor;

    private FakeClicks() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof HandledScreen<?> handled)) return;
            listen(ScreenMouseEvents.allowMouseClick(screen), handled);
            draw(ScreenEvents.afterRender(screen));
        });
    }

    /**
     * Subscribes without naming the callback's parameters.
     *
     * <p>What a click carries has changed shape between versions -- loose coordinates in some,
     * a single click object in others -- and this needs none of it: where the cursor is and
     * whether shift is down are both readable directly. Going through a proxy means the shape
     * of that callback cannot break the build, and the only thing named here is the interface,
     * which has not moved.
     */
    /**
     * Draws whatever is on the pointer, without naming what a render callback is handed.
     *
     * <p>Only one thing is needed out of it -- somewhere to draw -- and that is picked out
     * of the arguments by type. What else it carries, and how many, has changed between
     * versions and is nothing to do with this.
     */
    private static void draw(Event<ScreenEvents.AfterRender> event) {
        Object listener = Proxy.newProxyInstance(
                FakeClicks.class.getClassLoader(),
                new Class<?>[] { ScreenEvents.AfterRender.class },
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> "mirage cursor";
                        };
                    }

                    for (Object argument : args) {
                        if (argument instanceof DrawContext context) drawCursor(context);
                    }
                    return null;
                });

        @SuppressWarnings("unchecked")
        Event<Object> untyped = (Event<Object>) (Event<?>) event;
        untyped.register(listener);
    }

    /** The carried fake, under the pointer, where vanilla draws one. */
    private static void drawCursor(DrawContext context) {
        if (cursor == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        double[] mouse = pointer(client);
        if (mouse == null) return;

        int x = (int) mouse[0] - 8;
        int y = (int) mouse[1] - 8;
        context.drawItem(cursor.stack(), x, y);
        context.drawStackOverlay(client.textRenderer, cursor.stack(), x, y);
    }

    private static void listen(Event<ScreenMouseEvents.AllowMouseClick> event,
                               HandledScreen<?> screen) {
        Object listener = Proxy.newProxyInstance(
                FakeClicks.class.getClassLoader(),
                new Class<?>[] { ScreenMouseEvents.AllowMouseClick.class },
                (proxy, method, args) -> {
                    // Proxies route Object's own methods here as well.
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> "mirage click filter";
                        };
                    }
                    return !handle(screen);
                });

        @SuppressWarnings("unchecked")
        Event<Object> untyped = (Event<Object>) (Event<?>) event;
        untyped.register(listener);
    }

    /** @return true if this click was ours, and so must not reach vanilla or the server. */
    private static boolean handle(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.currentScreen != screen) return false;
        // Switched off, every click is somebody else's business again.
        if (!SelfFakes.enabled()) return false;

        ScreenHandler handler = screen.getScreenHandler();
        int size = handler.slots.size() - PLAYER_SLOTS;
        // Only a dispenser or dropper. Anything else is left entirely alone.
        if (size != ClientDispensers.STOCK_SLOTS) return false;

        Slot slot = slotAt(client, screen, handler);
        if (slot == null) {
            // Off the board with something in hand: vanilla would throw it away, and there
            // is no ground to throw a fake onto, so it goes back where it came from.
            if (cursor == null) return false;
            if (!repeated()) putBack(player);
            return true;
        }

        boolean ours = slot.id < size
                ? ClientDispensers.stockHolds(player, slot.id)
                : SelfFakes.has(inventorySlot(slot.id, size));
        // Nothing of ours and nothing in hand: somebody else's click entirely.
        if (!ours && cursor == null) return false;

        if (repeated()) return true;

        if (shiftDown(client)) {
            if (!ours) return true;
            if (slot.id < size) {
                ClientDispensers.takeFromStock(player, slot.id);
            } else {
                ClientDispensers.putIntoStock(player, inventorySlot(slot.id, size));
            }
            return true;
        }
        return carry(client, player, slot, size, ours);
    }

    /**
     * Picking a fake up and putting it down, the way vanilla moves a real one.
     *
     * <p>Left takes or lays down the lot, right takes half or lays down one. A slot holding
     * something real is never written to: what is in it belongs to the server, and covering
     * it would only last until the server said otherwise.
     */
    private static boolean carry(MinecraftClient client, ClientPlayerEntity player,
                                 Slot slot, int size, boolean ours) {
        boolean right = rightDown(client);
        FakeSpec inSlot = ours ? read(player, slot, size) : null;

        if (cursor == null) {
            if (inSlot == null) return false;

            int taken = right ? (inSlot.count + 1) / 2 : inSlot.count;
            cursor = inSlot.withCount(taken);
            write(player, slot, size, taken >= inSlot.count ? null
                    : inSlot.withCount(inSlot.count - taken));
            return true;
        }

        // Something real underneath, so there is nowhere for this to go.
        if (!ours && !slot.getStack().isEmpty()) return true;

        int laying = right ? 1 : cursor.count;
        if (inSlot == null) {
            write(player, slot, size, cursor.withCount(laying));
        } else if (inSlot.stacksWith(cursor)) {
            int room = Math.min(laying, 64 - inSlot.count);
            if (room <= 0) return true;

            laying = room;
            write(player, slot, size, inSlot.withCount(inSlot.count + room));
        } else {
            // Two different things: they change places, as they would in any screen.
            if (right || cursor.count != laying) return true;

            write(player, slot, size, cursor);
            cursor = inSlot;
            return true;
        }

        cursor = cursor.count > laying ? cursor.withCount(cursor.count - laying) : null;
        return true;
    }

    /** What a slot is faking, from whichever side of the screen it is on. */
    private static FakeSpec read(ClientPlayerEntity player, Slot slot, int size) {
        return slot.id < size
                ? ClientDispensers.stockAt(player, slot.id)
                : SelfFakes.all().get(inventorySlot(slot.id, size));
    }

    /** Puts a fake into a slot, or clears it when given nothing. */
    private static void write(ClientPlayerEntity player, Slot slot, int size, FakeSpec spec) {
        if (slot.id < size) {
            ClientDispensers.setStock(player, slot.id, spec);
            return;
        }

        int inventory = inventorySlot(slot.id, size);
        if (spec == null) {
            SelfFakes.clear(inventory, player);
        } else {
            SelfFakes.set(inventory, spec);
        }
    }

    /** Returns what is being carried to the inventory, since it cannot be dropped. */
    private static void putBack(ClientPlayerEntity player) {
        if (cursor != null) SelfFakes.collect(cursor, player);
        cursor = null;
    }

    /** Called when the screen goes, so nothing is left held with nowhere to be. */
    public static void closed(ClientPlayerEntity player) {
        if (cursor == null) return;
        if (player != null) SelfFakes.collect(cursor, player);
        cursor = null;
    }

    public static boolean carrying() {
        return cursor != null;
    }

    /** One physical click can be reported more than once; only the first moves anything. */
    private static boolean repeated() {
        long now = System.currentTimeMillis();
        boolean soon = now - lastHandled < REPEAT_MILLIS;
        lastHandled = now;
        return soon;
    }

    private static boolean rightDown(MinecraftClient client) {
        return GLFW.glfwGetMouseButton(client.getWindow().getHandle(),
                GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
    }

    private static boolean shiftDown(MinecraftClient client) {
        long handle = client.getWindow().getHandle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    /**
     * Which slot the cursor is over.
     *
     * <p>The screen's own hit test is not reachable from outside it, so this walks the slots,
     * whose positions are relative to the top left of the background. If the background is not
     * where we think it is, no slot matches and the click is simply left to vanilla.
     */
    private static Slot slotAt(MinecraftClient client, HandledScreen<?> screen,
                               ScreenHandler handler) {
        double[] mouse = pointer(client);
        if (mouse == null) return null;

        double mouseX = mouse[0];
        double mouseY = mouse[1];

        double originX = (screen.width - BACKGROUND_WIDTH) / 2.0;
        double originY = (screen.height - BACKGROUND_HEIGHT) / 2.0;

        for (Slot slot : handler.slots) {
            double x = mouseX - originX - slot.x;
            double y = mouseY - originY - slot.y;
            if (x >= 0.0 && x < SLOT_SIZE && y >= 0.0 && y < SLOT_SIZE) return slot;
        }
        return null;
    }

    /** Where the pointer is in the screen's own coordinates, or null if there is no window. */
    private static double[] pointer(MinecraftClient client) {
        Window window = client.getWindow();
        if (window.getWidth() == 0 || window.getHeight() == 0) return null;

        // The pointer is in real pixels; the screen is laid out in scaled ones.
        return new double[] {
                client.mouse.getX() * window.getScaledWidth() / window.getWidth(),
                client.mouse.getY() * window.getScaledHeight() / window.getHeight() };
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
