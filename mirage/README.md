# Mirage

A server-side Fabric mod for Minecraft **1.21.11** that shows players items that aren't
there: ghost blocks in their own inventory, fake contents inside dispensers, and fake
dispenser output flying out of a dispenser that never actually fired it.

It installs on the **server only**. Your friends connect with vanilla clients and have
nothing to download, so there's no mod list to give the game away.

## How it works (and why it's safe)

Everything is a lie told over the network. The server never modifies a real inventory:

- **Ghost items** are `ScreenHandlerSlotUpdateS2CPacket`s sent to one client. The server's
  copy of the inventory is untouched.
- **Fake dispenser contents** are the same packets, painted over an open dispenser screen.
- **Fake dispenser output** is a real, un-pickup-able item entity that deletes itself after
  three seconds.

Because the server stays authoritative, **no item duplication is possible**. When a victim
clicks a ghost item, the server sees the click against the real inventory, disagrees, and
resyncs — the item pops out of existence in their hands. That's the joke. Mirage re-sends
its ghosts once a second, so it comes right back.

There are **no mixins**. Dispenser firing is detected by watching the vanilla `TRIGGERED`
blockstate, so the mod can't break a dispenser's real behaviour and can't crash your server
on a version bump — the worst case is that it stops working.

## Building

JDK 21 is enough. From the project folder (the one with `gradlew.bat` in it):

```
gradlew build
```

On macOS or Linux use `./gradlew build`; you may need `chmod +x gradlew` first.

The first run downloads Gradle 9.5.1, which takes a minute. Loom 1.17.20 declares
`org.gradle.plugin.api-version` 9.5.0, so an older wrapper fails to resolve the plugin at
all — hence the pinned distribution in `gradle/wrapper/gradle-wrapper.properties`.

The jar lands in `build/libs/mirage-1.0.0.jar`. Ignore the `-sources` one.

### Toolchain versions

`gradle.properties` is set for Minecraft 1.21.11 and verified against Fabric's and
Modrinth's APIs. Retargeting another Minecraft version means regenerating them:

```
powershell -ExecutionPolicy Bypass -File fabric-versions.ps1 -Write
```

Drop `-Write` to print them instead of editing the file, or copy them from
<https://fabricmc.net/develop/>. You may also need to change the pinned `fabric-loom`
version in `build.gradle` — and if Loom changes, check that the wrapper's Gradle version
still satisfies whatever `org.gradle.plugin.api-version` that Loom declares.

`java_version` sets the bytecode level of the jar. 21 loads on any Java 21+ server; raise
it only if the compiler reports that Minecraft's class files are newer than that.

Loom resolves all of this while Gradle is still configuring the project, so if one value is
wrong the build stops before any Gradle task can run — which is why the version lookup is a
standalone script rather than a Gradle task.

## Installing

Drop `mirage-1.0.0.jar` and the [Fabric API](https://modrinth.com/mod/fabric-api) jar into
your server's `mods/` folder and restart. Nothing goes on the clients.

State lives in `<world>/mirage.json`, so pranks survive a restart.

## Client-side fake items (no server needed)

Everything above needs the mod on the **server**, because only the server can send packets
to other players' clients. If you play on a server you cannot install mods on, the client
half still works — but the fakes appear **only on your own screen**. Nobody standing next
to you sees anything. It is for screenshots, recordings and screen-shares, not for pranks.

Open the menu:

```
/fake ui
```

That is a *client* command: your client intercepts it, so it works on any server and is
never sent to anyone. Type an item name, then click the slots it should appear in. An empty
item field turns a click into a clear. There are typed equivalents too:

```
/fake set hotbar3 diamond_block 64
/fake clear [slot]
/fake list
```

Slots are `hotbar1`–`hotbar9` and `inv1`–`inv27`.

### Enchantments

The menu's third field takes a spec like `sharpness:5, unbreaking:3`, or as a command:

```
/fake set hotbar1 netherite_sword 1 sharpness:5, unbreaking:3
```

These are drawn as lore lines plus the enchantment glint, not as real enchantment
components. Building a real one needs a lookup in the dynamic enchantment registry, whose
shape has changed repeatedly between versions; a vanilla tooltip renders enchantments as grey
non-italic lines above the lore, which is exactly what this produces. Since only you ever see
the item, the distinction is invisible.

### Fake dispenser and dropper contents

The right-hand 3x3 grid in the menu is what you see when you open a dispenser or dropper.
Same rules: type an item, click a slot, empty field clears. Or:

```
/fake dispenser <slot 0-8> <item> [count]
/fake dispenser clear
```

This paints the first nine slots of whatever container you open, which is exactly a
dispenser or dropper's grid. Open a chest and its top row gets the same treatment — harmless,
and it keeps the code free of guessing at screen types.

### Fake ender chest contents

The **Ender chest** tab fills a 9x3 grid, or:

```
/fake ender <slot 0-26> <item> [count]
```

Containers are told apart by size: nine slots is a dispenser or dropper, twenty-seven an
ender chest. A normal chest is also twenty-seven and so shows the ender chest's fakes — a
fair trade for not having to guess at screen handler types.

### Making a dispenser appear to fire something

Look at a dispenser and run:

```
/fake dispenser watch
/fake dispenser result diamond_block
```

When that dispenser next fires, an item of your choosing appears to fly out, then vanishes
after three seconds. The **Dispenser** tab has buttons for both steps.

Firing is spotted from the vanilla `TRIGGERED` blockstate, which your client already
receives, and the item is an entity added to your client world only. Only dispensers you
explicitly watch are polled, so this costs a few blockstate reads per tick rather than a scan
of everything around you.

For the cleanest version of the gag, watch an **empty** dispenser: vanilla then does nothing
visible and only your fake item comes out.

```
/fake dispenser unwatch        # the one you're looking at
/fake dispenser unwatchall
```

### Fake arrows that always land where you say

For games where an arrow decides an outcome. Look at the exact spot you want it to land:

```
/fake arrow target
/fake dispenser watch     (looking at the dispenser that fires)
```

Every time that dispenser fires, an arrow flies out of it and lands on your chosen point.
The landing point is the precise spot on the block face you were looking at, not the middle
of the block, so it can sit on one pad rather than between two.

The arrow's path is interpolated along an arc rather than launched ballistically: solving for
a velocity that hits an exact point through Minecraft's drag is approximate, and the whole
point is that it never misses. `/fake arrow clear` turns it off.

**This changes nothing about the real outcome.** The arrow exists only on your client; the
server's arrow, and whatever it pays, are untouched and unaware.

### Price lines

Fake items can carry a lore line so they read like a server-formatted item rather than a
plain vanilla one. Prices live in `config/mirage-prices.json`:

```json
{
  "lore": ["&7Sell Price: &a$%price%"],
  "prices": {
    "minecraft:diamond_block": 4500,
    "minecraft:netherite_ingot": 32000
  }
}
```

`%price%` is the price times the stack size, `%unit%` is the per-item price, and `&` codes
are colours. An item with no entry gets no lore line at all.

**The shipped values are placeholders, not real server prices.** Nothing is fetched from
anywhere — put the real numbers in yourself, or the lore will read convincingly and be wrong.
After editing the file:

```
/fake prices reload
```

### Live prices from an API

The price file also has an `api` block, off by default:

```json
"api": {
  "enabled": true,
  "url": "https://your.api/price/%item_short%",
  "headers": { "Authorization": "Bearer YOUR_KEY" },
  "path": "result.price",
  "cacheMinutes": 30
}
```

`%item%` is the full id, `%item_short%` drops the namespace. `path` is a dotted path to the
number in the response, and `data[0].sell` style indexing works. Anything answering with JSON
containing a number will do — it is deliberately generic, because it was written without
sight of the API it would talk to.

Lookups run on a background thread and never block the game: a miss returns nothing, queues a
fetch, and the item updates a moment later. Anything in `prices` wins over the API, so a value
you set by hand is never overwritten. Failed responses are not logged with their body, since
a body can echo the request and your key with it.

**Your key sits in that file in plain text.** Don't share it or upload it anywhere.

That re-reads the file and rebuilds every fake you have set, so you don't retype anything.

### How it works, and why it is safe on someone else's server

The fake stacks are written into your client's own copy of your inventory each tick, so
vanilla draws them everywhere for free — hotbar, inventory screen, held item. Nothing is
transmitted: a Minecraft client sends *actions* ("I right-clicked"), never item identities,
so the server's view of your inventory is untouched and there is nothing for an anticheat
to see. Interacting with a fake simply does whatever your real item in that slot does.

The real stack a fake covers is remembered, so clearing a fake puts the truth straight back
rather than waiting for the server to next touch that slot. Fakes persist in
`config/mirage-client.json`.

Client fakes carry an item and a count only, not enchantments or custom names — the
server-side ghosts above keep full data components.

## Commands

On a dedicated server, all of `/mirage` requires op. In a single-player or LAN world it is
available to anyone in that world, because an integrated server keeps no op list — the host
holds full permissions without appearing in `ops.json`, so an op-list check would hide the
command from the very person running it.

Command output goes only to you — it is not broadcast to other operators.

If `/mirage` does not tab-complete at all, that is this permission check refusing you, which
looks identical to the mod having failed to load. Check the log for `Mirage loaded` to tell
the two apart.

### Ghost items in someone's inventory

```
/mirage ghost set <player> <slot> <item> [count]
/mirage ghost clear <player> [slot]
/mirage ghost list <player>
```

`<slot>` is a friendly name, and tab-completes:

| Name | Where |
| --- | --- |
| `hotbar1` … `hotbar9` | the hotbar, left to right |
| `inv1` … `inv27` | the main inventory, top-left first |
| `offhand` | off-hand slot |
| `head`, `chest`, `legs`, `feet` | armour slots |

```
/mirage ghost set Steve hotbar3 diamond_block 64
/mirage ghost set Steve head netherite_helmet
/mirage ghost clear Steve
```

Ghosts are per-player: nobody else sees them, including you.

### Rigged dispensers

```
/mirage dispenser show <x> <y> <z> <slot 0-8> <item> [count]   # fake contents when opened
/mirage dispenser result <x> <y> <z> <item> [count]            # fake item that flies out
/mirage dispenser only <x> <y> <z> <player>                    # restrict the fake contents
/mirage dispenser everyone <x> <y> <z>
/mirage dispenser clear <x> <y> <z>
/mirage dispenser list
```

```
/mirage dispenser show 100 64 -20 4 netherite_ingot 32
/mirage dispenser result 100 64 -20 diamond_block
```

Now that dispenser looks stacked with netherite when opened, and appears to shoot out a
diamond block that lands, sits there for three seconds and evaporates.

Two things worth knowing:

- **The fake output is a real entity, so everyone nearby sees it.** `only` restricts the
  fake *contents* (packet-based, per-player), not the flying item.
- **The fake output is added on top of whatever the dispenser really does.** For the classic
  "it dispensed a diamond block and then the block vanished" gag, rig an **empty**
  dispenser — vanilla does nothing visible and only your fake item flies out. If the
  dispenser is loaded, the real item comes out alongside the fake one.

### Undo

```
/mirage refresh <player>
```

Force-resyncs a player with reality. Ghosts return on the next refresh unless you cleared
them with `/mirage ghost clear` first.

## Notes

- Ghost items only paint onto a player's *own* inventory screen. While a chest or other
  container is open, the raw slot indices mean something different, so Mirage pauses and
  resumes when they close it.
- `/mirage dispenser only <player>` takes an online player.
- A hopper underneath a fake dispensed item can vacuum it up before it expires. Don't rig a
  dispenser over a hopper unless you want that.
- Rigs are saved by dimension and coordinates, so breaking the dispenser doesn't clear the
  rig — use `/mirage dispenser clear`.

## Adding fake blocks in the world

Not included, but it's the natural next prank and it's small: send the victim a
`BlockUpdateS2CPacket` with a fake blockstate for a position. They'll see diamond ore that
turns back into stone the moment they hit it. The existing per-player targeting and
persistence in `MirageState` would carry over as-is.

## License

MIT.
