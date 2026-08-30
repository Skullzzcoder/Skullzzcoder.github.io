"""Mirrors startRound/paperSlip from the shipped source and checks the rigged side always
draws higher, that the two machines never tie, and that the slips match what was laid out."""
import io, re, random, sys
rig  = io.open("src/main/java/dev/skullzz/mirage/client/RigProfile.java", encoding="utf-8").read()
disp = io.open("src/main/java/dev/skullzz/mirage/client/ClientDispensers.java", encoding="utf-8").read()

NUMBERS = int(re.search(r"public int numbers = (\d+)", rig).group(1))
SLOTS   = int(re.search(r"int STOCK_SLOTS = (\d+)", disp).group(1))
ROUND   = int(re.search(r"int ROUND_TICKS = (\d+)", disp).group(1))
SIDES   = re.findall(r'"(\w+)"', re.search(r"DEFAULT_SIDES = \{([^}]*)\}", rig).group(1))
HOUSE   = SIDES[int(re.search(r"house = DEFAULT_SIDES\[(\d)\]", rig).group(1))]
TIE_PCT = int(re.search(r"tieChance = (\d+)", rig).group(1))
assert 'this.numbers - random.nextInt(span)' in rig, "high roll formula changed"
assert 'int span = Math.max(1, this.numbers / 2)' in rig, "span formula changed"

MARKER = int(re.search(r"public long roundTick = (.+);", rig).group(1)
             .replace("Long.MIN_VALUE", str(-2**63)))

def as_long(v):
    return (v + 2**63) % 2**64 - 2**63

def slip_name(n, side): return "%d (%s)" % (n, side) if side else str(n)

class Rig:
    def __init__(self, winner="", house=HOUSE, tie_pct=TIE_PCT):
        self.numbers, self.winner = NUMBERS, winner
        self.house, self.tie_pct = house, tie_pct
        self.round_tick = -10**18
    def start_round(self, rnd, tick):
        self.round_tick = tick
        self.round_winner = self.winner or SIDES[rnd.randrange(len(SIDES))]
        span = max(1, self.numbers // 2)
        self.high = self.numbers - rnd.randrange(span)
        # a draw belongs to the house, so only a round the house takes may come out level
        level = self.round_winner == self.house and rnd.randrange(100) < self.tie_pct
        self.low = self.high if level else 1 + rnd.randrange(max(1, self.high - 1))

def fire(r, side, tick, rnd):
    if tick - r.round_tick > ROUND: r.start_round(rnd, tick)
    return slip_name(r.high if r.round_winner == side else r.low, side)

# what fill() lays out
layout = {s: [slip_name(n + 1, s) for n in range(min(NUMBERS, SLOTS))] for s in SIDES}

fails = []
ties = {s: 0 for s in SIDES}
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
        other = SIDES[0] if rigged == SIDES[1] else SIDES[1]

        if rigged == HOUSE:
            # a draw is the house's win, so level is allowed but never behind
            check("the house is never behind when rigged", nums[rigged] >= nums[other])
            if nums[rigged] == nums[other]: ties[rigged] += 1
        else:
            # a draw would hand the player the loss the rigging exists to avoid
            check("the player wins outright when rigged", nums[rigged] > nums[other])
            check("the player never draws", nums[rigged] != nums[other])
        check("slip is one that was laid out",
              left in layout[SIDES[0]] and right in layout[SIDES[1]])
        check("names carry the side", left.endswith("(%s)" % SIDES[0]))
        if fails: break
    if fails: break

# The no-round-yet marker is the most negative long there is, so the round test must add
# to the older side rather than subtract from the newer: subtracting overflows and reads as
# "same round" forever, leaving every draw on its starting value.
guard = re.search(r"if \((.+?)\) profile\.startRound", disp).group(1)
check("the round test does not subtract the marker", "tick - profile.roundTick" not in guard)
for t in (1, 100, 5000, 20 * 60 * 20):
    check("a first fire at tick %d starts a round" % t, t > MARKER + ROUND)
    check("subtracting the marker at tick %d would have failed" % t,
          not as_long(t - MARKER) > ROUND)

# a fresh round must be drawn once the machines have been idle
r = Rig(SIDES[0]); fire(r, SIDES[0], 0, rnd); first = r.high
fire(r, SIDES[0], ROUND + 1, rnd)
check("an idle gap starts a new round", r.round_tick == ROUND + 1)

# Watched dispensers are shared by every rig, so switching to paper reaches the roulette
# dropper and the coin-flip machines too. Only the two sides may ever be handed out.
side_at = re.search(r"public String sideAt\(BlockPos pos, Set<BlockPos> live\) \{(.*?)\n    \}",
                    rig, re.S).group(1)
check("a machine with no side left is not named", '"Side "' not in side_at)
check("only live machines hold a name", "live.contains" in side_at)
check("a sideless machine lays out nothing", "side.isEmpty() ? null" in disp)
check("a sideless machine fires nothing", "if (side.isEmpty()) return null;" in disp)
check("sides are pruned when a machine stops being watched", "profile.sides.remove(pos)" in disp)

def side_at_py(sides, pos, live):
    if pos in sides: return sides[pos]
    taken = {n for p, n in sides.items() if p in live}
    for c in SIDES:
        if c not in taken:
            sides[pos] = c
            return c
    return ""

# four watched machines, only two of them the paper game's
sides, live = {}, {"left", "right", "dropper", "flip"}
got = [side_at_py(sides, p, live) for p in ("left", "right", "dropper", "flip")]
check("the first two get the sides", got[:2] == SIDES)
check("the other machines get no side", got[2:] == ["", ""])

# unwatching one frees its name for the machine that replaces it
del sides["left"]
live = {"right", "dropper", "flip", "newleft"}
check("a freed name is reused", side_at_py(sides, "newleft", live) == SIDES[0])

# The simulation above is a model, so the shipped condition has to be read too: a level
# draw must be gated on the house taking the round, or the model and the code disagree
# and only the model is being tested.
level = re.search(r"boolean level = (.+?);", rig, re.S).group(1)
check("a level draw is gated on the house winning", "isHouse(this.roundWinner)" in level)
check("a level draw is still only sometimes", "tieChance" in level)
check("the winner is settled before the numbers",
      rig.index("this.roundWinner =") < rig.index("boolean level ="))

check("the house does draw sometimes", TIE_PCT == 0 or ties[HOUSE] > 0)
check("the player never draws at all", ties[SIDES[0] if HOUSE == SIDES[1] else SIDES[1]] == 0)

# a house of nobody means the machines never agree
r, rnd2 = Rig(HOUSE, house=""), random.Random(3)
levels = 0
for i in range(400):
    r.start_round(rnd2, i * (ROUND + 5))
    if r.low == r.high: levels += 1
check("with no house nothing is ever level", levels == 0)

# left to chance, both sides must win sometimes
r, wins = Rig(""), {s: 0 for s in SIDES}
for i in range(600):
    t = i * (ROUND + 5)
    fire(r, SIDES[0], t, rnd)
    wins[r.round_winner] += 1
check("chance picks both sides", all(w > 0 for w in wins.values()))

print("FAILED: " + "; ".join(dict.fromkeys(fails)) if fails else
      "paper draws: %s, slips 1-%d; %s takes draws (%d%% level, %d seen), %s never draws"
      % (SIDES, NUMBERS, HOUSE, TIE_PCT, ties[HOUSE],
         SIDES[0] if HOUSE == SIDES[1] else SIDES[1]))
sys.exit(1 if fails else 0)
