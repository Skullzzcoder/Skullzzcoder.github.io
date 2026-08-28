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

You need a JDK 21. From the project folder (the one with `gradlew.bat` in it):

```
gradlew build
```

On macOS or Linux use `./gradlew build`; you may need `chmod +x gradlew` first.

The jar lands in `build/libs/mirage-1.0.0.jar`. Ignore the `-sources` one.

The toolchain versions in `gradle.properties` are set for Minecraft 1.21.11 and verified
against Fabric's and Modrinth's APIs. If you retarget to another Minecraft version,
regenerate them:

```
powershell -ExecutionPolicy Bypass -File fabric-versions.ps1 -Write
```

Drop `-Write` to print them instead of editing the file, or copy them from
<https://fabricmc.net/develop/>. Loom resolves these while Gradle is still configuring the
project, so if one is wrong the build stops before any Gradle task can run -- which is why
the lookup is a standalone script and not a Gradle task.

## Installing

Drop `mirage-1.0.0.jar` and the [Fabric API](https://modrinth.com/mod/fabric-api) jar into
your server's `mods/` folder and restart. Nothing goes on the clients.

State lives in `<world>/mirage.json`, so pranks survive a restart.

## Commands

All of `/mirage` needs permission level 2 (op). Command output goes only to you — it is not
broadcast to other operators.

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
