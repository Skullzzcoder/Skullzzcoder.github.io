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

def stop_body(src):
    return re.search(r"private static void stop\(ClientWorld world\) \{(.*?)\n    \}",
                     src, re.S).group(1)

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
    check("the %s hook lets real items through" % name, "ActionResult.PASS" in body)

check("the use hook obeys the master switch", "SelfFakes.enabled()" in use)

# Breaking answers to what is on the screen rather than to the switch. The two agree --
# nothing is painted while the illusion is off -- but only one of them is the question
# being asked. A block being hit is ours exactly when the thing being hit is our paint:
# any less and vanilla mines a block the server has something else at, sending a real
# break for whatever is really underneath; any more and it steals a real block's break.
check("breaking takes over exactly where paint is showing",
      "FakeBlocks.paintedAt(pos)" in attack and "FakeBlocks.fakeAt(" not in attack)

# The rule that had to be learned the hard way, and cost three rounds of "nothing works".
# UseBlockCallback fires on EVERY right-click on EVERY block, and any answer but PASS
# cancels what vanilla would have done. Taking the click merely because a fake was in hand
# therefore took the button that fires the machines, the lever, the door and the dispenser's
# own screen with it -- the whole mod switched off by holding one of its own items.
#
# Vanilla gives the block first refusal unless the player is sneaking, so placing lives on
# that gesture and nothing else is ever intercepted. The test is not that the check exists
# but that it comes FIRST: after the held slot is known there is no way back to PASS, so a
# sneak test below that point would still swallow every click made holding a fake.
check("a right-click is only taken while sneaking", "!client.isSneaking()" in use)
sneak, held_slot = use.find("isSneaking"), use.find("heldFakeSlot")
check("and the block gets first refusal before anything else",
      0 <= sneak < held_slot)

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
# The fire key is the one thing that tells a wiring problem apart from a rigging one, so
# it has to report either way: fired-and-nothing-came-out reading the same as never-fired
# is what made each "nothing dispenses" start from nothing.
fire = re.search(r"private static void fireLookedAtOrAll\(MinecraftClient client\) \{"
                 r"(.*?)\n    \}", client, re.S).group(1)
check("firing says so when it worked", fire.count("say(client,") >= 2
      and "Fired " in fire)
check("and still says so when there was nothing to fire", "fired == 0" in fire)

# Laying a machine out succeeded in silence, which is how a coin flip showing nine of one
# block reads as the same key doing the same thing. What went in has to be said.
refill = re.search(r"private static void refillLookedAt\(MinecraftClient client\) \{"
                   r"(.*?)\n    \}", client, re.S).group(1)
check("laying out says what went in", "ClientDispensers.describeStock(pos)" in refill)
check("and names the one setting that fills all nine",
      "ClientDispensers.hasFixedAnswer(pos)" in refill and "/fake rig unset" in refill)

check("breaking is advanced from the tick", "FakeHands.tick(client)" in client)
check("breaking uses vanilla's own rate", "calcBlockBreakingDelta" in tick)
check("breaking shows the cracks", "setBlockBreakingInfo" in tick)
check("letting go stops it", "attackKey.isPressed()" in tick and "stop(world)" in tick)
check("looking away stops it", "aimedAt(client)" in tick)
check("breaking follows the paint, not the intent",
      "FakeBlocks.paintedAt(breaking)" in tick and "FakeBlocks.fakeAt(" not in tick)

# Creative mines a block the moment it is hit, and it decides that above the hook the
# attack callback answers -- so in creative no answer we give is ever heard, and the block
# vanishes five ticks after the click with no cracks and no item. Finishing the break
# ourselves first leaves vanilla nothing of ours at that position to find.
# Asked as "the creative branch finishes it" rather than as one spelling: a comment now
# sits between the test and the call, and a placed answer takes its own time ahead of both.
creative = re.search(r"isCreative\(\)\) \{(.*?)\n        \}", tick, re.S)
check("creative is finished before vanilla gets there",
      creative is not None and "finish(client, player, world, state);" in creative.group(1))

# The sweep comes round to a position about once a second. That is far too slow for one
# being hit, so the position being broken is pinned and put back every tick instead.
check("the block being broken is pinned", "FakeBlocks.pin(breaking)" in attack)
check("stopping unpins it", "FakeBlocks.pin(null)" in stop_body(hands))
check("finishing unpins it", "FakeBlocks.pin(null)" in
      re.search(r"private static void finish\(.*?\n    \}", hands, re.S).group(0))

blocks_tick = re.search(r"public static void tick\(MinecraftClient client\) \{(.*?)\n    \}",
                        blocks, re.S).group(1)
check("the pinned block is repainted outside the sweep",
      "refresh(world, player, pinned)" in blocks_tick)
check("and the sweep still runs", "order.get(cursor++)" in blocks_tick)

# Vanilla still takes its own copy of the block out from under us for the tick before the
# pin puts it back. What it leaves there is air, and remembering that as the server's word
# would put air back over a real wall the moment the build came down.
paint = re.search(r"private static void paint\(ClientWorld world, BlockPos pos, "
                  r"BlockState state\) \{(.*?)\n    \}", blocks, re.S).group(1)
check("a break cannot rewrite what was really there",
      "if (key.equals(pinned)) real.putIfAbsent(key, there);" in paint
      and "else real.put(key, there);" in paint)
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
