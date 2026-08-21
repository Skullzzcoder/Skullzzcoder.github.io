package com.skullzz.glaze.core;

/**
 * A single auction house entry read off an open menu.
 *
 * <p>Built from the item a slot already contains - the mod reads the menu you
 * opened and nothing else.
 */
public record AuctionListing(String itemName, int quantity, long price, String seller, int slot) {
	/** Asking price for one item, which is what prices should be compared on. */
	public long unitPrice() {
		return quantity <= 0 ? price : price / quantity;
	}

	public boolean hasPrice() {
		return price > 0;
	}
}
