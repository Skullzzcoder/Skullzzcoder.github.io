# Donut Flipper

Finds underpriced DonutSMP auction house listings using real sale history, and
shows them in a UI inside the game.

Java end to end. Fabric client mod for the interface, plain-Java analysis engine
behind it.

---

## How it works

```
  DonutSMP public API
          |
          v
  daemon  (headless, runs 24/7)          <-- collects sale history
    - polls /auction/list
    - polls /auction/transactions
    - values every item from completed sales
    - serves results on 127.0.0.1:8731
          |
          v
  mod     (Fabric, in game)              <-- shows you what to buy
    - press \ for the flip board
    - corner overlay while you play
```

The split exists for one reason: **valuations are built from completed sales, and
sales have to be collected continuously** — including the twenty hours a day you
are not logged in. A mod alone only sees the market while Minecraft is open,
which produces a badly biased sample. The daemon keeps watching regardless; the
mod is the window onto what it found.

## What makes it accurate

Three things do the real work.

**Item keying.** A Sharpness V + Mending netherite sword and a bare one are
different assets. Pricing them together is the classic way to lose money: the
tool sees enchanted copies selling for 400k, spots a bare one at 60k, and
reports a bargain nobody wants. Keys are built from material, enchantments,
wear band and container contents — never from the display name, because renaming
junk to look valuable is the oldest auction scam there is.

**Valuation from sales, not listings.** Listings show what sellers *hope* for. An
item listed at 10x market sits there forever, dragging any listing-based average
upward. Only completed transactions prove a price clears. Medians and trimmed
means throughout — auction prices are heavily right-skewed and an average chases
the whales.

**Manipulation resistance.** The obvious counter-play against any auction bot is
to sell junk to your alt at 10x three times, then list your copies at 6x and let
the bot buy them all. The filter drops repeated counterparty pairs, self-trades
and price outliers, and it refuses to price anything whose entire history is one
seller in one short burst.

Every number the UI shows comes with its evidence: `Strong 14/6` means fourteen
clean sales from six different sellers.

---

## Setup

### 1. Get an API key

Run `/api` in game on DonutSMP. You need a linked Discord account. This is a
one-off.

```bash
export DONUTSMP_API_KEY=your_key_here
```

Or put it in `~/.donutflipper/config.json`, which is created on first run.

> The key never goes in the repo and never goes in the mod. `.gitignore` covers
> the obvious accidents, but the real protection is that config lives in your
> home directory, outside any git tree.

### Day one, in order

```bash
export DONUTSMP_API_KEY=your_key_here
./gradlew :daemon:fatJar
J="java -jar daemon/build/libs/daemon-0.1.0-all.jar"

$J probe      # 1. what does the API actually return?
$J doctor     # 2. is anything broken?
$J collect    # 3. start gathering. leave it running.
# ...wait a day or two...
$J doctor     # 4. is there enough history yet?
$J backtest   # 5. would this actually have made money?
```

Steps 1 and 2 take a minute. Step 3 is the one that needs patience.

### 2. Confirm the API shape

```bash
./gradlew :daemon:run --args="probe"
```

This fetches one page from each endpoint, saves the raw JSON to
`~/.donutflipper/probe/`, and — importantly — reports whether the parser actually
understood it. **Do this first.** A parser that silently maps nothing produces an
empty database that looks exactly like a quiet market, and you would not notice
for days.

If it reports `MAPPER FAILED`, open the saved JSON and correct the alias arrays
at the top of `core/src/main/java/dev/skullzz/donutflipper/api/ApiMapper.java`.
That file is the only place the wire format is known.

The probe also writes `~/.donutflipper/probe/schema-report.md` — a compact,
paste-ready description of the wire format. It contains field names and one
sample record, and **no credentials**, so it is safe to share when asking for
help fixing the aliases.

### 2b. Diagnose

```bash
java -jar daemon/build/libs/daemon-0.1.0-all.jar doctor
```

Every failure in this system looks identical from the outside: an empty flip
list. A wrong key, mis-guessed field names, a collector that has only run an
hour, and a genuinely quiet market all present the same way. `doctor` tells you
which one you have, and it answers the two questions that decide the whole
strategy — whether the transaction feed exposes **buyer identity** (wash-trade
detection depends on it) and whether listings carry **enchantment data** (exact
keying depends on it).

### 3. Start collecting

```bash
./gradlew :daemon:fatJar
java -jar daemon/build/libs/daemon-0.1.0-all.jar collect
```

Leave it running.

> **Give it two or three days before trusting anything.** No sale history means
> no fair value. This is the single most common way people build one of these and
> lose money on day one. Nothing about the tool is broken during this period — it
> simply has not seen enough of the market yet.

### 4. Check whether it actually works

```bash
java -jar daemon/build/libs/daemon-0.1.0-all.jar backtest
```

Rewinds to a past moment, values everything using only sales from *before* that
moment, runs the real scanner, and checks each recommendation against what the
market actually did next. Reports hit rate and realised ROI.

Tune the profile thresholds against this, not against intuition. If it says
`NOT yet trustworthy`, believe it.

### 5. Build and install the mod

```bash
cd mod
./gradlew build
```

Drop `mod/build/libs/donutflipper-0.1.0.jar` into `.minecraft/mods` alongside
Fabric API.

| Key | Action |
|---|---|
| `\` | Open the flip board |
| `]` | Toggle the corner overlay |
| Click a row | Runs `/ah search` for that item |
| Shift-click a row | Copies the details to clipboard |

---

## Try it without a key

```bash
./gradlew :daemon:run --args="demo"
```

Runs the entire pipeline against a simulated auction house with **known true
prices**, including a planted wash-trading attack. Against live data you can
never tell a good valuation from a confidently wrong one; here you can check the
estimates against ground truth.

Current output recovers true prices to within a few percent, keeps the bare and
enchanted swords properly separated, refuses to price the illiquid elytra, and
holds its valuation within 2.3% of truth on the item being attacked at 4x.

---

## Strategy profiles

Switchable from the in-game UI. No rebuild.

| Profile | min ROI | min profit | min liquidity | evidence |
|---|---|---|---|---|
| `balanced` | 25% | 5k | 1.0 sales/day | Strong |
| `volume` | 15% | 1k | 3.0 sales/day | Fair |
| `whale` | 30% | 250k | 0.3 sales/day | Strong |

`whale` accepts slow-moving items because high-value gear trades slowly by
nature, but demands a large absolute profit to justify the capital sitting idle.
Move to it once the backtest says your valuations hold up.

---

## Project layout

```
core/     analysis engine, zero Minecraft dependency, 55 unit tests
daemon/   headless collector + localhost API
mod/      Fabric client mod (built separately - needs Minecraft artifacts)
```

```bash
./gradlew :core:test        # run the engine test suite
```

104 tests cover keying, valuation, the manipulation filter, the wire-format
parser across every payload shape it claims to handle, backtest isolation, and
end-to-end ingest against a real local HTTP server.

`core` has no Minecraft dependency on purpose: valuation logic is testable in
plain JUnit, and a Minecraft update cannot break your analysis engine. Only the
two classes that draw pixels touch Minecraft at all.

---

## On automation

The mod is a display. It reads the public API and draws the result; it does not
click auction GUI slots or play the game for you.

The semi-automatic assist runs the `/ah search` command you would have typed and
copies the seller name — the tedious part is finding the right listing among
thousands, and that is fully automated. The final buy is yours.

DonutSMP's rules treat automated input scripts as bannable, and appeals go
through Discord only. Anything beyond the current assist is worth deciding on
deliberately rather than by default.
