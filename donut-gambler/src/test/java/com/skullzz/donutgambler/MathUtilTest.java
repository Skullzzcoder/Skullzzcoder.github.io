package com.skullzz.donutgambler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.skullzz.donutgambler.advisor.MathUtil;

class MathUtilTest {
	@Test
	void expectedValueMatchesTheOdds() {
		assertEquals(0.0, MathUtil.expectedValue(0.5, 1.0), 1e-12);
		assertEquals(-0.025, MathUtil.expectedValue(0.5, 0.95), 1e-12);
		assertEquals(0.2, MathUtil.expectedValue(0.6, 1.0), 1e-12);
	}

	@Test
	void kellyRefusesToSizeANegativeEdge() {
		assertEquals(0.0, MathUtil.kellyFraction(0.5, 0.95), 1e-12);
		assertEquals(0.2, MathUtil.kellyFraction(0.6, 1.0), 1e-12);
	}

	@Test
	void flatBettingANegativeEdgeAlwaysRuinsYou() {
		assertEquals(1.0, MathUtil.riskOfRuin(0.5, 0.95, 1_000_000, 10_000), 1e-12);
	}

	@Test
	void ruinRiskFallsAsTheBankrollDeepens() {
		double shallow = MathUtil.riskOfRuin(0.6, 1.0, 1000, 200);
		double deep = MathUtil.riskOfRuin(0.6, 1.0, 1000, 20);
		assertTrue(deep < shallow, "more units of bankroll must be safer");
		assertTrue(shallow <= 1.0 && deep >= 0.0);
	}

	@Test
	void normalCdfAndPValues() {
		assertEquals(0.5, MathUtil.normalCdf(0), 1e-6);
		assertEquals(0.975, MathUtil.normalCdf(1.96), 1e-3);
		assertTrue(MathUtil.pValueAtLeast(18, 22, 0.5) < 0.01, "18 of 22 is hard to explain by luck");
		assertTrue(MathUtil.pValueAtLeast(12, 22, 0.5) > 0.2, "12 of 22 is ordinary");
	}

	@Test
	void wilsonIntervalBracketsTheObservedRate() {
		double lower = MathUtil.wilsonLower(38, 100, 1.96);
		double upper = MathUtil.wilsonUpper(38, 100, 1.96);
		assertTrue(lower < 0.38 && upper > 0.38);
		assertTrue(lower >= 0 && upper <= 1);
	}
}
