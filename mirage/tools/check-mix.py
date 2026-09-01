"""45/45/10: one machine, a fixed spread of items, and the rigging is simply which of them
comes out. The game is the odds you can see through the glass, so the layout is the part
that has to be right -- nine slots holding exactly what was asked for, arranged so the
odds can be read without counting, and the answer only ever an item that is in there."""
import io, re, sys
rig  = io.open("src/main/java/dev/skullzz/mirage/client/RigProfile.java", encoding="utf-8").read()
disp = io.open("src/main/java/dev/skullzz/mirage/client/ClientDispensers.java", encoding="utf-8").read()
mc   = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java", encoding="utf-8").read()

fails = []
def check(name, cond):
    if not cond: fails.append(name)

STOCK_SLOTS = int(re.search(r"STOCK_SLOTS = (\d+)", disp).group(1))
MIDDLE_SLOT = int(re.search(r"MIDDLE_SLOT = (\d+)", disp).group(1))

# ------------------------------------------------------------------ the spread
# Read out of the seeding rather than restated here, so the two cannot drift apart.
seed = re.search(r'if \(needsSeeding\("454510"\)\) \{(.*?)\n        \}', disp, re.S).group(1)
items   = re.findall(r'"(\w+)"', re.search(r"String\[\] items = \{(.*?)\};", seed).group(1))
counts  = [int(n) for n in re.search(r"int\[\] counts = \{(.*?)\};", seed).group(1).split(",")]
payouts = [int(n) for n in re.search(r"int\[\] payouts = \{(.*?)\};", seed).group(1).split(",")]

check("three kinds go in", len(items) == len(counts) == len(payouts) == 3)
check("four diamonds", items[0] == "diamond" and counts[0] == 4)
check("four emeralds", items[1] == "emerald" and counts[1] == 4)
check("one crystal", items[2] == "end_crystal" and counts[2] == 1)
check("the machine is exactly full", sum(counts) == STOCK_SLOTS)
check("a guessed kind pays double", payouts[0] == 2 and payouts[1] == 2)
check("the crystal pays four times", payouts[2] == 4)
check("the rig is in mix mode", "odds.mix = true;" in seed)
check("something is selected to start with", "odds.setPresetIndex(" in seed)

# The name says 45/45/10, so the machine has to be within rounding of it. Nine slots
# cannot be split 45/45/10 exactly -- four of nine is 44.4% -- but a spread that is not
# close to what the game is called is a machine whose name is a lie to the player.
total = sum(counts)
chances = [c * 100.0 / total for c in counts]
named = [45.0, 45.0, 10.0]
check("the odds match the name, within rounding",
      all(abs(a - b) <= 2.0 for a, b in zip(chances, named)))

# ------------------------------------------------------------------- the layout
# Run the real rule, read out of the source, against the real spread.
rarest = re.search(r"public int rarestPreset\(\) \{(.*?)\n    \}", rig, re.S).group(1)
check("ties leave the middle alone", "ties == 1 ? rarest : -1" in rarest)

def rarest_preset(counts):
    fewest, best, ties = None, -1, 0
    for i, held in enumerate(counts):
        if fewest is None or held < fewest: fewest, best, ties = held, i, 1
        elif held == fewest: ties += 1
    return best if ties == 1 else -1

def lay_out(counts):
    """The fill rule from ClientDispensers, followed by hand."""
    rare = rarest_preset(counts)
    free = [s for s in range(STOCK_SLOTS) if rare < 0 or s != MIDDLE_SLOT]
    slots, nxt = {}, 0
    for i, held in enumerate(counts):
        for n in range(held):
            if i == rare and n == 0: slot = MIDDLE_SLOT
            elif nxt < len(free): slot, nxt = free[nxt], nxt + 1
            else: break
            slots[slot] = i
    return slots

laid = lay_out(counts)
check("every slot is filled", sorted(laid) == list(range(STOCK_SLOTS)))
for i, item in enumerate(items):
    got = sum(1 for held in laid.values() if held == i)
    check("%d of %s go in, not %d" % (counts[i], item, got), got == counts[i])
check("the crystal sits in the middle", laid[MIDDLE_SLOT] == 2)

# An even spread has no centre to give, and must not lose a slot pretending it does.
even = lay_out([3, 3, 3])
check("an even spread still fills the machine", sorted(even) == list(range(STOCK_SLOTS)))
# More than fits must not silently drop the machine's shape either.
over = lay_out([9, 9, 9])
check("an overfull spread stops at the last slot", len(over) == STOCK_SLOTS)

# The fill branch has to be the one being described, and reached before the plain one.
fill = re.search(r"public static boolean fill\(BlockPos pos, boolean join\) \{(.*?)\n    \}",
                 disp, re.S).group(1)
check("mix has its own layout", "profile.mix" in fill and "rarestPreset()" in fill)
check("the middle is kept for the rarest",
      "if (rare < 0 || slot != MIDDLE_SLOT) free.add(slot);" in fill)
check("each slot gets its own copy", "spec.withCount(1)" in fill)

# ------------------------------------------------------------------- the answer
# No branch of its own in the tick: what comes out is whatever is selected, which is what
# makes the result key the rig. If mix ever grows one, this stops being true.
tick = re.search(r"public static void tick\(MinecraftClient client\) \{(.*?)\n    \}",
                 disp, re.S).group(1)
check("the result key is the rig", "profile.mix" not in tick
      and "result = profile.resultFor(fire.pos());" in tick)

# Cycling has to say what it landed on, and say something when it cannot cycle at all.
select = re.search(r"private static void selectPreset\(MinecraftClient client, int delta\) \{"
                   r"(.*?)\n    \}", mc, re.S).group(1)
check("cycling never fails silently", "if (spec == null) {" in select and "say(client," in select)
check("cycling reads back the payout", "profile.mixPayout(profile.presetIndex())" in select)

# The readback is off by default, so there has to be a way to see which of the three is
# rigged that shows nothing on screen. Otherwise the only honest answer is counting presses.
status = re.search(r"public static List<String> status\(ClientWorld world\) \{(.*?)\n    \}",
                   disp, re.S).group(1)
check("the status line marks the rigged item",
      'i == profile.presetIndex() ? " <- RIGGED" : ""' in status)
check("and shows the real odds", "profile.mixChance(i)" in status)

# The setting the readback hangs off had no command for it at all, which made it dead.
check("the readback can be turned on", 'literal("announce")' in mc
      and "SelfFakes.setAnnounceSwitching(true)" in mc
      and "SelfFakes.setAnnounceSwitching(false)" in mc)

# ---------------------------------------------------------------- it has to keep
save = re.search(r"public static void save\(JsonObject root\) \{(.*?)\n    \}", disp, re.S).group(1)
check("the spread is written", 'mix.add("counts", counts);' in save
      and 'mix.add("payouts", payouts);' in save)
read = re.search(r"private static void readProfile\(JsonObject json\) \{(.*?)\n    \}",
                 disp, re.S).group(1)
check("the spread is read back", 'json.has("mix")' in read and "profile.mix = true;" in read)
check("counts survive the trip", "profile.mixCounts.add(" in read)
check("payouts survive the trip", "profile.mixPayouts.add(" in read)

print("FAILED: " + "; ".join(fails) if fails else
      "45/45/10: %s laid %s into %d slots, crystal in the middle, paying %s"
      % (items, counts, STOCK_SLOTS, payouts))
sys.exit(1 if fails else 0)
