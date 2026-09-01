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
m = re.search(r"public int tieChance(?: = (\d+))?;", rig)
TIE_PCT = int(m.group(1)) if m.group(1) else 0   # no initialiser means off
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
    def start_round(self, rnd, tick, known=None):
        self.round_tick = tick
        known = SIDES if known is None else known
        # a winner nobody answers to would leave both machines on the low number, but
        # nothing is stale before any machine has been given a side
        wanted = self.winner
        if wanted and known and wanted not in known: wanted = ""
        self.round_winner = wanted or (rnd.choice(known) if known else "")
        span = max(1, self.numbers // 2)
        self.high = self.numbers - rnd.randrange(span)
        # a draw belongs to the house, so only a round the house takes may come out level
        level = (self.tie_pct > 0 and self.round_winner == self.house
                 and rnd.randrange(100) < self.tie_pct)
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
    r, tick = Rig(rigged, tie_pct=25), 0
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
check("a sideless machine fires nothing",
      re.search(r"if \(side\.isEmpty\(\)\) \{[^}]*return null;",
                re.search(r"private static FakeSpec paperSlip.*?\n    \}", disp, re.S).group(0),
                re.S) is not None)
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

# A key meaning "the player wins" has to keep meaning that whichever machine was watched
# first, so the side order is fixed by the built-in list rather than by insertion.
names = re.search(r"public List<String> sideNames\(\) \{(.*?)\n    \}", rig, re.S).group(1)
check("sides are listed in the built-in order", "DEFAULT_SIDES" in names)

def side_names_py(assigned):
    unique = list(dict.fromkeys(assigned))
    ordered = [n for n in SIDES if n in unique]
    return ordered + [n for n in unique if n not in SIDES]

check("the first side is the player however they were watched",
      side_names_py([SIDES[1], SIDES[0]])[0] == SIDES[0])
check("a custom side comes after the built-ins",
      side_names_py(["Dealer", SIDES[1], SIDES[0]]) == SIDES + ["Dealer"])

# Levelling is off unless asked for, and a stale winner must never survive into a draw.
check("levelling is off by default", TIE_PCT == 0)
check("levelling needs asking for", "this.tieChance > 0" in level)
check("a stale winner is dropped before the draw",
      "!hasSide(wanted)" in rig and rig.index("!hasSide(wanted)") < rig.index("this.roundWinner ="))
check("a stale winner is dropped on load", "!profile.hasSide(profile.winner)" in disp)

# The guard that broke it: before any machine is laid out nothing is known, so nothing is
# stale, and a winner just set by hand has to survive its first round rather than be wiped.
start = re.search(r"public void startRound\(.*?\n    \}", rig, re.S).group(0)
check("the draw never writes over the winner", 'this.winner = ""' not in start)
check("staleness is judged only against known sides", "!names.isEmpty() && !hasSide(wanted)" in rig)
check("load judges it only against known sides", "!profile.sideNames().isEmpty()" in disp)

# and the side must be settled before the round is drawn, or the first fire of a session
# draws a round with nobody in it to give the high number to
slip = re.search(r"private static FakeSpec paperSlip.*?\n    \}", disp, re.S).group(0)
check("the side is resolved before the round is drawn",
      slip.index("sideAt(pos, watched)") < slip.index("startRound"))

fresh = Rig(SIDES[0])                        # Z pressed, no machine laid out yet
fresh.start_round(random.Random(5), 0, known=[])
check("a winner set by hand survives the first round", fresh.winner == SIDES[0])
check("sides are matched regardless of case", "roundWinner.equalsIgnoreCase(side)" in disp)

# the reported failure: a winner matching neither machine gave both the same slip
stale = Rig("Side 3", tie_pct=0)
rnd3 = random.Random(11)
same = 0
for i in range(200):
    stale.start_round(rnd3, i * (ROUND + 5))
    a = stale.high if stale.round_winner == SIDES[0] else stale.low
    b = stale.high if stale.round_winner == SIDES[1] else stale.low
    if a == b: same += 1
check("a stale winner no longer draws every round", same == 0)

check("the house does draw when asked", ties[HOUSE] > 0)

# Whether the game is on had to be recorded either way. Writing the block only when it was
# on made a rig somebody had switched off look identical to one that had never heard of the
# game, so nothing on the way back in could tell which it was.
check("the paper block is written whether on or off",
      'profile.paper || profile.name.equals("paper")' in disp)
check("on is written explicitly", 'paper.addProperty("on", profile.paper)' in disp)
check("an older block with no word means on", '!paper.has("on")' in disp)
check("the file having spoken is remembered", "paperKnown" in disp)

# The old test for turning it on also demanded the rig hold no presets, so a couple of items
# added to it by accident left the game off and the machines laying out coins, not slips.
seed = re.search(r"private static void seedDefaults\(\) \{(.*?)\n    \}", disp, re.S).group(1)
check("turning it on does not depend on presets", "paper.presets.isEmpty()" not in seed)
check("turning it on defers to the file", "!paperKnown" in seed)

repair = re.search(r"private static void repair\(RigProfile profile\) \{(.*?)\n    \}",
                   disp, re.S).group(1)
# Inside the paper branch specifically: repair also collapses duplicate presets for the
# coin flip, so looking at the whole method would match that instead.
paper_branch = re.search(r"if \(profile\.paper\) \{(.*?)\n        \}", repair, re.S).group(1)
check("a paper rig carries no presets", "profile.presets.clear()" in paper_branch)

fill = re.search(r"public static boolean fill\(BlockPos pos, boolean join\) \{(.*?)\n    \}",
                 disp, re.S).group(1)
check("paper is laid out before any preset fallback",
      fill.index("profile.paper") < fill.index("profile.presets"))
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
      "paper draws: %s, slips 1-%d; levelling off by default, %s only when asked "
      "(%d seen at 25%%), %s never draws, stale winners recover"
      % (SIDES, NUMBERS, HOUSE, ties[HOUSE],
         SIDES[0] if HOUSE == SIDES[1] else SIDES[1]))
sys.exit(1 if fails else 0)
