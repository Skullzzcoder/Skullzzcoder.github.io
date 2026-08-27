package com.skullzz.donutgambler;

import com.mojang.blaze3d.platform.InputConstants;

import org.lwjgl.glfw.GLFW;

import com.skullzz.donutgambler.chat.ChatWatcher;
import com.skullzz.donutgambler.command.GamblerCommands;
import com.skullzz.donutgambler.gui.GamblerScreen;
import com.skullzz.donutgambler.hud.GamblerHud;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Entry point: wires up the chat watcher, the HUD, the key bind and the commands. */
public class DonutGamblerClient implements ClientModInitializer {
	/** Autosave cadence in client ticks (20 ticks = 1 second). */
	private static final int SAVE_INTERVAL_TICKS = 600;

	private static KeyMapping openKey;
	private int tickCounter;

	@Override
	public void onInitializeClient() {
		DonutGambler.init();
		ChatWatcher.register();
		GamblerCommands.register();

		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(DonutGambler.MOD_ID, "main"));
		openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.donutgambler.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, category));

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(DonutGambler.MOD_ID, "stats"), GamblerHud::render);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.consumeClick()) {
				client.setScreen(new GamblerScreen());
			}

			if (++tickCounter >= SAVE_INTERVAL_TICKS) {
				tickCounter = 0;
				DonutGambler.saveAll();
			}
		});

		// Joining a server starts a fresh session, so session P/L means "this play session".
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			DonutGambler.log().startNewSession();
			DonutGambler.config().sessionStartBankroll = DonutGambler.config().bankroll;
			DonutGambler.invalidateAdvice();
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> DonutGambler.saveAll());

		DonutGambler.LOGGER.info("[{}] ready. Press G or run /gambler.", DonutGambler.NAME);
	}

	public static KeyMapping openKey() {
		return openKey;
	}
}
