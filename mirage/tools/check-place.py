"""Standing a build somewhere, moving it, and taking it down again.

Two faults lived here. A placement was remembered with no note of which world it was made
in, so a build stood up on a server came back in single-player at the same coordinates,
in the middle of whatever was there, with nothing on screen saying why. And there was no
way to shift one: a build landed where it landed, and lining it up with something real
meant taking it down and re-aiming from scratch."""
import io, re, sys
blocks = io.open("src/main/java/dev/skullzz/mirage/client/FakeBlocks.java", encoding="utf-8").read()
client = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java", encoding="utf-8").read()

fails = []
def check(name, cond):
    if not cond: fails.append(name)

def body(source, signature):
    found = re.search(re.escape(signature) + r"(.*?)\n    \}", source, re.S)
    return found.group(1) if found else ""

# ------------------------------------------------------- raising and lowering
raise_ = body(blocks, "private static void raise(String name) {")
lower  = body(blocks, "private static boolean lower(String name) {")
put    = body(blocks, "public static int put(String name, BlockPos corner) {")
take   = body(blocks, "public static boolean take(String name) {")

check("painting and recording are separate", raise_ != "" and lower != "")
# The whole point of the split: a world change hides a build without forgetting it.
check("lowering keeps the record",
      "placed.remove" not in lower and "placedIn.remove" not in lower)
check("taking down forgets it", "placed.remove(name)" in take and "placedIn.remove(name)" in take)
check("lowering puts the real blocks back", "restore(world, pos)" in lower)
check("taking down goes through lowering", "lower(name)" in take)

# ------------------------------------------------------------ moving it
move   = body(blocks, "public static BlockPos move(String name, int dx, int dy, int dz) {")
moveTo = body(blocks, "public static BlockPos moveTo(String name, BlockPos corner) {")
check("a standing build can be nudged", move != "")
check("a nudge is relative to where it stands", "corner.add(dx, dy, dz)" in move)
check("a nudge goes through the exact move", "moveTo(name" in move)
check("moving one that is not standing does nothing", "return null" in move)
check("moving to a spot re-places it", "put(name, target)" in moveTo)
# Down before up, or the old position's real blocks are restored over the new position.
check("moving takes it down before putting it up", "take(name);" in put)
check("nothing is moved that is not standing", "placed.containsKey(name)" in moveTo)

# ------------------------------------------------------- which world it is in
key = body(blocks, "public static String worldKey() {")
check("a world can be named", key != "")
# A wrong guess at a Minecraft method is a crash; this is only ever a label, so it is
# asked for by reflection, by one exact name, and failure is not fatal.
check("the name is asked for, not assumed", 'getMethod("getCurrentServerEntry")' in key)
check("not knowing is not a crash", "catch (ReflectiveOperationException | RuntimeException" in key)
check("not knowing means every world matches", key.count('return "";') >= 2)
# A key built from a server's own text would carry its ping and change every tick.
check("the key cannot change on its own", "server.toString()" not in key)
check("the lookup happens once", "lookedForServerEntry" in key)

belongs = body(blocks, "public static boolean belongsHere(String name) {")
check("a build knows whether it belongs here", belongs != "")
check("an unrecorded world matches anywhere", "where == null" in belongs)

sync = body(blocks, "public static void syncWorld() {")
check("worlds are synced", sync != "")
check("nothing happens until the world changes", "if (now.equals(standingWorld)) return;" in sync)
check("what belongs here goes up", "raise(name)" in sync)
check("what does not comes off the screen", "lower(name)" in sync)
tick = body(blocks, "public static void tick(MinecraftClient client) {")
check("the sweep asks every tick", "syncWorld();" in tick)
# With nothing raised yet there is nothing to sweep, so the early return must come after.
check("and asks before giving up on an empty board",
      tick.index("syncWorld();") < tick.index("order.isEmpty()"))

# ------------------------------------------------- surviving a restart, honestly
check("the world is written down", 'json.addProperty("in"' in blocks)
check("and read back", 'json.has("in")' in blocks)
read = body(blocks, "private static void readFrom(JsonObject root) {")
# The old file format said nothing about worlds; those builds keep the old behaviour
# rather than vanishing.
check("an older file still stands its builds up", 'json.has("in") ? json.get("in").getAsString() : ""' in read)
# Loading must not stand anything up: the world is not loaded yet, and which world it
# turns out to be is the entire question.
check("loading raises nothing", "put(entry.getKey(), entry.getValue());" not in read)
check("loading leaves the world unknown", "standingWorld = null;" in read)
reset = body(blocks, "public static void reset() {")
check("leaving a world forces a fresh look", "standingWorld = null;" in reset)

# ------------------------------------------------------------ the commands
check("there is a move command", 'literal("move")' in client)
check("there is an align command", 'literal("align")' in client)
check("taking down needs no name", "MirageClient::buildTakeLookedAt" in client)
takeAt = body(blocks, "public static String takeAt(BlockPos pos) {")
check("what you look at is what comes down", "owner(pos)" in takeAt)

looked = body(client, "private static int buildTakeLookedAt(CommandContext<FabricClientCommandSource> context) {")
check("looking at nothing says what to do instead", "/fake build take <name>" in looked)
check("looking at a real block says so", "not part of a build" in looked)

moveCmd = body(client, "private static int buildMove(CommandContext<FabricClientCommandSource> context) {")
# Directions, not x/y/z: the corner it was placed from is rarely the one you can see.
for way in ("east", "up", "south"):
    check("a nudge is given in %s" % way, '"%s"' % way in moveCmd)
check("a nudge that lands says where", "corner.getX()" in moveCmd)
check("a nudge on a build that is down says so", "is not standing" in moveCmd)
NUDGE = int(re.search(r"int NUDGE = (\d+);", client).group(1))
check("a nudge is bounded", 8 <= NUDGE <= 4096)
check("the bound is on the argument", "IntegerArgumentType.integer(-NUDGE, NUDGE)" in client)

align = body(client, "private static int buildAlign(CommandContext<FabricClientCommandSource> context) {")
check("aligning puts the corner where you point", "hit.getBlockPos()" in align)
check("aligning does not aim one block off", "hit.getBlockPos().up()" not in align)
check("aligning points at nudging next", "/fake build move" in align)

# A build standing elsewhere answers both "why can I not see it" and "why did that appear".
check("the list says when a build is in another world", "not here" in client)

for name in ("move", "align", "take"):
    check("%s is remembered across a restart" % name,
          client.count("FakeBlocks.persist()") >= 3)

print("FAILED: " + "; ".join(fails) if fails else
      "builds raise and lower without forgetting; a placement is tied to its world and "
      "an unknown world matches all; nudge up to %d blocks, align by eye" % NUDGE)
sys.exit(1 if fails else 0)
