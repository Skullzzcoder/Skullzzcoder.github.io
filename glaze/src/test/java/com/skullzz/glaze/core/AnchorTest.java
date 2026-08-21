package com.skullzz.glaze.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnchorTest {
	@Test
	void topLeftMeasuresFromTheTopLeft() {
		assertEquals(4, Anchor.TOP_LEFT.resolveX(4, 50, 400));
		assertEquals(4, Anchor.TOP_LEFT.resolveY(4, 10, 300));
	}

	@Test
	void rightAnchorMeasuresInwardFromTheRightEdge() {
		// 400 wide screen, 50 wide element, 4px from the right edge.
		assertEquals(346, Anchor.TOP_RIGHT.resolveX(4, 50, 400));
	}

	@Test
	void bottomAnchorMeasuresUpwardFromTheBottom() {
		assertEquals(286, Anchor.BOTTOM_LEFT.resolveY(4, 10, 300));
	}

	@Test
	void centreAnchorsCentreTheElement() {
		assertEquals(175, Anchor.TOP_CENTER.resolveX(0, 50, 400));
	}

	@Test
	void elementsStayOnScreenWhenTheWindowShrinks() {
		int x = Anchor.TOP_LEFT.resolveX(9_000, 50, 400);
		assertTrue(x >= 0 && x + 50 <= 400, () -> "x was " + x);
	}

	@Test
	void anOversizedElementPinsToZeroRatherThanGoingNegative() {
		assertEquals(0, Anchor.TOP_RIGHT.resolveX(4, 500, 400));
	}

	@Test
	void offsetsRoundTripThroughAbsolutePositions() {
		for (Anchor anchor : Anchor.values()) {
			int absoluteX = anchor.resolveX(20, 60, 640);
			int absoluteY = anchor.resolveY(30, 12, 480);

			assertEquals(20, anchor.toOffsetX(absoluteX, 60, 640), () -> "x for " + anchor);
			assertEquals(30, anchor.toOffsetY(absoluteY, 12, 480), () -> "y for " + anchor);
		}
	}

	@Test
	void nearestPicksTheCornerADropLandedIn() {
		assertEquals(Anchor.TOP_LEFT, Anchor.nearest(10, 10, 600, 400));
		assertEquals(Anchor.TOP_RIGHT, Anchor.nearest(590, 10, 600, 400));
		assertEquals(Anchor.BOTTOM_LEFT, Anchor.nearest(10, 390, 600, 400));
		assertEquals(Anchor.BOTTOM_RIGHT, Anchor.nearest(590, 390, 600, 400));
		assertEquals(Anchor.TOP_CENTER, Anchor.nearest(300, 10, 600, 400));
	}
}
