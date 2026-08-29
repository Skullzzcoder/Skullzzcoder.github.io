"""Mirrors startRound/paperSlip from the shipped source and checks the rigged side always
draws higher, that the two machines never tie, and that the slips match what was laid out."""
import io, re, random, sys
rig  = io.open("src/main/java/dev/skullzz/mirage/client/RigProfile.java", encoding="utf-8").read()
disp = io.open("src/main/java/dev/skullzz/mirage/client/ClientDispensers.java", encoding="utf-8").read()

NUMBERS = int(re.search(r"public int numbers = (\d+)", rig).group(1))
SLOTS   = int(re.search(r"int STOCK_SLOTS = (\d+)", disp).group(1))
ROUND   = int(re.search(r"int ROUND_TICKS = (\d+)", disp).group(1))
SIDES   = re.findall(r'"(\w+)"', re.search(r"DEFAULT_SIDES = \{([^}]*)\}", rig).group(1))
assert 'this.numbers - random.nextInt(span)' in rig, "high roll formula changed"
assert 'int span = Math.max(1, this.numbers / 2)' in rig, "span formula changed"

def slip_name(n, side): return "%d (%s)" % (n, side) if side else str(n)

class Rig:
    def __init__(self, winner=""):
        self.numbers, self.winner = NUMBERS, winner
        self.round_tick = -10**18
    def start_round(self, rnd, tick):
        self.round_tick = tick
        span = max(1, self.numbers // 2)
        self.high = self.numbers - rnd.randrange(span)
        self.low  = 1 + rnd.randrange(max(1, self.high - 1))
        self.round_winner = self.winner or SIDES[rnd.randrange(len(SIDES))]

def fire(r, side, tick, rnd):
    if tick - r.round_tick > ROUND: r.start_round(rnd, tick)
    return slip_name(r.high if r.round_winner == side else r.low, side)

# what fill() lays out
layout = {s: [slip_name(n + 1, s) for n in range(min(NUMBERS, SLOTS))] for s in SIDES}

fails = []
def check(name, cond):
    if not cond: fails.append(name)

rnd = random.Random(7)
check("two default sides", len(SIDES) == 2)

for rigged in SIDES:
    r, tick = Rig(rigged), 0
    for round_no in range(400):
        tick += ROUND + 5                       # a new round
        left  = fire(r, SIDES[0], tick, rnd)
        right = fire(r, SIDES[1], tick + 1, rnd)   # same round: within the window

        nums = {s: int(t.split(" ")[0]) for s, t in ((SIDES[0], left), (SIDES[1], right))}
        check("no tie", nums[SIDES[0]] != nums[SIDES[1]])
        check("%s wins when rigged" % rigged,
              nums[rigged] > nums[SIDES[0] if rigged == SIDES[1] else SIDES[1]])
        check("slip is one that was laid out",
              left in layout[SIDES[0]] and right in layout[SIDES[1]])
        check("names carry the side", left.endswith("(%s)" % SIDES[0]))
        if fails: break
    if fails: break

# a fresh round must be drawn once the machines have been idle
r = Rig(SIDES[0]); fire(r, SIDES[0], 0, rnd); first = r.high
fire(r, SIDES[0], ROUND + 1, rnd)
check("an idle gap starts a new round", r.round_tick == ROUND + 1)

# left to chance, both sides must win sometimes
r, wins = Rig(""), {s: 0 for s in SIDES}
for i in range(600):
    t = i * (ROUND + 5)
    fire(r, SIDES[0], t, rnd)
    wins[r.round_winner] += 1
check("chance picks both sides", all(w > 0 for w in wins.values()))

print("FAILED: " + "; ".join(dict.fromkeys(fails)) if fails else
      "paper draws: %s, slips 1-%d, rigged side always higher, no ties" % (SIDES, NUMBERS))
sys.exit(1 if fails else 0)
