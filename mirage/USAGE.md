# Mirage — how each part works

Everything here happens on **your client only**. Nobody else sees any of it and
nothing is ever sent to the server: the client edits its own copy of what is on
screen, so there is nothing on the wire for anyone to notice.

Two config files, both under your instance's `config/` folder:

| file | holds |
|---|---|
| `mirage-client.json` | every fake, every rig, the dashboard settings |
| `mirage-prices.json` | prices, lore format, and the price API key |
| `mirage-builds.json` | saved schematics |

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

Slot names tab-complete: `hotbar1`–`hotbar9`, `inv1`–`inv27`, and the worn
slots — `helmet`, `chest`, `legs`, `boots`, `offhand`.

The worn ones show **on your player model**, not just in the screen, so a fake
set is on you in third person and in your own hand.

A whole set in one go:

    /fake wear netherite
    /fake wear diamond protection:4, unbreaking:3
    /fake wear off

Or a piece at a time:

    /fake set helmet netherite_helmet
    /fake set chest elytra
    /fake set offhand totem_of_undying

What you are wearing is drawn from what you have **equipped**, and where that is
read from has moved between versions, so the mod writes both the slot and the
equipment. Costs nothing and means it is actually on you either way.

A slot holding a **real** item is never touched, so nothing of yours can be lost
or hidden, and anything picked up goes to the bag rather than onto your head.

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

While a dispenser is open, fakes move like anything else.

**Drag them.** Left-click picks the whole stack onto your pointer, right-click
takes half. Left-click lays the lot down, right-click lays one. Like onto like
merges up to 64; unlike swaps places. The carried stack is drawn under your
pointer with its count, exactly as a real one would be.

**Shift-click** still moves a whole stack across in one go — into the dispenser
from your inventory, or back out of it.

Close the screen or click off the board and whatever you were carrying goes back
to your bag. There is no ground to throw a fake onto, so nothing is ever lost.

**A slot holding something real is never written to.** What is in it belongs to
the server, and covering it would only last until the server said otherwise —
so real items are untouched and still behave normally. Nothing in any of this
reaches the server: a click on a slot it thinks is empty is worse than no click
at all.

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

Five come built in, and are put back if a config file is missing them:

| rig | for |
|---|---|
| `5050` | coin flip — gold block / diamond block |
| `454510` | 45/45/10 — diamonds, emeralds and one crystal |
| `paper` | the named-paper game |
| `roulette` | the crystal game |
| `tower` | the five-floor shulker box climb |

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

## 5a. 45/45/10

One dispenser holding nine things: **four diamonds, four emeralds and one end
crystal**. The player calls which kind will come out. Calling the kind right pays
**2x**; calling the crystal right pays **4x**.

    /fake rig use 454510
    (look at the dispenser)
    /fake dispenser watch
    H

The nine slots come out laid like this, with the prize in the middle where the
odds can be read at a glance rather than counted:

    D D D
    D C E
    E E E

### Rigging it

This one needs no rig of its own — **`F` and `R` are the rig**. Whichever of the
three items is selected is what comes out, every time, and the machine still
looks like a nine-slot spread whichever way it is set. Press `F` to step
diamond → emerald → crystal and back round.

It is **silent by default**, like every other switching key — you can step it
mid-game with the machine open in front of somebody.

Three items on one key is one more than you can keep count of, though, and losing
count by a single press means handing out a 4x you meant to keep. So there are two
ways to check without guessing:

`/fake dispenser status` marks the one that is rigged (see below), and shows
nothing to anybody else.

Or turn the readback on and it names the item and its payout in your action bar
each time you press:

    /fake announce on

    Emerald - pays 2x
    End Crystal - pays 4x

The action bar is the small line above your hotbar, on your screen only. Off
again with `/fake announce off`.

### Checking it

`/fake dispenser status` prints the spread with the real odds worked out:

    Rig '454510', 45/45/10
      holds 4x diamond (44%, pays 2x), 4x emerald (44%, pays 2x) <- RIGGED, 1x end_crystal (11%, pays 4x)
      fires emerald

(Nine slots cannot split 45/45/10 exactly — four of nine is 44.4% — which is why
the status line shows what is actually in there rather than the name.)

### Changing the spread

The counts and payouts are saved with the rig in `config/mirage-client.json`
under `mix`, alongside the items:

    "mix": { "counts": [4, 4, 1], "payouts": [2, 2, 4] }

Edit those and restart the game (the client config is read once, at startup).
The item with the fewest of it takes the middle slot; if nothing is rarest —
three of each, say — they just fill in order. A fourth item added with
`/fake preset add <item>` gets a count of 1 and a payout of 1x until you give it
its own numbers there.

---

## 5b. The paper game

Two machines each fire a numbered slip and the higher number wins. Setting it
up is two commands:

    /fake rig use paper
    (look at the left dispenser)  /fake dispenser watch
    (look at the right dispenser) /fake dispenser watch

Sides are handed out in the order you **watch** them while the paper rig is
active: the first is **Player**, the second is **Host**. A machine already
watched for another game joins by being watched again, by pressing `H` at it, or
simply **by going off** during a paper game — a machine firing while the game is
on is the plainest sign it belongs to it.

Only two are ever taken. Once both sides are spoken for, any other machine that
fires says so and stays out. Only those two — watched dispensers are shared by every rig,
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

## 5c. The tower

Five machines, one per floor, each holding **two white and two black shulker
boxes**. The player calls a colour before each floor and climbs while they are
right, with the multiplier going up as they go.

    /fake rig use tower
    (look at floor 1) /fake dispenser watch
    ... and so on up to floor 5

Floors are handed out in the order you **watch** them while the tower rig is
active. If a machine is already watched from another game, watching it again
joins it to the tower — or just look at it and press `H`, which does the same.

Got one wrong? Look at it and run `/fake rig tower floor 3`.

A machine also joins simply **by going off** during a run, up to the five the run
has. Past that it says so and stays out, so the roulette dropper and the coin
flip are left alone and keep showing their real contents.

`/fake dispenser status` lists which machine is which floor.

### Playing it

The **call is the only thing you have to enter**. What a floor fires is their own
answer given back to them, or the other colour when the run ends.

| key | means |
|---|---|
| `[` | they called **white** |
| `]` | they called **black** |
| `;` | **this next floor ends the run** |

So a normal floor is one keypress: they say white, you press `[`, the machine
fires white and they climb. When you want the run over, press `;` first and the
machine fires the other colour instead.

Arming beats everything and is spent by the floor it lands on, so you can end a
run at any point without changing the setup.

### The box is placed, not thrown

A dispenser holding shulker boxes **places** them, so a floor's answer stands on
the ground in front of the machine rather than bouncing out of it. That is on by
default for the tower.

One box per machine: the next round takes the last one away first, which is what
clearing the floor between rounds looks like. `/fake dispenser sweep` clears them
all, and so does `N`.

    /fake dispenser place off    (throw the answer out as an item instead)
    /fake dispenser place on

Because it is a block rather than an item, it is held back from **directly under
your feet** and nowhere else — you can stand beside it, look at it from a step
away, and it stays. Only a block that could hold you up is unsafe; one at your
own level cannot be stepped onto without jumping, and one that merely blocks the
way never puts you anywhere the server disagrees with.

### Ending it on a set floor instead

    /fake rig tower ends 4        (every run ends on floor 4)
    /fake rig tower ends armed    (only when you arm it — the default)

### Other settings

    /fake rig tower floors 5
    /fake rig tower colours white_shulker_box black_shulker_box
    /fake rig tower call white     (same as the keys, if you prefer typing)
    /fake rig tower on | off

`/fake dispenser status` prints the floor of each machine, what they last
called, and where the run ends.

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

## 10. Whole builds — a base without building it

Copy something that really exists, then stand it up anywhere as a fake. The
blocks go into your client's own copy of the world; the server is told nothing
and its world is untouched.

    (look at one corner)          /fake build corner
    (look at the opposite corner) /fake build corner
    /fake build save casino

    (look at where the low corner should go)
    /fake build put casino

    /fake build cut             (open up the block you are looking at)
    /fake build cut 2           (open a 5x5x5 around it)
    /fake build uncut [radius]  (fill it back in)
    /fake build uncutall casino (fill every hole in)

    /fake build take casino     (down, real blocks back)
    /fake build takeall
    /fake build list
    /fake build forget casino   (delete it)

Builds live in `config/mirage-builds.json`, written only when one changes.
Air is skipped, so only what is really built is carried, and the corner is
measured from the lowest of the three axes.

**Up to 500,000 blocks each**, which is a whole base rather than a booth:

| blocks | file | memory | full repaint |
|---|---|---|---|
| 30,000 | 0.6 MB | ~2 MB | 3.0 s |
| 120,000 | 2.6 MB | ~9 MB | 3.0 s |
| 500,000 | 10.7 MB | ~40 MB | 4.2 s |

The repaint is a rolling sweep sized to the build, so a bigger one costs a
little more each tick rather than taking proportionally longer to catch up.
Only blocks in loaded chunks are painted, so a large build fills in as you
approach rather than all at once.

If you copy from a single-player world, note the two worlds need the same
coordinates only for `save` — `put` places it wherever you are looking.

Copy from a creative single-player world, a plot, or anything already standing
on the server — then put it wherever you like.

### Making room for real machines

`B`, or `/fake build cut`, opens up the fake block you are looking at: the real
world comes back there and you can place a dispenser, a chest, a sign — whatever
has to genuinely be there. `/fake build cut <radius>` opens a cube around it, up
to radius 5.

Holes are **held against the build**, not the world, so they survive taking it
down and standing it up again, and they are still there next session. The rest
of the place looks exactly as it did.

`/fake build uncut` fills one back in, `/fake build uncutall <name>` all of them.

The usual order is: stand the build up, cut a hole where each machine goes, place
the real dispenser or dropper, then `/fake dispenser watch` it as normal.

### Standing on it — place a real floor

Paint over a **real** block and it is only a change of skin: both your client
and the server agree something solid is there, so you can stand on it and walk
into it exactly as it looks. Paint over **air** and only your client thinks it
is there, which is the dangerous kind.

So the rule is: a fake is held back only where the server has nothing, and only
within one block of you.

That gives you the hybrid. **Place real blocks for the floor you gamble on** —
any blocks, they get re-skinned to whatever the schematic says — and leave the
walls, roof and decoration purely visual. You stand on the real floor while it
looks like the build, and you walk straight through the fake walls, which is
also how you get inside.

Cheap stone under a quartz schematic reads as quartz. Only the shape has to be
right, so build the standing floor out of **full blocks**, not slabs or stairs.

Place a block under a fake and it becomes standable straight away — the server
update arrives, the mod notes the real block underneath, and stops holding the
paint back there.

Where you have not placed anything, the fake opens up as you walk into it and
you drop through. That is the mod telling you exactly where the floor needs to
go.

### It will not get you flagged

Your movement stays identical to a client with no mod on it: you never stand on
or walk into anything the server does not also have. One smaller thing: if the
server sends a real update for a position it flickers back for up to a second
before the sweep repaints it. The one exception is a block you are actually
hitting, which is put back every tick instead — see section 11.

## 11. Placing and breaking

A fake in your hand behaves like the real thing.

**Right-click** to place it. The block goes down where a real one would, against
the face you clicked, the stack drops by one, your hand swings and it makes the
right sound. Anything that is not a block does nothing.

**Hold left-click** to break it back out. The cracks appear, it takes as long as
a real one would for whatever you are swinging, it makes the right sound, and the
block goes back into your bag. Let go or look away and the progress resets, same
as vanilla. (No break particles yet: the call the game uses for them has moved,
and a wrong guess at the new name is a build that will not start.)

In **creative** it goes in one hit, the way creative does. That is deliberate:
creative decides a block is broken *before* the hook this mod answers, so left
alone the block would simply vanish about a quarter of a second after you clicked
— no cracks, no item, and a real break sent to the server for whatever is really
behind the paint. Breaking it ourselves first means there is nothing left there
for the game to find.

Breaking a block that belongs to a **build** cuts it out permanently — the hole
stays where you broke it rather than coming back the next time you stand the
build up, which is what breaking something means. Breaking one a machine put down
just removes it.

None of this reaches the server. Every interaction with a fake is cancelled
before it becomes a packet: the slot the server sees is empty, and a click on an
empty slot is worse than no click at all. A block is only taken over when the
paint is actually on the screen there — a fake held back from underfoot, or one
the master switch has taken down, is a real block again and breaks normally.

## 12. Item frames and armour stands

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
| `F` / `R` | next / previous item in the current rig (45/45/10: what comes out) |
| `\` | next rig |
| `;` | arm the next spin |
| `'` | fire the dispenser you are looking at |
| `H` | refill the dispenser you are looking at |
| `K` | clear every fake out of your inventory |
| `N` | turn everything off / back on |
| `B` | open up the fake block you are looking at |
| `[` / `]` | tower: they called the first / second colour |
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
