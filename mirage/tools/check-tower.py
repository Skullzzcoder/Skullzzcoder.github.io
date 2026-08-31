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
check("the floor comes from the machine, not a counter", "floorOf(pos)" in box)
check("a machine outside the run fires nothing", "if (floor == 0) return null;" in box)
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

# firing must never make a machine part of a game it was not set up for
check("firing reads the floor rather than handing one out",
      "profile.floorOf(pos)" in box and "floorAt" not in box)
paper_slip = re.search(r"private static FakeSpec paperSlip\(.*?\n    \}", disp, re.S).group(0)
check("firing reads the side rather than handing one out",
      "profile.sideOf(pos)" in paper_slip and "sideAt" not in paper_slip)

check("a game with no machines says so", "partsInGame" in disp and "partsInGame" in client)

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
