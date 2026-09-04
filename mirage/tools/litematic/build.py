"""Writes the .litematic files the reader is run against, and says what should come out."""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make import write, region

AIR = ('minecraft:air', None)
STONE = ('minecraft:stone', None)
GLASS = ('minecraft:glass', None)
STAIRS = ('minecraft:oak_stairs', {'facing': 'north', 'half': 'bottom', 'shape': 'straight'})
PLANKS = ('minecraft:oak_planks', None)

def simple_indices():
    idx = [0] * 18
    for z in range(3):
        for x in range(3):
            idx[(0 * 3 + z) * 3 + x] = 1
    idx[(1 * 3 + 1) * 3 + 1] = 1
    return idx

def expected_simple():
    out = ["   %d,0,%d minecraft:stone" % (x, z) for z in range(3) for x in range(3)]
    return out + ["   1,1,1 minecraft:stone"]

def build(into):
    """Returns {filename: expected harness output}, having written each file."""
    want = {}

    idx = simple_indices()
    write(os.path.join(into, 'simple.litematic'),
          [region('main', (10, 64, -20), (3, 2, 3), [AIR, STONE], idx)])
    want['simple.litematic'] = ["simple.litematic OK name=test size=3x2x3 regions=1 "
                                "palette=1 blocks=10"] + expected_simple()

    # Five entries means three bits, so entries sit across the join between longs. A reader
    # that starts each long afresh gets a different building and says nothing about it.
    pal = [AIR, STONE, PLANKS, STAIRS, GLASS]
    names = [p[0] for p in pal]
    idx5 = [(x + y + z) % 5 for y in range(5) for z in range(5) for x in range(5)]
    write(os.path.join(into, 'spanning.litematic'),
          [region('main', (0, 0, 0), (5, 5, 5), pal, idx5)])
    rows = []
    for y in range(5):
        for z in range(5):
            for x in range(5):
                v = (x + y + z) % 5
                if v == 0: continue
                extra = "{facing=north, half=bottom, shape=straight}" if v == 3 else ""
                rows.append("   %d,%d,%d %s%s" % (x, y, z, names[v], extra))
    want['spanning.litematic'] = ["spanning.litematic OK name=test size=5x5x5 regions=1 "
                                  "palette=4 blocks=%d" % len(rows)] + rows

    # A size may be written negative: the region runs back from its position, and the
    # blocks still have to come out measured from the low corner.
    write(os.path.join(into, 'negative.litematic'),
          [region('main', (10, 64, 10), (-3, 2, -3), [AIR, STONE], idx)])
    want['negative.litematic'] = ["negative.litematic OK name=test size=3x2x3 regions=1 "
                                  "palette=1 blocks=10"] + expected_simple()

    # Two regions with overlapping palettes: the same block in both must be one entry.
    write(os.path.join(into, 'two.litematic'), [
        region('a', (0, 0, 0), (2, 1, 2), [AIR, STONE], [1, 1, 1, 1]),
        region('b', (5, 3, 5), (2, 1, 2), [AIR, GLASS, STONE], [1, 2, 2, 1]),
    ])
    want['two.litematic'] = [
        "two.litematic OK name=test size=7x4x7 regions=2 palette=2 blocks=8",
        "   0,0,0 minecraft:stone", "   1,0,0 minecraft:stone",
        "   0,0,1 minecraft:stone", "   1,0,1 minecraft:stone",
        "   5,3,5 minecraft:glass", "   6,3,5 minecraft:stone",
        "   5,3,6 minecraft:stone", "   6,3,6 minecraft:glass"]

    # Every earlier case is square in x and z, so swapping the two in the index order
    # gives the same answer and proves nothing. This one is 4 wide, 2 deep and every
    # cell different, which no reordering survives.
    pal4 = [AIR, STONE, GLASS, PLANKS]
    names4 = [p[0] for p in pal4]
    # y is the outer axis, then z, then x: cell (x, z) holds 1 + ((x + 2 * z) % 3).
    idx_flat = [1 + ((x + 2 * z) % 3) for z in range(2) for x in range(4)]
    write(os.path.join(into, 'oblong.litematic'),
          [region('main', (0, 0, 0), (4, 1, 2), pal4, idx_flat)])
    rows = ["   %d,0,%d %s" % (x, z, names4[1 + ((x + 2 * z) % 3)])
            for z in range(2) for x in range(4)]
    want['oblong.litematic'] = ["oblong.litematic OK name=test size=4x1x2 regions=1 "
                                "palette=3 blocks=8"] + rows

    # A negative size in a file with only one region changes nothing anyone can see: every
    # block is measured from the low corner either way. It takes a second region to make
    # the normalising matter, and getting it wrong moves one half of the build.
    write(os.path.join(into, 'negtwo.litematic'), [
        region('anchor', (0, 0, 0), (1, 1, 1), [AIR, STONE], [1]),
        # Runs back from x=8 to x=6: the low corner is 6, not 8.
        region('back', (8, 0, 0), (-3, 1, 1), [AIR, GLASS], [1, 1, 1]),
    ])
    want['negtwo.litematic'] = [
        "negtwo.litematic OK name=test size=9x1x1 regions=2 palette=2 blocks=4",
        "   0,0,0 minecraft:stone",
        "   6,0,0 minecraft:glass", "   7,0,0 minecraft:glass", "   8,0,0 minecraft:glass"]

    # Air has three names, and a schematic cut out of a cave or the end is full of the
    # other two. Treating either as a block fills the build in solid.
    CAVE = ('minecraft:cave_air', None)
    VOID = ('minecraft:void_air', None)
    write(os.path.join(into, 'airs.litematic'),
          [region('main', (0, 0, 0), (4, 1, 1), [AIR, CAVE, VOID, STONE], [0, 1, 2, 3])])
    want['airs.litematic'] = [
        "airs.litematic OK name=test size=4x1x1 regions=1 palette=1 blocks=1",
        "   3,0,0 minecraft:stone"]

    # Broken on purpose. Each has to be refused by name rather than painted as rubbish.
    write(os.path.join(into, 'shortarray.litematic'),
          [region('main', (0, 0, 0), (3, 2, 3), [AIR, STONE], idx,
                  override_longs=[0, 0, 0, 0, 0])])
    want['shortarray.litematic'] = ["REFUSED", "needs 1 packed values", "the file holds 5"]

    write(os.path.join(into, 'badindex.litematic'),
          [region('main', (0, 0, 0), (3, 2, 3), [AIR, STONE], [3] * 18)])
    want['badindex.litematic'] = ["REFUSED", "points at palette entry 3 of 2"]

    write(os.path.join(into, 'empty.litematic'),
          [region('main', (0, 0, 0), (2, 2, 2), [AIR, STONE], [0] * 8)])
    want['empty.litematic'] = ["REFUSED", "nothing but air"]

    # A list written as "type END, but nine of them" is malformed. Without the guard the
    # reader tries to read nine payloads of a type that has none and walks off the end of
    # the file; a file is something the player was handed, so it has to be refused.
    from make import comp, ccomp, cint, cstr, clist, xyz, palette_tag, clonga, b, s as nbtstr
    import gzip, struct
    hostile = comp([
        cint('Version', 6),
        ccomp('Regions', [ccomp('main', [
            xyz('Position', 0, 0, 0), xyz('Size', 1, 1, 1),
            palette_tag([AIR, STONE]),
            clonga('BlockStates', [1]),
            clist('Entities', 0, [], count=9),
        ])]),
    ])
    with gzip.open(os.path.join(into, 'hostilelist.litematic'), 'wb') as f:
        f.write(b(10) + nbtstr('') + hostile)
    # With the guard it reads fine; without it, reading nine payloads of a type that has
    # none throws instead. So the passing case is what pins the guard.
    want['hostilelist.litematic'] = [
        "hostilelist.litematic OK name= size=1x1x1 regions=1 palette=1 blocks=1",
        "   0,0,0 minecraft:stone"]

    return want
