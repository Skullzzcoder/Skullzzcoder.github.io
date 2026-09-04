"""The half of schematic loading that cannot be run here.

check-litematic.py runs the reader against real files. This one covers what is left: the
reader must stay free of Minecraft or that can never happen again, a schematic must become
an ordinary build rather than a second kind of thing, and a block this version does not
have must not take the whole file down with it."""
import io, re, sys
SRC = "src/main/java/dev/skullzz/mirage/client/"
lit    = io.open(SRC + "Litematic.java", encoding="utf-8").read()
nbt    = io.open(SRC + "Nbt.java", encoding="utf-8").read()
schem  = io.open(SRC + "Schematic.java", encoding="utf-8").read()
blocks = io.open(SRC + "FakeBlocks.java", encoding="utf-8").read()
client = io.open(SRC + "MirageClient.java", encoding="utf-8").read()
dash   = io.open(SRC + "WebDashboard.java", encoding="utf-8").read()

fails = []
def check(name, cond):
    if not cond: fails.append(name)

def body(source, signature):
    found = re.search(re.escape(signature) + r"(.*?)\n    \}", source, re.S)
    return found.group(1) if found else ""

# The whole reason the reader can be tested for real: no game in it, at all.
for name, source in (("the reader", lit), ("the NBT parser", nbt)):
    check("%s does not import Minecraft" % name, "net.minecraft" not in source)
    check("%s does not import Fabric" % name, "net.fabricmc" not in source)

# A file is something the player was handed, so it cannot be trusted to be sane.
check("nesting is bounded", "MAX_DEPTH" in nbt)
check("a claimed length is bounded", "MAX_ENTRIES" in nbt)
check("an unknown tag stops the read", "unknown NBT tag type" in nbt)
check("a file that is not NBT is named", "not an NBT file" in nbt)
check("an uncompressed file still opens", "0x1F" in nbt and "0x8B" in nbt)

# The format's own rules are the check on the reading, because a schematic read the wrong
# way does not look wrong -- it looks like a different building.
region = body(lit, "private static Region readRegion(String name, Map<String, Object> body) throws IOException {")
check("the packed length must match the volume", "packed.length != needed" in region)
check("every index must land in the palette", "value >= region.palette.size()" in region)
check("a size of zero is not a region", "sizeX == 0" in region)
check("bits never drop below the format's floor", "int bits = 2;" in lit)
check("entries may cross a long", "packed[last] << (64 - offset)" in lit)
check("the cap is the one builds already have", "FakeBlocks.MAX_BLOCKS" in schem)

# Loading has to end in an ordinary build, or none of put/take/move/align/cut/persist
# apply to it and all of them would have to be written twice.
check("a schematic becomes a build", "FakeBlocks.adopt(" in schem)
adopt = body(blocks, "public static Build adopt(String name, List<BlockState> palette, int[] blocks,")
check("adopting takes down what it replaces", "take(name);" in adopt)
check("adopting registers the build", "builds.put(name, build)" in adopt)
check("loading is remembered", "FakeBlocks.persist()" in schem)

# Block states go through the game's own codec, which this mod already saves builds with,
# rather than any method looked up by name.
check("states go through the codec already in use", "BlockState.CODEC.parse" in schem)
check("the codec is fed the shape it documents", '"Name"' in schem and '"Properties"' in schem)
resolve = body(schem, "static BlockState resolve(Litematic.Entry entry) {")
# A property this version dropped fails the whole parse; a stair is still a stair.
check("a state that will not parse is tried without its properties", "Map.of()" in resolve)
check("a block this version lacks does not become a hole", "Blocks.STONE" in schem)
check("and is counted and reported", "unknown++" in schem and "unknown > 0" in schem)

# Refusals carry the reader's own words: it names what was wrong with the file.
load = body(schem, "public static int load(String fileName, String name) {")
check("a refusal keeps the reason", "failure.getMessage()" in load)
check("a missing file says where it looked", "describePlaces()" in load)
check("nothing is loaded on a refusal", "return -1;" in load)

# Finding the file is the same code pictures use; the last duplicated rule caused a bug.
check("finding is shared with pictures", "Disk.find(" in schem)
check("listing is shared with pictures", "Disk.list(" in schem)
check("only .litematic is offered", '".litematic"' in schem)

# The commands, the screen and the dashboard.
check("there is a schem command", 'literal("schem")' in client)
for sub in ("ui", "files", "folder", "load"):
    check("there is a schem %s" % sub, 'literal("%s")' % sub in client)
check("a path can be pasted", "StringArgumentType.string()" in body(client,
      "private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> schematicBranch() {"))
check("the screen is opened on the next tick, not inside the command", "openSchems" in client)
check("the dashboard carries the schematics", '\\"schematics\\":{\\"folder\\":' in client)
check("and what is loaded", '\\"builds\\":[' in client)
check("the page shows the folder", 'id="schempath"' in dash)
check("with a copy button", 'id="copyschem"' in dash)
# One copy of the copying, two buttons: the same rule that put finding a file in Disk.
check("both copy buttons share one implementation", dash.count("navigator.clipboard.writeText") == 1)
check("the dashboard listing is cached", "filesCached()" in client and "filesCached()" in schem)

print("FAILED:\n  " + "\n  ".join(fails) if fails else
      "the reader has no game in it; schematics become ordinary builds, states go through "
      "the codec already in use, and a missing block is stone rather than a hole")
sys.exit(1 if fails else 0)
