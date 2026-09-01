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

# What a player is wearing is drawn from what they have equipped, and where that is read
# from has moved between versions. Writing the slot alone is not enough to be sure it shows.
EQUIP = re.findall(r"EquipmentSlot\.(\w+)", re.search(r"WORN_SLOTS = \{([^}]*)\}",
                                                      self_, re.S).group(1))
check("every worn slot has a model slot", len(EQUIP) == len(WORN))
check("they line up with the names", EQUIP == ["FEET", "LEGS", "CHEST", "HEAD", "OFFHAND"])

wear = re.search(r"private static void wear\(.*?\n    \}", self_, re.S).group(0)
check("wearing goes onto the model", "player.equipStack(WORN_SLOTS[worn]" in wear)
check("wearing ignores the carried slots", "worn < 0" in wear)

apply_ = re.search(r"private static void applyInventory\(.*?\n    \}", self_, re.S).group(0)
check("painting a worn slot also equips it", "wear(player, slot, copy)" in apply_)
revert = re.search(r"private static void revert\(.*?\n    \}", self_, re.S).group(0)
check("taking it off puts the real one back on", "wear(player, slot, back)" in revert)
clear = re.search(r"public static void clear\(int slot, ClientPlayerEntity player\) \{(.*?)\n    \}",
                  self_, re.S).group(1)
check("clearing a worn slot unequips it", "wear(player, slot, real)" in clear)

# a whole set in one go, in the order the slots run
armour = re.search(r"public static String\[\] armourSet\(.*?\n    \}", self_, re.S).group(0)
check("a set is boots upwards", armour.index("_boots") < armour.index("_leggings")
      < armour.index("_chestplate") < armour.index("_helmet"))
check("gold is spelt the way the items are", '"golden"' in armour)
index = re.search(r"public static int slotIndex\(.*?\n    \}", self_, re.S).group(0)
for alias in ("leggings", "chestplate", "head"):
    check("the everyday name '%s' works too" % alias, '"%s"' % alias in index)

print("FAILED: " + "; ".join(fails) if fails else
      "%d carried + %s = %d slots; pickups stay in the bag" % (CARRIED, WORN, TOTAL))
sys.exit(1 if fails else 0)
