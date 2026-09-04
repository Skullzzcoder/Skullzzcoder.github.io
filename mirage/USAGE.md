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
| `blackjack` | a shoe of numbered slips |

`\` cycles to the next rig without opening anything.

### Two shapes of rigging

**Cycled** — the rig holds a list, one entry is selected, and every watched
dispenser fires the selected one.

    /fake preset add gold_block
    /fake preset add diamond_block
    /fake preset list
    /fake preset clear

`F` and `R` step through that list. Nothing shows on screen when you do, so you
can switch mid-game. The dispenser holds **one of each** — a coin flip is the two
things you could win sitting side by side, not a box full of them, and holding
both means switching which one is rigged never changes what the machine looks
like. Adding an item the rig already has is ignored — a repeat is
not a second option, it is one more press of `F` that appears to do nothing.

> `F` is vanilla's swap-with-offhand. Minecraft will mark it as a conflict and
> run **both**, so clear the vanilla binding: Options → Controls → Gameplay →
> **Swap Item With Offhand** → set to none. `/fake dispenser result <item> [count]` overwrites
whichever entry is selected.

**Per dispenser** — one dispenser always fires one thing, whatever is selected.

    (look at the dispenser)
    /fake rig set diamond_block 1 Jackpot

`/fake rig unset` puts the dispenser you are looking at back on the cycled item.

> **A machine with its own answer holds nine of it.** That is deliberate — a
> dispenser that always pays a diamond block looks like a box of diamond blocks —
> but it is also the only way a coin flip stops showing one of each. If `H` fills
> all nine slots with the same thing when you wanted one gold and one diamond,
> that dispenser has an answer set on it: look at it, `/fake rig unset`, then `H`.
>
> `H` now says what it laid out, and names this setting when it is what did it.

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

This one needs no rig of its own — **`F` and `R` are the rig**, the same pair that
rigs every other game. Whichever of the three items is selected is what comes out,
every time, and the machine still looks like a nine-slot spread whichever way it
is set. `F` steps diamond → emerald → crystal and back round; `R` goes the other
way.

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

### Only two machines can be in it

This is the one thing that catches people out. The paper game hands out exactly
**two** sides — `Player` and `Host` — and every watched dispenser competes for
them, including the roulette dropper, the coin flip and the blackjack shoes. A
machine that misses out **fires nothing at all**.

If `H` on a paper machine says *"Both sides are already taken (Player at 100 64
-20, Host at 103 64 -20)"*, that is what happened. Two fixes:

    (look at the machine you want)
    /fake paper side Player        or Host

or hand the sides out again from scratch:

    /fake dispenser parts

That drops every side and floor the rig has assigned. They go to the machines you
fill or fire next, so press `H` on the two you actually want, in order — Player
first, then Host. **This is the fix when the sides went to machines from another
game**, which is easy to end up with because every watched dispenser competes for
them and an assignment sticks until something lets go of it.


`/fake doctor` shows it too — a machine with no side reads
`would fire nothing - not in the paper game`.

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

## 5c. Blackjack

Two boxes: one deals the **Player's** cards, one deals the **Host's**. Each holds a
shoe of nine slips, two of each — **A** (the ace) through 9.

    /fake rig use blackjack
    (look at the Player's box)   H
    (look at the Host's box)     H

The first box you fill becomes Player, the second Host. If they come out the wrong
way round, `/fake dispenser parts` and fill them again in the order you want.

### The rig is who wins, not which card

You do not name numbers. **The mod keeps both hands and works the card back from the
result you chose.**

| key | what it does |
|---|---|
| `Z` | the Player wins this hand |
| `X` | the Host wins |
| `F` / `R` | step Player → Host → chance → round again |
| `;` | new hand — both boxes back to nothing |

Then just fire the boxes as the hand is played. Every card is chosen for you:

- the side that is **meant to win** is never dealt a card that busts them, and
  otherwise gets the one that leaves them closest to 21
- the side that is **meant to lose** gets a card that busts them if any card can,
  and otherwise the one that leaves them lowest
- on **chance** it deals honestly at random

Ties between equally good cards are broken at random, so the same situation twice
does not deal the same card twice.

**The ace is 1 or 11** — whichever keeps the hand alive, re-read every time a card
lands, so `A + 9 + 5` is 15 rather than a bust.

### Watching it

`/fake dispenser status` and the dashboard both show both hands live:

    Rig 'blackjack', blackjack
      a shoe of 2x each of A-9, rigged for Player
        Player holds [1, 9] = 20
        Host holds [7, 8] = 15

### One box instead of two

If the table has a single dispenser, `[` and `]` say whose card the next deal is —
`[` for the first side, `]` for the second — and everything else works the same.

### Changing the shoe

    /fake blackjack numbers 9   (how many different cards, 1-9)
    /fake blackjack each 2      (how many of each)
    /fake blackjack item paper  (what the cards are made of)

Lay the boxes out again with `H` after any of those.

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

**Off by default.** Nothing appears under a fake item's name unless you ask for
it:

    /fake prices on
    /fake prices off

Turning it off also strips the line from every fake you are already holding, so
it takes effect the moment you run it rather than the next time an item is
rebuilt.

With it on, sell values are appended as lore so items read the way they do in
game. They live in `config/mirage-prices.json`:

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

> Watched dispensers look after themselves — a build will not paint over one, or
> over the block it fires into. This section is for everything else: chests,
> buttons, signs, a doorway.


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

**Sneak + right-click** to place it. The block goes down where a real one would,
against the face you clicked, the stack drops by one, your hand swings and it
makes the right sound. Anything that is not a block does nothing.

> **Sneak is required, and this is why.** A plain right-click belongs to the block
> you clicked — a button, a lever, a door, a dispenser's own screen. Placing used
> to take every right-click while a fake was in hand, which meant the button that
> fires the machines could not be pressed and the machines could not be opened.
> Holding one of the mod's own items switched the rest of the mod off. Placing now
> lives on the gesture vanilla already reserves for building, so it can never take
> an interaction that was not meant for it.

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

## 11b. Map art — your own designs, and numbers

    /fake map <id> 21
    /fake map <id> A
    /fake map <id> clear

Draws a number or a short word across a map, as large as it fits, **on your screen
only**. Nobody else's map changes.

**It repaints a map you already own — it cannot invent one.** A map's picture comes
from the server: your client renders map N from pixels the server sent it, and has
none for an id it has never been told about. So:

1. Craft a map (or take one you have) and **look at it once**, so the server sends
   your client its picture.
2. Read its id — hover it with F3+H on, or `/fake map <id> 8` and try ids until one
   changes.
3. Paint it.

If the client has never seen that map, the command says so rather than doing
nothing:

    the client has no map 42 yet. Hold a real map, look at it once so the server
    sends it, then use that map's id.

`/fake doctor` line 7 shows what the last paint did.

### Copying art you are holding

**It never had to be art you made — only art your client can see.** A map in your
hand is one the server has already sent you, so it can be copied as it is:

    (hold the map)
    /fake map save held dragon

That reads the id off the item, so there is no hunting for it. It works on anyone's
art, bought, found or given.

### Importing a picture from your computer

Nothing in the game is needed for this half.

**You do not have to find any folder.** Three ways in, easiest first:

**1. Leave the picture where it already is.** Desktop, Downloads and Pictures are
searched by name, so a file you just downloaded works as it sits:

    /fake map pictures                  (everything it can see, anywhere)
    /fake map import wolf.png dragon

**2. Paste the whole path**, in quotes. Right-click the file → *Copy as path* on
Windows, or ⌥-right-click → *Copy as Pathname* on a Mac:

    /fake map import "C:\Users\you\Desktop\wolf.png" dragon

Quotes, spaces and `~` all work. The name after it is what you call the design.

**3. Use the mod's own folder**, if you would rather keep them together. It is made
for you at startup, and the mod will tell you exactly where it is:

    /fake map folder

That prints the whole path — and the **dashboard** shows it too, with a **Copy this
folder path** button, which beats retyping it. It is inside your Minecraft folder,
not the mod jar:

| Launcher | Where `config/` lives |
| --- | --- |
| Vanilla / Mojang, Windows | `%appdata%\.minecraft\config\mirage-pictures` |
| Vanilla / Mojang, macOS | `~/Library/Application Support/minecraft/config/mirage-pictures` |
| Vanilla / Mojang, Linux | `~/.minecraft/config/mirage-pictures` |
| CurseForge / Prism / MultiMC / ATLauncher | inside *that instance's* folder — use the instance's **Open Folder** button, then `config/` |

The instance ones are the common trap: a launcher gives each instance its own
`config/`, so the one next to `.minecraft` may not be the one this mod is reading.
`/fake map folder` prints the one that is actually being read, which is why it exists.

Then either way:

    /fake map import logo.png dragon    (keep it as a design)
    /fake map load dragon 118           (put it on a map you own)

Any size of picture is squashed to the 128×128 a map is, smoothly, and every pixel
is matched to the nearest of the 248 colours a map can show. **Transparency is
kept** — a logo with a cut-out background keeps the cut-out rather than gaining a
white square.

> **You still need one real map to show it on.** The picture can come from
> anywhere; the *surface* cannot. A map's pixels live in your client because the
> server sent them, so a blank crafted map you have looked at once is enough — but
> an id you have never seen has nothing to paint onto, and the command will say so.

### Importing art you actually made

The drawn numbers are a fallback. **Real map art is thousands of placed blocks —
makeable in a world of your own, not on somebody else's.** So build it where you
can and carry it over, one map at a time, the same trade the whole-build feature
makes:

**In your own world**, standing where the art renders:

    /fake map save 7 dragon        (map 7's picture, kept as "dragon")

**On the server**, holding any map you own:

    /fake map load dragon 118      (map 118 now shows it, on your screen)

Designs live in `config/mirage-maps.json` and survive restarts and worlds.

    /fake map list
    /fake map forget dragon

**Copying without keeping**, when both maps are in the same world:

    /fake map copy 7 118           (118 shows what 7 shows; 7 is untouched)

The map you copy *from* has to be one your client has seen too — same rule as
everything else here.

### Using them in a game

A fake item can point at a map id — that is what the `#` in an item spec means:

    /fake set hotbar1 filled_map#42

So a painted map can be a card, a dice face, or a result marker in any of the
games. Point several fakes at several maps you own, paint each with its number,
and the machines deal pictures instead of named paper.

### What is drawn

Digits `0-9`, the letters `A J K Q X`, `-`, and space. Anything else is skipped.
One character fills the map; two or three share it and are still large. Black on
white by default.

> **One honest caveat.** Handing the picture to the client is the only part of this
> that touches Minecraft's own code, and the call for it has moved between
> versions — so it is found by reflection rather than named. It can never crash or
> fail the build; if this version keeps its maps somewhere the search does not
> reach, the command says exactly what it could not find, and that message is what
> I need to fix it.

## 12. Item frames and armour stands

Client-only decoration for your base.

    /fake decor frame <item>
    /fake decor stand <material>
    /fake decor remove      (the one you are looking at)
    /fake decor list
    /fake decor clear

---

## Keys

**`F` and `R` rig whatever game is on.** They keep their place on the keyboard and
change meaning with the rig, so switching game with `\` switches what they do:

| rig | `F` | `R` |
|---|---|---|
| `5050` | next item | previous item |
| `454510` | next item | previous item |
| `paper` | next winner (Player → Host → chance) | previous winner |
| `roulette` | arm the loaded shot | cancel the arm |
| `blackjack` | next winner | previous winner |

`\` says which game you have landed on and what `F` and `R` now mean, in your
action bar. `/fake keys` prints the lot, and `/fake dispenser status` carries the
same line.

Every game's own dedicated key still works — this is one more way in, not a
replacement. A key per outcome is still the surest thing when you already know
which one you want.

| key | does |
|---|---|
| `F` / `R` | rig the current game forward / back (table above) |
| sneak + right-click | place the fake block you are holding |
| `\` | next rig — and `F` / `R` change with it |
| `;` | arm the next spin — blackjack: new hand |
| `'` | fire the dispenser you are looking at |
| `H` | refill the dispenser you are looking at |
| `K` | clear every fake out of your inventory |
| `N` | turn everything off / back on |
| `B` | open up the fake block you are looking at |
| `[` / `]` | blackjack: deal to the first / second side |
| `Z` / `X` | paper game and blackjack: Player wins / Host wins |
| `M` | paper game: step Player → Host → chance |
| — | open the menu (unbound by default) |

`;` now has a way back: on roulette, `R` cancels an arming made by
accident, which previously could only be spent by letting the machine fire the
shot it was set up to ruin.

### Seeing them

`/fake keys` opens a screen with every key in one place, or press **Keys** in the
main menu (`/fake ui`). `/fake keys list` prints the same thing as text.

**It marks clashes, which is the point of it.** A key Minecraft also uses shows in
red, and it counts them at the bottom, because Minecraft runs *both* bindings —
`F` is swap-to-offhand by default, so rigging a game also threw your sword into
your other hand and nothing said why.

**Changing a key is still done in Options → Controls → Miscellaneous.** These are
ordinary key bindings, so that screen has always worked on them. Rebinding from
inside the Mirage screen is not wired up yet: setting a binding and reading a key
press both moved in this version of Minecraft, and rather than guess at the new
names a third time, `gradlew inspectApi` prints them and the screen grows the two
buttons back once it has.

## Playing with nothing on your screen

    /fake quiet on

Every message the mod would put in your action bar stops appearing. **Nothing is
lost** — it all goes to the dashboard instead, on your other screen:

    http://127.0.0.1:25599

The dashboard now shows everything the text used to, live:

- **which game is on**, and what `F` and `R` do on it right now
- a **red bar** when the rig cannot produce anything at all, saying why
- **every machine** — where it is, whether it is ok, what it would fire if it went
  off this second, and what it is holding. A machine that is covered, unloaded or
  no longer a dispenser turns red.
- **what the machines did** — the last dozen fires, with `STOPPED` lines in red
- **messages** — everything the mod has said lately, newest first
- the current game's settings: the blackjack shoe and next card, the 45/45/10 spread
  with real odds, the paper winner, whether answers are placed and how long they
  take to break

`/fake quiet off` puts the messages back on screen.

## When nothing happens

## `/fake doctor`

**Run this first.** It walks the whole chain a fake has to travel and marks the first
thing that is wrong, with the key that fixes it:

    --- Mirage doctor ---
    1. Master switch   ON
    2. Rig             '5050' (cycled)   F next item
    3. Rig has answer  yes
    4. Machines        2
         100 64 -20  would fire 1x gold_block; holds 1x gold_block, 1x diamond_block
         103 64 -20  COVERED by a build  <-- look at it and press B
    5. Last fires      t8120  100 64 -20  fired by hand
         t8120  100 64 -20  fired 1x gold_block
    6. Collect fakes   on

Three lines carry most of the answer:

- **Line 1 `OFF`** — the master switch. Everything else is dead while it is. Press `N`.
- **Line 4 `would fire ...`** — what the rig *would* produce right now, worked out
  without spending anything. If that is the right item and nothing comes out, the
  rigging is fine and the problem is downstream. If it says `nothing`, the rig is
  the problem.
- **Line 5 `NONE SEEN YET`** — no machine has gone off at all, so nothing is even
  reaching the rig. Press `'` while looking at one; if that logs a fire and
  redstone never does, your wiring is something the client cannot see, and the key
  is the answer.

Every fire is recorded there whatever becomes of it, including the ones that
stopped — `STOPPED:` lines say exactly where and why.

**`/fake dispenser status`** goes into more detail on the machines themselves. It is the first thing to
run, and it names every way this can go wrong:

| what it says | what it means |
|---|---|
| `EVERYTHING IS OFF` | the master switch. Press `N`. |
| `No dispensers watched` | look at each machine and press `H` |
| `COVERED by one of your builds` | scenery painted over the machine — press `B` on it |
| `not a dispenser any more` | it was broken or moved. Rewatch it. |
| `chunk not loaded` | go closer |
| `fires nothing` | the rig has no item selected — press `F` |

Then press `'`. It now always says what it did, and that single line splits the
problem in half:

- **"Fired 3 machines"** and nothing came out → the rig, not the wiring. Check
  what `status` says it fires.
- **"No dispensers watched"** → nothing is set up. Look at each machine, press `H`.
- **the button works but redstone never fires it** → the dispenser is being worked
  by something your client cannot see. Use the key.

Then:

1. `/fake rig list` — is the rig you think you are on the active one?
2. `/fake debug on`, then set the dispenser off — does the watcher see it?
3. `'` — if the key produces the item but the redstone does not, the dispenser
   is being worked by something the client cannot see. Use the key.

### Builds never cover a machine

A build block painted over a watched dispenser used to switch **every rig off at
once**, silently. The mod finds its machines by reading your client's copy of the
world — the same copy builds are painted into — so a covered dispenser was not
hidden, it had stopped existing as far as the rest of the mod could tell.

Builds now keep off every watched machine and the block it fires into,
automatically. You do not need to cut holes for the machines any more; watching
one is enough, and the wall comes back if you unwatch it. Anything already
painted over a machine comes off within a second of the mod noticing.

## A note on who sees what

Only the person who opens a dispenser sees its contents, and they see them from
their own client. So the ring, the depletion and the shift-click loading are all
on **your** screen — they hold the illusion together for you and for anything you
are recording or sharing, and the other player's client is unaffected either way.
