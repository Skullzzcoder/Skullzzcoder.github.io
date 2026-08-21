package com.skullzz.glaze.core;

/**
 * A place you saved, plus the maths for pointing at it.
 *
 * <p>Only your own marks live here - somewhere you died, a base, a stash. The mod
 * has no notion of where anyone else is and never draws other players.
 */
public final class Waypoint {
	public String name = "";
	public int x;
	public int y;
	public int z;
	public String dimension = "";
	public long createdAt;

	public Waypoint() {
	}

	public Waypoint(String name, int x, int y, int z, String dimension, long createdAt) {
		this.name = name;
		this.x = x;
		this.y = y;
		this.z = z;
		this.dimension = dimension;
		this.createdAt = createdAt;
	}

	/** Horizontal distance, which is what matters for walking there. */
	public double distanceTo(double px, double pz) {
		double dx = x - px;
		double dz = z - pz;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/**
	 * Where this waypoint lies relative to the way the player is facing.
	 *
	 * @return degrees in [-180, 180); 0 is straight ahead, negative is to the left
	 */
	public double bearingFrom(double px, double pz, float yawDegrees) {
		return bearing(x - px, z - pz, yawDegrees);
	}

	/**
	 * Bearing to an offset, in Minecraft's yaw convention where yaw 0 faces +Z and
	 * the facing vector is {@code (-sin yaw, cos yaw)}.
	 */
	public static double bearing(double dx, double dz, float yawDegrees) {
		double target = Math.toDegrees(Math.atan2(-dx, dz));
		return wrapDegrees(target - yawDegrees);
	}

	/** Normalises an angle to [-180, 180). */
	public static double wrapDegrees(double degrees) {
		double d = degrees % 360.0;

		if (d >= 180.0) {
			d -= 360.0;
		}

		if (d < -180.0) {
			d += 360.0;
		}

		return d;
	}

	/** A compass letter for a bearing, for the HUD readout. */
	public static String arrow(double bearingDegrees) {
		double b = wrapDegrees(bearingDegrees);

		if (b >= -22.5 && b < 22.5) {
			return "^";
		}

		if (b >= 22.5 && b < 67.5) {
			return "/";
		}

		if (b >= 67.5 && b < 112.5) {
			return ">";
		}

		if (b >= 112.5 && b < 157.5) {
			return "\\";
		}

		if (b >= -67.5 && b < -22.5) {
			return "\\";
		}

		if (b >= -112.5 && b < -67.5) {
			return "<";
		}

		if (b >= -157.5 && b < -112.5) {
			return "/";
		}

		return "v";
	}

	public String coords() {
		return x + ", " + y + ", " + z;
	}
}
