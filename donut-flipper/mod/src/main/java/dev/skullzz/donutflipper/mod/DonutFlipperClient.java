package dev.skullzz.donutflipper.mod;

import dev.skullzz.donutflipper.mod.ui.FlipHud;
import dev.skullzz.donutflipper.mod.ui.FlipScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Mod entry point: registers the keybinds, the HUD overlay and the poll loop.
 *
 * <p>Client-side only. Nothing here sends anything to the server -- the mod
 * reads the public API through the local collector and draws the result. It is a
 * display, not an actor.
 */
public class DonutFlipperClient implements ClientModInitializer {

    public static final String MOD_ID = "donutflipper";

    private static FlipperState state;

    private KeyBinding openScreenKey;
    private KeyBinding toggleHudKey;

    private boolean hudVisible = true;

    @Override
    public void onInitializeClient() {
        state = new FlipperState();
        state.start();

        openScreenKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.donutflipper.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSLASH,
                "category.donutflipper"));

        toggleHudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.donutflipper.togglehud",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_BRACKET,
                "category.donutflipper"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openScreenKey.wasPressed()) {
                client.setScreen(new FlipScreen(state));
            }
            while (toggleHudKey.wasPressed()) {
                hudVisible = !hudVisible;
                if (client.player != null) {
                    client.player.sendMessage(
                            Text.literal("[Flipper] overlay " + (hudVisible ? "on" : "off")), true);
                }
            }

            // A chime when something clearly better than anything seen so far
            // appears, so the tool is useful while you are doing something else.
            if (state.consumeNewBestFlag() && client.player != null) {
                client.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 0.6f, 1.6f);
            }
        });

        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            if (hudVisible) {
                FlipHud.render(context, state);
            }
        });
    }

    public static FlipperState state() {
        return state;
    }
}
