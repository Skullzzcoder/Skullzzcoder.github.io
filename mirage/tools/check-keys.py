"""One pair of keys rigs every game, and what they mean follows the rig.

That only holds if four things agree: which game a rig counts as, what the forward key does,
what the back key does, and what they are labelled. A label that says one thing while the key
does another is worse than no label, because it is believed."""
import io, re, sys
rig = io.open("src/main/java/dev/skullzz/mirage/client/RigProfile.java", encoding="utf-8").read()
mc  = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java", encoding="utf-8").read()
disp = io.open("src/main/java/dev/skullzz/mirage/client/ClientDispensers.java", encoding="utf-8").read()

fails = []
def check(name, cond):
    if not cond: fails.append(name)

def body(src, sig):
    m = re.search(re.escape(sig) + r"(.*?)\n    \}", src, re.S)
    return m.group(1) if m else ""

# ------------------------------------------------------------------ every game
MODES = re.search(r"public enum Keys \{(.*?)\}", rig).group(1)
MODES = [m.strip() for m in MODES.split(",") if m.strip()]
check("there is a name for each shape of rigging", sorted(MODES)
      == ["BLACKJACK", "CYCLED", "PAPER", "ROULETTE"])

keys = body(rig, "public Keys keys() {")
for mode in MODES:
    if mode == "CYCLED": continue
    check("keys() knows about %s" % mode, "Keys." + mode in keys)
check("anything else cycles", "return Keys.CYCLED;" in keys)

# Each of the three that answer "what does this key do" must have a branch per game, or a
# game silently falls through to another game's answer.
for name, sig in (("mode", "public String mode() {"),
                  ("the forward label", "public String forwardLabel() {"),
                  ("the back label", "public String backLabel() {")):
    text = body(rig, sig)
    for mode in MODES:
        if mode == "CYCLED":
            check("%s has a fallback" % name, "default:" in text)
        else:
            check("%s covers %s" % (name, mode), "case " + mode + ":" in text)

# ---------------------------------------------------------------- the dispatch
result = body(mc, "private static void rigResult(MinecraftClient client, int delta) {")
check("the keys switch on the same thing the labels do",
      "ClientDispensers.active().keys()" in result)
for mode in MODES:
    if mode == "CYCLED":
        check("the dispatch has a fallback", "default:" in result)
    else:
        check("the dispatch covers %s" % mode, "case " + mode + ":" in result)

# Both result keys go through the dispatch. Either one left wired straight to the presets
# would keep working on the coin flip and do nothing on the other four.
press = re.search(r"while \(nextResult\.wasPressed\(\)\).*?\n.*?previousResult.*?\n", mc, re.S).group(0)
check("the forward key dispatches", "rigResult(client, 1)" in press)
check("the back key dispatches", "rigResult(client, -1)" in press)
check("neither key still calls the presets straight", "selectPreset" not in press)

# --------------------------------------------------- the label matches the act
# Tower: forward is the first colour, and the first colour is what the forward key calls.
fwd, back = body(rig, "public String forwardLabel() {"), body(rig, "public String backLabel() {")
check("forward is labelled the next card up", 'case BLACKJACK: return "next card up";' in fwd)
check("back is labelled the next card down", 'case BLACKJACK: return "next card down";' in back)
check("forward steps the card up", "case BLACKJACK:\n                stepCard(client, delta);" in result)
# The direction has to reach the ring: passing a boolean would have lost it.
card = body(mc, "private static void stepCard(MinecraftClient client, int delta) {")
check("the direction is carried through", "ClientDispensers.cycleCard(delta)" in card)

# Roulette: forward arms, back takes the arming back off.
check("forward is labelled as arming", 'case ROULETTE: return "arm the loaded shot";' in fwd)
check("back is labelled as cancelling", 'case ROULETTE: return "cancel the arm";' in back)
check("forward arms", "case ROULETTE:\n                setArmed(client, delta > 0);" in result)
armed = body(mc, "private static void setArmed(MinecraftClient client, boolean on) {")
check("arming arms and not-arming disarms",
      "ClientDispensers.armNext();" in armed and "ClientDispensers.disarm();" in armed)

# Paper steps both ways rather than only forward.
check("paper steps by the direction given",
      "case PAPER:\n                stepWinner(client, delta);" in result)
check("the winner step takes a direction", "cycleWinner(delta)" in mc)
check("and carries it through", "active().cycleWinner(delta)" in disp)

# --------------------------------------------------------- stepping both ways
# Run the real ring: the sides, with chance one past the end.
step = body(rig, "public String cycleWinner(int delta) {")
check("chance sits past the last side", "int chance = names.size();" in step)
check("the ring wraps both ways", "Math.floorMod(index + delta, chance + 1)" in step)

def walk(sides, winner, delta):
    chance = len(sides)
    index = chance if not winner else (sides.index(winner) if winner in sides else chance)
    index = (index + delta) % (chance + 1)
    return "" if index >= chance else sides[index]

SIDES = ["Player", "Host"]
seen, at = [], ""
for _ in range(3):
    at = walk(SIDES, at, 1)
    seen.append(at)
check("forward walks Player, Host, chance", seen == ["Player", "Host", ""])

seen, at = [], ""
for _ in range(3):
    at = walk(SIDES, at, -1)
    seen.append(at)
check("back walks the other way", seen == ["Host", "Player", ""])

# Overshooting by one press has to be one press back, which is the whole point of a back key.
at = ""
for _ in range(5): at = walk(SIDES, at, 1)
there = at
check("one back undoes one forward", walk(SIDES, walk(SIDES, there, 1), -1) == there)

# A rig with no sides yet must not throw or invent one.
check("no sides means no winner", walk([], "", 1) == "")
check("an unknown winner counts from chance", walk(SIDES, "Nobody", 1) == "Player")

# ------------------------------------------------------------- it has to say so
# Switching rig with the key changes what F and R mean. Doing that silently is the whole
# problem this was meant to fix.
cycle = re.search(r"while \(cycleRig\.wasPressed\(\)\) \{(.*?)\n            \}", mc, re.S).group(1)
check("switching rig says what the keys now do", "announceRig(client)" in cycle)
say = body(mc, "private static void announceRig(MinecraftClient client) {")
check("and names the game and both keys", "profile.mode()" in say
      and "profile.forwardLabel()" in say and "profile.backLabel()" in say)
check("the nag is not written over", "if (!nagIfUnset(client)) announceRig(client);" in cycle)

# The same answer has to be reachable without pressing anything.
check("the status line carries it",
      'lines.add("  F " + profile.forwardLabel() + ", R " + profile.backLabel());' in disp)
check("there is a key list", 'literal("keys")' in mc and "listKeys" in mc)

print("FAILED: " + "; ".join(fails) if fails else
      "F and R follow the rig across %s; labels, dispatch and status all read from keys()"
      % ", ".join(m.lower() for m in MODES))
sys.exit(1 if fails else 0)
