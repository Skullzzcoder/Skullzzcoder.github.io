package com.skullzz.donutgambler;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Client-side chat output. Nothing here is ever sent to the server. */
public final class ClientChat {
	private ClientChat() {
	}

	public static MutableComponent prefix() {
		return Component.literal("[Gambler] ").withStyle(ChatFormatting.GOLD);
	}

	public static void send(Component body) {
		Minecraft client = Minecraft.getInstance();

		if (client.player != null) {
			client.player.displayClientMessage(prefix().append(body), false);
		}
	}

	public static void info(String text) {
		send(Component.literal(text).withStyle(ChatFormatting.GRAY));
	}

	public static void good(String text) {
		send(Component.literal(text).withStyle(ChatFormatting.GREEN));
	}

	public static void warn(String text) {
		send(Component.literal(text).withStyle(ChatFormatting.YELLOW));
	}

	public static void bad(String text) {
		send(Component.literal(text).withStyle(ChatFormatting.RED));
	}

	/** Status-bar line (above the hotbar), for things not worth a chat entry. */
	public static void overlay(String text) {
		Minecraft client = Minecraft.getInstance();

		if (client.player != null) {
			client.player.displayClientMessage(Component.literal(text), true);
		}
	}
}
