package com.skullzz.glaze.mc;

import com.skullzz.glaze.core.Money;
import com.skullzz.glaze.core.PriceBook;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Thin wrappers over the client API.
 *
 * <p>Everything version-sensitive that the features need is funnelled through here,
 * so a Minecraft update that renames something is a fix in one file rather than
 * thirty.
 */
public final class Mc {
	/** How long a cached inventory tally stays good for. */
	private static final long CACHE_TTL_MILLIS = 250;

	@SuppressWarnings("unchecked")
	private static final Map<String, Integer>[] cached = new Map[2];
	private static final long[] cachedAt = new long[2];

	private Mc() {
	}

	public static MinecraftClient client() {
		return MinecraftClient.getInstance();
	}

	public static Optional<ClientPlayerEntity> player() {
		return Optional.ofNullable(client().player);
	}

	public static boolean inGame() {
		return client().player != null && client().world != null;
	}

	/** Prints a message to the local chat. Never reaches the server. */
	public static void send(Text text) {
		MinecraftClient client = client();

		if (client.inGameHud != null) {
			client.inGameHud.getChatHud().addMessage(text);
		}
	}

	/** Prints a message tagged with the mod name so it is obviously client-side. */
	public static void sendPrefixed(String message, Formatting colour) {
		send(Text.literal("[Glaze] ").formatted(Formatting.GOLD)
				.append(Text.literal(message).formatted(colour)));
	}

	public static void sendPrefixed(String message) {
		sendPrefixed(message, Formatting.WHITE);
	}

	public static MutableText literal(String text, Formatting... styles) {
		return Text.literal(text).formatted(styles);
	}

	/**
	 * Plays a UI sound to the local player only.
	 *
	 * <p>{@code SoundEvents} entries are registry references, hence {@code value()}.
	 */
	public static void beep(float pitch) {
		play(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), pitch);
	}

	public static void warnSound() {
		play(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.7F);
	}

	private static void play(SoundEvent sound, float pitch) {
		player().ifPresent(p -> p.playSound(sound, 1.0F, pitch));
	}

	/** The address of the server the client is connected to, lower-cased. */
	public static String serverAddress() {
		MinecraftClient client = client();

		if (client.getCurrentServerEntry() != null && client.getCurrentServerEntry().address != null) {
			return client.getCurrentServerEntry().address.toLowerCase();
		}

		return "";
	}

	/** Round-trip time to the server in milliseconds, or -1 when unknown. */
	public static int ping() {
		MinecraftClient client = client();

		if (client.player == null || client.getNetworkHandler() == null) {
			return -1;
		}

		PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
		return entry == null ? -1 : entry.getLatency();
	}

	/**
	 * The plain display name of a stack, with colour codes removed.
	 *
	 * <p>This is what price data is keyed on: the server renames auction items
	 * freely, and the display name is the only thing that matches what a player
	 * sees and searches for.
	 */
	public static String displayName(ItemStack stack) {
		return stack.isEmpty() ? "" : Money.clean(stack.getName().getString());
	}

	/** Every lore line under a stack, as plain strings. */
	public static List<String> lore(ItemStack stack) {
		List<String> out = new ArrayList<>();

		if (stack.isEmpty()) {
			return out;
		}

		var lore = stack.get(DataComponentTypes.LORE);

		if (lore != null) {
			lore.lines().forEach(line -> out.add(Money.clean(line.getString())));
		}

		return out;
	}

	/**
	 * The contents of a shulker box or bundle, or an empty list for anything else.
	 */
	public static List<ItemStack> containerContents(ItemStack stack) {
		List<ItemStack> out = new ArrayList<>();

		if (stack.isEmpty()) {
			return out;
		}

		ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);

		if (container != null) {
			container.iterateNonEmpty().forEach(out::add);
		}

		return out;
	}

	public static boolean isContainer(ItemStack stack) {
		return !stack.isEmpty() && stack.contains(DataComponentTypes.CONTAINER);
	}

	/** Every stack in the player's inventory, including armour and off-hand. */
	public static List<ItemStack> inventoryStacks() {
		List<ItemStack> out = new ArrayList<>();

		player().ifPresent(p -> {
			PlayerInventory inventory = p.getInventory();

			// Iterating the Inventory interface rather than its backing lists keeps
			// this working across the inventory refactors between versions.
			for (int i = 0; i < inventory.size(); i++) {
				ItemStack stack = inventory.getStack(i);

				if (!stack.isEmpty()) {
					out.add(stack);
				}
			}
		});

		return out;
	}

	/**
	 * Counts everything the player is carrying, keyed by normalised item name.
	 *
	 * @param lookInsideContainers also count items stored in carried shulker boxes
	 */
	public static Map<String, Integer> inventoryCounts(boolean lookInsideContainers) {
		Map<String, Integer> counts = new HashMap<>();

		for (ItemStack stack : inventoryStacks()) {
			counts.merge(PriceBook.key(displayName(stack)), stack.getCount(), Integer::sum);

			if (lookInsideContainers) {
				for (ItemStack inner : containerContents(stack)) {
					counts.merge(PriceBook.key(displayName(inner)), inner.getCount(), Integer::sum);
				}
			}
		}

		return counts;
	}

	/**
	 * A short-lived cache over {@link #inventoryCounts}.
	 *
	 * <p>Tooltips render every frame and the deep variant walks every carried
	 * shulker box, so recomputing per frame is real work for a number that changes
	 * at most a few times a second.
	 */
	public static Map<String, Integer> cachedInventoryCounts(boolean lookInsideContainers) {
		int index = lookInsideContainers ? 1 : 0;
		long now = System.currentTimeMillis();

		if (cached[index] == null || now - cachedAt[index] > CACHE_TTL_MILLIS) {
			cached[index] = inventoryCounts(lookInsideContainers);
			cachedAt[index] = now;
		}

		return cached[index];
	}

	/** Drops the cache, for when an inventory change must be reflected at once. */
	public static void invalidateInventoryCache() {
		cached[0] = null;
		cached[1] = null;
	}

	/** Formats money the way the player asked for it in config. */
	public static String money(long amount, boolean compact) {
		return compact ? Money.compact(amount) : Money.full(amount);
	}
}
