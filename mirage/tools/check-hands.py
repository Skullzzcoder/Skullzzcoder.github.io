"""Placing and breaking a fake. The client settles what an interaction did before it tells
the server anything, so both can happen with nothing crossing the wire -- but only if every
interaction with a fake is stopped before it becomes a packet."""
import io, re, sys
hands = io.open("src/main/java/dev/skullzz/mirage/client/FakeHands.java", encoding="utf-8").read()
self_ = io.open("src/main/java/dev/skullzz/mirage/client/SelfFakes.java", encoding="utf-8").read()
blocks = io.open("src/main/java/dev/skullzz/mirage/client/FakeBlocks.java", encoding="utf-8").read()
client = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java", encoding="utf-8").read()

fails = []
def check(name, cond):
    if not cond: fails.append(name)

use = re.search(r"private static ActionResult onUse\(.*?\n    \}", hands, re.S).group(0)
attack = re.search(r"private static ActionResult onAttack\(.*?\n    \}", hands, re.S).group(0)
tick = re.search(r"public static void tick\(MinecraftClient client\) \{(.*?)\n    \}",
                 hands, re.S).group(1)

# Both hooks fire on the server too. Acting there would be acting on somebody else's world.
# Both hooks fire on the server too, and acting there would be acting on somebody else's
# world. Being the client's own player is a stronger test of that than asking the world --
# which in this version does not answer.
for name, body in (("use", use), ("attack", attack)):
    check("the %s hook only acts on the client" % name,
          "player instanceof ClientPlayerEntity" in body)
    check("the %s hook obeys the master switch" % name, "SelfFakes.enabled()" in body)
    check("the %s hook lets real items through" % name, "ActionResult.PASS" in body)

# Holding a fake, nothing may reach the server: the slot it sees is empty, and a click on
# an empty slot is worse than no click at all.
# The real rule, and the one a looser check missed: once a fake is known to be held, no
# path out may hand the interaction back to vanilla. Whatever happens next, the slot the
# server sees is empty, and a click on an empty slot is worse than no click at all.
after = use[use.index("if (slot < 0) return ActionResult.PASS;") + 40:]
check("a fake in hand never becomes a packet", "ActionResult.PASS" not in after)
check("and it still does something", "ActionResult.SUCCESS" in after
      and "ActionResult.FAIL" in after)
check("breaking a fake is never sent", "ActionResult.SUCCESS" in attack)

# Which slot is held has to be settled by identity: a real item of the same kind in the
# same hand belongs to the player and must be left alone.
held = re.search(r"public static int heldFakeSlot\(.*?\n    \}", self_, re.S).group(0)
check("the held slot is found by identity", "held == entry.getValue()" in held)
check("and only where a fake really is", "fakes.containsKey" in held)

# placing
check("placing goes against the face hit", "hit.getBlockPos().offset(hit.getSide())" in use)
check("placing takes one off the stack", "SelfFakes.takeOne(slot)" in use)
check("placing swings the hand", "swingHand(hand)" in use)
check("placing is only where there is room", "FakeBlocks.place(target, state)" in use)

take = re.search(r"public static FakeSpec takeOne\(int slot\) \{(.*?)\n    \}", self_, re.S).group(1)
check("the last one leaves the slot", "fakes.remove(slot)" in take)
# the stack in the slot is still the one we wrote, so nothing would repaint it by itself
check("taking one forces a repaint", "applied.remove(slot)" in take)

# breaking is driven from the tick, because the attack fires once and breaking is a hold
check("breaking is advanced from the tick", "FakeHands.tick(client)" in client)
check("breaking uses vanilla's own rate", "calcBlockBreakingDelta" in tick)
check("breaking shows the cracks", "setBlockBreakingInfo" in tick)
check("letting go stops it", "attackKey.isPressed()" in tick and "stop(world)" in tick)
check("looking away stops it", "aimedAt(client)" in tick)
stop = re.search(r"private static void stop\(ClientWorld world\) \{(.*?)\n    \}",
                 hands, re.S).group(1)
check("stopping clears the cracks", "-1" in stop)

finish = re.search(r"private static void finish\(.*?\n    \}", hands, re.S).group(0)
check("breaking gives the block back", "SelfFakes.collect" in finish)
check("breaking is heard", "play(player, state, true)" in finish)

# A broken build block has to stay broken: coming back the next time the build is stood up
# is not what breaking something means.
broke = re.search(r"public static BlockState broke\(BlockPos pos\) \{(.*?)\n    \}",
                  blocks, re.S).group(1)
check("breaking a build block cuts it out", "cut(pos, 0)" in broke)
check("breaking a placed block just removes it", "unplace(pos)" in broke)

print("FAILED: " + "; ".join(fails) if fails else
      "placing and breaking stay on the client, at vanilla's rate, and give the block back")
sys.exit(1 if fails else 0)
