package com.skullzz.donutgambler.advisor;

import java.util.ArrayList;
import java.util.List;

/** The advisor's read on your current situation: one verdict, a headline, and the reasoning. */
public class Advice {
	public enum Verdict {
		/** Nothing wrong: the bet is sized, the game is not obviously rigged, you are not tilted. */
		GREEN("OK", 0xFF4CE07A),
		/** Something is off. Read the lines before betting again. */
		YELLOW("CAUTION", 0xFFE8C547),
		/** Stop. Losing streak, blown stop-loss, negative edge you keep paying, or a flagged opponent. */
		RED("STOP", 0xFFE5544B);

		public final String label;
		public final int color;

		Verdict(String label, int color) {
			this.label = label;
			this.color = color;
		}
	}

	public enum Severity {
		GOOD(0xFF4CE07A),
		INFO(0xFFB9BCC4),
		WARN(0xFFE8C547),
		BAD(0xFFE5544B);

		public final int color;

		Severity(int color) {
			this.color = color;
		}
	}

	public record Line(Severity severity, String text) {
	}

	public Verdict verdict = Verdict.GREEN;
	public String headline = "Nothing flagged.";
	public final List<Line> lines = new ArrayList<>();
	/** Kelly-sized, cap-limited stake for the focus game. 0 means "do not bet". */
	public double recommendedBet;
	/** Chance of losing the whole bankroll flat-betting at the current typical stake. */
	public double riskOfRuin;
	public String focusGameId = "";
	public String focusGameName = "";

	public void add(Severity severity, String text) {
		lines.add(new Line(severity, text));

		if (severity == Severity.BAD) {
			verdict = Verdict.RED;
		} else if (severity == Severity.WARN && verdict != Verdict.RED) {
			verdict = Verdict.YELLOW;
		}
	}

	/** The first line that drove the verdict, used for the HUD and chat pings. */
	public String primaryReason() {
		for (Line l : lines) {
			if (l.severity() == Severity.BAD) return l.text();
		}

		for (Line l : lines) {
			if (l.severity() == Severity.WARN) return l.text();
		}

		return lines.isEmpty() ? headline : lines.get(0).text();
	}
}
