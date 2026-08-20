# Open questions — resolve on first live run

> **Fastest path:** run `daemon doctor`. It answers items 1, 3 and 4 below
> directly and tells you which of the rest still need looking at.

Everything here is a guess that the code is currently built to tolerate. Each one
has a defined answer procedure and a named place to change.

## 1. Auction house tax rate

**Assumed:** 5% (`auctionTaxRate` in `~/.donutflipper/config.json`)

Deliberately set high. Overestimating makes the scanner miss marginal flips;
underestimating makes it recommend trades that lose money. Wrong-and-cautious
beats wrong-and-greedy.

**How to confirm:** sell one item at a known price and compare the coins received
against the listing price.

## 2. Exact API field names

**Assumed:** a wide set of aliases in `ApiMapper` (`id`/`uuid`/`listingId`,
`price`/`cost`/`amount`, and so on).

**How to confirm:** `daemon probe`. It saves raw JSON and reports whether the
parser matched. Trim the alias arrays to the confirmed names afterwards — a
narrow correct list is easier to reason about than a wide speculative one.

## 3. Does the transaction feed expose buyer identity?

**Matters because:** wash-trade detection works on counterparty pairs. Without
buyer names, `removeWashPairs` degrades to a no-op and outlier trimming carries
the load alone — weaker, but not broken. There is already a test covering the
anonymous case.

**How to confirm:** `probe` prints `buyer identity present on N/M sales`.

## 4. Do listings expose full enchantment data?

**The big one.** If listings only carry a material id and no NBT, exact keying is
impossible and everything degrades to the family tier. That would change the
strategy substantially — away from gear and toward high-volume commodity flips
where material alone identifies the item.

**How to confirm:** look at the saved `auction_list.json` from `probe` for an
enchanted item.

## 5. Pagination depth on transactions

**Assumed:** newest-first, overlapping between polls, capped at 60 pages.

**Matters because:** it sets how quickly initial history can be built. If the feed
reaches back days, the 2–3 day warm-up shortens considerably.

**How to confirm:** walk pages in `probe` and watch the oldest timestamp.

## 6. Listing duration and per-player listing caps

**Matters because:** the backtest treats a listing disappearing as a sale. If
listings expire on a timer, some disappearances are expirations, which inflates
apparent liquidity.

**How to confirm:** track a specific listing id from first sighting to
disappearance and compare against the transaction feed.

## 7. Does the mod's own polling share the API budget?

Currently no — the mod reads the local daemon, not the API. If that ever changes,
`rateLimitUtilisation` (default 0.75) is the headroom that keeps the two from
colliding into 429s.
