# One file per Minecraft version

Each file here is the Fabric toolchain for one Minecraft version, plus the range the
built jar declares to Fabric. Build against one with:

    gradlew build -Ptarget=1.21.11

Add a version by generating its file rather than writing it by hand -- Loom resolves
these while Gradle is still configuring, so a wrong value fails the build before any
task runs:

    powershell -ExecutionPolicy Bypass -File fabric-versions.ps1 -MinecraftVersion 26.2 -Write

That writes `versions/26.2.properties`. Then:

    gradlew build -Ptarget=26.2

**One jar per Minecraft version.** Not a limitation of this setup -- a Fabric mod is
compiled against remapped Minecraft names, and those names move between versions. A jar
built for one will not load on the other. Modrinth expects this: upload both files to the
same project and tick the versions each supports.
