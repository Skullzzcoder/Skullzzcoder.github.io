# Glaze

Client-side quality-of-life tools for DonutSMP. Fabric, Minecraft 1.21.11.

Glaze watches what your client already receives — chat messages, the menus you
open, your own inventory — and shows you more useful things about it. It does not
ask the server for anything, does not act for you, and does not show you anything
the server has not already sent.

## What it does

**Economy and auction house**

- Price tooltips on every item: median, low/high, sample count, and the value of
  the stack you are hovering.
- A price book built from auction pages you open and trades you complete. Prices
  are stored per single item, so a stack of 64 and a single unit are comparable.
- Deal highlighting — listings priced well under their median get tinted green.
  Configurable threshold, and it stays quiet until it has enough samples to have
  an opinion.
- Watchlist alerts with optional per-item price ceilings.
- `/glaze filter <text>` picks out matching slots in an open auction page and
  dims the rest.
- `/glaze price <item>` prints median, range, quartiles and last-seen price.

**Inventory and storage**

- Shulker box and bundle tooltips show their contents, merged into one line per
  item type, with the total value of what is inside.
- Hovering an item tells you how many you are carrying in total, including inside
  shulker boxes.
- Durability warnings before gear breaks, throttled per item.
- Low-consumable warnings for pearls, totems, gapples and crystals — thresholds
  configurable, and it only warns about things you were actually carrying.
- Loadout checker: define a kit, press a key, get told what you are missing. It
  reports only — it never moves an item.
- Quick-stash (off by default, see the rules note below).

**HUD and session tracking**

- Draggable readouts: session time, balance, session earnings, money per hour,
  kills/deaths, coordinates, ping, combat timer, inventory value, consumable
  counts, nearest waypoint.
- Money-per-hour that excludes idle time, so an AFK stretch does not flatten it.
- Combat tag countdown driven by the server's own combat messages.
- Death points saved automatically, with the coordinates also printed to chat so
  they survive even if the config is lost.
- Personal waypoints with a direction arrow and distance.

## Installing on Lunar Client

Lunar loads external Fabric mods, but it does not bundle Fabric API, so you need
both jars:

1. Build the mod (below) or grab `glaze-x.y.z+mc1.21.11.jar` from `build/libs/`.
   Ignore the `-sources` jar.
2. Download **Fabric API 0.141.6+1.21.11** from Modrinth or CurseForge.
3. Drop both into `~/.lunarclient/profiles/<your profile>/1.21/mods/`
   (`%USERPROFILE%\.lunarclient\...` on Windows).
4. Restart Lunar and pick the 1.21 profile.

If Lunar's own HUD elements overlap Glaze's, move Glaze's with the HUD editor
rather than fighting it — press the "Edit HUD layout" key (see Controls) and drag.

On vanilla Fabric, install Fabric Loader 0.17.3+ and drop both jars in `.minecraft/mods`.

## Building

```
cd glaze
./gradlew build
```

Needs JDK 21. The jar lands in `build/libs/`.

The build resolves the current yarn mappings build number from `meta.fabricmc.net`
at configure time and falls back to `yarn_mappings_fallback` in `gradle.properties`
when offline. If you want to pin it, look the value up at
<https://fabricmc.net/develop/> and set it there.

`./gradlew test` runs the unit tests. They cover the parsing and maths — money
formats, chat patterns, price statistics, session timing, HUD anchoring, waypoint
bearings — and need no Minecraft jar.

## Controls

Every key binding ships **unbound** so nothing is taken from another mod. Assign
them under Options → Controls → Glaze:

| Action | What it does |
| --- | --- |
| Open Glaze settings | The toggle screen |
| Edit HUD layout | Drag readouts, right-click to show/hide |
| Check loadout | Prints what your kit is missing |
| Clear auction filter | Cancels `/glaze filter` |
| Quick-stash into container | Only if you enabled it |

## Commands

| Command | What it does |
| --- | --- |
| `/glaze` | Command list |
| `/glaze stats` | Session summary |
| `/glaze loadout` | What your kit is missing |
| `/glaze price <item>` | Price history for an item |
| `/glaze filter <text>` | Highlight matching auction slots |
| `/glaze waypoint add\|remove\|list` | Your own saved places |
| `/glaze chatlog` | Print raw chat text, for writing patterns |
| `/glaze reload` | Re-read `config.json` from disk |

All of these are client commands. Nothing is sent to the server.

## Fixing the chat patterns

This is the part most likely to need your attention.

Glaze reads your balance, payments, purchases, sales, kills and combat tag out of
chat, using regexes in `config/glaze/config.json`. The defaults are best-effort:
they were written without a live connection to the server, so some of them will
not match DonutSMP's exact wording.

When a readout stays blank:

1. Run `/glaze chatlog` and do the thing that should have been picked up — check
   your balance, take a payment, get combat tagged.
2. Copy the raw line it prints.
3. Edit the matching entry in `chatPatterns` in `config/glaze/config.json`.
4. Run `/glaze reload`.

Named groups you can use: `amount`, `player`, `item`, `qty`. A pattern that will
not compile is reported in chat and skipped — it never takes the mod down.

The same applies to `listingPricePatterns` and `listingSellerPatterns`, which read
auction menu lore.

## Server rules

Everything in this mod except quick-stash is read-only. It renders information
your client already has: chat it received, menus you opened, your own inventory.
That is the same category as a stats overlay or an inventory tooltip mod, and it
is the reason the mod is built the way it is.

Two deliberate omissions:

- **No player tracking.** No radar, no tracers, no entity ESP, no "where is that
  player" of any kind. Waypoints only mark places *you* saved.
- **No automated buying.** Deal highlighting tints a slot. It cannot click one.

**Quick-stash is the exception** and is off by default. It sends real shift-click
interactions to the server. It is throttled to one click per configurable interval
with random jitter, capped per action, built only from atomic single-slot moves,
and abandoned the moment the menu changes. Inventory-tweak mods of this shape are
generally accepted, but "generally" is not "definitely" — check the current rules,
or ask staff, before you turn it on. That is your call to make, not this README's.

If you are unsure about any of it, ask a staff member. Getting banned over a
convenience feature would be a bad trade.

## Known gaps

- **No server TPS readout.** Measuring real server tick rate from the client needs
  a hook into the world-time packet, and the handler's name could not be verified
  for 1.21.11 — a wrong mixin target crashes the game at launch, which is a bad
  trade for one number. Ping is shown instead.
- **No container sorting.** Sorting means multi-step cursor operations on real
  items; shipping that without being able to test it risks scattering or losing
  someone's inventory. Quick-stash, which is atomic per slot, is there instead.
- **Shulker previews are text, not a rendered grid.** A grid needs a custom
  tooltip component; the text version conveys the same information.
- **Waypoints are HUD-only** — an arrow, a name and a distance, with no in-world
  beam.

## Layout

```
src/main/java/com/skullzz/glaze/
  core/      plain Java: money, chat patterns, price stats, session maths, config
  mc/        the adapter over the Minecraft client API, keybinds, commands
  feature/   chat, session, warnings, tooltips, auction scanning, quick-stash
  hud/       HUD rendering and the layout editor
  config/    JSON persistence and the settings screen
  mixin/     one accessor, for the container menu's origin
src/test/java/  unit tests for everything under core/
```

`core/` deliberately imports nothing from Minecraft. That is what makes the tests
runnable without the game, and it is where the logic worth testing lives.
