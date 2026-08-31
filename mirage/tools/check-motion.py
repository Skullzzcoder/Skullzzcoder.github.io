"""Everything the mod puts in the world is client-only, so nothing arrives by the packet
that would normally set it up. These are the things that go wrong when it does not."""
import io, re, sys
disp = io.open("src/main/java/dev/skullzz/mirage/client/ClientDispensers.java", encoding="utf-8").read()
blocks = io.open("src/main/java/dev/skullzz/mirage/client/FakeBlocks.java", encoding="utf-8").read()

fails = []
def check(name, cond):
    if not cond: fails.append(name)

# The renderer draws between where a thing was last tick and where it is now. A freshly
# built entity has no last tick, so its first frame is drawn from wherever those fields
# started -- usually the world origin, as a streak across the map.
spawn = re.search(r"private static void spawn\(.*?\n    \}", disp, re.S).group(0)
check("a thrown item starts where it is", "refreshPositionAndAngles" in spawn)
launch = re.search(r"private static void launchArrow\(.*?\n    \}", disp, re.S).group(0)
check("an arrow starts where it is", "refreshPositionAndAngles" in launch)
check("an arrow is not merely placed", "arrow.setPosition(from.x" not in launch)

land = re.search(r"private static void land\(FlyingArrow arrow\) \{(.*?)\n    \}",
                 disp, re.S).group(1)
check("an arrow lands without a jump", "refreshPositionAndAngles" in land)

# Once we carry an item to the player ourselves, vanilla has to stop carrying it too, or
# gravity and the throw pull one way each tick and we snap it back the other.
tidy = re.search(r"private static void tidySpawned\(.*?\n    \}", disp, re.S).group(0)
start = tidy[tidy.index("item.collectStart = tick;"):]
for stop in ("setVelocity(Vec3d.ZERO)", "setNoGravity(true)", "noClip = true"):
    check("the flight in stops vanilla: %s" % stop, stop in start)

# and the sweep must not be sent back to the start every time a block is placed, or a
# large build never reaches its far side
reindex = re.search(r"private static void reindex\(\) \{(.*?)\n    \}", blocks, re.S).group(1)
check("the sweep keeps its place", "cursor = 0;" not in reindex
      or "if (cursor > order.size())" in reindex)

print("FAILED: " + "; ".join(dict.fromkeys(fails)) if fails else
      "spawned entities start where they are, the flight in is ours alone, "
      "and the sweep keeps its place")
sys.exit(1 if fails else 0)
