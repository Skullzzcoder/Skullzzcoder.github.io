package dev.skullzz.donutflipper.model;

import java.time.Instant;

/**
 * A completed auction house transaction -- an item someone actually paid for.
 *
 * <p>Sales, not listings, are the basis for every valuation in this project.
 * Current listings tell you what sellers <em>hope</em> to get, and the tail of
 * that distribution is pure fantasy; a listing sitting unsold at 10x market
 * still shows up in the listing feed forever. Only a completed sale is evidence
 * that a price clears.
 *
 * @param saleId  server-side transaction id
 * @param seller  who sold it
 * @param buyer   who bought it; may be null if the API does not expose it
 * @param price   total paid for the whole stack
 * @param item    what changed hands
 * @param soldAt  when the sale completed
 */
public record Sale(
        String saleId,
        String seller,
        String buyer,
        long price,
        AuctionItem item,
        Instant soldAt
) {

    public double unitPrice() {
        return (double) price / (double) Math.max(1, item.count());
    }

    public ItemKey key() {
        return ItemKey.of(item);
    }

    /**
     * Identifies the counterparty pair for wash-trade detection. Null-safe and
     * order-preserving: we want to know that seller A repeatedly sold to buyer B,
     * which is the signature of someone inflating a price against their own alt.
     */
    public String counterpartyPair() {
        return (seller == null ? "?" : seller.toLowerCase())
                + ">"
                + (buyer == null ? "?" : buyer.toLowerCase());
    }

    public boolean hasKnownBuyer() {
        return buyer != null && !buyer.isBlank();
    }
}
