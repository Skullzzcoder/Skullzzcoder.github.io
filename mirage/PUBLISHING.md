# Building for more than one Minecraft version

## The short version

**One jar per Minecraft version. Always.** There is no property, range or flag that makes
a single Fabric jar work on two Minecraft releases.

A Fabric mod is compiled against *remapped* Minecraft names, and those names move between
releases. This mod touches 92 distinct Minecraft and Fabric types. When it moved to
1.21.11, four of them had been renamed — that is why the keys screen still cannot rebind
a key. A jar built for 1.21.11 does not load on 26.2, and widening the range in
`fabric.mod.json` does not change that; it only stops Fabric from warning you first. The
player gets a crash inside this mod, with this mod's name on it, instead of a clean
"requires 1.21.11".

Modrinth expects one file per version. Upload both jars to the same project and tick the
versions each supports.

## Adding a version

    powershell -ExecutionPolicy Bypass -File fabric-versions.ps1 -MinecraftVersion 26.2 -Write

That looks up the Fabric toolchain from Fabric's own API and writes
`versions/26.2.properties`. Then:

    gradlew build -Ptarget=26.2
    gradlew build -Ptarget=1.21.11

Two jars in `build/libs`, named `mirage-mc26.2-*.jar` and `mirage-mc1.21.11-*.jar` — the
Minecraft version is in the file name so the second build cannot silently replace the
first.

With no `-Ptarget` the values in `gradle.properties` are used, exactly as before.

## Expect the second target not to compile first time — see PORTING.md

This is normal and it is not a mistake in the setup. Between Minecraft versions, yarn
names move. What you will see is a list of `cannot find symbol` errors naming the methods
that were renamed.

The tool for that is already here:

    gradlew inspectApi -Ptarget=26.2

It prints the real members of every class this mod leans on, read off the remapped
classpath — so a renamed method is looked up rather than guessed at. `inspectKeys` does
the same for the four the keys screen needs.

Fix them per version. If a name differs between the two targets, the honest options are a
small per-version source set or reflection with a fallback; this mod already uses
reflection where an API is known to be fragile (the map lookup, the server name).

## What the jar claims

`minecraft_depend` in each version file is what the jar tells Fabric it runs on. It is
pinned to the version it was compiled against, and `tools/check-publish.py` refuses
anything wider — `>=1.21.11 <27` is precisely the promise that turns "this mod needs
1.21.11" into a crash report.

## Before you list it publicly

Two things worth checking that are not about code:

- **Read Modrinth's content rules for your project first.** Projects whose purpose is
  gaining an advantage over other players on multiplayer servers are treated differently
  from client-side visual mods, and the difference decides whether a listing stays up.
  Check the current rules rather than taking this file's word for it.
- **Describe what it actually does.** The rigging features are the part a reader needs to
  know about — a listing that describes only the visual half is the same problem as a
  false name, at a larger scale.
