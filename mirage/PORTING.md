# Moving the mod to another Minecraft version

## Why this is not one command

A Fabric mod is compiled against *remapped* Minecraft names, and those names move between
releases. This mod mentions **92 Minecraft and Fabric types, 102 constants and 571 method
names**. Some fraction of those will have been renamed; nobody knows which without asking
the target's own classpath.

Normally that means a queue of one-error builds: compile, read one `cannot find symbol`,
fix it, compile again, a minute each. `portCheck` collapses that into one list.

## The loop

**1. Get the toolchain.** This needs the internet and Fabric's API:

```
powershell -ExecutionPolicy Bypass -File fabric-versions.ps1 -MinecraftVersion 26.2 -Write
```

Writes `versions/26.2.properties`. If Fabric has no mappings for that version yet, the
script says so — that is a real answer, not a failure to work around. A Fabric mod cannot
be built for a version Fabric has not mapped.

### If yarn has no mappings for that version

This is the one wall that no amount of build configuration gets past, so it is worth
naming plainly. Fabric API and the loader can exist for a version before yarn does — the
lookup will find `fabric_version=0.159.0+26.2` and come back empty for yarn.

The script now says which of two things happened, because they need different answers:

- **yarn lists the version, but the lookup failed** — a network or shape problem. Pass it
  by hand: `-YarnMappings 26.2+build.1`.
- **yarn has no mappings for it** — it prints the newest versions yarn *does* have. There
  is no build from here. A Fabric mod is compiled against mappings; if they do not exist,
  nothing can be compiled against them.

If it is the second, there are exactly two honest options:

1. **Wait.** Yarn usually follows a release within days. Nothing in the project needs to
   change; re-run the script when it lands.
2. **Move to Mojang's official mappings.** Loom supports it, and it does not depend on
   yarn. Be clear about the cost before starting: mojmap names are *different* names, not
   a different spelling — `ClientWorld` is `ClientLevel`, `setBlockState` is `setBlock`.
   That is all 92 imports and a large share of 571 call sites, and it is a rewrite of
   every Minecraft-touching file, not a switch to flip. It also means the 1.21.11 build
   and the 26.2 build no longer share source.

Either way the version file is still written, with the blank marked, so `-Ptarget` gets
far enough to name what is missing instead of failing inside Loom.

**2. Ask what moved:**

```
gradlew portCheck -Ptarget=26.2
```

Three lists, written to `build/mirage-port-report.txt`:

- **Types that are gone** — an import that no longer resolves. Always a real break.
- **Constants that are gone** — `BlockState.CODEC`, `DataComponentTypes.MAP_ID` and the
  like, with **what the class does have** printed beside each.
- **Method names no imported type has** — broad on purpose. A name here is either gone or
  belongs to a type not imported directly, so check each against the class it is called on
  before changing anything.

Nothing in that report is a suggestion. Where something is missing it lists what exists,
so the replacement is **looked up rather than invented**. Four invented names are why the
keys screen still cannot rebind a key.

**3. Fix, then build:**

```
gradlew build -Ptarget=26.2
```

`gradlew inspectApi -Ptarget=26.2` dumps a class whole when the report is not enough.

## Where it will break first

From this codebase's own history, most fragile first:

| Area | Why | Where |
| --- | --- | --- |
| **Keys screen rebinding** | `setKeyCode`, `getTranslationKey`, `keyPressed`, `mouseClicked` all moved once already and are still unfixed | `MirageKeysScreen` |
| **Screen widgets** | `ButtonWidget.builder`, `drawTextWithShadow`, `addDrawableChild` | `MirageKeysScreen`, `MirageSchematicsScreen`, `FakeItemsScreen` |
| **Data components** | `DataComponentTypes.MAP_ID`, `LORE`, `CUSTOM_NAME`, `ENCHANTMENT_GLINT_OVERRIDE` | `SelfFakes`, `FakeLore`, `MapArt` |
| **Block states** | `BlockState.CODEC` — builds and schematics are both saved through it | `FakeBlocks`, `Schematic` |
| **World painting** | `setBlockState`, `isChunkLoaded`, `getBlockState` | `FakeBlocks` |
| **Fabric events** | `UseBlockCallback`, `AttackBlockCallback`, `ScreenEvents` | `FakeHands`, `FakeClicks` |

## What will not break

Written down as data rather than asked of the game, on purpose — these are file formats
and arithmetic, not APIs, and they are version-proof:

- **`Nbt.java` and `Litematic.java`** — no Minecraft imports at all. `check-litematic.py`
  runs them against real `.litematic` files with no game installed, and will keep working
  on any version.
- **`MapPalette.java`** — the 248 map colours, as numbers.
- **`Disk.java`** — finding a file on disk.

Two places already use reflection with a safe fallback rather than a compile-time name,
so they degrade instead of failing to build: the map storage lookup in `MapArt`, and the
server name in `FakeBlocks.worldKey()`. If something in the table above turns out to move
every version, that is the pattern to copy.

## Before you start

Run the checks. They do not need Minecraft, and they will tell you whether a port broke
something that has nothing to do with Minecraft:

```
for %f in (tools\check-*.py) do python %f
```

32 of them. `check-litematic.py` and `check-palette.py` in particular verify real
behaviour, not just the shape of the source.
