"""Guards the bug that has now bitten twice: a config written before a game existed holds
that rig's name and nothing else, so the rig has to count as empty and be laid down again."""
import io, re, sys
rig  = io.open("src/main/java/dev/skullzz/mirage/client/RigProfile.java", encoding="utf-8").read()
disp = io.open("src/main/java/dev/skullzz/mirage/client/ClientDispensers.java", encoding="utf-8").read()
fails = []
def check(name, cond):
    if not cond: fails.append(name)

seed = re.search(r"private static void seedDefaults\(\) \{(.*?)\n    \}", disp, re.S).group(1)
empty = re.search(r"public boolean isEmpty\(\) \{(.*?)\n    \}", rig, re.S).group(1)
needs = re.search(r"private static boolean needsSeeding\(String name\) \{(.*?)\n    \}", disp, re.S).group(1)

# every built-in rig must go through needsSeeding, not a bare containsKey
built_in = re.findall(r'needsSeeding\("(\w+)"\)', seed)
check("all three built-ins are seeded", sorted(built_in) == ["5050", "paper", "roulette"])
check("no bare containsKey left in seeding", 'containsKey("' not in seed)
check("needsSeeding treats an empty rig as missing", "isEmpty()" in needs)

# isEmpty has to look at every field that makes a rig meaningful, or a rig carrying only
# that field is wrongly wiped, or one carrying nothing is wrongly kept
STATE = ["presets", "perDispenser", "arrowTarget", "roulette", "paper", "stock"]
for field in STATE:
    check("isEmpty considers %s" % field, field in empty)

# any future mode flag must be added to isEmpty too
modes = re.findall(r"^    public boolean (\w+);", rig, re.M)
for mode in modes:
    check("mode '%s' is missing from isEmpty" % mode, mode in empty or mode in ("armed", "manualTrigger"))

# the paper rig specifically has to be upgraded in place, since a user may have set one up
check("an existing paper rig gets the mode turned on", "paper.paper = true;" in seed)

print("FAILED: " + "; ".join(fails) if fails else
      "built-ins %s reseed when empty; isEmpty covers %s" % (built_in, modes))
sys.exit(1 if fails else 0)
