"""The rig half's own switch.

Two halves of this mod have nothing to do with each other: builds, schematics and map art
are pictures on your own screen, and the rigs decide what comes out of a machine. Turning
the rigs off must leave the first half completely alone -- and must not quietly leave
anything of the second half running, which is the failure that would be hardest to notice."""
import io, re, sys
SRC = "src/main/java/dev/skullzz/mirage/client/"
client = io.open(SRC + "MirageClient.java", encoding="utf-8").read()
fakes  = io.open(SRC + "SelfFakes.java", encoding="utf-8").read()
disp   = io.open(SRC + "ClientDispensers.java", encoding="utf-8").read()
dash   = io.open(SRC + "WebDashboard.java", encoding="utf-8").read()

fails = []
def check(name, cond):
    if not cond: fails.append(name)

def body(source, signature):
    found = re.search(re.escape(signature) + r"(.*?)\n    \}", source, re.S)
    return found.group(1) if found else ""

# ------------------------------------------------------------------ the switch
check("there is a rig switch", "public static boolean rigsOn()" in fakes)
check("it can be set", "public static void setRigsOn(boolean on)" in fakes)
check("setting it is remembered", "save();" in body(fakes, "public static void setRigsOn(boolean on) {"))
check("it is written to the config", 'addProperty("rigsOn"' in fakes)
check("it is read back", 'root.has("rigsOn")' in fakes)
# An older config says nothing about it, and those setups had the rigs running.
check("an older config keeps its rigs", '!root.has("rigsOn") ||' in fakes)
check("it starts on", "boolean rigsOn = true;" in fakes)

# ----------------------------------------------------- off means actually off
tick = client[client.index("ClientTickEvents.END_CLIENT_TICK.register"):]
tick = tick[:tick.index("ClientPlayConnectionEvents.DISCONNECT")]
check("the rig tick is skipped", "if (SelfFakes.rigsOn()) ClientDispensers.tick(client);" in tick)
check("the rig block is guarded", "if (SelfFakes.rigsOn()) {" in tick)

# The guard over watched machines has to be let go, or a build keeps a hole punched in it
# for a machine nothing is listening to any more.
check("the machine guard is released", "ClientDispensers.standDown();" in tick)
stand = body(disp, "public static void standDown() {")
check("standing down clears the guard", "FakeBlocks.keepClear(" in stand)
check("standing down forgets nothing", "watched.clear()" not in stand
      and "profiles.clear()" not in stand)

# A key read with while(wasPressed()) walks a queue. Skipping the read leaves the presses
# in it, and they all happen at once when the rigs come back.
check("rig keys are drained, not ignored", "drainRigKeys();" in tick)
drain = body(client, "private static void drainRigKeys() {")
check("the drain empties each key", "while (key.wasPressed())" in drain)
for key in ("armNext", "fireNow", "cycleRig", "nextResult", "previousResult",
            "callFirst", "callSecond", "winFirst", "winSecond", "cycleWinner", "refill"):
    check("%s is drained" % key, key in drain)

# ------------------------------------------------------- the menus stay reachable
# The lockout this guards: the client menu is where the rigs get switched back on, so if
# its key were inside the rig gate, turning them off would take away the way to undo it.
for menu in ("openClient", "openRigs"):
    check("%s is not gated on the rigs" % menu,
          menu + ".wasPressed()" in tick
          and menu + ".wasPressed()" not in tick[tick.index("if (SelfFakes.rigsOn()) {")
                                                 :tick.index("drainRigKeys();")])

# The master switch drains every key it disables, or a press while everything is off opens
# the screen the moment it comes back.
drainall = body(client, "private static boolean drainKeys() {")
for menu in ("openClient", "openRigs", "openMenu"):
    check("%s is drained by the master switch" % menu, menu in drainall)

# ------------------------------------------------- the other half is untouched
# These run outside the guarded block, or turning the rigs off would take the builds with
# them -- which is the whole thing being asked for.
after = tick[tick.index("drainRigKeys();"):]
for kept in ("cutBlock", "openMenu", "clearFakes", "FakeClicks.closed"):
    check("%s still runs with rigs off" % kept, kept in after)
guarded = tick[tick.index("if (SelfFakes.rigsOn()) {"):tick.index("drainRigKeys();")]
for gone in ("cutBlock", "openMenu"):
    check("%s is not inside the rig block" % gone, gone not in guarded)
# The three painting layers are never gated on it.
for layer in ("FakeBlocks.tick(client)", "ClientDecor.tick(client.world)"):
    check("%s is not gated on the rigs" % layer, layer in tick
          and layer not in guarded)

# --------------------------------------------------------- and it is findable
check("there is a rigs command", 'literal("rigs")' in client)
check("the command says what stays on", "Builds, schematics and map art" in client)
check("asking without an argument reports the state", 'SelfFakes.rigsOn() ? "on" : "off"' in client)
# Silent failure is the enemy: with the rigs off, every line the doctor prints about a rig
# describes something that is not running.
doctor = body(client, "private static int doctor(CommandContext<FabricClientCommandSource> context) {")
check("the doctor says when the rigs are off", "Rigs            OFF" in doctor)
check("and how to turn them back on", "/fake rigs on" in doctor)
check("and that it invalidates what follows", "Nothing below is running" in doctor)

# The dashboard is the one place with no chat, so it must show and set it.
check("the dashboard carries the state", '\\"rigsOn\\":' in client)
check("the page has a switch", "id=\"rigs\"" in dash)
check("the switch posts", "post('/rigs?on=" in dash)
check("there is an endpoint", 'createContext("/rigs"' in dash)
check("it is polled", "pollRigs()" in dash and "pollRigs()" in client)
# Read before the master switch's early return, or it cannot be turned on from a
# dashboard while everything is off.
check("the poll happens before the master switch returns",
      tick.index("pollRigs()") < tick.index("if (!SelfFakes.enabled())"))

print("FAILED:\n  " + "\n  ".join(fails) if fails else
      "rigs switch off skips the rig tick, releases the machine guard and drains its keys; "
      "builds, schematics, map art and the fake-item keys are untouched")
sys.exit(1 if fails else 0)
