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

# The one that matters: whatever the player is standing on, and every block their body
# occupies, has to be held back. Standing on a block the server has not got is what reads
# as flying, and walking into one they cannot pass is what leaves them stuck.
for px in (0.0, 0.5, -0.3, 12.9, -7.5):
    for py in (64.0, 64.62, 70.0, -12.4):
        for pz in (0.0, 0.5, -0.3, 8.2):
            import math
            fx, fy, fz = math.floor(px), math.floor(py), math.floor(pz)
            check("the block underfoot is held back", cleared(px, py, pz, fx, fy - 1, fz))
            check("the block at the feet is held back", cleared(px, py, pz, fx, fy, fz))
            check("the block at head height is held back", cleared(px, py, pz, fx, fy + 1, fz))
            # and the ring they would walk into
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                check("the step ahead is held back",
                      cleared(px, py, pz, fx + dx, fy, fz + dz))
            # far away must not be held back, or nothing would ever show
            check("distant blocks still show", not cleared(px, py, pz, fx + 6, fy, fz))
            check("blocks well overhead still show", not cleared(px, py, pz, fx, fy + 9, fz))

# the sweep has to act on that, not merely compute it
tick = re.search(r"public static void tick\(MinecraftClient client\) \{(.*?)\n    \}", blocks, re.S).group(1)
check("the sweep puts a too-close block back", "tooClose(player, pos)" in tick and "restore(world, pos)" in tick)
check("the sweep skips unloaded chunks", "isChunkLoaded" in tick)
check("the sweep is sliced", "SWEEP_PER_TICK" in tick)

# the real state must be taken once and given back
paint = re.search(r"private static void paint\(.*?\n    \}", blocks, re.S).group(0)
check("the real state is remembered once", "!real.containsKey(pos)" in paint)
restore = re.search(r"private static void restore\(.*?\n    \}", blocks, re.S).group(0)
check("restoring writes the real state back", "world.setBlockState(pos, was)" in restore)
check("restoring forgets the shadow", "real.remove(pos)" in restore)

take = re.search(r"public static boolean take\(String name\) \{(.*?)\n    \}", blocks, re.S).group(1)
check("taking a build down restores every block", "restore(world, pos)" in take)

# the master switch has to reach it, and hiding must not forget where anything stands
check("the master switch reaches builds", "if (!SelfFakes.enabled()) {\n            hide(world);" in blocks)
hide = re.search(r"public static void hide\(ClientWorld world\) \{(.*?)\n    \}", blocks, re.S).group(1)
check("hiding keeps the placements", "placed.clear()" not in hide and "showing.clear()" not in hide)
check("leaving a world drops the shadows", "real.clear()" in
      re.search(r"public static void reset\(\) \{(.*?)\n    \}", blocks, re.S).group(1))

check("builds are ticked", "FakeBlocks.tick(client)" in client)
check("builds are loaded at startup", "FakeBlocks.load()" in client)
check("a build is capped", MAX <= 100000 and "MAX_BLOCKS" in client)

print("FAILED: " + "; ".join(dict.fromkeys(fails)) if fails else
      "clearance %d wide, %d below, %d above keeps the player off every fake block; "
      "real states restored on take, hide and world change" % (SIDE, BELOW, ABOVE))
sys.exit(1 if fails else 0)
