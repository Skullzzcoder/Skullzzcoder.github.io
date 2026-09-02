"""A build must never cover a machine.

Everything this mod does with a dispenser it does by reading the client's own copy of the
world: whether one just went off, which way it faces, where to put what comes out, which
machine an open screen belongs to. That copy is the same one builds are painted into. So a
build block landing on a watched dispenser does not hide the machine, it deletes it -- every
rig stops at once, everywhere, and the paths that notice mostly say nothing."""
import io, re, sys
blocks = io.open("src/main/java/dev/skullzz/mirage/client/FakeBlocks.java", encoding="utf-8").read()
disp = io.open("src/main/java/dev/skullzz/mirage/client/ClientDispensers.java", encoding="utf-8").read()

fails = []
def check(name, cond):
    if not cond: fails.append(name)

def body(src, sig):
    m = re.search(re.escape(sig) + r"(.*?)\n    \}", src, re.S)
    return m.group(1) if m else ""

# --------------------------------------------------------------- the paint stops
refresh = body(blocks, "private static void refresh(ClientWorld world, "
                       "ClientPlayerEntity player, BlockPos pos) {")
check("the sweep keeps off guarded positions", "keepClear.contains(pos)" in refresh)
check("and takes off anything already there", "restore(world, pos)" in refresh)

# The order matters and is the whole trick: a machine that is ALREADY covered is showing
# exactly what the build asked for, so the has-it-drifted test calls it settled and leaves
# it. The guard has to be tested before that, or it only ever prevents and never repairs.
guard = refresh.find("keepClear.contains(pos)")
drift = refresh.find("world.getBlockState(pos) == wanted")
check("the guard is tested before the drift test", 0 <= guard < drift)

# Holding a position back is not cutting it out of the build: unwatching the machine has to
# bring the wall home.
clear = body(blocks, "public static void keepClear(Set<BlockPos> positions) {")
check("guarded positions are held back, not deleted", "showing.remove" not in clear)
check("anything already painted comes off at once", "restore(world, pos)" in clear)
check("the set is replaced, not added to", "keepClear.clear();" in clear)

# ------------------------------------------------------------- what is guarded
machines = body(disp, "private static void guardMachines() {")
check("every watched machine is guarded", "for (BlockPos pos : watched)" in machines
      and "clear.add(pos.toImmutable());" in machines)
check("so is the cell it fires into",
      "clear.add(pos.offset(state.get(DispenserBlock.FACING)));" in machines)
# Which way it faces has to be read from the real block. Once something is covering the
# machine, the painted answer is the very thing being got rid of.
check("facing is read from the real block", "FakeBlocks.realAt(world, pos)" in machines)
check("an unloaded chunk is skipped rather than guessed", "isChunkLoaded" in machines)
check("the guard is handed over whole", "FakeBlocks.keepClear(clear);" in machines)

# Every route that changes which positions are machines has to re-guard, or the guard is
# a snapshot of a setup that has moved on.
for route, sig in (("watching one", "public static boolean watch(BlockPos pos) {"),
                   ("dropping one", "public static boolean unwatch(BlockPos pos) {"),
                   ("dropping them all", "public static void unwatchAll() {"),
                   ("loading the config", "public static void load(JsonObject root) {")):
    check("re-guarded after %s" % route, "guardMachines()" in body(disp, sig))

# Facing cannot be read until the chunk arrives, so it cannot be a one-shot at watch time.
tick = body(disp, "public static void tick(MinecraftClient client) {")
check("the guard is kept up as chunks arrive", "guardMachines()" in tick)
check("and not every tick", "tick % GUARD_TICKS" in tick)

# ------------------------------------------------------------ nothing is silent
# Both ways out have to say why. spawn() was the silent one: a fire that got all the way
# there and produced nothing was indistinguishable from the mod not being loaded.
for name, sig in (("throwing it", "private static boolean spawn(ClientWorld world, "
                                  "BlockPos pos, FakeSpec result) {"),
                  ("standing it up", "private static boolean stand(ClientWorld world, "
                                     "BlockPos pos, FakeSpec result) {")):
    out = body(disp, sig)
    gate = out[:out.index("return false;") + len("return false;")]
    check("%s says why it could not" % name, "warn(whyNotDispensing(world, pos));" in gate)

why = body(disp, "private static String whyNotDispensing(ClientWorld world, BlockPos pos) {")
check("being covered is named as its own cause", "FakeBlocks.fakeAt(pos) != null" in why)
check("and says how to fix it", "press B" in why)
check("too far is told apart from gone", "isChunkLoaded" in why and "moved, or broken" in why)
# Asked of the loop rather than of one spelling of the call: the reason a fire stopped is
# now both said and logged, so pinning the exact line meant a check that failed on a change
# that kept the rule.
fire_loop = re.search(r"Iterator<PendingFire> iterator.*?\n        \}", disp, re.S).group(0)
check("the fire loop uses it", "whyNotDispensing(world, fire.pos())" in fire_loop)
check("the status list names it too", "COVERED by one of your builds" in disp)

# --------------------------------------------- the guard must not eat the answer
# The guard keeps builds off a machine and off the cell it fires into. What the machine
# itself puts down in that cell is not a build -- it is the answer, and making room for
# exactly that is what the guard is for. Without the exception, placing succeeded, the
# stock went down, and nothing ever appeared: every game that stands its answer on the
# ground, silently.
check("a machine's own placed answer survives the guard",
      "keepClear.contains(pos) && !underfootOnly.contains(pos)" in refresh)
# And the marker it is judged by has to be the one placing actually sets.
place = body(blocks, "public static boolean place(BlockPos pos, BlockState state) {")
check("placing is what marks it", "underfootOnly.add(key)" in place)

print("FAILED: " + "; ".join(fails) if fails else
      "builds keep off every watched machine and the cell it fires into; "
      "a covered machine repairs itself and says so")
sys.exit(1 if fails else 0)
