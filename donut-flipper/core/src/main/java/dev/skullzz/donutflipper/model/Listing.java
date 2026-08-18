package dev.skullzz.donutflipper.model;

import java.time.Instant;

/**
 * A live auction house listing.
 *
 * @param listingId  server-side id; the dedupe handle so one listing is never alerted twice
 * @param seller     seller username
 * @param price      total asking price for the whole stack, in coins
 * @param item       what is being sold
 * @param firstSeen  when our poller first observed it
 * @param lastSeen   most recent sweep that still saw it; how we detect it going away
 */
public record Listing(
        String listingId,
        String seller,
        long price,
        AuctionItem item,
        Instant firstSeen,
        Instant lastSeen
) {

    /**
     * Price per single unit.
     *
     * <p>Comparing raw prices is the single easiest way to generate nonsense: a
     * stack of 64 at 64,000 and a single at 1,500 look like a 42x difference in
     * the price column and are actually a good deal and a bad one respectively.
     * Everything downstream compares unit prices.
     */
    public double unitPrice() {
        return (double) price / (double) Math.max(1, item.count());
    }

    public ItemKey key() {
        return ItemKey.of(item);
    }

    /** How long this listing has been sitting unsold, in seconds. */
    public long ageSeconds(Instant now) {
        return Math.max(0, now.getEpochSecond() - firstSeen.getEpochSecond());
    }
}
