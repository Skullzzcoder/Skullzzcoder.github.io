# Donut Gambler

A client-side Fabric mod for Minecraft **1.21.11** that tracks your DonutSMP gambling and tells you,
in numbers, whether you should be doing it.

It watches chat for gambling results, logs every bet, and turns that history into advice from four
angles at once:

| Angle | What it does |
| --- | --- |
| **Bankroll maths** | Kelly-sized bet ceilings, a hard % cap, and your risk of losing the whole bankroll at your current bet size |
| **Your own stats** | Win rate, ROI, streaks and profit per game, per opponent and over time - with confidence intervals, so you know when a sample is too small to mean anything |
| **Per-game EV** | You set each game's odds, payout and house tax; it works out the edge and what it costs you per million staked |
| **Opponent tracking** | Every player's record against you and a p-value: how unlikely their run of wins is if the game were fair |

Plus tilt detection: loss streaks, bet chasing after a loss, bets-per-minute, and stop-loss / stop-win limits you set yourself.

**It never gambles for you.** The mod only reads chat and draws overlays - it sends no commands, clicks
no GUIs, and automates nothing. That is deliberate: passive HUD mods are the kind servers allow, macros
and autoclickers are not.

---

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer) 0.17.3+ for Minecraft 1.21.11.
2. Drop these in `.minecraft/mods/`:
   - [Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.11
   - `donut-gambler-1.0.0.jar` (from the [Actions build artifacts](../../actions) or `./gradlew build`)
3. Launch, join DonutSMP, press **G**.

## First five minutes

The shipped chat patterns are deliberately generic, because every server words its messages
differently. Tune them once and everything else works by itself:

1. Gamble a couple of times so the mod captures some chat.
2. Press **G** -> **Games** -> click **Coinflip**.
3. Hit **Build win**, click the exact line the server printed when you won, and hit **Use as WIN pattern**.
   The mod turns that line into a regex, with the money as `(?<amount>...)` and the player as `(?<opponent>...)`.
4. Do the same with **Build loss**.
5. Press **Test** to see how many of your recent chat lines each pattern catches.
6. Set the real odds: **Win chance**, **Payout** and **House tax** drive every number the advisor prints.
7. Tell it your money: `/gambler balance 25m` (or leave balance-reading on and run whatever command
   shows your balance once).

That is it. From then on, every result is logged automatically.

## The screen (press G)

- **Dashboard** - session and all-time profit, a cumulative-profit chart, and the advisor's full reasoning line by line.
- **Games** - every game with its edge, its Kelly bet size and your measured record. Click to edit, click `ON/OFF` to toggle, `x` to delete.
- **Opponents** - who you play, their win rate against you, what they have taken, and a p-value. Rows in red are hard to explain by luck.
- **Log** - every bet, newest first, with **Undo last** for a mis-parse.
- **Rules** - bankroll, Kelly fraction, max bet %, stop-loss, stop-win, tilt thresholds, opponent-alert settings.
- **Display** - HUD corner, offsets, scale, which lines it shows, and which notifications go to chat.

## Commands

All client-side; none of them are sent to the server.

```
/gambler                                open the screen
/gambler stats                          session and all-time totals, split by game
/gambler advice                         the full advisor read-out in chat
/gambler balance <amount>               set your bankroll (accepts 25m, 1.5k, 250000)
/gambler log <game> <win|loss|push> <amount> [opponent]
                                        log a bet by hand when chat parsing missed it
/gambler undo                           remove the last logged bet
/gambler session reset                  start a new session from now
/gambler export                         write the whole history to CSV
/gambler hud                            toggle the overlay
```

## What the numbers mean

- **Edge** - expected profit per unit staked: `p * payout - (1 - p)`, after the house tax. A 5%-tax
  coinflip is `0.5 * 0.95 - 0.5 = -2.5%`: every $1M you stake gives back $975K on average.
- **Kelly** - the stake that maximises long-run growth: `(p*b - q) / b` of your bankroll. Full Kelly is
  famously wild, so the default bets a quarter of it and caps every bet at 5% of bankroll. On a
  negative-edge game Kelly is zero, and the mod says so instead of sizing a bet.
- **Risk of ruin** - the chance of losing the entire bankroll flat-betting your current stake. Even-money
  games use the exact `(q/p)^units` result; other payouts use the standard diffusion approximation.
- **95% CI** - a Wilson interval on your measured win rate. If the odds you configured sit outside it,
  either the game is not what it claims or the pattern is mislabelling results.
- **p-value** - for an opponent, the chance a fair game would hand them a run this good. 0.002 means
  roughly 1 in 500.

None of this beats a negative edge. The mod's most useful answer is often "do not bet".

## Files

Everything lives in `.minecraft/config/donutgambler/`:

- `config.json` - settings and game definitions
- `history.json` - every logged bet
- `export-*.csv` - CSV exports

## Building

```bash
cd donut-gambler
./gradlew build          # jar lands in build/libs/
./gradlew test           # advisor, parser and storage unit tests
```

CI builds the jar on every push and attaches it to the run as an artifact.

### Building for a different Minecraft version

Edit `gradle.properties`:

```properties
minecraft_version=1.21.11
loader_version=0.17.3
fabric_version=0.141.6+1.21.11
```

Look the matching versions up on the [Fabric versions page](https://fabricmc.net/develop/). Note that
this mod is written against **official Mojang mappings**, the same set Fabric API uses on 1.21.11 - if
you drop to a much older version, some names (`GuiGraphics`, `Identifier`, `KeyMapping.Category`,
`HudElementRegistry`) differ and will need adjusting.

## A word on the actual gambling

The advisor is honest about the maths, and the maths on a taxed coinflip is not on your side: bet it
long enough and you lose, no matter how the last ten went. The stop-loss, stop-win and tilt warnings
exist because they are the only part of this that reliably saves money. Set them before you need them.
