"""Carrying a fake on the pointer. Mirrors carry() from the shipped source and checks it
moves things the way any screen does, and never writes over something real."""
import io, re, sys
clicks = io.open("src/main/java/dev/skullzz/mirage/client/FakeClicks.java", encoding="utf-8").read()
disp = io.open("src/main/java/dev/skullzz/mirage/client/ClientDispensers.java", encoding="utf-8").read()
client = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java", encoding="utf-8").read()

carry = re.search(r"private static boolean carry\(.*?\n    \}", clicks, re.S).group(0)
fails = []
def check(name, cond):
    if not cond: fails.append(name)

# --- the model, mirroring the shipped method ----------------------------
class Spec:
    def __init__(self, kind, count): self.kind, self.count = kind, count
    def with_count(self, n): return Spec(self.kind, n)
    def stacks_with(self, o): return o is not None and o.kind == self.kind
    def __repr__(self): return "%dx%s" % (self.count, self.kind)

MAX = int(re.search(r"64 - inSlot\.count", carry) and 64)

def do(cursor, in_slot, right, real_under=False):
    """carry(), as shipped. Returns (cursor, in_slot, handled)."""
    ours = in_slot is not None
    if cursor is None:
        if in_slot is None: return cursor, in_slot, False
        taken = (in_slot.count + 1) // 2 if right else in_slot.count
        new_cursor = in_slot.with_count(taken)
        rest = None if taken >= in_slot.count else in_slot.with_count(in_slot.count - taken)
        return new_cursor, rest, True

    if not ours and real_under: return cursor, in_slot, True

    laying = 1 if right else cursor.count
    if in_slot is None:
        in_slot = cursor.with_count(laying)
    elif in_slot.stacks_with(cursor):
        room = min(laying, MAX - in_slot.count)
        if room <= 0: return cursor, in_slot, True
        laying = room
        in_slot = in_slot.with_count(in_slot.count + room)
    else:
        if right or cursor.count != laying: return cursor, in_slot, True
        return in_slot, cursor, True

    cursor = cursor.with_count(cursor.count - laying) if cursor.count > laying else None
    return cursor, in_slot, True

# picking up
c, s, _ = do(None, Spec("gold", 9), right=False)
check("left takes the lot", c.count == 9 and s is None)
c, s, _ = do(None, Spec("gold", 9), right=True)
check("right takes half, rounded up", c.count == 5 and s.count == 4)
c, s, _ = do(None, Spec("gold", 1), right=True)
check("right on a single takes it", c.count == 1 and s is None)

# laying down
c, s, _ = do(Spec("gold", 5), None, right=False)
check("left lays the lot down", c is None and s.count == 5)
c, s, _ = do(Spec("gold", 5), None, right=True)
check("right lays one down", c.count == 4 and s.count == 1)

# merging
c, s, _ = do(Spec("gold", 5), Spec("gold", 3), right=False)
check("like onto like merges", c is None and s.count == 8)
c, s, _ = do(Spec("gold", 10), Spec("gold", 60), right=False)
check("merging stops at a full stack", c.count == 6 and s.count == 64)
c, s, handled = do(Spec("gold", 5), Spec("gold", 64), right=False)
check("nothing goes onto a full stack", c.count == 5 and s.count == 64 and handled)

# swapping
c, s, _ = do(Spec("gold", 5), Spec("iron", 3), right=False)
check("unlike swaps", c.kind == "iron" and c.count == 3 and s.kind == "gold" and s.count == 5)
c, s, _ = do(Spec("gold", 5), Spec("iron", 3), right=True)
check("right does not swap", c.kind == "gold" and s.kind == "iron")

# nothing of ours, nothing carried
check("an empty slot with nothing in hand is not ours", do(None, None, False)[2] is False)

# --- and the source has to agree ----------------------------------------
check("a real item underneath is never written over",
      "!ours && !slot.getStack().isEmpty()" in carry)
check("right lays down exactly one", "right ? 1 : cursor.count" in carry)
check("right takes half, rounded up", "(inSlot.count + 1) / 2" in carry)
check("a full stack is respected", "64 - inSlot.count" in carry)

# Nothing may be left held once the screen it was picked up in has gone, and there is no
# ground to throw a fake onto, so off-the-board goes back to the bag.
check("closing the screen puts it back", "FakeClicks.closed(client.player)" in client)
closed = re.search(r"public static void closed\(.*?\n    \}", clicks, re.S).group(0)
check("putting it back is a pickup", "SelfFakes.collect" in closed)
check("off the board it goes back too", "putBack(player)" in clicks)

# and the pointer has to be drawn, or a carried fake is invisible
check("the carried fake is drawn", "drawItem(cursor.stack()" in clicks)
check("its count is drawn", "drawStackOverlay" in clicks)
check("the render hook is not named by its arguments",
      "argument instanceof DrawContext" in clicks)

check("a dispenser slot can be written", "setStock" in disp and "stockAt" in disp)

print("FAILED: " + "; ".join(fails) if fails else
      "carry: left all, right half or one, like merges to 64, unlike swaps, real never touched")
sys.exit(1 if fails else 0)
