"""Quiet mode, and the dashboard that has to carry what it takes off the screen.

The rule this whole mod has been built around is that nothing fails in silence. Quiet mode
is the one feature that could break it outright, so every message has to be kept before
anything decides whether to show it, and the dashboard has to carry the lot."""
import io, re, sys
disp = io.open("src/main/java/dev/skullzz/mirage/client/ClientDispensers.java", encoding="utf-8").read()
mc   = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java", encoding="utf-8").read()
self_ = io.open("src/main/java/dev/skullzz/mirage/client/SelfFakes.java", encoding="utf-8").read()
web  = io.open("src/main/java/dev/skullzz/mirage/client/WebDashboard.java", encoding="utf-8").read()

fails = []
def check(name, cond):
    if not cond: fails.append(name)

def body(src, sig):
    m = re.search(re.escape(sig) + r"(.*?)\n    \}", src, re.S)
    return m.group(1) if m else ""

# ----------------------------------------------- nothing is dropped, only moved
# Both ways the mod speaks. In each, the keeping must come before anything that can
# swallow it -- the quiet switch, and in one case a throttle as well.
say = body(mc, "private static void say(MinecraftClient client, String message) {")
def before(text, first, second):
    """Whether `first` appears, and appears ahead of `second`. A missing piece is a
    failure rather than a crash: these run against deliberately broken source."""
    a, b = text.find(first), text.find(second)
    return 0 <= a < b if b >= 0 else a >= 0

check("saying keeps the message first", "ClientDispensers.notice(message);" in say)
check("and only then decides whether to show it",
      before(say, "notice(message)", "SelfFakes.quiet()"))

warn = body(disp, "private static void warn(String message) {")
check("warning keeps the message first", "notice(message);" in warn)
check("before the quiet switch", before(warn, "notice(message)", "SelfFakes.quiet()"))
check("and before the throttle", before(warn, "notice(message)", "WARN_GAP_TICKS"))

notice = body(disp, "public static void notice(String message) {")
check("the record is bounded", "NOTICE_SIZE" in notice)
check("and never conditional", "quiet" not in notice and "debug" not in notice)

# The switch has to survive a restart, or it is on for one session and a surprise the next.
check("quiet is saved", 'root.addProperty("quiet", quiet);' in self_)
check("and read back", 'root.has("quiet")' in self_)
check("there is a command", 'literal("quiet")' in mc
      and "SelfFakes.setQuiet(true)" in mc and "SelfFakes.setQuiet(false)" in mc)

# ------------------------------------------------------ the dashboard carries it
pub = body(mc, "private static void publishDashboard() {")
for field, probe in (("which game is on", "shown.mode()"),
                     ("what F does", "shown.forwardLabel()"),
                     ("what R does", "shown.backLabel()"),
                     ("whether the rig can answer", "ClientDispensers.noAnswer()"),
                     ("every machine", "ClientDispensers.watchedPositions()"),
                     ("what each would fire", "ClientDispensers.preview(pos)"),
                     ("what each holds", "ClientDispensers.describeStock(pos)"),
                     ("the fire log", "ClientDispensers.fireLog()"),
                     ("the messages", "ClientDispensers.notices()"),
                     ("the blackjack shoe", "shown.blackjack"),
                     ("the mix spread", "shown.mixChance(i)"),
                     ("whether quiet is on", "SelfFakes.quiet()")):
    check("the dashboard publishes %s" % field, probe in pub)

# And the page has to actually draw them, or publishing is talking to itself. Pinned by
# what each section reads rather than by the name of the function that draws it, since the
# page has been laid out twice now and the field is the thing that matters.
for section in ("overview", "rigs", "machines", "builds", "schematics", "mapart", "log"):
    check("the page has a %s section" % section, "pages.%s = " % section in web)
for field in ("s.machines", "s.fires", "s.notices", "s.mix", "s.blackjack", "s.paper",
              "s.rigs", "s.rigsOn", "s.answer"):
    check("the page reads %s" % field, field in web)

# Three things the first layout did that the second nearly lost. All three are the same
# rule: the state you opened the page to find must not be the state that looks ordinary.
check("a machine that is not ready is marked",
      "machines[i].state === 'ok' ? '' : 'bad'" in web and "tr.bad td" in web)
check("a stopped fire is marked", "line.includes('STOPPED')" in web)
check("the newest line is first", ".slice().reverse()" in web)
check("a machine that is not ready is counted where you land",
      "'Machines not ready'" in web)

# ------------------------------------------------------------- the break time
# A prize that vanishes the instant it is touched does not read as having been mined.
hands = io.open("src/main/java/dev/skullzz/mirage/client/FakeHands.java", encoding="utf-8").read()
tick = body(hands, "public static void tick(MinecraftClient client) {")
check("a placed answer breaks on its own clock",
      "ClientDispensers.placedBreakTicks(breaking)" in tick and "progress += 1.0F / placed;" in tick)
# Creative is where the instant break came from, so the answer must beat that test to it.
check("creative does not skip it", before(tick, "placed > 0", "isCreative()"))
check("anything else still breaks at its own hardness",
      "state.calcBlockBreakingDelta(player, world, breaking)" in tick)

ticks = body(disp, "public static int placedBreakTicks(BlockPos pos) {")
check("only what a machine put down gets a time", "FakeBlocks.isPlaced(pos)" in ticks)
check("and zero seconds means vanilla again", "seconds <= 0 ? 0" in ticks)
check("never zero ticks, which would divide by nothing", "Math.max(1," in ticks)
check("the time is saved", 'addProperty("breakSeconds"' in disp)
check("and settable", 'literal("breaktime")' in mc)

# ------------------------------------------------------------- the price line
# The line under a fake's name that makes it read like something the server formatted.
# Worth having only while that is the look you want, so it is off unless asked for.
lore = io.open("src/main/java/dev/skullzz/mirage/client/FakeLore.java", encoding="utf-8").read()
apply_to = re.search(r"public static ItemStack applyTo\(ItemStack stack, String enchantSpec, "
                     r"Double priceOverride\) \{(.*?)\n    \}", lore, re.S).group(1)
check("the price line answers to the switch", "SelfFakes.showPrices()" in apply_to)
# One gate, at the one place that writes lore. A per-fake price set by hand must not slip a
# line back onto an item that is meant to be bare.
check("there is only one place lore is written",
      lore.count("DataComponentTypes.LORE") == 1)
check("and the switch is tested before the price is used",
      before(apply_to, "SelfFakes.showPrices()", "loreLines"))

check("the switch is off unless asked for",
      "private static boolean showPrices = false;" in self_)
check("it survives a restart", 'root.addProperty("showPrices", showPrices);' in self_
      and 'root.has("showPrices")' in self_)
# Every fake already made has the old answer baked into the stack it hands out.
setter = body(self_, "public static void setShowPrices(boolean show) {")
check("turning it off strips the fakes already made", "rebuildAll();" in setter)
check("there is a command", "SelfFakes.setShowPrices(true)" in mc
      and "SelfFakes.setShowPrices(false)" in mc)

print("FAILED: " + "; ".join(fails) if fails else
      "every message is kept before it is shown; the dashboard carries the game, the keys, "
      "the machines and both logs; a placed answer takes its own time to break; "
      "the price line is off unless asked for")
sys.exit(1 if fails else 0)
