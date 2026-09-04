"""The schematic reader, run for real.

Every other check here reads the source and asserts on its shape. This one does not: it
writes actual .litematic files, compiles the reader with a real javac, runs it against
them and compares what comes out block by block. That is possible only because the reader
and the NBT parser underneath it were kept free of Minecraft -- the awkward half of this
format is the bit-packing, and a packing read from memory needs running, not describing.

The case that matters is a palette of five. Two bits per entry divides 64 evenly, so a
reader that starts every long afresh gets a three-block-palette schematic exactly right
and a five-block one silently wrong."""
import io, os, shutil, subprocess, sys, tempfile
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "litematic"))
import build as scenarios

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = "src/main/java/dev/skullzz/mirage/client"

fails = []
def check(name, cond):
    if not cond: fails.append(name)

if shutil.which("javac") is None or shutil.which("java") is None:
    print("SKIPPED: no JDK on this machine to run the reader with")
    sys.exit(0)

work = tempfile.mkdtemp(prefix="mirage-litematic-")
try:
    want = scenarios.build(work)
    classes = os.path.join(work, "classes")

    # Compiled on their own, with no Minecraft on the classpath at all. If this ever fails
    # it means the reader has grown a game dependency and can no longer be run here.
    compile_to = subprocess.run(
        ["javac", "-proc:none", "-nowarn", "-d", classes,
         os.path.join(SRC, "Nbt.java"), os.path.join(SRC, "Litematic.java"),
         os.path.join(HERE, "litematic", "Harness.java")],
        capture_output=True, text=True)
    check("the reader compiles with no game on the classpath", compile_to.returncode == 0)
    if compile_to.returncode != 0:
        print("FAILED: " + "; ".join(fails) + "\n" + compile_to.stderr[:2000])
        sys.exit(1)

    files = sorted(want)
    run = subprocess.run(["java", "-cp", classes, "Harness"] + files,
                         capture_output=True, text=True, cwd=work)
    check("the reader runs", run.returncode == 0)
    output = [line for line in run.stdout.splitlines() if not line.startswith("Picked up")]

    # Split the run's output back into one block per file.
    got, current = {}, None
    for line in output:
        if not line.startswith("   "):
            current = line.split(" ", 1)[0]
            got[current] = [line]
        elif current:
            got[current].append(line)

    for name in files:
        lines = got.get(name)
        if lines is None:
            fails.append("%s produced no output at all" % name)
            continue

        expected = want[name]
        if expected[0] == "REFUSED":
            # A file that cannot be trusted must be named and refused, never half-read:
            # a schematic read the wrong way does not look wrong, it looks like a
            # different building.
            check("%s is refused" % name, "REFUSED" in lines[0])
            for phrase in expected[1:]:
                check("%s says why (%s)" % (name, phrase), phrase in lines[0])
            check("%s paints nothing" % name, len(lines) == 1)
        else:
            if lines != expected:
                shown = [l for a, l in zip(expected + [None] * 9, lines) if a != l][:3]
                fails.append("%s read differently: %s" % (name, shown or "length differs"))

    total = sum(len(v) - 1 for k, v in want.items() if v[0] != "REFUSED")
finally:
    shutil.rmtree(work, ignore_errors=True)

print("FAILED: " + "; ".join(fails) if fails else
      "%d schematics read block for block (%d blocks, positions and states exact); "
      "%d broken files refused by name" % (
          sum(1 for v in want.values() if v[0] != "REFUSED"), total,
          sum(1 for v in want.values() if v[0] == "REFUSED")))
sys.exit(1 if fails else 0)
