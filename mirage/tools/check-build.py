"""The schematic layer paints into the client's own world, so two things have to hold: the
player must never be able to collide with a block the server does not have, and the real
state of every position must come back exactly as it was. Read off the shipped source."""
import io, re, sys
blocks = io.open("src/main/java/dev/skullzz/mirage/client/FakeBlocks.java", encoding="utf-8").read()
client = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java", encoding="utf-8").read()

SIDE  = int(re.search(r"CLEAR_SIDE = (\d+)", blocks).group(1))
BELOW = int(re.search(r"CLEAR_BELOW = (\d+)", blocks).group(1))
ABOVE = int(re.search(r"CLEAR_ABOVE = (\d+)", blocks).group(1))
MAX   = int(re.search(r"MAX_BLOCKS = (\d+)", blocks).group(1))
TICKS = int(re.search(r"SWEEP_TICKS = (\d+)", blocks).group(1))
MIN_S = int(re.search(r"MIN_SLICE = (\d+)", blocks).group(1))
MAX_S = int(re.search(r"MAX_SLICE = (\d+)", blocks).group(1))

fails = []
def check(name, cond):
    if not cond: fails.append(name)

def cleared(px, py, pz, bx, by, bz):
    """tooClose(), as shipped."""
    import math
    feet = math.floor(py)
    if bz is None: return False
    if by < feet - BELOW or by > feet + ABOVE: return False
    return abs(bx - math.floor(px)) <= SIDE and abs(bz - math.floor(pz)) <= SIDE

def suppressed(px, py, pz, bx, by, bz, real_is_air):
    """A fake is held back only near the player AND only where the server has nothing."""
    return cleared(px, py, pz, bx, by, bz) and real_is_air

# The one that matters: over air, whatever the player stands on and every block their body
# occupies has to be held back. Standing on a block the server has not got is what reads as
# flying. Over a real block the paint is only a change of skin -- both sides agree something
# solid is there -- so it stays, which is what lets a real floor hold up a painted one.
for px in (0.0, 0.5, -0.3, 12.9, -7.5):
    for py in (64.0, 64.62, 70.0, -12.4):
        for pz in (0.0, 0.5, -0.3, 8.2):
            import math
            fx, fy, fz = math.floor(px), math.floor(py), math.floor(pz)
            check("nothing to stand on underfoot is held back",
                  suppressed(px, py, pz, fx, fy - 1, fz, True))
            check("nothing at the feet is held back",
                  suppressed(px, py, pz, fx, fy, fz, True))
            check("nothing at head height is held back",
                  suppressed(px, py, pz, fx, fy + 1, fz, True))
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                check("nothing in the step ahead is held back",
                      suppressed(px, py, pz, fx + dx, fy, fz + dz, True))

            # over a real block, the paint stays even right underfoot
            check("a real floor keeps its paint underfoot",
                  not suppressed(px, py, pz, fx, fy - 1, fz, False))
            check("a real wall keeps its paint beside you",
                  not suppressed(px, py, pz, fx + 1, fy, fz, False))

            # far away must never be held back, or nothing would ever show
            check("distant blocks still show", not suppressed(px, py, pz, fx + 6, fy, fz, True))
            check("blocks well overhead still show",
                  not suppressed(px, py, pz, fx, fy + 9, fz, True))

# the sweep has to act on that, not merely compute it
tick = re.search(r"public static void tick\(MinecraftClient client\) \{(.*?)\n    \}", blocks, re.S).group(1)
# One position is put right in one place, and the sweep is what walks the positions past
# it. Both routes in have to go through it: the slice, and the block being broken, which
# cannot wait the second the sweep would take to come round to it.
refresh = re.search(r"private static void refresh\(ClientWorld world, ClientPlayerEntity player, "
                    r"BlockPos pos\) \{(.*?)\n    \}", blocks, re.S).group(1)
check("the sweep goes through it", "refresh(world, player, order.get(cursor++))" in tick)
check("and so does the block being broken", "refresh(world, player, pinned)" in tick)
check("the sweep puts a too-close block back",
      "tooClose(player, pos)" in refresh and "restore(world, pos)" in refresh)
check("holding back is only over air", "beneath(world, pos).isAir()" in refresh)
check("the sweep skips unloaded chunks", "isChunkLoaded" in refresh)
check("the sweep is sliced", "MAX_SLICE" in tick and "MIN_SLICE" in tick)

# The slice scales with the build, so a bigger one is still painted in about the same time
# rather than taking proportionally longer to catch up after the server corrects it.
def slice_for(n):
    return min(n, max(MIN_S, min(MAX_S, n // TICKS)))

for n in (2000, 30000, 120000, MAX):
    passes = -(-n // slice_for(n))
    check("a %d block build sweeps within 6s" % n, passes / 20.0 <= 6.0)
    check("a %d block build does not sweep in one tick" % n, slice_for(n) <= MAX_S)
check("a small build sweeps almost at once", -(-500 // slice_for(500)) <= 2)

# The block list is stored as one string: a few million JsonPrimitives cost far more in
# objects than the file ever does in bytes.
check("blocks are packed into one value", 'addProperty("packed"' in blocks)
check("older files still load", '"blocks"' in blocks and "readLoose" in blocks)
check("packing is base64 of the raw ints", "Base64.getEncoder" in blocks
      and "asIntBuffer" in blocks)

# and the capture has to stop at the cap across all three loops, not just the innermost
save = re.search(r"public static Build save\(ClientWorld world, String name\) \{(.*?)\n    \}",
                 blocks, re.S).group(1)
check("the cap breaks out of the whole capture", "break capture;" in save)
check("the box is measured in long arithmetic", "public static long regionSize()" in blocks)

# the real state must be taken once and given back
paint = re.search(r"private static void paint\(.*?\n    \}", blocks, re.S).group(0)
# Refreshed on every paint, not just the first: a block placed by hand under a fake arrives
# as a server update, and treating that as still-air would keep holding the fake back and
# the floor would never become standable.
check("the real state is refreshed each paint", "else real.put(key, there);" in paint)
check("the real state is not stale-guarded", "!real.containsKey(pos)" not in paint)
# The one exception, and it may only be that one: while a position is being broken, what is
# there is not the server's word but whatever vanilla just mined out of the client's copy.
check("with the block being broken the only exception",
      paint.count("putIfAbsent") == 1 and "if (key.equals(pinned)) real.putIfAbsent" in paint)
restore = re.search(r"private static void restore\(.*?\n    \}", blocks, re.S).group(0)
check("restoring writes the real state back", "world.setBlockState(pos, was)" in restore)
check("restoring forgets the shadow", "real.remove(pos)" in restore)

take = re.search(r"public static boolean take\(String name\) \{(.*?)\n    \}", blocks, re.S).group(1)
lower = re.search(r"private static boolean lower\(String name\) \{(.*?)\n    \}", blocks, re.S).group(1)
# Taking down is lowering plus forgetting; the blocks go back either way.
check("taking a build down restores every block",
      "restore(world, pos)" in lower and "lower(name)" in take)
check("and forgets that it stands", "placed.remove(name)" in take)
check("lowering alone does not forget", "placed.remove" not in lower)

# the master switch has to reach it, and hiding must not forget where anything stands
check("the master switch reaches builds", "if (!SelfFakes.enabled()) {\n            hide(world);" in blocks)
hide = re.search(r"public static void hide\(ClientWorld world\) \{(.*?)\n    \}", blocks, re.S).group(1)
check("hiding keeps the placements", "placed.clear()" not in hide and "showing.clear()" not in hide)
check("leaving a world drops the shadows", "real.clear()" in
      re.search(r"public static void reset\(\) \{(.*?)\n    \}", blocks, re.S).group(1))

# A hole cut for a real machine has to survive standing the build up again, and has to
# put the world back where it was rather than just stop painting.
cut = re.search(r"public static int cut\(BlockPos centre, int radius\) \{(.*?)\n    \}",
                blocks, re.S).group(1)
check("cutting puts the real block back", "restore(world, pos)" in cut)
check("cutting drops it from the board", "showing.remove(pos)" in cut)
check("a hole is held against the build", "cuts.add(offset)" in cut)
check("holes are relative to the corner", "pos.getX() - corner.getX()" in cut)

put = re.search(r"public static int put\(String name, BlockPos corner\) \{(.*?)\n    \}",
                blocks, re.S).group(1)
raise_ = re.search(r"private static void raise\(String name\) \{(.*?)\n    \}",
                   blocks, re.S).group(1)
# Standing up is put() recording where, and raise() painting it; the holes are skipped
# in the painting half, and put() must go through it rather than paint its own copy.
check("standing it up again keeps the holes",
      "cuts.contains" in raise_ and "raise(name);" in put)
check("standing up records where it stands", "placed.put(name" in put)
check("and which world it stands in", "placedIn.put(name" in put)
check("holes are written", 'json.add("cuts", cuts)' in blocks)
check("holes are read back", 'getAsJsonArray("cuts")' in blocks)

# filling one back in must not take the whole build down and repaint it
refill = re.search(r"private static int refill\(.*?\n    \}", blocks, re.S).group(0)
# "showing.put" is the board; a bare put()/take() would be restanding the whole build
bare = re.sub(r"showing\.put\(", "", refill)
check("filling a hole does not restand the build",
      re.search(r"\b(put|take)\(", bare) is None)
check("filling a hole puts it back on the board", "showing.put(" in refill)

MAXCUT = int(re.search(r"MAX_CUT_RADIUS = (\d+)", blocks).group(1))
check("the cut radius is bounded", 0 < MAXCUT <= 8)
check("the command bounds it too", "MAX_CUT_RADIUS" in client)

check("builds are ticked", "FakeBlocks.tick(client)" in client)
check("builds are loaded at startup", "FakeBlocks.load()" in client)
check("a build is capped", 0 < MAX <= 1000000 and "MAX_BLOCKS" in client)
# 16 bytes a block before base64, so the file stays somewhere sane at the cap
check("the file stays manageable at the cap", MAX * 16 * 4 / 3 < 20e6)

print("FAILED: " + "; ".join(dict.fromkeys(fails)) if fails else
      "clearance %d wide, %d below, %d above keeps the player off every fake block; "
      "cap %d, full sweep %.1fs at the cap; paint kept over real blocks so a placed "
      "floor is standable" % (SIDE, BELOW, ABOVE, MAX, -(-MAX // slice_for(MAX)) / 20.0))
sys.exit(1 if fails else 0)
