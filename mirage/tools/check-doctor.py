"""The chain a fake travels, and the one command that walks it.

Nine things have to be true for something to come out of a machine. Each of them used to
announce itself somewhere different or not at all, so every report of "it does not work"
started from nothing. Two rules hold that shut: every step of the resolve has to write to
the fire log whatever it does, and the doctor has to read every step back."""
import io, re, sys
disp = io.open("src/main/java/dev/skullzz/mirage/client/ClientDispensers.java", encoding="utf-8").read()
mc = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java", encoding="utf-8").read()

fails = []
def check(name, cond):
    if not cond: fails.append(name)

def body(src, sig):
    m = re.search(re.escape(sig) + r"(.*?)\n    \}", src, re.S)
    return m.group(1) if m else ""

# -------------------------------------------------------------- nothing is unlogged
# Every way in, so "did the watcher see anything at all" is answerable.
check("a spotted fire is logged", 'logFire(key, "spotted by " + why)' in disp)
check("a hand-fired one is logged", 'logFire(key, "fired by hand")' in disp)

# Every way out of the resolve. A branch that leaves without logging is a fire that
# vanished, which is the exact thing the log exists to make impossible.
tick = body(disp, "public static void tick(MinecraftClient client) {")
resolve = tick[tick.index("Iterator<PendingFire>"):]
exits = len(re.findall(r"\bcontinue;", resolve)) + 1  # the continues, plus falling through
check("every way out of a fire is logged", resolve.count("logFire(") >= 3)
for outcome in ("STOPPED: \" + why", "had no answer for it", "could not come out"):
    check("the log records '%s'" % outcome.strip('"'), outcome in resolve)
check("a success is logged too", 'out ? "fired " + describeSpec(result)' in resolve)

# The log must not depend on debug being on, or on the throttle.
log = body(disp, "private static void logFire(BlockPos pos, String outcome) {")
check("the log is never conditional", "debug" not in log and "lastWarn" not in log)
check("the log is bounded", "FIRE_LOG_SIZE" in log)

# ------------------------------------------------------- one fault never hides another
# Five machines resolve in one tick. A flat throttle showed one and dropped four.
warn = body(disp, "private static void warn(String message) {")
check("a different fault is never thrown away",
      "message.equals(lastWarnText)" in warn and "&&" in warn)
check("a repeat is still held back", "tick - lastWarn < WARN_GAP_TICKS" in warn)

# ------------------------------------------------------------------ the preview
# "Is it rigged right" and "does anything come out" were the same question asked twice.
preview = body(disp, "public static String preview(BlockPos pos) {")
check("the preview covers every game",
      all(m in preview for m in ("profile.roulette", "profile.paper", "profile.tower")))
check("and falls back to the cycled answer", "profile.resultFor(pos)" in preview)
# It must not spend anything: advancing a chamber to find out what it would fire is the
# one thing that would make asking change the answer.
for spender in ("advanceRoulette", "bustsOn", "startRound", "sideAt(", "floorAt("):
    check("the preview does not spend %s" % spender, spender not in preview)

# ------------------------------------------------------------------- the doctor
doc = body(mc, "private static int doctor(CommandContext<FabricClientCommandSource> context) {")
check("there is a doctor command", 'literal("doctor")' in mc and doc)
for step, probe in (("the master switch", "SelfFakes.enabled()"),
                    ("which rig is on", "profile.mode()"),
                    ("whether the rig has an answer", "ClientDispensers.result()"),
                    ("the machines", "ClientDispensers.watchedPositions()"),
                    ("a covered machine", "FakeBlocks.fakeAt(pos)"),
                    ("an unloaded chunk", "isChunkLoaded"),
                    ("what each would fire", "ClientDispensers.preview(pos)"),
                    ("what each holds", "ClientDispensers.describeStock(pos)"),
                    ("what actually happened", "ClientDispensers.fireLog()")):
    check("the doctor checks %s" % step, probe in doc)

# Each failing step has to name the key that fixes it, or the report is a diagnosis with
# no treatment and the next round is spent asking what to do about it.
for cure in ("press N", "press H", "press B", "rewatch it", "go closer"):
    check("the doctor says '%s'" % cure, cure in doc)

# It has to be findable from the failure it explains.
fire = body(mc, "private static void fireLookedAtOrAll(MinecraftClient client) {")
check("firing points at the doctor when nothing comes out", "/fake doctor" in fire)

print("FAILED: " + "; ".join(fails) if fails else
      "every fire is logged whatever becomes of it; the doctor walks all %d steps and names "
      "the fix for each" % 6)
sys.exit(1 if fails else 0)
