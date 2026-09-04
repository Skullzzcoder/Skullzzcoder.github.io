package dev.skullzz.mirage.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Litematica schematic, turned into positions and block names.
 *
 * <p>Deliberately knows nothing about Minecraft. The awkward half of this format is the
 * bit-packing, and keeping that in plain Java is what lets it be run against a real file
 * on a machine with no game on it -- which for a format read from memory is the difference
 * between "it should work" and "it does".
 *
 * <p>The format's own rules are used as the check on the reading. Every packed index must
 * land inside the palette and the packed array must be exactly as long as the volume needs;
 * if either fails the file is refused by name rather than painted as rubbish, because a
 * schematic read the wrong way does not look wrong, it looks like a different building.
 */
public final class Litematic {

    /** One entry of a region's palette: a block and the properties that pick its variant. */
    public static final class Entry {
        public final String name;
        public final Map<String, String> properties;

        Entry(String name, Map<String, String> properties) {
            this.name = name;
            this.properties = properties;
        }

        /** The key two identical entries from different regions share. */
        String key() {
            return this.name + this.properties;
        }
    }

    public final String name;
    public final String author;
    public final List<Entry> palette;
    /** Four numbers per block: x, y, z from the low corner, then which palette entry. */
    public final int[] blocks;
    public final int width;
    public final int height;
    public final int depth;
    public final int regions;

    private Litematic(String name, String author, List<Entry> palette, int[] blocks,
                      int width, int height, int depth, int regions) {
        this.name = name;
        this.author = author;
        this.palette = palette;
        this.blocks = blocks;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.regions = regions;
    }

    public int count() {
        return this.blocks.length / 4;
    }

    public String size() {
        return this.width + "x" + this.height + "x" + this.depth;
    }

    /** Nothing to paint: these are what an empty cell is called. */
    static boolean isAir(String block) {
        return block.equals("minecraft:air") || block.equals("minecraft:cave_air")
                || block.equals("minecraft:void_air") || block.equals("air");
    }

    /**
     * Reads a parsed file into blocks.
     *
     * @param limit the most blocks to accept, so a huge schematic is refused rather than
     *              swallowed
     */
    public static Litematic parse(Map<String, Object> root, int limit) throws IOException {
        Map<String, Object> regions = Nbt.compound(root, "Regions");
        if (regions == null || regions.isEmpty()) {
            throw new IOException("no Regions in this file -- is it a .litematic?");
        }

        Map<String, Object> metadata = Nbt.compound(root, "Metadata");
        String name = Nbt.string(metadata, "Name", "");
        String author = Nbt.string(metadata, "Author", "");

        // Read every region first: the low corner of the whole thing is not known until
        // all of them have been seen, and every block is measured from it.
        List<Region> read = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, Object> entry : regions.entrySet()) {
            if (!(entry.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) entry.getValue();

            Region region = readRegion(entry.getKey(), body);
            if (region == null) continue;
            read.add(region);
            total += region.solid;
        }

        if (read.isEmpty()) throw new IOException("every region in this file was unreadable");
        if (total == 0) throw new IOException("this schematic is nothing but air");
        if (total > limit) {
            throw new IOException(total + " blocks, over the " + limit + " limit");
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Region region : read) {
            minX = Math.min(minX, region.minX);
            minY = Math.min(minY, region.minY);
            minZ = Math.min(minZ, region.minZ);
            maxX = Math.max(maxX, region.minX + region.width - 1);
            maxY = Math.max(maxY, region.minY + region.height - 1);
            maxZ = Math.max(maxZ, region.minZ + region.depth - 1);
        }

        // Regions each carry their own palette, and the same block in two of them must not
        // become two entries -- a merged palette is what the rest of the mod expects.
        List<Entry> palette = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        int[] blocks = new int[(int) total * 4];
        int at = 0;

        for (Region region : read) {
            for (int y = 0; y < region.height; y++) {
                for (int z = 0; z < region.depth; z++) {
                    for (int x = 0; x < region.width; x++) {
                        int cell = region.indices[(y * region.depth + z) * region.width + x];
                        Entry entry = region.palette.get(cell);
                        if (isAir(entry.name)) continue;

                        Integer known = index.get(entry.key());
                        if (known == null) {
                            known = palette.size();
                            index.put(entry.key(), known);
                            palette.add(entry);
                        }

                        blocks[at++] = region.minX + x - minX;
                        blocks[at++] = region.minY + y - minY;
                        blocks[at++] = region.minZ + z - minZ;
                        blocks[at++] = known;
                    }
                }
            }
        }

        // The count is worked out twice, once to size the array and once while filling it.
        // They must agree, or something read differently the second time.
        if (at != blocks.length) {
            throw new IOException("counted " + (blocks.length / 4) + " blocks but found "
                    + (at / 4) + "; the file did not read the same way twice");
        }

        return new Litematic(name, author, palette, blocks,
                maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, read.size());
    }

    // ------------------------------------------------------------------- a region

    private static final class Region {
        int minX, minY, minZ;
        int width, height, depth;
        List<Entry> palette;
        int[] indices;
        int solid;
    }

    private static Region readRegion(String name, Map<String, Object> body) throws IOException {
        Map<String, Object> position = Nbt.compound(body, "Position");
        Map<String, Object> size = Nbt.compound(body, "Size");
        List<Object> paletteTag = Nbt.list(body, "BlockStatePalette");
        long[] packed = Nbt.longs(body, "BlockStates");

        if (position == null || size == null || paletteTag == null || packed == null) {
            return null;
        }

        int sizeX = Nbt.number(size, "x", 0);
        int sizeY = Nbt.number(size, "y", 0);
        int sizeZ = Nbt.number(size, "z", 0);
        if (sizeX == 0 || sizeY == 0 || sizeZ == 0) return null;

        Region region = new Region();
        region.width = Math.abs(sizeX);
        region.height = Math.abs(sizeY);
        region.depth = Math.abs(sizeZ);

        // A size may be written negative, meaning the region runs the other way from its
        // position. The blocks are stored from the low corner either way, so that is what
        // gets worked out here.
        region.minX = low(Nbt.number(position, "x", 0), sizeX);
        region.minY = low(Nbt.number(position, "y", 0), sizeY);
        region.minZ = low(Nbt.number(position, "z", 0), sizeZ);

        region.palette = new ArrayList<>();
        for (Object element : paletteTag) {
            if (!(element instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> state = (Map<String, Object>) element;

            Map<String, String> properties = new LinkedHashMap<>();
            Map<String, Object> written = Nbt.compound(state, "Properties");
            if (written != null) {
                for (Map.Entry<String, Object> property : written.entrySet()) {
                    if (property.getValue() instanceof String) {
                        properties.put(property.getKey(), (String) property.getValue());
                    }
                }
            }
            region.palette.add(new Entry(Nbt.string(state, "Name", "minecraft:air"), properties));
        }

        if (region.palette.isEmpty()) {
            throw new IOException("region '" + name + "' has an empty palette");
        }

        long volume = (long) region.width * region.height * region.depth;
        if (volume > Integer.MAX_VALUE) {
            throw new IOException("region '" + name + "' is too big to read");
        }

        int bits = bitsFor(region.palette.size());
        long needed = (volume * bits + 63) / 64;
        if (packed.length != needed) {
            throw new IOException("region '" + name + "' says " + region.width + "x"
                    + region.height + "x" + region.depth + " with a palette of "
                    + region.palette.size() + ", which needs " + needed
                    + " packed values, but the file holds " + packed.length
                    + ". This is not a Litematica schematic this version can read.");
        }

        region.indices = new int[(int) volume];
        for (int i = 0; i < volume; i++) {
            int value = at(packed, i, bits);
            if (value < 0 || value >= region.palette.size()) {
                throw new IOException("region '" + name + "' points at palette entry "
                        + value + " of " + region.palette.size()
                        + ", so it was not unpacked correctly");
            }
            region.indices[i] = value;
            if (!isAir(region.palette.get(value).name)) region.solid++;
        }
        return region;
    }

    /** The low end of a run that starts at a position and may go either way. */
    static int low(int position, int size) {
        return Math.min(position, position + (size > 0 ? size - 1 : size + 1));
    }

    /**
     * How many bits one palette index takes.
     *
     * <p>Never fewer than two, which is the format's own floor -- a one-entry palette still
     * spends two bits a block.
     */
    static int bitsFor(int paletteSize) {
        int bits = 2;
        while ((1 << bits) < paletteSize) bits++;
        return bits;
    }

    /**
     * One packed index.
     *
     * <p>Entries run straight through the array and may sit across the join between two
     * longs, which is the older packing and the one this format kept. Reading it as though
     * each long started a fresh entry gives a building that is subtly, wholly wrong.
     */
    static int at(long[] packed, int index, int bits) {
        long mask = (1L << bits) - 1L;
        long start = (long) index * bits;
        int first = (int) (start >> 6);
        int last = (int) (((long) (index + 1) * bits - 1) >> 6);
        int offset = (int) (start & 63);

        if (first == last) {
            return (int) ((packed[first] >>> offset) & mask);
        }
        return (int) (((packed[first] >>> offset) | (packed[last] << (64 - offset))) & mask);
    }

    private Litematic() {
        this(null, null, null, null, 0, 0, 0, 0);
    }
}
