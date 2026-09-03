"""Symbols this project owns must resolve.

The structural javac pass throws away every "cannot find symbol" because Minecraft is not on
the classpath outside a Loom build, and almost all of them are that. But a field deleted out
from under its users produces exactly the same error, so the filter that made the pass usable
also made it blind -- twenty-eight of them in one build, none caught here.

The two are distinguishable: javac names the location, and a location that is one of this
project's own types is not the missing classpath. check-calls covers methods across classes;
this covers everything javac can see, fields included."""
import glob, os, re, subprocess, sys, tempfile

SRC = "src/main/java"
sources = sorted(glob.glob(SRC + "/**/*.java", recursive=True))
ours = {os.path.basename(f)[:-5] for f in sources}

with tempfile.TemporaryDirectory() as out:
    javac = subprocess.run(["javac", "-proc:none", "-Xmaxerrs", "100000", "-nowarn",
                            "-d", out] + sources,
                           capture_output=True, text=True)

# javac prints, for each: the error line, the offending source, then "symbol:" and
# "location:". A location naming one of our own types is a symbol we were supposed to have.
lines = javac.stderr.splitlines()
real = []
for i, line in enumerate(lines):
    if "error: cannot find symbol" not in line:
        continue
    where = ""
    symbol = ""
    for follow in lines[i + 1:i + 6]:
        if follow.strip().startswith("symbol:"):
            symbol = follow.split(":", 1)[1].strip()
        if follow.strip().startswith("location:"):
            where = follow.split(":", 1)[1].strip()
            break
    # Two shapes share the words. "symbol: class ItemStack, location: class Mirage" is a
    # Minecraft type referenced inside one of ours -- the missing classpath, and noise.
    # "symbol: variable mix, location: ... RigProfile" is a member we were supposed to have.
    # Java's own naming convention separates them: a type is capitalised, a member is not.
    # "variable super" is super.render() on a class whose supertype is Minecraft's, which
    # is the missing classpath again rather than a member we forgot.
    if not re.match(r"variable [a-z]\w*$", symbol) or symbol in ("variable super",
                                                                 "variable this"):
        continue

    named = set(re.findall(r"\b(\w+)\b", where))
    if named & ours:
        real.append("%s  %s  (%s)" % (line.split(":")[0].replace(SRC + "/", ""), symbol, where))

if real:
    print("FAILED: %d symbol(s) missing from this project's own types:" % len(real))
    for line in sorted(set(real))[:40]:
        print("  " + line)
    sys.exit(1)

print("every symbol on this project's own types resolves (%d files)" % len(sources))
