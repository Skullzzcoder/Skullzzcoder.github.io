"""Mirrors fill()/deplete()/advanceRoulette() from the shipped source and checks the
roulette table looks right. Constants are read out of the Java so this cannot drift."""
import io, re, random, sys

src = io.open("src/main/java/dev/skullzz/mirage/client/ClientDispensers.java", encoding="utf-8").read()
rig = io.open("src/main/java/dev/skullzz/mirage/client/RigProfile.java", encoding="utf-8").read()

SLOTS  = int(re.search(r"int STOCK_SLOTS = (\d+)", src).group(1))
MIDDLE = int(re.search(r"int MIDDLE_SLOT = (\d+)", src).group(1))
# the seeded roulette rig
CHAMBERS = int(re.search(r"roulette\.chambers = (\d+)", src).group(1))
BULLET = re.search(r'lookupItem\("(\w+)"\);\s*\n\s*if \(crystal', src).group(1)
BLANK  = re.search(r'lookupItem\("(\w+)"\);\s*\n\s*if \(obsidian', src).group(1)
assert "manualTrigger = true" in src, "seeded roulette rig is no longer manual"

def fill(bullet, blank):
    return {s: (bullet if s == MIDDLE else blank) for s in range(SLOTS)
            if (bullet if s == MIDDLE else blank) is not None}

def deplete(slots, fired):
    matching = [s for s, v in slots.items() if v == fired]
    if not matching:
        return False
    del slots[random.choice(matching)]
    return True

class Rig:
    def __init__(self): self.shot = 0; self.armed = False
    def advance(self):
        self.shot += 1
        if self.shot > CHAMBERS: self.shot = 1
        if self.armed:
            self.armed = False
            return BULLET
        return BLANK          # manualTrigger: blanks until armed

fails = []
def check(name, cond):
    if not cond: fails.append(name)

stock = fill(BULLET, BLANK)
check("ring is nine slots", len(stock) == SLOTS)
check("crystal in the middle", stock[MIDDLE] == BULLET)
check("eight blanks around it", sum(1 for v in stock.values() if v == BLANK) == SLOTS - 1)

r = Rig()
for spin in range(SLOTS - 1):
    fired = r.advance()
    check("unarmed spin %d fires a blank" % spin, fired == BLANK)
    check("blank spin %d takes one out" % spin, deplete(stock, fired))
    check("crystal survives spin %d" % spin, stock.get(MIDDLE) == BULLET)

check("only the crystal is left", list(stock.values()) == [BULLET])

r.armed = True
fired = r.advance()
check("armed spin fires the crystal", fired == BULLET)
check("the crystal comes out", deplete(stock, fired))
check("dispenser now looks empty", stock == {})
check("arming spent itself", not r.armed)

# a blank fired with nothing left must not remove some other item
stock = {0: BULLET}
check("no matching item is left alone", not deplete(stock, BLANK) and stock == {0: BULLET})

# A roulette rig must never be able to lay out nothing: unloaded, it fills no slots and
# fires nothing, which is indistinguishable from the game being broken.
fill = re.search(r"public static boolean fill\(BlockPos pos, boolean join\) \{(.*?)\n    \}",
                 src, re.S).group(1)
check("an unloaded roulette rig loads itself", 'defaultSpec("end_crystal")' in fill
      and 'defaultSpec("obsidian")' in fill)

# A dispenser played down to its last slot keeps its key with nothing under it. Skipping
# those is why a rig switched back to came up bare.
empties = re.search(r"public static int fillEmptyWatched\(\) \{(.*?)\n    \}", src, re.S).group(1)
check("an emptied dispenser counts as needing filling", "containsKey" not in empties
      and "isEmpty()" in empties)

print("bullet=%s blank=%s chambers=%d slots=%d middle=%d" % (BULLET, BLANK, CHAMBERS, SLOTS, MIDDLE))
print("FAILED: " + ", ".join(fails) if fails else "all roulette stock checks pass")
sys.exit(1 if fails else 0)
