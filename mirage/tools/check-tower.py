"""The tower fires the player's own call back at them while they climb, and the other colour
to end the run. Read off the shipped source, and simulated against it."""
import io, re, sys
rig  = io.open("src/main/java/dev/skullzz/mirage/client/RigProfile.java", encoding="utf-8").read()
disp = io.open("src/main/java/dev/skullzz/mirage/client/ClientDispensers.java", encoding="utf-8").read()
client = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java", encoding="utf-8").read()

FLOORS = int(re.search(r"public int floors = (\d+)", rig).group(1))
EACH   = int(re.search(r"public int towerEach = (\d+)", rig).group(1))
A      = re.search(r'towerA = "(\w+)"', rig).group(1)
B      = re.search(r'towerB = "(\w+)"', rig).group(1)
SLOTS  = int(re.search(r"int STOCK_SLOTS = (\d+)", disp).group(1))

fails = []
def check(name, cond):
    if not cond: fails.append(name)

check("five floors by default", FLOORS == 5)
check("two of each colour", EACH == 2)
check("white and black boxes", "white" in A and "black" in B and "shulker" in A and "shulker" in B)
check("the boxes fit a dispenser", EACH * 2 <= SLOTS)

# --- the model, mirroring the shipped methods ---------------------------
class Tower:
    def __init__(self):
        self.floors, self.bust_at, self.bust_next, self.call = FLOORS, 0, False, ""
    def called(self):
        return self.call or A
    def other(self, c):
        return B if c == A else A
    def busts_on(self, floor):
        if self.bust_next:
            self.bust_next = False
            return True
        return self.bust_at > 0 and floor == self.bust_at
    def fire(self, floor):
        return self.other(self.called()) if self.busts_on(floor) else self.called()

# a clean run: whatever they call comes back, every floor
for call in (A, B):
    t = Tower(); t.call = call
    out = [t.fire(f) for f in range(1, FLOORS + 1)]
    check("an unrigged run always pays", out == [call] * FLOORS)

# ended on a set floor, and only that floor
for end in range(1, FLOORS + 1):
    t = Tower(); t.call = A; t.bust_at = end
    out = [t.fire(f) for f in range(1, FLOORS + 1)]
    check("floor %d ends the run" % end, out[end - 1] == B)
    check("no other floor ends it", out.count(B) == 1)

# arming ends the next floor whatever floor it is, and is spent by it
for at in range(1, FLOORS + 1):
    t = Tower(); t.call = B
    out = []
    for f in range(1, FLOORS + 1):
        if f == at: t.bust_next = True
        out.append(t.fire(f))
    check("arming ends floor %d" % at, out[at - 1] == A)
    check("arming is spent once", out.count(A) == 1)

# arming beats a counted floor rather than doubling up on it
t = Tower(); t.call = A; t.bust_at = 4; t.bust_next = True
check("arming takes the floor it lands on", t.fire(1) == B)
check("the counted floor still ends it", [t.fire(f) for f in (2, 3, 4)][2] == B)

# a call the player never made must not change the answer's shape
t = Tower()
check("no call yet still fires something", t.fire(1) == A)

# --- and the source has to agree with all that --------------------------
box = re.search(r"private static FakeSpec towerBox\(.*?\n    \}", disp, re.S).group(0)
check("the floor comes from the machine, not a counter",
      "floorAt(pos, watched)" in box and "++profile" not in box)
check("a machine outside the run fires nothing",
      re.search(r"if \(floor == 0\) \{[^}]*return null;", box, re.S) is not None)
check("the call decides the colour", "profile.called()" in box and "otherColour" in box)

busts = re.search(r"public boolean bustsOn\(int floor\) \{(.*?)\n    \}", rig, re.S).group(1)
check("arming is spent when it fires", "this.bustNext = false;" in busts)
check("arming beats the counted floor", busts.index("bustNext") < busts.index("bustAt"))

floor_at = re.search(r"public int floorAt\(BlockPos pos, Set<BlockPos> live\) \{(.*?)\n    \}",
                     rig, re.S).group(1)
check("floors only go to machines in play", "live.contains" in floor_at)
check("no floor past the last one", "candidate <= this.floors" in floor_at)
check("a machine past the run gets none", "return 0;" in floor_at)

# Watched dispensers belong to every rig at once. Laying a game out over all of them handed
# its floors to whichever machines were watched first -- the roulette dropper included --
# leaving the machines it is actually played on with no part in the game.
fill_sig = re.search(r"public static boolean fill\(BlockPos pos, boolean join\)", disp)
check("filling can be told not to join a machine", fill_sig is not None)

fillbody = re.search(r"public static boolean fill\(BlockPos pos, boolean join\) \{(.*?)\n    \}",
                     disp, re.S).group(1)
check("a floor is only handed out when joining", "join ? profile.floorAt" in fillbody)
check("a side is only handed out when joining", "join ? profile.sideAt" in fillbody)

for bulk in ("fillEmptyWatched", "refillWatched"):
    body = re.search(r"public static int %s\(\) \{(.*?)\n    \}" % bulk, disp, re.S).group(1)
    check("%s never joins a machine" % bulk, "fill(pos, false)" in body)

watch = re.search(r"public static boolean watch\(BlockPos pos\) \{(.*?)\n    \}",
                  disp, re.S).group(1)
check("watching joins, even a machine already watched",
      "fill(pos);" in watch and "if (added) fill" not in watch)

# A machine going off during a game is the plainest sign it belongs to it, so firing may
# hand out a part -- but only through the capped methods, which give nothing once the game
# is full. What could not be allowed was the bulk fill above doing it to every watched
# dispenser at once.
check("firing may join, through the capped method", "profile.floorAt(pos, watched)" in box)
check("a full tower turns a machine away", "floors are taken" in box)

paper_slip = re.search(r"private static FakeSpec paperSlip\(.*?\n    \}", disp, re.S).group(0)
check("firing may join a side, through the capped method",
      "profile.sideAt(pos, watched)" in paper_slip)
check("a full paper game turns a machine away", "sides are taken" in paper_slip)

check("a game with no machines says so", "partsInGame" in disp and "partsInGame" in client)

# A dispenser holding shulker boxes places them rather than throwing them, so the answer
# stands on the ground in front of the machine instead of bouncing out of it.
blocks = io.open("src/main/java/dev/skullzz/mirage/client/FakeBlocks.java", encoding="utf-8").read()
check("the tower places its answer", "tower.placeOutput = true" in disp)
stand = re.search(r"private static boolean stand\(.*?\n    \}", disp, re.S).group(0)
check("it goes where the machine faces", "pos.offset(state.get(DispenserBlock.FACING))" in stand)
check("one per machine, the last taken away", "FakeBlocks.unplace(was)" in stand)
check("only where there is room", "FakeBlocks.place(target" in stand)

# A machine with something already in front of it used to take the item off its count
# anyway, so its stock drained while nothing ever appeared.
check("placing reports whether it happened", "private static boolean stand(" in disp)
check("throwing reports whether it happened", "private static boolean spawn(" in disp)
check("nothing is taken off the count unless it came out", "if (out) deplete(" in disp)

# Each arm on its own: reading the whole choice would match both calls and pass whichever
# way round they were.
fire = re.search(r"profile\.placeOutput\s*\?(.*?):(.*?);", disp, re.S)
check("placing is what the flag does", "stand(world" in fire.group(1)
      and "spawn(world" not in fire.group(1))
check("throwing is what it does otherwise", "spawn(world" in fire.group(2)
      and "stand(world" not in fire.group(2))

# A placed block is meant to be looked at from a step away, so the wide clearance a build
# uses would leave a hole exactly where the answer should be. Only what could hold the
# player up is unsafe: a full block at their own level cannot be stepped onto without a
# jump, and one that merely blocks the way never puts them where the server disagrees.
close = re.search(r"private static boolean tooClose\(.*?\n    \}", blocks, re.S).group(0)
check("a placed block uses the tight rule", "underfootOnly.contains(pos)" in close)
under = re.search(r"private static boolean underfoot\(.*?\n    \}", blocks, re.S).group(0)
check("tight means the block below the feet", "Math.floor(player.getY()) - 1" in under)
check("tight means the player's own column", "0.8" in under)

def underfoot_py(px, py, pz, bx, by, bz):
    import math
    if by != math.floor(py) - 1: return False
    return abs(px - (bx + 0.5)) < 0.8 and abs(pz - (bz + 0.5)) < 0.8

for px, pz in ((0.5, 0.5), (0.2, 0.9), (12.5, -7.5)):
    import math
    fx, fy, fz = math.floor(px), 64, math.floor(pz)
    check("standing on it is held back", underfoot_py(px, 64.0, pz, fx, fy - 1, fz))
    check("beside it at your level still shows",
          not underfoot_py(px, 64.0, pz, fx + 1, fy, fz))
    check("beside it below your level still shows",
          not underfoot_py(px, 64.0, pz, fx + 2, fy - 1, fz))
    check("above your head still shows", not underfoot_py(px, 64.0, pz, fx, fy + 1, fz))

check("placed blocks go with the master switch", "clearStanding()" in disp)

check("the arm key ends a tower run", "profile.bustNext = true;" in disp)
check("the call keys are bound", "callFirst" in client and "callSecond" in client)
check("floors are pruned with the machine", "towerFloors.remove(pos)" in disp)
# Scoped to repair: fill has its own "if (profile.tower)" branch, and matching that one
# would pass while repair quietly kept the presets.
repair = re.search(r"private static void repair\(RigProfile profile\) \{(.*?)\n    \}",
                   disp, re.S).group(1)
tower_branch = re.search(r"if \(profile\.tower\) \{(.*?)\n        \}", repair, re.S).group(1)
check("a tower rig carries no presets", "profile.presets.clear()" in tower_branch)
check("floors are kept inside the run", "profile.bustAt = 0" in tower_branch)
check("an unknown colour falls back", "white_shulker_box" in tower_branch)

print("FAILED: " + "; ".join(dict.fromkeys(fails)) if fails else
      "tower: %d floors, %dx%s and %dx%s each, call fires back, arming or floor %s ends it"
      % (FLOORS, EACH, A, EACH, B, "n"))
sys.exit(1 if fails else 0)
