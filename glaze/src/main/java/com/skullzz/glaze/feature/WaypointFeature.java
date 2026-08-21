package com.skullzz.glaze.feature;

import com.skullzz.glaze.GlazeClient;
import com.skullzz.glaze.core.Waypoint;
import com.skullzz.glaze.mc.Mc;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import net.minecraft.util.Formatting;

/**
 * Your own saved places, and the death point saved automatically when you die.
 *
 * <p>Strictly personal marks. There is no player tracking here and none is
 * planned - a mod that draws where other people are is the kind that gets you
 * banned, and it is not what this one is for.
 */
public final class WaypointFeature {
	/** Death points kept before the oldest is dropped. */
	private static final int MAX_DEATH_POINTS = 5;

	private WaypointFeature() {
	}

	public static List<Waypoint> all() {
		return GlazeClient.config().waypoints;
	}

	/** Saves the player's current position under {@code name}. */
	public static Optional<Waypoint> add(String name) {
		ClientPlayerEntity player = Mc.client().player;

		if (player == null || name == null || name.isBlank()) {
			return Optional.empty();
		}

		Waypoint waypoint = new Waypoint(name.trim(),
				(int) Math.floor(player.getX()),
				(int) Math.floor(player.getY()),
				(int) Math.floor(player.getZ()),
				dimensionOf(player),
				System.currentTimeMillis());

		// A repeated name replaces the old mark rather than accumulating duplicates.
		all().removeIf(w -> w.name.equalsIgnoreCase(waypoint.name));
		all().add(waypoint);
		GlazeClient.saveConfig();
		return Optional.of(waypoint);
	}

	public static boolean remove(String name) {
		boolean removed = all().removeIf(w -> w.name.equalsIgnoreCase(name.trim()));

		if (removed) {
			GlazeClient.saveConfig();
		}

		return removed;
	}

	/**
	 * Saves where the player just died, and prints it as a chat line so the
	 * coordinates survive even if the config is lost.
	 */
	public static void recordDeathPoint(ClientPlayerEntity player, long now) {
		Waypoint waypoint = new Waypoint("death",
				(int) Math.floor(player.getX()),
				(int) Math.floor(player.getY()),
				(int) Math.floor(player.getZ()),
				dimensionOf(player),
				now);

		List<Waypoint> waypoints = all();
		long deaths = waypoints.stream().filter(w -> w.name.startsWith("death")).count();
		waypoint.name = "death-" + (deaths + 1);
		waypoints.add(waypoint);
		pruneDeathPoints(waypoints);
		GlazeClient.saveConfig();

		Mc.send(Text.literal("[Glaze] ").formatted(Formatting.GOLD)
				.append(Text.literal("Died at " + waypoint.coords() + " (" + waypoint.dimension + ")")
						.formatted(Formatting.RED)));
	}

	private static void pruneDeathPoints(List<Waypoint> waypoints) {
		List<Waypoint> deaths = waypoints.stream()
				.filter(w -> w.name.startsWith("death"))
				.sorted(Comparator.comparingLong(w -> w.createdAt))
				.toList();

		for (int i = 0; i < deaths.size() - MAX_DEATH_POINTS; i++) {
			waypoints.remove(deaths.get(i));
		}
	}

	/** The nearest waypoint in the dimension the player is standing in. */
	public static Optional<Waypoint> nearest() {
		ClientPlayerEntity player = Mc.client().player;

		if (player == null) {
			return Optional.empty();
		}

		String dimension = dimensionOf(player);

		return all().stream()
				.filter(w -> w.dimension.isEmpty() || w.dimension.equals(dimension))
				.min(Comparator.comparingDouble(w -> w.distanceTo(player.getX(), player.getZ())));
	}

	private static String dimensionOf(ClientPlayerEntity player) {
		// Entity's world getter is getEntityWorld() as of 1.21.11.
		World world = player.getEntityWorld();
		return world == null ? "" : world.getRegistryKey().getValue().toString();
	}
}
