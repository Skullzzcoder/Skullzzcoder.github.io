"""Subscribing to a Fabric event without naming it, run against a mock of the real shape.

Two things go wrong here and both did, in game, before this existed:

  Event.register(T) erases to register(Object), so asking the register method which
  interface it wants gives Object, and nothing ever subscribes. The interface has to come
  from the field's generic type, which survives erasure.

  An event object is an instance of a class that is not public -- Fabric's live in an impl
  package -- so the method reflected off the runtime class has an inaccessible declaring
  class and invoking it throws IllegalAccessException even though the method says public.

The mock in tools/events/fake is shaped like the real thing in exactly those two ways, so
this runs the real resolution code with no Minecraft anywhere."""
import io, os, shutil, subprocess, sys, tempfile

SRC = "src/main/java/dev/skullzz/mirage/client/Events.java"
HERE = os.path.dirname(os.path.abspath(__file__))

fails = []
def check(name, cond):
    if not cond: fails.append(name)

if shutil.which("javac") is None or shutil.which("java") is None:
    print("SKIPPED: no JDK to run the subscriber with")
    sys.exit(0)

source = io.open(SRC, encoding="utf-8").read()
check("the subscriber has no Minecraft in it", "net.minecraft" not in source)
check("nor Fabric", "net.fabricmc" not in source)

# The two fixes, pinned by what they do rather than by a comment.
check("the listener interface comes from the field's generic type",
      "getGenericType()" in source and "ParameterizedType" in source)
check("a nested interface is the fallback", "getDeclaredClasses()" in source)
check("register is found on a public declaring class",
      "Modifier.isPublic" in source and "getDeclaringClass().getModifiers()" in source)
check("with asking for access as the last resort", "setAccessible(true)" in source)
# A failure must name what was wrong, or the mod is silently deaf.
check("every failure carries a reason", source.count("new Result(false,") >= 5)

work = tempfile.mkdtemp(prefix="mirage-events-")
try:
    classes = os.path.join(work, "classes")
    fake = [os.path.join(HERE, "events", "fake", name)
            for name in sorted(os.listdir(os.path.join(HERE, "events", "fake")))
            if name.endswith(".java")]
    build = subprocess.run(["javac", "-proc:none", "-nowarn", "-d", classes, SRC]
                           + fake + [os.path.join(HERE, "events", "Harness.java")],
                           capture_output=True, text=True)
    check("it compiles with no game on the classpath", build.returncode == 0)
    if build.returncode != 0:
        print("FAILED: " + "; ".join(fails) + "\n" + build.stderr[:1500])
        sys.exit(1)

    run = subprocess.run(["java", "-cp", classes, "Harness"],
                         capture_output=True, text=True)
    lines = [line for line in run.stdout.splitlines() if not line.startswith("Picked up")]
    for line in lines:
        if line.startswith("FAIL "):
            fails.append(line[5:])
    check("the harness ran", run.returncode == 0 and lines and lines[-1] == "OK")

    # The callers have to go through it, or the two bugs come back one at a time.
    for name in ("ChatHook", "HudBar"):
        body = io.open("src/main/java/dev/skullzz/mirage/client/%s.java" % name,
                       encoding="utf-8").read()
        check("%s subscribes through Events" % name, "Events.subscribe(" in body)
        check("%s does not roll its own proxy" % name, "Proxy.newProxyInstance" not in body)
        check("%s remembers why it failed" % name, "reason" in body)
finally:
    shutil.rmtree(work, ignore_errors=True)

print("FAILED:\n  " + "\n  ".join(fails) if fails else
      "a generic event subscribes and receives against a mock shaped like Fabric's, "
      "erasure and an inaccessible implementation class included")
sys.exit(1 if fails else 0)
