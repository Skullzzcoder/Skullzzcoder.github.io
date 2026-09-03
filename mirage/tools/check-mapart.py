"""Numbers drawn onto a map.

The drawing is arithmetic on a 128x128 grid and is checked as such -- run here, on the real
font and the real layout read out of the source. Handing the grid to the client is the only
part that touches Minecraft, and the call for it has moved between versions, so that part is
reflective and the rule is that it can never throw and never fail quietly."""
import io, re, sys
art = io.open("src/main/java/dev/skullzz/mirage/client/MapArt.java", encoding="utf-8").read()
mc  = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java", encoding="utf-8").read()

fails = []
def check(name, cond):
    if not cond: fails.append(name)

SIZE = int(re.search(r"int SIZE = (\d+)", art).group(1))
check("a map is 128 across", SIZE == 128)

# ------------------------------------------------------------------- the font
# Read the glyphs out of the source so this cannot drift from what ships.
FONT = {}
for match in re.finditer(r"glyph\('(.)',\s*([^)]*)\);", art):
    letter = match.group(1)
    rows = [int(v.strip(), 0) for v in match.group(2).split(",")]
    FONT[letter] = rows

check("every card the shoe deals can be drawn",
      all(c in FONT for c in "A123456789"))
check("each glyph is seven rows", all(len(r) == 7 for r in FONT.values()))
check("no glyph is wider than five", all(all(0 <= v < 32 for v in r) for r in FONT.values()))
# A blank that is not blank would draw a block over the map.
check("the space is empty", all(v == 0 for v in FONT[" "]))
# Distinct glyphs, or two cards read the same on the table.
drawn = {c: tuple(r) for c, r in FONT.items() if c != " "}
check("no two characters draw the same", len(set(drawn.values())) == len(drawn))

# --------------------------------------------------------------- the layout
# The real rule, taken from the source, run here.
MARGIN = int(re.search(r"int margin = (\d+);", art).group(1))

# This mirrors the layout to run it here; a mirror goes stale in silence, so the lines it
# copies are pinned. Change the scale or the placement in Java and this stops claiming to
# have checked them.
MIRRORED = [
    "int wide = drawable.length() * 5 + (drawable.length() - 1);",
    "int scale = Math.max(1, Math.min(room / wide, room / 7));",
    "int left = (SIZE - drawnWidth) / 2;",
    "int top = (SIZE - drawnHeight) / 2;",
    "int originX = left + index * 6 * scale;",
]
missing = [line for line in MIRRORED if line not in art]
if missing:
    print("FAILED: this check no longer mirrors the layout. Missing:")
    for line in missing:
        print("  " + line)
    sys.exit(1)

# A margin the art must keep whatever the source says its margin is. Testing against the
# configured value tests nothing: set it to zero and the check moves with it.
KEEP_CLEAR = 4
check("the source keeps a margin at all", MARGIN >= KEEP_CLEAR)

def render(text):
    pixels = [0] * (SIZE * SIZE)
    letters = [c for c in text.upper() if c in FONT]
    if not letters:
        return pixels
    room = SIZE - MARGIN * 2
    wide = len(letters) * 5 + (len(letters) - 1)
    scale = max(1, min(room // wide, room // 7))
    left = (SIZE - wide * scale) // 2
    top = (SIZE - 7 * scale) // 2
    for index, letter in enumerate(letters):
        rows = FONT[letter]
        ox = left + index * 6 * scale
        for row in range(7):
            for col in range(5):
                if not (rows[row] & (1 << (4 - col))):
                    continue
                for dy in range(scale):
                    for dx in range(scale):
                        x, y = ox + col * scale + dx, top + row * scale + dy
                        if 0 <= x < SIZE and 0 <= y < SIZE:
                            pixels[y * SIZE + x] = 1
    return pixels

def bounds(pixels):
    on = [(i % SIZE, i // SIZE) for i, v in enumerate(pixels) if v]
    if not on: return None
    xs = [p[0] for p in on]; ys = [p[1] for p in on]
    return min(xs), min(ys), max(xs), max(ys)

# Nothing may run off the map, at any length the command accepts.
for text in ["A", "7", "21", "A9", "123", "QKJ", "999"]:
    box = bounds(render(text))
    check("'%s' stays on the map" % text,
          box and box[0] >= 0 and box[1] >= 0 and box[2] < SIZE and box[3] < SIZE)
    check("'%s' keeps clear of the edge" % text,
          box and box[0] >= KEEP_CLEAR and box[1] >= KEEP_CLEAR
          and box[2] < SIZE - KEEP_CLEAR and box[3] < SIZE - KEEP_CLEAR)

# Big enough to read across a table: one character should fill most of the height.
one = bounds(render("8"))
check("a single card is drawn large", one[3] - one[1] > SIZE * 0.6)
check("and centred", abs((one[0] + one[2]) // 2 - SIZE // 2) <= 3
      and abs((one[1] + one[3]) // 2 - SIZE // 2) <= 3)

# Two characters must both be there, side by side, not overlapping.
two = render("21")
left_half = any(two[y * SIZE + x] for y in range(SIZE) for x in range(SIZE // 2))
right_half = any(two[y * SIZE + x] for y in range(SIZE) for x in range(SIZE // 2, SIZE))
check("two cards draw on both sides", left_half and right_half)
check("a longer word is still smaller than a shorter one",
      (bounds(render("123"))[2] - bounds(render("123"))[0]) >= (one[2] - one[0]))

# Nothing to draw must leave a clean map rather than a half-drawn one.
check("nothing drawable leaves it blank", bounds(render("~~~")) is None)

# ------------------------------------------------------- handing it over safely
paint = re.search(r"public static boolean paint\(int mapId, byte\[\] pixels\) \{(.*?)\n    \}",
                  art, re.S).group(1)
# Reading and painting share the one uncertain lookup, so the promises about it are asked
# of the pair rather than of whichever half happens to hold the line today.
look = re.search(r"private static byte\[\] pixelsOf\(ClientWorld world, int mapId\)"
                 r".*?\n    \}", art, re.S).group(0)
handover = paint + look
check("a wrong-sized picture is refused", "pixels.length != SIZE * SIZE" in paint)
check("no world is refused", "world == null" in paint)
# The whole point of doing this reflectively: a version it cannot read must not crash.
check("nothing thrown escapes", "catch (ReflectiveOperationException | RuntimeException" in paint)
check("and every way out leaves a reason", handover.count("lastReason =") >= 5)
check("the pixels are found by type, not by name",
      "field.getType() == byte[].class" in art and '"colors"' not in art)
check("a map the client has never seen is named as such",
      "the client has no map" in handover and "Hold a real map" in handover)

# It has to be readable afterwards, or a failure is invisible again.
check("the reason is exposed", "public static String lastReason()" in art)
check("the doctor reads it back", "MapArt.lastReason()" in mc)
check("and the command says it", "error(context, MapArt.lastReason())" in mc)
check("there is a command", 'literal("map")' in mc and "MapArt.render(" in mc)

# ------------------------------------------------------------------ the library
# Real map art is thousands of placed blocks: makeable in a world of your own, not on
# somebody else's. So a design is lifted off the map it was built on and put back elsewhere.
check("a design can be taken off a map", "public static boolean save(String name, int mapId)" in art)
check("and put onto another", "public static boolean load(String name, int mapId)" in art)
check("and copied straight across", "public static boolean copy(int from, int to)" in art)

read = re.search(r"public static byte\[\] read\(int mapId\) \{(.*?)\n    \}", art, re.S).group(1)
# Reading must hand back a copy: the array it finds is the one the game renders from, so
# keeping it would make every saved design a live view of one map rather than a picture.
check("reading takes a copy", "live.clone()" in read)
check("but painting writes into the live one", "System.arraycopy(pixels, 0, live, 0" in paint)

# The size is what makes a design a design; anything else painted would be a stripe.
loader = re.search(r"public static void load\(\) \{(.*?)\n    \}", art, re.S).group(1)
check("a design that is not map-sized is dropped", "pixels.length == SIZE * SIZE" in loader)
check("designs survive a restart", "mirage-maps.json" in art
      and "Base64.getEncoder()" in art and "Base64.getDecoder()" in art)
check("and are read at startup", "MapArt.load();" in mc)
check("saving writes at once", "persist();" in re.search(
      r"public static boolean save\(String name, int mapId\) \{(.*?)\n    \}", art, re.S).group(1))

for command in ("save", "load", "copy", "forget", "list"):
    check("there is a %s command" % command, 'literal("%s")' % command in mc)
check("the names are suggested", "CommandSource.suggestMatching(MapArt.names(), builder)" in mc)

print("FAILED: " + "; ".join(fails) if fails else
      "%d glyphs, all distinct; text of 1-3 stays on the map with its margin, drawn %d px "
      "tall for one card; the hand-over cannot throw and always says why; designs "
      "save and load whole"
      % (len(FONT), one[3] - one[1] + 1))
sys.exit(1 if fails else 0)
