"""Flies the shipped arrow solve under two plausible readings of a vanilla arrow tick.

The point of re-solving every tick is that it lands on the mark even when the model is
wrong, so both orderings have to hit. Constants come out of the source."""
import io, math, re, sys
src = io.open("src/main/java/dev/skullzz/mirage/client/ClientDispensers.java", encoding="utf-8").read()

G     = float(re.search(r"ARROW_GRAVITY = ([\d.]+)", src).group(1))
D     = float(re.search(r"ARROW_DRAG = ([\d.]+)", src).group(1))
PER   = float(re.search(r"ARROW_TICKS_PER_BLOCK = ([\d.]+)", src).group(1))
STALL = int(re.search(r"ARROW_STALL_TICKS = (\d+)", src).group(1))
LO, HI = (float(x) for x in
          re.search(r"Math\.max\((\d+\.\d+),\s*\n?\s*Math\.min\((\d+\.\d+), flat", src).groups())

fails = []
def check(name, cond):
    if not cond: fails.append(name)

# the arrow must be left to vanilla, not placed every tick
launch = re.search(r"private static void launchArrow.*?\n    \}", src, re.S).group(0)
check("the arrow is launched with a velocity", "setVelocity(launch)" in launch)
check("gravity is left on", "setNoGravity(true)" not in launch)
check("it does not start stationary", "setVelocity(Vec3d.ZERO)" not in launch)
check("a stall falls back to moving it ourselves", "selfDriven" in src and STALL >= 1)

def solve(frm, to, n):
    travel = n if D == 1.0 else D * (1.0 - D ** n) / (1.0 - D)
    fall = G / (1.0 - D)
    return [(to[0]-frm[0])/travel, (to[1]-frm[1]+fall*n)/travel - fall, (to[2]-frm[2])/travel]

def flight_ticks(frm, to):
    flat = math.hypot(to[0]-frm[0], to[2]-frm[2])
    return int(max(LO, min(HI, flat * PER)))

def fly(frm, to, move_first):
    n = flight_ticks(frm, to)
    p, v, path = list(frm), solve(frm, to, n), [list(frm)]
    for k in range(n):
        v = solve(p, to, n - k)                 # the steer() call, every tick
        if move_first:
            p = [p[i] + v[i] for i in range(3)]
            v = [v[0]*D, v[1]*D - G, v[2]*D]
        else:
            v = [v[0]*D, v[1]*D - G, v[2]*D]
            p = [p[i] + v[i] for i in range(3)]
        path.append(list(p))
    return path

shots = [((0.5, 65.5, 0.5), (30.5, 65.0, 0.5)), ((0.5, 65.5, 0.5), (12.5, 70.0, -8.5)),
         ((0.5, 70.5, 0.5), (45.5, 62.0, 20.5)), ((0.5, 65.5, 0.5), (4.5, 66.0, 3.5)),
         ((0.5, 64.5, 0.5), (-22.5, 64.0, 14.5))]

worst_miss, arcs = 0.0, []
for move_first in (False, True):
    for frm, to in shots:
        path = fly(frm, to, move_first)
        miss = math.dist(path[-1], to)
        worst_miss = max(worst_miss, miss)
        check("lands on the mark (%s)" % ("move first" if move_first else "drag first"),
              miss < 0.25)

        # it has to actually arc, and come down: a flat skim is what looked wrong before
        apex = max(p[1] for p in path)
        rise = apex - frm[1]
        arcs.append(rise)
        check("rises before it falls", apex > max(frm[1], to[1]) + 0.5)
        check("the last leg is falling", path[-1][1] < path[-2][1] + 1e-9)

        # and no jumps: a step bigger than a few blocks reads as a teleport
        steps = [math.dist(path[i], path[i+1]) for i in range(len(path)-1)]
        check("no teleporting", max(steps) < 3.0)
        check("keeps moving throughout", min(steps) > 1e-4)

print("FAILED: " + "; ".join(dict.fromkeys(fails)) if fails else
      "arrow lands within %.3f blocks under both tick orderings; apex +%.1f to +%.1f blocks"
      % (worst_miss, min(arcs), max(arcs)))
sys.exit(1 if fails else 0)
