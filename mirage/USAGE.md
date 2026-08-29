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
| a dispenser with a fixed answer | nine of that |
| anything else | the rig's items dealt round the nine slots |

A coin flip therefore holds **both** gold and diamond, so flipping which way it
is rigged does not change what is in the box.

**When it fires, the item leaves.** One of the matching slots empties, chosen at
random the way a real dispenser picks, so opening it afterwards agrees with what
everyone just watched come out. This is on for every game, not only roulette.

    /fake dispenser fill      (lay out again — the one you are looking at, or all)
    /fake dispenser unfill    (show its real contents again)
    /fake dispenser status    (says how many slots each one is holding)

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

`]` and `[` step through that list. Nothing shows on screen when you do, so you
can switch mid-game. `/fake dispenser result <item> [count]` overwrites
whichever entry is selected.

**Per dispenser** — one dispenser always fires one thing, whatever is selected.
This is the one for the paper game, where two dispensers fire and the higher
number wins.

    (look at the left dispenser)
    /fake rig set paper 1 Seven
    (look at the right dispenser)
    /fake rig set paper 1 Two

`/fake rig unset` puts the dispenser you are looking at back on the cycled item.

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
it fires. It rises, slows, tips over and drops like a real one — the launch
velocity is solved for rather than the shape being drawn.

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
| `]` / `[` | next / previous item in the current rig |
| `\` | next rig |
| `;` | arm the next spin |
| `'` | fire the dispenser you are looking at |
| — | open the menu (unbound by default) |

Rebindable in Options → Controls → Miscellaneous.

## When nothing happens

1. `/fake dispenser status` — is anything watched, is it still a dispenser, is
   the chunk loaded, does the rig have something to fire?
2. `/fake rig list` — is the rig you think you are on the active one?
3. `/fake debug on`, then set the dispenser off — does the watcher see it?
4. `'` — if the key produces the item but the redstone does not, the dispenser
   is being worked by something the client cannot see. Use the key.
