"""Worn slots put a fake on the player model rather than only in the screen. They sit past
the carried part of the inventory, which is where a pickup must never reach."""
import io, re, sys
self_ = io.open("src/main/java/dev/skullzz/mirage/client/SelfFakes.java", encoding="utf-8").read()

TOTAL   = int(re.search(r"int SLOT_COUNT = (\d+)", self_).group(1))
CARRIED = int(re.search(r"int CARRIED_SLOTS = (\d+)", self_).group(1))
WORN    = re.findall(r'"(\w+)"', re.search(r"WORN = \{([^}]*)\}", self_).group(1))

fails = []
def check(name, cond):
    if not cond: fails.append(name)

check("the carried part is the usual 36", CARRIED == 36)
check("four pieces of armour and an offhand", len(WORN) == 5)
check("the slots add up", TOTAL == CARRIED + len(WORN))
# The inventory keeps armour feet upwards, so the names have to be in that order or a
# helmet would be painted onto the boots.
check("worn slots are in the inventory's own order",
      WORN == ["boots", "legs", "chest", "helmet", "offhand"])

# Reaching past what this version's inventory actually holds would throw, and how many
# slots a player carries has moved between versions.
apply_ = re.search(r"private static void applyInventory\(.*?\n    \}", self_, re.S).group(0)
check("painting is bounded by the inventory itself", "inventory.size()" in apply_)
revert = re.search(r"private static void revert\(.*?\n    \}", self_, re.S).group(0)
check("putting it back is bounded too", "inventory.size()" in revert)

# Something walked over belongs in the bag; landing it on your head would be absurd.
collect = re.search(r"public static boolean collect\(.*?\n    \}", self_, re.S).group(0)
check("a pickup stops at the carried part", "slot < CARRIED_SLOTS" in collect)
check("a pickup never reaches the worn slots", "slot < SLOT_COUNT" not in collect)

# every worn name has to survive the round trip through the slot lookup
names = re.search(r"public static List<String> slotNames\(\) \{(.*?)\n    \}",
                  self_, re.S).group(1)
check("worn slots are offered by name", "WORN" in names)
index = re.search(r"public static int slotIndex\(.*?\n    \}", self_, re.S).group(0)
for alias in ("leggings", "chestplate", "head"):
    check("the everyday name '%s' works too" % alias, '"%s"' % alias in index)

print("FAILED: " + "; ".join(fails) if fails else
      "%d carried + %s = %d slots; pickups stay in the bag" % (CARRIED, WORN, TOTAL))
sys.exit(1 if fails else 0)
