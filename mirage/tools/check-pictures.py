"""Finding the picture -- the step that was actually stopping people.

Two separate faults lived here. The command argument was a *word*, so a path with a slash,
a colon or a space failed to parse before any of the import code ran; and the import only
ever looked in one folder, which the player then had to go and find. Both are the kind of
failure that looks like "the feature is broken" rather than "the file is somewhere else",
so both are pinned here."""
import io, re, sys
art  = io.open("src/main/java/dev/skullzz/mirage/client/MapArt.java", encoding="utf-8").read()
disk = io.open("src/main/java/dev/skullzz/mirage/client/Disk.java", encoding="utf-8").read()
mc   = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java", encoding="utf-8").read()
dash = io.open("src/main/java/dev/skullzz/mirage/client/WebDashboard.java", encoding="utf-8").read()

fails = []
def check(name, cond):
    if not cond: fails.append(name)

def body(source, signature):
    found = re.search(re.escape(signature) + r"(.*?)\n    \}", source, re.S)
    return found.group(1) if found else ""

# ------------------------------------------------- a path has to survive the parser
imp = re.search(r'literal\("import"\)(.*?)literal\("pictures"\)', mc, re.S)
imp = imp.group(1) if imp else ""
check("there is an import command", imp != "")

# Only the file argument matters here: the design name stays a word on purpose. word()
# accepts no slash, no colon and no space, so a pasted path dies in the parser and the
# error blames the command rather than the argument.
fileArg = re.search(r'argument\("file",\s*\n?\s*StringArgumentType\.(\w+)\(\)', imp)
check("the file argument is declared", fileArg is not None)
check("a path is not parsed as a bare word", fileArg and fileArg.group(1) != "word")
check("the file argument takes a whole path", fileArg and fileArg.group(1) == "string")

# ------------------------------------------------------------ where it looks
places = body(disk, "public static List<Path> places(Path own) {")
check("the mod's own folder is looked in first",
      places.index("folders.add(own)") < places.index("user.home"))
for folder in ("Desktop", "Downloads", "Pictures"):
    check("%s is looked in" % folder, '"%s"' % folder in places)
check("and the home folder itself", "folders.add(base);" in places)
check("no home means no crash", "home != null" in places)

find = body(disk, "public static Path find(Path own, String fileName) {")
check("a whole path is taken as it stands", "given.isAbsolute()" in find)
check("a home-relative path is expanded", 'cleaned.startsWith("~")' in find)
check("a pasted path keeps its quotes out of the name", 'startsWith("\\"")' in find)
check("a name alone is looked for in every folder", "for (Path folder : places(own))" in find)
# On Windows a stray character throws rather than returning; a thrown import helps nobody.
# RuntimeException alone catches it: InvalidPathException is one, and naming both is a
# compile error (see check-catch.py), which is exactly how this was first written.
check("an impossible path is a missing file, not a crash",
      "catch (RuntimeException" in find)
# One copy, two callers. The last time this rule lived in two places, one copy was fixed.
check("pictures and schematics use the same finding", "Disk.find(" in art
      and "Disk.find(" in io.open("src/main/java/dev/skullzz/mirage/client/Schematic.java",
                                  encoding="utf-8").read())
check("nothing found is nothing returned", find.rstrip().endswith("return null;"))

# -------------------------------------------------------------- what it offers
pics = body(disk, "public static List<String> list(Path own, List<String> kinds) {")
check("every folder is listed, not just one", "for (Path folder : places(own))" in pics)
check("only the right kinds are offered", "looksRight(fileName, kinds)" in pics)
check("a huge folder cannot flood the list", "found.size() < MOST" in pics)
check("the same name twice is offered once", "!found.contains(fileName)" in pics)
check("an unreadable folder holds nothing", "catch (IOException | RuntimeException" in pics)
MOST = int(re.search(r"int MOST = (\d+);", disk).group(1))
check("the cap is a help rather than a wall", 10 <= MOST <= 200)

KINDS = re.search(r"KINDS =\s*java\.util\.List\.of\(([^)]*)\)", art).group(1)
for kind in (".png", ".jpg", ".jpeg"):
    check("%s is offered" % kind, '"%s"' % kind in KINDS)
check("the ending is matched whatever its case", "toLowerCase" in disk)

# The dashboard rebuilds twenty times a second; walking four folders that often is a
# stutter you can feel, so the listing it uses has to be the cached one.
cache = body(art, "public static java.util.List<String> picturesCached() {")
check("the constant listing is cached", "cachedAt" in cache and "CACHE_MS" in cache)
CACHE_MS = int(re.search(r"long CACHE_MS = (\d+)L;", art).group(1))
check("the cache is short enough to feel live", 500 <= CACHE_MS <= 10000)
check("the dashboard uses the cached listing", "MapArt.picturesCached()" in mc)
# ...but a command run by hand right after dropping a file must see that file.
listing = body(mc, "private static int listPictures(CommandContext<FabricClientCommandSource> context) {")
check("the command run by hand scans afresh", "MapArt.pictures()" in listing)

# --------------------------------------------------------- saying where it is
check("there is a folder command", 'literal("folder")' in mc)
folder = body(mc, "private static int pictureFolder(CommandContext<FabricClientCommandSource> context) {")
check("it prints the whole path", "MapArt.pictureFolder()" in folder)
check("and every other place it looks", "describePlaces()" in folder)
check("an empty listing still says where it looked", "describePlaces()" in listing)

# The dashboard is the one place a path can be copied rather than retyped.
check("the dashboard carries the folder", '\\"pictures\\":{\\"folder\\":' in mc)
check("the page shows it", "path.id = 'picpath'" in dash)
check("selectable, not just readable", "user-select: all" in dash)
# One copy button, built by one function, used by both folder pages.
check("there is a copy button", "copyButton(host, 'picpath')" in dash
      and "const copyButton = " in dash)
check("a refused clipboard still leaves it selected",
      "selectNodeContents" in dash and "catch (failure)" in dash)
check("an empty folder says what to do", "Drop a png or jpg" in dash)

print("FAILED: " + "; ".join(fails) if fails else
      "a picture is found by whole path, by ~, or by name in %d folders; %d kinds offered, "
      "capped at %d, cached %dms; the folder is printed and copyable"
      % (places.count("folders.add"), KINDS.count('"') // 2, MOST, CACHE_MS))
sys.exit(1 if fails else 0)
