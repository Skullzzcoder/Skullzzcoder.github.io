package com.skullzz.donutgambler.gui.tabs;

import com.skullzz.donutgambler.DonutGambler;
import com.skullzz.donutgambler.config.GamblerConfig;
import com.skullzz.donutgambler.gui.Fields;
import com.skullzz.donutgambler.gui.GamblerScreen;

/** HUD placement and which notifications you want in chat. */
public class DisplayTab extends SettingsTabBase {
	@Override
	public String title() {
		return "Display";
	}

	@Override
	public void init(GamblerScreen screen) {
		labels.clear();
		GamblerConfig c = DonutGambler.config();

		screen.add(Fields.toggle(labelX(screen, 0), rowY(screen, 0), CONTROL_WIDTH + 90, 18,
				"HUD", () -> c.hudEnabled, v -> c.hudEnabled = v));

		screen.add(Fields.cycle(labelX(screen, 0), rowY(screen, 1), CONTROL_WIDTH + 90, 18,
				"Corner", () -> c.hudAnchor.label(), () -> c.hudAnchor = c.hudAnchor.next()));

		label(screen, 0, 2, "HUD x offset");
		screen.add(Fields.number(screen.font(), controlX(screen, 0), rowY(screen, 2), CONTROL_WIDTH, 18,
				"HUD x", c.hudX, 0, 4000, v -> c.hudX = (int) v));

		label(screen, 0, 3, "HUD y offset");
		screen.add(Fields.number(screen.font(), controlX(screen, 0), rowY(screen, 3), CONTROL_WIDTH, 18,
				"HUD y", c.hudY, 0, 4000, v -> c.hudY = (int) v));

		label(screen, 0, 4, "HUD scale");
		screen.add(Fields.number(screen.font(), controlX(screen, 0), rowY(screen, 4), CONTROL_WIDTH, 18,
				"HUD scale", c.hudScale, 0.5, 3, v -> c.hudScale = v));

		label(screen, 0, 5, "Background alpha (0-255)");
		screen.add(Fields.number(screen.font(), controlX(screen, 0), rowY(screen, 5), CONTROL_WIDTH, 18,
				"HUD alpha", c.hudBackgroundAlpha, 0, 255, v -> c.hudBackgroundAlpha = (int) v));

		note(screen, 0, 6, "Press the open key (default G) to reopen this menu.");

		screen.add(Fields.toggle(labelX(screen, 1), rowY(screen, 0), CONTROL_WIDTH + 90, 18,
				"Show session line", () -> c.hudShowSession, v -> c.hudShowSession = v));

		screen.add(Fields.toggle(labelX(screen, 1), rowY(screen, 1), CONTROL_WIDTH + 90, 18,
				"Show all-time line", () -> c.hudShowAllTime, v -> c.hudShowAllTime = v));

		screen.add(Fields.toggle(labelX(screen, 1), rowY(screen, 2), CONTROL_WIDTH + 90, 18,
				"Show streak line", () -> c.hudShowStreak, v -> c.hudShowStreak = v));

		screen.add(Fields.toggle(labelX(screen, 1), rowY(screen, 3), CONTROL_WIDTH + 90, 18,
				"Show max bet", () -> c.hudShowRecommendedBet, v -> c.hudShowRecommendedBet = v));

		screen.add(Fields.toggle(labelX(screen, 1), rowY(screen, 4), CONTROL_WIDTH + 90, 18,
				"Show warnings", () -> c.hudShowAdvice, v -> c.hudShowAdvice = v));

		screen.add(Fields.toggle(labelX(screen, 1), rowY(screen, 5), CONTROL_WIDTH + 90, 18,
				"Chat: every bet", () -> c.chatNotifyOnBet, v -> c.chatNotifyOnBet = v));

		screen.add(Fields.toggle(labelX(screen, 1), rowY(screen, 6), CONTROL_WIDTH + 90, 18,
				"Chat: warnings", () -> c.chatNotifyOnAlert, v -> c.chatNotifyOnAlert = v));
	}
}
