"""Checks FakeClicks' slot mapping against how a container really lays the player out:
the three main rows (PlayerInventory 9..35) first, then the hotbar (0..8)."""
import io, re, sys
src = io.open("src/main/java/dev/skullzz/mirage/client/FakeClicks.java", encoding="utf-8").read()

PLAYER = int(re.search(r"int PLAYER_SLOTS = (\d+)", src).group(1))
MAIN   = int(re.search(r"int MAIN_SLOTS = (\d+)", src).group(1))
body   = re.search(r"private static int inventorySlot\(int id, int size\) \{(.*?)\n    \}", src, re.S).group(1)
assert "offset < MAIN_SLOTS ? 9 + offset : offset - MAIN_SLOTS" in body, "mapping changed:" + body

def inventory_slot(slot_id, size):
    offset = slot_id - size
    if offset < 0 or offset >= PLAYER: return -1
    return 9 + offset if offset < MAIN else offset - MAIN

# how vanilla actually builds it
expected = {}
size = 9
sid = size
for inv in range(9, 36):   # main rows
    expected[sid] = inv; sid += 1
for inv in range(0, 9):    # hotbar
    expected[sid] = inv; sid += 1

fails = [f"id {sid} -> {inventory_slot(sid, size)}, should be {inv}"
         for sid, inv in expected.items() if inventory_slot(sid, size) != inv]

got = [inventory_slot(i, size) for i in range(size, size + PLAYER)]
if sorted(got) != list(range(36)): fails.append("not a bijection onto 0..35")
if inventory_slot(0, size) != -1: fails.append("container slot 0 leaked into the inventory")
if inventory_slot(size + PLAYER, size) != -1: fails.append("past the end is not rejected")

print("FAILED: " + "; ".join(fails) if fails else
      "slot mapping matches vanilla (ids %d-%d -> inv 9..35 then 0..8)" % (size, size + PLAYER - 1))
sys.exit(1 if fails else 0)
