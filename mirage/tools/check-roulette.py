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

# ------------------------------------------------- a rig that lost its own game
# A rig named for a game, carrying no mode and no presets, fires nothing at all: the mode
# branch is skipped and the cycled branch it falls to has nothing to cycle. Nobody sets
# that up on purpose, so it is repaired -- the same treatment the paper rig has.
mc = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java", encoding="utf-8").read()
repair = re.search(r"private static void repair\(RigProfile profile\) \{(.*?)\n    \}",
                   src, re.S).group(1)
# Scoped to the repair's own condition, not to the whole method: repair also sets the
# preset index from profile.presets.isEmpty(), so looking anywhere in it for that phrase
# passed with the guard deleted.
mode_back = re.search(r'if \(profile\.name\.equals\("roulette"\)(.*?)\) \{', repair, re.S)
check("a roulette rig with no mode gets it back", mode_back is not None)
guard = mode_back.group(1) if mode_back else ""
# But only when it has no other job. A rig given presets has been made something else --
# the tower turned into a coin flip -- and must be left alone.
check("a rig with presets is left alone", "profile.presets.isEmpty()" in guard)
check("and one already in another mode is too",
      "!profile.paper" in guard and "!profile.blackjack" in guard and "!profile.mix" in guard)

# Whether the rig can answer at all has to be asked of the rig. The doctor used to test
# presets, which roulette does not use, so a roulette rig with nothing loaded read as fine.
no_answer = re.search(r"public static String noAnswer\(\) \{(.*?)\n    \}",
                      src, re.S).group(1)
check("a roulette rig with nothing loaded is caught",
      "profile.bullet == null && profile.blank == null" in no_answer)
# The blank-only branch specifically. "profile.blank == null" also appears in the
# both-missing test above it, so looking for the phrase alone passed with this gone.
check("and one with no blank, since every unarmed shot is a blank",
      re.search(r"if \(profile\.blank == null\) \{\s*\n\s*return", no_answer) is not None
      and "fires nothing" in no_answer)
check("the games with parts answer per machine instead",
      "if (profile.hasSides()) return null;" in no_answer)
check("the doctor asks it", "ClientDispensers.noAnswer()" in mc)
check("and no longer guesses from presets",
      "ClientDispensers.presets().isEmpty() && !profile.roulette" not in mc)

print("bullet=%s blank=%s chambers=%d slots=%d middle=%d" % (BULLET, BLANK, CHAMBERS, SLOTS, MIDDLE))
print("FAILED: " + ", ".join(fails) if fails else "all roulette stock checks pass")
sys.exit(1 if fails else 0)
