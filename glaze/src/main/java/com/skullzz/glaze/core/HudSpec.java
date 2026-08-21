package com.skullzz.glaze.core;

/** Where one HUD readout sits and whether it is shown at all. */
public final class HudSpec {
	public String id = "";
	public boolean enabled = true;
	public Anchor anchor = Anchor.TOP_LEFT;
	public int offsetX = 4;
	public int offsetY = 4;

	public HudSpec() {
	}

	public HudSpec(String id, boolean enabled, Anchor anchor, int offsetX, int offsetY) {
		this.id = id;
		this.enabled = enabled;
		this.anchor = anchor;
		this.offsetX = offsetX;
		this.offsetY = offsetY;
	}

	/** Guards against a hand-edited config leaving fields null. */
	public HudSpec sanitised() {
		if (anchor == null) {
			anchor = Anchor.TOP_LEFT;
		}

		if (id == null) {
			id = "";
		}

		offsetX = Math.max(0, offsetX);
		offsetY = Math.max(0, offsetY);
		return this;
	}
}
