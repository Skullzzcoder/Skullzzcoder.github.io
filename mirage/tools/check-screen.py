"""Screens may only call what has already been seen to compile.

The keys screen is half-finished because four Minecraft methods were guessed at and none
of them existed. There is no Minecraft on this machine to check a name against -- but
there is a whole mod that does build, so a method used anywhere in it is a method that
exists in this version. Anything a new screen calls that appears nowhere else is a guess,
and a guess is a build the player has to run to find out about.

This is a floor, not a ceiling: it cannot say a name is right, only that it is not new."""
import io, re, sys, glob, os

SCREENS = ["src/main/java/dev/skullzz/mirage/client/MirageSchematicsScreen.java"]

# Java and this project's own idioms; not Minecraft, so not evidence either way.
JDK = {"literal", "join", "max", "min", "size", "get", "isEmpty", "length", "substring",
       "lastIndexOf", "replaceAll", "equals", "containsKey", "keySet", "toString", "add",
       "run", "formatted", "trim", "contains", "put", "remove", "valueOf", "format",
       "startsWith", "endsWith", "toLowerCase", "toUpperCase", "split", "hashCode",
       "abs", "getOrDefault", "of", "stream", "sort", "indexOf", "charAt", "isBlank"}

fails = []
def check(name, cond):
    if not cond: fails.append(name)

everything = {}
for path in glob.glob("src/main/java/**/*.java", recursive=True):
    everything[path.replace("\\", "/")] = io.open(path, encoding="utf-8").read()

checked = 0
for screen in SCREENS:
    screen = screen.replace("\\", "/")
    if screen not in everything:
        fails.append("%s is gone" % screen)
        continue

    source = everything[screen]
    elsewhere = "".join(body for path, body in everything.items() if path != screen)

    calls = set(re.findall(r"\.([a-zA-Z]\w*)\s*\(", source))
    # Anything the screen declares itself is its own, not a Minecraft call.
    declared = set(re.findall(r"\b(?:void|int|boolean|String|List<String>|BlockPos|"
                              r"MirageSchematicsScreen)\s+(\w+)\s*\(", source))

    for call in sorted(calls):
        if call in declared or call in JDK:
            continue
        checked += 1
        if call not in elsewhere:
            fails.append("%s calls %s(), which appears nowhere else in the mod -- so "
                         "nothing here has ever seen it compile" % (os.path.basename(screen), call))

    # The two that were actually guessed wrong before, named so they cannot come back
    # quietly on the strength of the rule above alone.
    for risky in ("clearChildren", "rebuildWidgets", "setKeyCode", "getTranslationKey"):
        check("%s does not call %s()" % (os.path.basename(screen), risky),
              ("." + risky + "(") not in source)

    # Redrawing goes through setScreen, which every screen here already does on close.
    check("the screen redraws the way the rest of the mod already does",
          "setScreen(" in source)

print("FAILED:\n  " + "\n  ".join(fails) if fails else
      "%d Minecraft calls across %d screen(s), every one of them already used elsewhere "
      "in code that builds" % (checked, len(SCREENS)))
sys.exit(1 if fails else 0)
