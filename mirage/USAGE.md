# Mirage — how each part works

Everything here happens on **your client only**. Nobody else sees any of it and
nothing is ever sent to the server: the client edits its own copy of what is on
screen, so there is nothing on the wire for anyone to notice.

Two config files, both under your instance's `config/` folder:

| file | holds |
|---|---|
| `mirage-client.json` | every fake, every rig, the dashboard settings |
| `mirage-prices.json` | prices, lore format, and the price API key |

Menu: `/fake ui`. Dashboard: <http://127.0.0.1:25599> (starts by itself).
Check anything: `/fake list`.

## The off switch

**`N`**, the top button on the dashboard, or `/fake off` and `/fake on`.

Off puts everything real back at once — inventory, ender chest, whatever
container is open, the dispenser layouts, the item frames and armour stands —
and takes anything still in the air with it, mid-flight. Your screen becomes
indistinguishable from a client with no mod installed.

**Nothing is forgotten.** Every rig, layout, price and fake stays exactly where
it was, and `N` again brings all of it straight back. It is remembered across
restarts, so if you leave it off it stays off.

While it is off, the other keys do nothing and say so rather than stacking up
and all firing at once when it comes back. `N` always prints which way it went:
being unsure which state you are in is the one thing worse than no switch.

---

## 1. Fake inventory items

    /fake set hotbar1 diamond_block
    /fake set hotbar1 diamond_block 64
    /fake set hotbar1 netherite_sword 1 sharpness:5,mending:1

Slot names tab-complete (`hotbar1`–`hotbar9`, `main1`–`main27`, `offhand`,
armour slots). A slot holding a **real** item is never touched, so nothing of
yours can be lost or hidden.

`/fake list` shows what is set. `/fake clear` wipes the lot.

## 2. Fake ender chest

    /fake ender 0 shulker_box 64
    /fake ender clear

## 3. What a dispenser looks like it is holding

**This happens by itself.** Watch a dispenser and it is laid out at once, from
the rig it belongs to:

| rig | the GUI shows |
|---|---|
| roulette | 8 blanks in a ring, the loaded item in the middle slot |
| paper | slips 1–9, each named for the side that machine plays |
| a dispenser with a fixed answer | nine of that |
| anything else | one of each of the rig's items, in order |

A coin flip therefore holds one gold block in slot 0 and one diamond block in
slot 1 — nothing else. Holding both means flipping which way it is rigged does
not change what is in the box.

The order is your preset order. To set it, or swap it round:

    /fake preset clear
    /fake preset add gold_block
    /fake preset add diamond_block
    /fake dispenser fill

**When it fires, the item leaves.** One of the matching slots empties, chosen at
random the way a real dispenser picks, so opening it afterwards agrees with what
everyone just watched come out. This is on for every game, not only roulette.

### Moving items by hand

**Shift-click** works in both directions while a dispenser is open:

- shift-click a fake in your inventory → it loads into the dispenser
- shift-click one in the dispenser → it comes back to your inventory

It stacks onto a matching slot first and takes the next free slot otherwise,
same as loading a real dispenser. So the loop closes: the crystal fires, lands
in your inventory, and you shift-click it back into the middle for the next
round without typing anything.

A plain (non-shift) click on a faked slot does nothing at all, deliberately.
Vanilla would send that click to the server, which sees the slot's **real**
contents — so a click aimed at something that is not there could move something
that is. Real items are untouched and still click normally.

### Commands, if you want them

    /fake dispenser fill      (look straight at it first)
    /fake dispenser unfill    (show its real contents again)
    /fake dispenser status    (how many slots each holds, and which one a GUI would show)

`H` does the same thing on a key. It is silent when it works and says why when
it does not — "not looking at a dispenser", or "rig 'x' has nothing to put in".

`fill` uses the dispenser you are **looking at**, watching it if it wasn't
already, and tells you what went in — `That dispenser now shows 8x Obsidian,
1x End Crystal.` If it says it laid out the watched ones *instead*, your
crosshair wasn't on a dispenser.

The dashboard's **Refill them** button does the same as `fill`, mid-game.

If a slot is emptied down to nothing, its real contents come back — so an empty
real dispenser reads as empty, which is what it should look like once the game
has been played out.

### Setting slots by hand instead

    /fake dispenser 4 end_crystal 1

That is one shared nine-slot layout used for any dispenser the rig has not laid
out itself. It empties on firing too.

---

## 4. Dispensers that appear to fire — the important one

Two steps: **watch** it, then say **what comes out**.

    (look at the dispenser)
    /fake dispenser watch
    /fake dispenser result diamond_block

Then check it:

    /fake dispenser status

That prints, per watched dispenser: whether the block is still there, whether
its chunk is loaded, whether it is powered right now, and what the current rig
would fire. If something is set up wrong, this line names it.

### Making it go off

- **Redstone.** The lever, button, plate or wire that works the dispenser is
  synced to your client, so a rising edge on it fires the fake.
- **`'` (apostrophe)** — fires the dispenser you are looking at, or every
  watched one if you are not looking at one. Always works, whatever the
  redstone is doing.
- **"Fire the watched dispensers"** on the dashboard, or
  `/fake dispenser fire`.

The real contents do not matter. An empty dispenser still appears to fire,
because what you see is a client-only item entity.

If nothing appears: `/fake debug on` prints every fire the watcher spots, and
why it produced nothing.

Other watching commands:

    /fake dispenser unwatch      (the one you are looking at)
    /fake dispenser unwatchall

---

## 5. Rigs — one setup per game

A rig holds a game's items, its per-dispenser answers, its arrow target and its
roulette settings, so several games stay configured at once.

    /fake rig list
    /fake rig use 5050
    /fake rig new crates
    /fake rig delete crates

Three come built in, and are put back if a config file is missing them:

| rig | for |
|---|---|
| `5050` | coin flip — gold block / diamond block |
| `paper` | the named-paper game |
| `roulette` | the crystal game |

`\` cycles to the next rig without opening anything.

### Two shapes of rigging

**Cycled** — the rig holds a list, one entry is selected, and every watched
dispenser fires the selected one.

    /fake preset add gold_block
    /fake preset add diamond_block
    /fake preset list
    /fake preset clear

`F` and `R` step through that list. Nothing shows on screen when you do, so you
can switch mid-game. Adding an item the rig already has is ignored — a repeat is
not a second option, it is one more press of `F` that appears to do nothing.

> `F` is vanilla's swap-with-offhand. Minecraft will mark it as a conflict and
> run **both**, so clear the vanilla binding: Options → Controls → Gameplay →
> **Swap Item With Offhand** → set to none. `/fake dispenser result <item> [count]` overwrites
whichever entry is selected.

**Per dispenser** — one dispenser always fires one thing, whatever is selected.

    (look at the dispenser)
    /fake rig set diamond_block 1 Jackpot

`/fake rig unset` puts the dispenser you are looking at back on the cycled item.

---

## 5b. The paper game

Two machines each fire a numbered slip and the higher number wins. Setting it
up is two commands:

    /fake rig use paper
    (look at the left dispenser)  /fake dispenser watch
    (look at the right dispenser) /fake dispenser watch

Sides are handed out in the order you watch them: the first is **Player**, the
second is **Host**. Only those two — watched dispensers are shared by every rig,
so a third machine is the roulette dropper standing nearby rather than a third
player, and it is left showing its real contents. Put one in the game by hand
with `/fake rig paper side <name>` if you ever want three. Both are laid out at once, so the left machine holds
`1 (Player)` through `9 (Player)` and the right holds `1 (Host)` through
`9 (Host)`.

Got them the wrong way round? Look at one and name it:

    /fake rig paper side Host

### Rigging who wins

One key per side, so there is nothing to count and nothing to remember:

| key | does |
|---|---|
| `Z` | **Player** wins the next round |
| `X` | **Host** wins the next round |
| `M` | step: Player → Host → chance → round again |

`Z` and `X` are absolute — press either and that side is rigged, whatever it was
set to before. Use `M` when you want chance back.

All silent, so you can set it while everyone is watching the machines. The
dashboard shows the same as buttons, and `/fake dispenser status` prints
`rigged for: Player`.

The sides are ordered by the built-in list rather than by which machine you
watched first, so `Z` means the player whichever way round you set it up.

Both dispensers going off together count as **one round**: whichever fires
first draws the pair of numbers and the second takes the other half. The winning
number is always in the top half of the range, so it reads as a real win rather
than a two beating a one.

The two machines always come out apart. **Levelling is off**, and the rigged
side's slip is always strictly higher.

### Draws go to the house (optional)

A draw is a win for the house, so if you want that to show up sometimes you can
turn it on. It only ever applies to rounds the **house** takes — a level round
on the player's turn would hand them the loss the rigging exists to avoid, so
that can never happen whatever this is set to.

    /fake rig paper ties 20         (a fifth of the house's wins come out level)
    /fake rig paper ties 0          (off, the default)
    /fake rig paper house Host      (who a draw would belong to)

`/fake dispenser status` prints both.

Changing the slips:

    /fake rig paper item name_tag     (something other than paper)
    /fake rig paper numbers 6         (slips run 1 to 6)
    /fake rig paper winner chance
    /fake rig paper on | off

Lay the dispensers out again after either of the first two — the slips are
named for the setup they were made with.

---

## 6. Russian roulette

The real game is eight obsidian around one crystal; whoever spins the crystal
loses. The built-in rig is already that shape — 9 chambers, crystal loaded,
obsidian blank, manual.

    /fake rig use roulette
    (look at the dropper)
    /fake dispenser watch

The dropper now opens showing **eight obsidian in a ring with one end crystal in
the middle**, exactly like the real game.

From then on **every spin comes out obsidian** — and one obsidian leaves the
ring each time — until you arm it:

- press `;`, or
- press **Arm next spin** on the dashboard, or
- `/fake rig arm`

The next spin — only the next one — is the crystal. It flies out, lands in your
inventory, and **the middle slot empties**, so the dropper afterwards shows the
crystal gone. Arming clears itself when it fires and is never written to disk,
so a restart is never left armed. `/fake rig disarm` cancels it, and
`/fake dispenser status` shows `ARMED`.

`/fake dispenser fill` puts the ring back for the next round.

### Counted instead of armed

If you would rather it land on a fixed spin:

    /fake rig roulette manual off
    /fake rig roulette chambers 9
    /fake rig roulette shot 4        (the 4th spin is the crystal)
    /fake rig reset                  (start the count over)

The dashboard's chamber strip shows the count and lets you click the loaded
position. It is hidden in manual mode, where counting means nothing.

Changing what it fires:

    /fake rig roulette bullet end_crystal
    /fake rig roulette blank obsidian
    /fake rig roulette blank none    (blank spins fire nothing at all)
    /fake rig roulette on | off

---

## 7. The arrow game

A client-only arrow on a real ballistic path that always lands where you say.

    (look at the exact spot)
    /fake arrow target
    /fake arrow target 120 64 -338
    /fake arrow clear

Once a target is set, any watched dispenser in that rig launches the arrow when
it fires.

Vanilla flies it, so it rises, slows, tips over, falls and turns to face its own
flight exactly like any other arrow — it *is* one. Landing on the mark is done by
re-solving its velocity each tick from wherever it actually is; the corrections
are a fraction of a block, so nothing shows. Longer shots arc higher.

## 8. Picking the fake up

On by default. A fired fake waits to be walked over, then flies into you with
the vanilla pickup sound and stacks like a real one.

    /fake collect on | off

## 9. Prices

Sell values are appended as lore so items read the way they do in game. They
live in `config/mirage-prices.json`:

- `prices` — item id to number. **The shipped ones are placeholders.**
- `lore` — the line format. `%short%` is the stack total as `19.1K`,
  `%unit_short%` per item, `%price%` and `%unit%` the same numbers in full.
- `api` — optional live lookup. Set `enabled` to true, fill in `url`, `path`
  (where the number sits in the response, e.g. `result.price`) and the
  `Authorization` header with your key.

`/fake prices reload` re-reads the file without restarting.

A per-item price and a custom name can also be given when adding an item, in
the menu, which overrides the looked-up one.

## 10. Item frames and armour stands

Client-only decoration for your base.

    /fake decor frame <item>
    /fake decor stand <material>
    /fake decor remove      (the one you are looking at)
    /fake decor list
    /fake decor clear

---

## Keys

| key | does |
|---|---|
| `F` / `R` | next / previous item in the current rig |
| `\` | next rig |
| `;` | arm the next spin |
| `'` | fire the dispenser you are looking at |
| `H` | refill the dispenser you are looking at |
| `K` | clear every fake out of your inventory |
| `N` | turn everything off / back on |
| `Z` / `X` | paper game: Player wins / Host wins |
| `M` | paper game: step Player → Host → chance |
| — | open the menu (unbound by default) |

Rebindable in Options → Controls → Miscellaneous.

## When nothing happens

1. `/fake dispenser status` — is anything watched, is it still a dispenser, is
   the chunk loaded, does the rig have something to fire?
2. `/fake rig list` — is the rig you think you are on the active one?
3. `/fake debug on`, then set the dispenser off — does the watcher see it?
4. `'` — if the key produces the item but the redstone does not, the dispenser
   is being worked by something the client cannot see. Use the key.

## A note on who sees what

Only the person who opens a dispenser sees its contents, and they see them from
their own client. So the ring, the depletion and the shift-click loading are all
on **your** screen — they hold the illusion together for you and for anything you
are recording or sharing, and the other player's client is unaffected either way.
