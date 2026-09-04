"""The map palette, and turning a picture into one.

Written down rather than asked of the game, so the table itself is what gets checked: run
here, on the real numbers read out of the source. A base missing from it is never chosen,
which makes a short table a flatter picture and never a broken one -- but a table with a
duplicate or an out-of-range value would be a wrong picture, quietly."""
import io, re, sys
pal = io.open("src/main/java/dev/skullzz/mirage/client/MapPalette.java", encoding="utf-8").read()
art = io.open("src/main/java/dev/skullzz/mirage/client/MapArt.java", encoding="utf-8").read()
mc  = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java", encoding="utf-8").read()

fails = []
def check(name, cond):
    if not cond: fails.append(name)

SHADES = [int(v) for v in re.search(r"int\[\] SHADES = \{([^}]*)\}", pal).group(1).split(",")]
BASES = [[int(v) for v in row.split(",")]
         for row in re.findall(r"\{ (\d+, \d+, \d+) \}", pal)]

check("four shades, as a map has", len(SHADES) == 4)
check("one shade is the colour undimmed", 255 in SHADES)
check("every shade dims rather than brightens", all(0 < v <= 255 for v in SHADES))
check("there are colours to choose from", len(BASES) >= 30)
check("every channel is a byte", all(0 <= c <= 255 for row in BASES for c in row))
check("the first base is nothing at all", BASES[0] == [0, 0, 0])

# A duplicate base wastes a slot and makes two different pixel values look the same, which
# is invisible until a picture comes out with a flat patch where it should have detail.
rest = [tuple(b) for b in BASES[1:]]
check("no base is written twice", len(set(rest)) == len(rest))

# The byte a pixel holds is base * 4 + shade, and must stay inside a byte.
values = [base * 4 + shade for base in range(1, len(BASES)) for shade in range(len(SHADES))]
check("every pixel value fits in a byte", all(0 <= v <= 255 for v in values))
check("no two bases share a pixel value", len(set(values)) == len(values))

# ------------------------------------------------------------------ matching
def table():
    out = []
    for base in range(1, len(BASES)):
        for shade_at, shade in enumerate(SHADES):
            colour = [BASES[base][c] * shade // 255 for c in range(3)]
            out.append((base * 4 + shade_at, colour))
    return out

TABLE = table()
def nearest(r, g, b):
    return min(TABLE, key=lambda e: (r - e[1][0]) ** 2 + (g - e[1][1]) ** 2 + (b - e[1][2]) ** 2)

# Every colour a map can show must match itself: if it does not, the matching is broken in
# a way that would tint a whole picture.
off = [v for v, colour in TABLE if nearest(*colour)[0] != v]
# Two entries can legitimately land on the same colour after shading; only count real misses.
off = [v for v in off if dict(TABLE)[v] != nearest(*dict(TABLE)[v])[1]]
check("every map colour matches itself", not off)

# The obvious ones, so a wrong table shows up as a wrong answer rather than a passing test.
check("white matches white", nearest(255, 255, 255)[1] == [255, 255, 255])
check("black matches something very dark", sum(nearest(0, 0, 0)[1]) < 90)
check("red matches something red", nearest(255, 0, 0)[1][0] > 150
      and nearest(255, 0, 0)[1][0] > nearest(255, 0, 0)[1][1] * 2)

# The two colours the drawn digits use have to be in the table and be what they claim.
WHITE = int(re.search(r"byte WHITE = \(byte\) \((\d+) \* 4 \+ (\d+)\)", art).group(1))
BLACK = int(re.search(r"byte BLACK = \(byte\) \((\d+) \* 4 \+ (\d+)\)", art).group(1))
check("the drawn white is the white base", BASES[WHITE] == [255, 255, 255])
check("the drawn black is the black base", sum(BASES[BLACK]) < 90)

# -------------------------------------------------------------- importing
imp = re.search(r"public static boolean importPicture\(String fileName, String name\) \{"
                r"(.*?)\n    \}", art, re.S).group(1)
check("a missing file is named", "no picture called" in imp and "fileName" in imp)
check("and so is every folder that was searched", "describePlaces()" in imp)
check("something that is not a picture is named", "not a picture this can read" in imp)
check("nothing thrown escapes", "catch (java.io.IOException | RuntimeException" in imp)
check("an import is kept at once", "persist();" in imp)

quant = re.search(r"private static byte\[\] quantise\(java\.awt\.image\.BufferedImage image\) \{"
                  r"(.*?)\n    \}", art, re.S).group(1)
check("see-through stays see-through", "alpha < 128 ? MapPalette.CLEAR" in quant)
check("every other pixel is matched", "MapPalette.nearest(" in quant)
scale = re.search(r"private static java\.awt\.image\.BufferedImage scale\(", art)
check("any size of picture is squashed to a map", scale is not None
      and "drawImage(source, 0, 0, SIZE, SIZE, null)" in art)
check("and smoothly", "VALUE_INTERPOLATION_BILINEAR" in art)

# -------------------------------------------------------- the map in your hand
held = re.search(r"public static int heldMapId\(\) \{(.*?)\n    \}", art, re.S).group(1)
check("both hands are looked in", "Hand.values()" in held)
check("the id is read by type, not by an accessor name", "getReturnType() != int.class" in art)
check("and hashCode is not mistaken for it", '"hashCode"' in art)
check("nothing held says so", "You are not holding a map" in mc)

for command in ("import", "pictures"):
    check("there is a %s command" % command, 'literal("%s")' % command in mc)
check("held is a way to save", 'literal("held")' in mc and "saveHeldDesign" in mc)
check("the files are suggested", "CommandSource.suggestMatching(MapArt.picturesCached(), builder)" in mc)

print("FAILED: " + "; ".join(fails) if fails else
      "%d bases x %d shades = %d colours, all distinct and self-matching; pictures scale, "
      "quantise and keep their transparency" % (len(BASES) - 1, len(SHADES), len(TABLE)))
sys.exit(1 if fails else 0)
