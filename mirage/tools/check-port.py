"""The port loop's extraction, run on the real source.

portCheck reads this mod's source, then asks the target's remapped classpath about every
type, constant and method name it finds. The asking cannot be tested here -- there is no
Minecraft on this machine, which is the whole reason the task exists. The finding can be,
and it is the half that goes quietly wrong: a regex that matches nothing reports a clean
port and is indistinguishable from one.

So the patterns are read out of portcheck.gradle and run here, against the same files
Gradle would read. If somebody edits the Groovy, this runs the edited version."""
import io, re, sys, glob

fails = []
def check(name, cond):
    if not cond: fails.append(name)

try:
    gradle = io.open("portcheck.gradle", encoding="utf-8").read()
except IOError:
    print("FAILED: portcheck.gradle is gone, so the port loop has no extraction at all")
    sys.exit(1)

build = io.open("build.gradle", encoding="utf-8").read()
# A broken build script stops every target, not just the one being ported, so the task
# lives in its own file that is applied only if it is there.
check("the port task is in its own file", "apply from: 'portcheck.gradle'" in build)
check("and is applied only if present", "file('portcheck.gradle').exists()" in build)
# An applied script cannot see the parent script's local variables.
check("the target is handed over explicitly", "ext.mirageTarget" in build
      and "project.mirageTarget" in gradle)
check("and never as null", "target == null ? '' :" in build)

# The three patterns, taken from the Groovy rather than copied.
patterns = re.findall(r"text =~ /(.*?)/\)", gradle)
check("all three patterns are found (got %d)" % len(patterns), len(patterns) == 3)
if len(patterns) != 3:
    print("FAILED: " + "; ".join(fails))
    sys.exit(1)

imp_re, static_re, call_re = [re.compile(p) for p in patterns]

imports, statics, calls = set(), {}, set()
files = sorted(glob.glob("src/main/java/**/*.java", recursive=True))
for path in files:
    text = io.open(path, encoding="utf-8").read()
    imports |= set(imp_re.findall(text))
    for holder, constant in static_re.findall(text):
        statics.setdefault(holder, set()).add(constant)
    calls |= set(call_re.findall(text))

flat = {holder + "." + name for holder, names in statics.items() for name in names}

check("it reads the source at all", len(files) > 20)
# A regex that matches nothing reports a clean port. These floors are what stop that.
check("imports are found (%d)" % len(imports), len(imports) >= 60)
check("constants are found (%d)" % len(flat), len(flat) >= 50)
check("called names are found (%d)" % len(calls), len(calls) >= 300)

check("only Minecraft and Fabric types are collected",
      all(i.startswith(("net.minecraft", "net.fabricmc", "com.mojang")) for i in imports))

# The two constants this mod would be worst broken by, and one call from every layer that
# has already moved once or is known fragile.
for constant in ("BlockState.CODEC", "DataComponentTypes.MAP_ID", "Blocks.AIR"):
    check("%s is seen" % constant, constant in flat)
for call in ("setBlockState", "drawTextWithShadow", "getBlockPos", "addDrawableChild"):
    check("%s() is seen" % call, call in calls)

# It has to report rather than guess: the keys screen is unfinished because of four
# guesses, and a tool that suggests a replacement would make that mistake at scale.
check("a missing constant is answered with what the class does have",
      "has:" in gradle and "type.fields" in gradle)
check("a missing name is answered with near matches, not a choice",
      "near:" in gradle)
check("the report says outright that nothing in it is a guess",
      "Nothing above is a guess" in gradle)
check("the report is written to a file as well as printed",
      "file.text = out.toString()" in gradle)

print("FAILED:\n  " + "\n  ".join(fails) if fails else
      "portCheck's own patterns find %d imported types, %d constants and %d called names "
      "across %d files" % (len(imports), len(flat), len(calls), len(files)))
sys.exit(1 if fails else 0)
