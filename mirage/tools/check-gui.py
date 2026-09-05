"""The click GUI's arithmetic, run rather than eyeballed.

Hit testing is wrong by four pixels and looks almost right. Easing tied to the frame rate
looks fine at 60fps and wrong at 30, on somebody else's machine. Both are invisible in a
screenshot and obvious in use, so the model has no Minecraft in it and is run here."""
import io, os, shutil, subprocess, sys, tempfile

SRC = "src/main/java/dev/skullzz/mirage/client/RyneGui.java"
HERE = os.path.dirname(os.path.abspath(__file__))

fails = []
def check(name, cond):
    if not cond: fails.append(name)

if shutil.which("javac") is None or shutil.which("java") is None:
    print("SKIPPED: no JDK to run the model with")
    sys.exit(0)

source = io.open(SRC, encoding="utf-8").read()
check("the model has no Minecraft in it", "net.minecraft" not in source)
check("nor Fabric", "net.fabricmc" not in source)
# What a row does is the JDK's Runnable, so the model can drive the mod without importing
# any of it.
check("rows act through plain Java", "Runnable" in source and "BooleanSupplier" in source)

# Easing measured in seconds, not per frame. The naive form is the bug this guards.
check("easing takes a duration", "float ease(float value, float target, float perSecond, float seconds)" in source)
check("and is exponential in it", "Math.exp(-perSecond * seconds)" in source)
check("a panel dragged away can be dragged back", "static void clamp(" in source
      and "screenHeight - TITLE_HEIGHT" in source)
# Half a row is not a target.
check("a shut panel takes no row clicks", "if (panel.shut()) return -1;" in source)

work = tempfile.mkdtemp(prefix="mirage-gui-")
try:
    classes = os.path.join(work, "classes")
    build = subprocess.run(["javac", "-proc:none", "-nowarn", "-d", classes, SRC,
                            os.path.join(HERE, "gui", "Harness.java")],
                           capture_output=True, text=True)
    check("it compiles with no game on the classpath", build.returncode == 0)
    if build.returncode != 0:
        print("FAILED: " + "; ".join(fails) + "\n" + build.stderr[:1500])
        sys.exit(1)

    run = subprocess.run(["java", "-cp", classes, "Harness"], capture_output=True, text=True)
    lines = [line for line in run.stdout.splitlines() if not line.startswith("Picked up")]
    for line in lines:
        if line.startswith("FAIL "):
            fails.append(line[5:])
    check("the harness ran", run.returncode == 0 and lines and lines[-1] == "OK")
finally:
    shutil.rmtree(work, ignore_errors=True)

print("FAILED:\n  " + "\n  ".join(fails) if fails else
      "hit testing, dragging, clamping and frame-rate-independent easing all run and agree")
sys.exit(1 if fails else 0)
