# donut-flip

A tracker and advisor for **order flipping** on DonutSMP: buying items through
`/order` below the market and reselling them on `/ah` above it, pocketing the
spread.

It watches the auction house, works out what each item variant is *really* worth,
and ranks the items where that spread is worth your time and your coins. It is
read-only — it advises, it does not play the game for you.

```
  # ITEM                        BID ≤    LIST AT   PROFIT/U      ROI   SOLD/H    PROFIT/H   CONF FLAGS
  ───────────────────────────────────────────────────────────────────────────────────────────────────
  1 ancient_debris              32.4k      42.2k       7.7k    23.7%    172.9      266.1k   0.86  baitlow
  2 totem_of_undying            17.0k      25.7k       7.5k    43.9%    134.3      218.9k   0.88
  3 netherite_ingot            186.8k     236.5k      37.8k    20.3%      7.5       99.3k   0.64
  4 enchanted_golden_apple     112.8k     154.0k      33.4k    29.6%      9.5      111.2k   0.34
  5 shulker_shell               25.1k      30.5k       3.9k    15.6%     60.9       47.6k   0.73
```

## Try it in ten seconds

No API key, no network — runs against a built-in synthetic market:

```bash
cd donut-flip
node bin/donut-flip.js scan --mock --budget 25m
node bin/donut-flip.js serve --mock          # dashboard on http://localhost:8787
npm test
```

## Running it for real

```bash
export DONUT_API_KEY=your-key-here     # /api in game, or the DonutSMP dashboard
cp config.example.json config.json     # optional, but this is where you tune it
node bin/donut-flip.js scan --budget 50m
```

If the scan comes back empty or the numbers look absurd, run
`node bin/donut-flip.js probe` — it dumps the raw API response so you can check
the field names against `api.*` in your config. See
[When the API changes](#when-the-api-changes).

## How it decides

Ranking on the biggest spread is how people lose coins here. A 400% margin on an
item that trades twice a week is worth less than 12% on totems, and a spread
measured off one manipulated listing is not a spread at all. So each item variant
goes through this chain:

```
expected sell price  →  − AH tax        →  net proceeds
net proceeds         ÷  (1 + target ROI) →  the most you should ever bid
observed top order   ×  (1 + outbid)     →  what it actually costs you today
profit per unit      ×  realistic throughput → profit per hour
profit per hour      ×  confidence        → score        ← the ranking
```

The judgement calls behind that, and why:

- **Prices are per single unit, always.** The AH sells stacks. A 64-stack of
  totems at 1.6m is 25k an item, and comparing that 1.6m against a per-item order
  price would invent a margin that does not exist.
- **Variants are separate markets.** A Sharpness V netherite sword is not a plain
  one, and a filled shulker is not an empty one. Enchantments, potion type, trim,
  custom names and container contents are all part of the item's identity, so
  their prices are never averaged together.
- **Sales beat asks.** Anyone can list anything at any price; a sale means someone
  paid. When recent sales are cheaper than the ask floor, the bot believes the
  sales.
- **You have to undercut to sell.** The resale price is the ask floor minus
  `undercutPct`, not the ask floor itself.
- **Bait listings are ignored.** One item priced at a fraction of the pack gets
  excluded from the floor (it will be sniped in seconds) and the row is flagged
  `baitlow`.
- **Turnover is the point.** Throughput comes from units actually sold per hour,
  scaled by the share of that flow one player realistically captures and by how
  many sellers are already camped on the floor you need to undercut.
- **Confidence is a multiplier, not an average.** Sample size, price stability,
  listing depth, freshness and seller concentration each multiply, so one
  badly-evidenced input drags the whole row down instead of being averaged away.
  Under 0.35 is a guess; treat it as such.

Risk flags on each row: `thin` history, `nosales`, `volatile` prices, `monopoly`
(one seller owns the listings), `baitlow`, `nolist`, `falling` price, `ench`,
`contents`, `glut` (oversupplied), `nobook` (no observed bid — see below).

## The order-book gap, and what the bot does about it

**There is no public API for the `/order` book.** The auction house is exposed;
buy orders are not. The bot handles this two ways, and tells you which one each
row used:

**Advisory mode (default, `nobook`).** Working the maths backwards, the bot tells
you *the highest bid that still clears your target ROI* after tax — which is the
number you type into `/order` anyway. Every row shows exactly `targetRoiPct` by
construction, so here the ranking is what matters: it is sorted by how much
profit per hour each item can actually absorb.

**Observed mode.** Supply the top bids yourself and margins are measured against
the real book. Copy `data/orders.example.json` to `data/orders.json`, keep it
updated, and set:

```json
"orders": { "source": "file", "file": "data/orders.json" }
```

JSON (array, object, or `{orders:[...]}`) and CSV (`item,price,quantity`) all
work; prices accept `17k` / `1.5m` style suffixes. Rows priced from a real bid
carry more confidence than advisory ones, and a snapshot over 12 hours old warns.

## Commands

| Command | What it does |
| --- | --- |
| `scan` | One pass; prints the ranked table, writes `data/opportunities.json` |
| `watch` | Rescans on an interval, alerting on new or improved flips |
| `serve` | Dashboard on `http://localhost:8787` (scans cached 60s) |
| `probe` | Dumps raw API responses for diagnosing a shape change |

Useful flags: `--mock`, `--no-orders`, `--budget 25m`, `--min-roi 15`,
`--min-confidence 0.5`, `--include totem,debris`, `--exclude shulker_box`,
`--top 40`, `--interval 15`, `--json out.json`, `--verbose`. Coin amounts accept
`k`/`m`/`b` suffixes. `--help` for the full list.

With `--budget`, the bot also allocates it: greedy by score, capped per item at
`maxExposurePct` of the bankroll so one illiquid row cannot eat everything, and
never ordering more units than the market plausibly absorbs in a day.

Set `alerts.discordWebhook` (or `DONUT_DISCORD_WEBHOOK`) to get pushes from
`watch`. Repeat alerts for the same item are suppressed for `cooldownMinutes`
unless the opportunity gets materially better.

## Calibrate before you trust the numbers

Two settings decide whether the output is real. **Check them against the game.**

- `economics.ahTaxPct` — the cut the AH takes from your sale. Defaults to 5%.
  If the real rate is higher, every profit figure here is overstated.
- `economics.captureShare` — the share of observed flow one player captures.
  Defaults to 0.35. It scales profit-per-hour, so it changes the ranking.

Also worth tuning: `undercutPct` (how far below the floor you list),
`outbidPct` (how far above the top bid your order sits), `targetRoiPct`
(advisory mode), and `windows.salesLookbackHours` (shorter reacts faster and is
noisier).

The defaults are deliberately conservative — they understate profit rather than
overstate it, so a wrong assumption costs you a missed flip, not coins.

## When the API changes

The upstream API is not versioned in a way this repo controls, and third-party
mirrors reshape it. So nothing assumes an exact envelope: the extractor finds the
record array under any common wrapper and reads each field by trying known
spellings. Endpoints, methods, bodies and the auth header are all config, not
code.

**The API defaults here are unverified.** They were written from public
documentation; the API host was unreachable from the machine this was built on,
so they have never been exercised against the live service. The mock market,
tests and every calculation above are exercised. If the defaults are wrong,
`probe` will show you, and the fix is a config edit.

## Layout

```
bin/donut-flip.js   CLI
src/api.js          HTTP client: rate limiting, retries, pagination
src/extract.js      Tolerant readers for upstream JSON
src/items.js        Canonical item identity and per-unit pricing
src/market.js       Listings + sales → one row per item variant
src/flip.js         Pricing, profit, throughput, confidence, allocation
src/orders.js       Buy-order book from file
src/stats.js        Robust statistics (medians, MAD, outlier rejection)
src/store.js        Append-only price history for trend detection
src/report.js       Table, JSON report, Discord alerts
src/mock.js         Deterministic synthetic market
web/index.html      Dashboard (works served or as a static page)
test/               42 tests, no network needed
```

Zero dependencies; Node 18+.

## Fair play

This reads the public API and prints advice. It does not automate gameplay,
click for you, or touch the game client. Keep it that way, and check that your
use of the API fits the server's rules.
