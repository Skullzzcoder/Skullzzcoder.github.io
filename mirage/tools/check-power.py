"""The master switch has to reach every layer that paints or spawns something, and it has
to put things back rather than forget them. Read off the shipped source."""
import io, re, sys
src = lambda f: io.open("src/main/java/dev/skullzz/mirage/client/%s.java" % f, encoding="utf-8").read()
self_, disp, decor, clicks, client = (src(f) for f in
        ("SelfFakes", "ClientDispensers", "ClientDecor", "FakeClicks", "MirageClient"))
fails = []
def check(name, cond):
    if not cond: fails.append(name)

# every layer that shows something has to ask
check("inventory and containers stop painting", "if (!enabled) {\n            revert(player);" in self_)
check("containers hand their slots back", "!enabled) target = Map.of()" in self_)
check("dispensers stop firing", "if (!SelfFakes.enabled()) {\n            recall();" in disp)
check("decor comes out of the world", "if (!SelfFakes.enabled()) {\n            hide();" in decor)
check("clicks go back to vanilla", "if (!SelfFakes.enabled()) return false;" in clicks)

# off must put things back, not forget them
revert = re.search(r"private static void revert\(ClientPlayerEntity player\) \{(.*?)\n    \}",
                   self_, re.S).group(1)
check("the real stack is written back", "inventory.setStack(slot," in revert)
check("only our own stack is touched", "!= entry.getValue()) continue" in revert)
check("the fakes themselves are kept", "fakes.clear()" not in revert)
check("decor is hidden, not deleted", "pieces.clear()" not in
      re.search(r"public static void hide\(\) \{(.*?)\n    \}", decor, re.S).group(1))

# and anything already in the air has to come back immediately
recall = re.search(r"private static void recall\(\) \{(.*?)\n    \}", disp, re.S).group(1)
for gone in ("spawned.clear()", "arrows.clear()", "pending.clear()", "lastTriggered.clear()"):
    check("recall drops %s" % gone, gone in recall)

# the switch itself must survive being off, and the others must not stack up
tick = re.search(r"ClientTickEvents\.END_CLIENT_TICK\.register\(client -> \{(.*?)\n        \}\);",
                 client, re.S).group(1)
check("the switch is read before the rest", tick.index("power.wasPressed()") < tick.index("pollSelection"))
check("the rest is skipped while off", "if (!SelfFakes.enabled()) {" in tick and "drainKeys()" in tick)
check("held presses do not stack up", "while (key.wasPressed()) pressed = true;" in client)
check("it says which way it went", "Everything you can see is real" in client)
check("the state is remembered", '"enabled", enabled' in self_ and 'root.has("enabled")' in self_)

print("FAILED: " + "; ".join(fails) if fails else
      "the switch reaches inventory, containers, dispensers, arrows, decor and clicks; "
      "off restores rather than forgets")
sys.exit(1 if fails else 0)
