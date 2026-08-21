package com.skullzz.glaze.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WaypointTest {
	@Test
	void distanceIgnoresHeight() {
		Waypoint w = new Waypoint("base", 30, 200, 40, "overworld", 0);
		assertEquals(50.0, w.distanceTo(0, 0), 1e-9);
	}

	@Test
	void straightAheadIsZeroBearing() {
		// Yaw 0 faces +Z, so a point due +Z is dead ahead.
		assertEquals(0.0, Waypoint.bearing(0, 10, 0f), 1e-9);
	}

	@Test
	void behindIsOneEighty() {
		assertEquals(-180.0, Waypoint.bearing(0, -10, 0f), 1e-9);
	}

	@Test
	void turningTheCameraTurnsTheBearing() {
		// Facing +Z with the target at +Z: ahead. Turn 90 degrees and it moves side on.
		assertEquals(0.0, Waypoint.bearing(0, 10, 0f), 1e-9);
		assertEquals(-90.0, Waypoint.bearing(0, 10, 90f), 1e-9);
		assertEquals(90.0, Waypoint.bearing(0, 10, -90f), 1e-9);
	}

	@Test
	void bearingStaysInRangeForAnyYaw() {
		for (float yaw = -720; yaw <= 720; yaw += 7.5f) {
			final float currentYaw = yaw;
			double b = Waypoint.bearing(13, -27, currentYaw);
			assertTrue(b >= -180.0 && b < 180.0,
					() -> "out of range at yaw " + currentYaw + ": " + Waypoint.bearing(13, -27, currentYaw));
		}
	}

	@Test
	void wrapNormalisesFullTurns() {
		assertEquals(0.0, Waypoint.wrapDegrees(720.0), 1e-9);
		assertEquals(-90.0, Waypoint.wrapDegrees(270.0), 1e-9);
		assertEquals(10.0, Waypoint.wrapDegrees(370.0), 1e-9);
	}

	@Test
	void arrowPointsTheRightWay() {
		assertEquals("^", Waypoint.arrow(0));
		assertEquals(">", Waypoint.arrow(90));
		assertEquals("<", Waypoint.arrow(-90));
		assertEquals("v", Waypoint.arrow(180));
		assertEquals("v", Waypoint.arrow(-180));
	}

	@Test
	void arrowCoversEveryBearingWithoutFallingThrough() {
		for (double b = -180; b < 180; b += 0.5) {
			final double bearing = b;
			assertTrue(!Waypoint.arrow(bearing).isEmpty(), () -> "no arrow for " + bearing);
		}
	}
}
