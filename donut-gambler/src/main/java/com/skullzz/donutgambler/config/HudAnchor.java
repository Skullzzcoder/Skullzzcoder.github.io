package com.skullzz.donutgambler.config;

/** Screen corner the HUD offsets are measured from. */
public enum HudAnchor {
	TOP_LEFT("Top left"),
	TOP_RIGHT("Top right"),
	BOTTOM_LEFT("Bottom left"),
	BOTTOM_RIGHT("Bottom right");

	private final String label;

	HudAnchor(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public HudAnchor next() {
		return values()[(ordinal() + 1) % values().length];
	}

	public boolean isRight() {
		return this == TOP_RIGHT || this == BOTTOM_RIGHT;
	}

	public boolean isBottom() {
		return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
	}
}
