package com.skullzz.glaze.feature;

import com.skullzz.glaze.GlazeClient;
import com.skullzz.glaze.core.GlazeConfig;
import com.skullzz.glaze.mc.Mc;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Formatting;

/**
 * Moves every item you already have some of in the open container into it.
 *
 * <p>The only part of the mod that interacts with the server for you, and it is
 * off by default. Two deliberate constraints keep it from behaving like a macro:
 * clicks go out one per queued step with a randomised gap, and each step is a
 * single shift-click, which is atomic from the server's point of view. Closing
 * the menu abandons the queue.
 *
 * <p>Check your server's rules before enabling this. Read-only features are
 * uncontroversial; anything that clicks for you is a judgement call you should
 * make deliberately.
 */
public final class QuickStash {
	private static final Deque<Integer> pending = new ArrayDeque<>();
	private static final Random RANDOM = new Random();

	private static int syncId = -1;
	private static long nextClickAt;
	private static int moved;

	private QuickStash() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(QuickStash::tick);
	}

	public static boolean running() {
		return !pending.isEmpty();
	}

	public static void cancel() {
		pending.clear();
		syncId = -1;
	}

	/**
	 * Queues a stash of everything the container already holds a kind of.
	 *
	 * <p>Only matching items are moved - stashing your whole inventory including
	 * your armour and tools is almost never what anyone wants.
	 */
	public static void start() {
		GlazeConfig config = GlazeClient.config();

		if (!config.automation.quickStash) {
			Mc.sendPrefixed("Quick-stash is off. Enable it in the config if you want it, "
					+ "and check your server's rules on click automation first.", Formatting.YELLOW);
			return;
		}

		MinecraftClient client = Mc.client();

		if (!(client.currentScreen instanceof HandledScreen<?> screen) || client.player == null) {
			Mc.sendPrefixed("Open a container first", Formatting.YELLOW);
			return;
		}

		if (running()) {
			cancel();
			Mc.sendPrefixed("Quick-stash cancelled");
			return;
		}

		var slots = screen.getScreenHandler().slots;
		int containerSize = containerSlotCount(slots.size());

		if (containerSize <= 0) {
			Mc.sendPrefixed("That menu has no container section", Formatting.YELLOW);
			return;
		}

		// What the container already holds decides what counts as "belongs here".
		Set<String> wanted = new HashSet<>();

		for (int i = 0; i < containerSize; i++) {
			ItemStack stack = slots.get(i).getStack();

			if (!stack.isEmpty()) {
				wanted.add(Mc.displayName(stack));
			}
		}

		if (wanted.isEmpty()) {
			Mc.sendPrefixed("Container is empty, so there is nothing to match against",
					Formatting.YELLOW);
			return;
		}

		pending.clear();

		for (int i = containerSize; i < slots.size(); i++) {
			ItemStack stack = slots.get(i).getStack();

			if (!stack.isEmpty() && wanted.contains(Mc.displayName(stack))) {
				pending.add(slots.get(i).id);
			}
		}

		if (pending.size() > config.automation.maxClicksPerAction) {
			Mc.sendPrefixed("That would take " + pending.size() + " moves, over the "
					+ config.automation.maxClicksPerAction + " limit in your config",
					Formatting.YELLOW);
			pending.clear();
			return;
		}

		if (pending.isEmpty()) {
			Mc.sendPrefixed("Nothing in your inventory matches what is in there");
			return;
		}

		syncId = screen.getScreenHandler().syncId;
		moved = 0;
		nextClickAt = System.currentTimeMillis();
		Mc.sendPrefixed("Stashing " + pending.size() + " stacks", Formatting.GRAY);
	}

	/**
	 * Where the container's own slots end and the player's inventory begins.
	 *
	 * <p>The player section is always the last 36 slots of a container menu.
	 */
	private static int containerSlotCount(int totalSlots) {
		return totalSlots - 36;
	}

	private static void tick(MinecraftClient client) {
		if (pending.isEmpty()) {
			return;
		}

		// Any change of menu abandons the run rather than clicking into whatever
		// replaced it.
		if (!(client.currentScreen instanceof HandledScreen<?> screen)
				|| client.player == null
				|| screen.getScreenHandler().syncId != syncId) {
			finish(true);
			return;
		}

		long now = System.currentTimeMillis();

		if (now < nextClickAt) {
			return;
		}

		GlazeConfig config = GlazeClient.config();
		int slotId = pending.poll();

		Slot slot = findSlot(screen, slotId);

		if (slot != null && slot.hasStack()) {
			client.interactionManager.clickSlot(syncId, slotId, 0,
					SlotActionType.QUICK_MOVE, client.player);
			moved++;
		}

		int jitter = config.automation.clickJitterMillis;
		nextClickAt = now + config.automation.clickDelayMillis
				+ (jitter > 0 ? RANDOM.nextInt(jitter + 1) : 0);

		if (pending.isEmpty()) {
			finish(false);
		}
	}

	private static Slot findSlot(HandledScreen<?> screen, int slotId) {
		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.id == slotId) {
				return slot;
			}
		}

		return null;
	}

	private static void finish(boolean interrupted) {
		pending.clear();
		syncId = -1;
		Mc.invalidateInventoryCache();

		if (interrupted) {
			Mc.sendPrefixed("Quick-stash stopped after " + moved + " moves", Formatting.YELLOW);
		} else {
			Mc.sendPrefixed("Stashed " + moved + " stacks", Formatting.GREEN);
		}
	}
}
