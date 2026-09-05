"""Multi-catch alternatives, which the structural javac pass cannot see.

`catch (InvalidPathException | RuntimeException e)` does not compile: alternatives may not
be related by subclassing. It reached a real build because the offline javac run stops at
the missing Minecraft classes and never gets as far as the flow analysis that would report
it -- so every error of this shape is invisible here and lands on the player's machine.

Written down as a table for the same reason the map palette is: the JDK's exception tree
is fixed, and a relation missing from the table is a pair not checked rather than a wrong
answer."""
import io, re, sys, glob

# Each entry is an exception and everything it is a subclass of. Only what the source
# actually catches needs to be here; unknown names are simply not compared.
PARENTS = {
    "RuntimeException":            {"Exception", "Throwable"},
    "IllegalArgumentException":    {"RuntimeException", "Exception", "Throwable"},
    "IllegalStateException":       {"RuntimeException", "Exception", "Throwable"},
    "NumberFormatException":       {"IllegalArgumentException", "RuntimeException",
                                    "Exception", "Throwable"},
    "ArithmeticException":         {"RuntimeException", "Exception", "Throwable"},
    "InvalidPathException":        {"IllegalArgumentException", "RuntimeException",
                                    "Exception", "Throwable"},
    "NullPointerException":        {"RuntimeException", "Exception", "Throwable"},
    "IndexOutOfBoundsException":   {"RuntimeException", "Exception", "Throwable"},
    "ClassCastException":          {"RuntimeException", "Exception", "Throwable"},
    "UnsupportedOperationException": {"RuntimeException", "Exception", "Throwable"},
    "ConcurrentModificationException": {"RuntimeException", "Exception", "Throwable"},
    "JsonParseException":          {"RuntimeException", "Exception", "Throwable"},
    "JsonSyntaxException":         {"JsonParseException", "RuntimeException",
                                    "Exception", "Throwable"},
    "IOException":                 {"Exception", "Throwable"},
    "FileNotFoundException":       {"IOException", "Exception", "Throwable"},
    "UncheckedIOException":        {"RuntimeException", "Exception", "Throwable"},
    "InterruptedException":        {"Exception", "Throwable"},
    "ReflectiveOperationException": {"Exception", "Throwable"},
    "ClassNotFoundException":      {"ReflectiveOperationException", "Exception", "Throwable"},
    "NoSuchMethodException":       {"ReflectiveOperationException", "Exception", "Throwable"},
    "NoSuchFieldException":        {"ReflectiveOperationException", "Exception", "Throwable"},
    "IllegalAccessException":      {"ReflectiveOperationException", "Exception", "Throwable"},
    "InvocationTargetException":   {"ReflectiveOperationException", "Exception", "Throwable"},
    "Exception":                   {"Throwable"},
    "Throwable":                   set(),
}

def simple(name):
    return name.strip().split(".")[-1]

def related(a, b):
    return b in PARENTS.get(a, set()) or a in PARENTS.get(b, set())

fails = []
seen = 0
unknown = set()

for path in sorted(glob.glob("src/main/java/**/*.java", recursive=True)):
    source = io.open(path, encoding="utf-8").read()
    for line_no, line in enumerate(source.splitlines(), 1):
        found = re.search(r"catch \(([^)]*\|[^)]*)\)", line)
        if not found:
            continue
        seen += 1
        # The last word is the variable name, not a type.
        types = [simple(t) for t in found.group(1).rsplit(" ", 1)[0].split("|")]
        for name in types:
            if name not in PARENTS:
                unknown.add("%s:%d %s" % (path, line_no, name))
        for i in range(len(types)):
            for j in range(i + 1, len(types)):
                if related(types[i], types[j]):
                    fails.append("%s:%d catches %s and %s, which are related"
                                 % (path, line_no, types[i], types[j]))

# A type nobody has written down is not compared, and silence about that would be the
# same blind spot in a new coat.
for name in sorted(unknown):
    fails.append("%s is not in the table, so it was not checked" % name)

print("FAILED:\n  " + "\n  ".join(fails) if fails else
      "%d multi-catches, every alternative pair unrelated" % seen)
sys.exit(1 if fails else 0)
