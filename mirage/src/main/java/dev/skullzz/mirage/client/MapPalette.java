package dev.skullzz.mirage.client;

/**
 * The colours a map can show, and the nearest one to any other colour.
 *
 * <p>Written down rather than asked of the game. These are the on-disk format of a map -- a
 * pixel is {@code base * 4 + shade} -- so they are data with a long memory, not an API that
 * moves between versions. Getting one slightly wrong costs a shade in a picture; guessing at
 * a method name costs a build that does not compile, and this file exists to trade the
 * second risk for the first.
 *
 * <p>A base that is missing from this table is simply never chosen, so a short table makes a
 * flatter picture and never a broken one.
 */
public final class MapPalette {
    /** How much of the base colour each of the four shades shows. */
    private static final int[] SHADES = { 180, 220, 255, 135 };

    /** Base colours, in map order. Index 0 is nothing at all and is never matched. */
    private static final int[][] BASES = {
        { 0, 0, 0 },        // 0  none -- transparent, and deliberately unmatched
        { 127, 178, 56 },   // 1  grass
        { 247, 233, 163 },  // 2  sand
        { 199, 199, 199 },  // 3  wool
        { 255, 0, 0 },      // 4  fire
        { 160, 160, 255 },  // 5  ice
        { 167, 167, 167 },  // 6  metal
        { 0, 124, 0 },      // 7  plant
        { 255, 255, 255 },  // 8  snow
        { 164, 168, 184 },  // 9  clay
        { 151, 109, 77 },   // 10 dirt
        { 112, 112, 112 },  // 11 stone
        { 64, 64, 255 },    // 12 water
        { 143, 119, 72 },   // 13 wood
        { 255, 252, 245 },  // 14 quartz
        { 216, 127, 51 },   // 15 orange
        { 178, 76, 216 },   // 16 magenta
        { 102, 153, 216 },  // 17 light blue
        { 229, 229, 51 },   // 18 yellow
        { 127, 204, 25 },   // 19 lime
        { 242, 127, 165 },  // 20 pink
        { 76, 76, 76 },     // 21 grey
        { 153, 153, 153 },  // 22 light grey
        { 76, 127, 153 },   // 23 cyan
        { 127, 63, 178 },   // 24 purple
        { 51, 76, 178 },    // 25 blue
        { 102, 76, 51 },    // 26 brown
        { 102, 127, 51 },   // 27 green
        { 153, 51, 51 },    // 28 red
        { 25, 25, 25 },     // 29 black
        { 250, 238, 77 },   // 30 gold
        { 92, 219, 213 },   // 31 diamond
        { 74, 128, 255 },   // 32 lapis
        { 0, 217, 58 },     // 33 emerald
        { 129, 86, 49 },    // 34 podzol
        { 112, 2, 0 },      // 35 nether
        { 209, 177, 161 },  // 36 white terracotta
        { 159, 82, 36 },    // 37 orange terracotta
        { 149, 87, 108 },   // 38 magenta terracotta
        { 112, 108, 138 },  // 39 light blue terracotta
        { 186, 133, 36 },   // 40 yellow terracotta
        { 103, 117, 53 },   // 41 lime terracotta
        { 160, 77, 78 },    // 42 pink terracotta
        { 57, 41, 35 },     // 43 grey terracotta
        { 135, 107, 98 },   // 44 light grey terracotta
        { 87, 92, 92 },     // 45 cyan terracotta
        { 122, 73, 88 },    // 46 purple terracotta
        { 76, 62, 92 },     // 47 blue terracotta
        { 76, 50, 35 },     // 48 brown terracotta
        { 76, 82, 42 },     // 49 green terracotta
        { 142, 60, 46 },    // 50 red terracotta
        { 37, 22, 16 },     // 51 black terracotta
        { 189, 48, 49 },    // 52 crimson nylium
        { 148, 63, 97 },    // 53 crimson stem
        { 92, 25, 29 },     // 54 crimson hyphae
        { 22, 126, 134 },   // 55 warped nylium
        { 58, 142, 140 },   // 56 warped stem
        { 86, 44, 62 },     // 57 warped hyphae
        { 20, 180, 133 },   // 58 warped wart
        { 100, 100, 100 },  // 59 deepslate
        { 216, 175, 147 },  // 60 raw iron
        { 127, 167, 150 },  // 61 glow lichen
    };

    /** Every byte a pixel may hold, with the colour it shows. Built once. */
    private static final byte[] BYTES;
    private static final int[][] COLOURS;

    static {
        // Base 0 is transparent and has no colour to match against, so the table starts at 1.
        int count = (BASES.length - 1) * SHADES.length;
        BYTES = new byte[count];
        COLOURS = new int[count][3];

        int at = 0;
        for (int base = 1; base < BASES.length; base++) {
            for (int shade = 0; shade < SHADES.length; shade++) {
                BYTES[at] = (byte) (base * 4 + shade);
                for (int channel = 0; channel < 3; channel++) {
                    COLOURS[at][channel] = BASES[base][channel] * SHADES[shade] / 255;
                }
                at++;
            }
        }
    }

    /** The pixel value for fully transparent. */
    public static final byte CLEAR = 0;

    private MapPalette() {
    }

    public static int size() {
        return BYTES.length;
    }

    /** The colour a pixel value shows, as {r, g, b}. */
    public static int[] colourOf(byte pixel) {
        for (int i = 0; i < BYTES.length; i++) {
            if (BYTES[i] == pixel) return COLOURS[i].clone();
        }
        return new int[] { 0, 0, 0 };
    }

    /**
     * The pixel value closest to a colour.
     *
     * <p>Nearest by plain distance in red, green and blue. Weighting the channels the way an
     * eye does would be better and is not worth the argument: a map has sixty-odd colours in
     * four shades, so almost everything is close to something.
     */
    public static byte nearest(int red, int green, int blue) {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;

        for (int i = 0; i < COLOURS.length; i++) {
            long dr = red - COLOURS[i][0];
            long dg = green - COLOURS[i][1];
            long db = blue - COLOURS[i][2];
            long distance = dr * dr + dg * dg + db * db;

            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return BYTES[best];
    }
}
