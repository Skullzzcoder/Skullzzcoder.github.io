"""Reading money out of chat.

The tracker adds up what came in and what went out. It reads lines already on your screen
and does arithmetic on them -- so the only way it can be wrong is quietly: a suffix read
as the wrong scale, a decimal lost, or somebody else's chat message counted as yours.

Chat is written by other people. That is the case worth being strict about: without the
line being anchored, anyone typing "you paid Bob $10000000" in public chat lands in your
tally, and a tally strangers can write to is worse than no tally at all.

Run for real: the parser has no Minecraft in it, so it compiles and runs here."""
import io, json, os, shutil, subprocess, sys, tempfile

SRC = "src/main/java/dev/skullzz/mirage/client/Tracker.java"
HERE = os.path.dirname(os.path.abspath(__file__))

fails = []
def check(name, cond):
    if not cond: fails.append(name)

if shutil.which("javac") is None or shutil.which("java") is None:
    print("SKIPPED: no JDK to run the parser with")
    sys.exit(0)

source = io.open(SRC, encoding="utf-8").read()
check("the parser has no Minecraft in it", "net.minecraft" not in source)
check("nor Fabric", "net.fabricmc" not in source)
# Money in a double loses cents on the way past a million.
check("money is not held in floating point",
      "double cents" not in source and "float " not in source)
check("amounts are cents", "cents" in source)

SECTION = "§"
CASES = [
    # line,                                              expected ("-" for no match)
    ("Skullzz paid you $1,500",                          "IN Skullzz 150000"),
    ("You paid Skullzz $2.5M",                           "OUT Skullzz 250000000"),
    ("You have paid Alex $3b",                           "OUT Alex 300000000000"),
    (SECTION + "aNotch has paid you " + SECTION + "e$750K", "IN Notch 75000000"),
    ("[Trade] Bob paid you $10",                         "IN Bob 1000"),
    ("Skullzz paid you $1.50",                           "IN Skullzz 150"),
    ("Skullzz paid you $0.01",                           "IN Skullzz 1"),

    # Written by other people. None of these may land in the tally.
    ("Someone whispered: you paid Bob $10 for it",       "-"),
    ("<Griefer> you paid Bob $999999999",                "-"),
    ("Griefer: Skullzz paid you $50000000",              "-"),
    ("lol you paid Bob $10",                             "-"),

    # Not payments, or not numbers.
    ("hello there",                                      "-"),
    ("Bob paid you $0",                                  "-"),
    ("Bob paid you $1.2.3",                              "-"),
    ("Bob paid you $",                                   "-"),
    ("You paid Bob",                                     "-"),
]

work = tempfile.mkdtemp(prefix="mirage-tracker-")
try:
    classes = os.path.join(work, "classes")
    build = subprocess.run(["javac", "-proc:none", "-nowarn", "-d", classes, SRC,
                            os.path.join(HERE, "tracker", "Harness.java")],
                           capture_output=True, text=True)
    check("the parser compiles with no game on the classpath", build.returncode == 0)
    if build.returncode != 0:
        print("FAILED: " + "; ".join(fails) + "\n" + build.stderr[:1500])
        sys.exit(1)

    run = subprocess.run(["java", "-cp", classes, "Harness"] + [c[0] for c in CASES],
                         capture_output=True, text=True)
    got = [line for line in run.stdout.splitlines() if not line.startswith("Picked up")]
    check("every line got an answer", len(got) == len(CASES))

    for (line, want), answer in zip(CASES, got):
        check("%r -> %s (got %s)" % (line, want, answer), answer == want)
finally:
    shutil.rmtree(work, ignore_errors=True)

# The direction has to be decided before the amount is: a line containing both shapes read
# the wrong way turns money out into money in, which is worse than missing it.
check("money out is tested for first", source.index("OUT.matcher") < source.index("IN.matcher"))
check("both patterns are anchored", source.count('Pattern.compile(\n            "^') == 2)

# ------------------------------------------------------- the rest of the chain
sess = io.open("src/main/java/dev/skullzz/mirage/client/Sessions.java",
               encoding="utf-8").read()
hook = io.open("src/main/java/dev/skullzz/mirage/client/ChatHook.java",
               encoding="utf-8").read()
client = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java",
                 encoding="utf-8").read()

def body(source, signature):
    import re as _re
    found = _re.search(_re.escape(signature) + r"(.*?)\n    \}", source, _re.S)
    return found.group(1) if found else ""

# Nothing counts by accident. A tally that starts itself is a tally you cannot trust the
# start of, and this one reads chat, which should be a decision.
check("tracking starts off", "boolean tracking = false;" in sess)
check("the HUD starts off", "boolean hud = false;" in sess)
offer = body(sess, "public static Tracker.Payment offer(String line) {")
check("nothing is read while tracking is off", "if (!tracking) return null;" in offer)
check("a payment outside a session is not counted",
      "if (current != null)" in offer)

# The alert is once per run. One that repeats is one that gets ignored, and it is still
# the same run.
streak = body(sess, "private static void checkStreak() {")
check("the alert fires once per run", "run > alertedAt" in streak)
check("and resets when the run breaks", "if (run == 0)" in streak)

# The hook is looked up, never named at compile time -- a wrong guess there would be a
# build that does not compile, which is the mistake this project has made most.
# The subscribing itself moved into Events, which is where both of its bugs were fixed;
# check-events.py runs that against a mock. What matters here is that the chat hook still
# goes through it rather than growing its own copy.
events = io.open("src/main/java/dev/skullzz/mirage/client/Events.java",
                 encoding="utf-8").read()
check("the chat hook subscribes through Events", "Events.subscribe(" in hook)
check("and does not roll its own", "Proxy.newProxyInstance" not in hook)
check("the chat class is named as data, not imported", "EVENT_CLASSES" in hook
      and "net.fabricmc" not in hook.split("EVENT_CLASSES")[0])
check("the shared subscriber proxies the callback", "Proxy.newProxyInstance" in events)
check("the message is found by asking, not by position", 'getMethod("getString")' in hook)
check("a filter callback is answered harmlessly", "return true;" in hook)
check("failing to hook is remembered", "reason =" in hook)
check("every field tried is reported", "tried.append" in hook)
check("and never throws", "catch (ReflectiveOperationException | RuntimeException" in hook)

# Silence is the enemy: a zero that means "nothing happened" looks exactly like a zero
# that means "nothing was heard".
status = body(client, "private static int trackStatus(CommandContext<FabricClientCommandSource> context) {")
# "Chat" also appears inside ChatHook.attached(), so ordering by that word alone passed
# even with the whole line deleted. The marker is what has to be there.
check("status says loudly when chat is not being read",
      "NOT READING" in status and "ChatHook.reason()" in status)
check("and says it before anything about the tally",
      "NOT READING" in status
      and status.index("NOT READING") < status.index("Session"))
check("status says how to fix each thing off",
      "/fake track on" in status and "/fake track start" in status)
check("turning it on says if chat cannot be read", "ChatHook.attached()" in client)

for sub in ("on", "off", "start", "end", "rake", "alert"):
    check("there is a track %s command" % sub, 'literal("%s")' % sub in client)

print("FAILED:\n  " + "\n  ".join(fails) if fails else
      "%d chat lines parsed exactly, including %d written by other people that must not "
      "count; nothing counts until both switches are on, and a chat hook that fails says so"
      % (len(CASES), sum(1 for _, want in CASES if want == "-")))
sys.exit(1 if fails else 0)
