package dev.skullzz.mirage.client;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reading NBT, by hand.
 *
 * <p>Written down rather than asked of the game, for the same reason the map palette is:
 * this is a file format, not an API. Twelve tag types, big-endian, unchanged since 2011 --
 * whereas the class that reads it inside Minecraft has moved more than once, and a guessed
 * method name there is a build that will not compile. A format written down can only be
 * wrong about the format.
 *
 * <p>Everything comes out as plain Java: a compound is a Map, a list is a List, and the
 * rest are the boxes you would expect. Nothing here knows what a schematic is.
 */
public final class Nbt {

    private static final int END = 0;
    private static final int BYTE = 1;
    private static final int SHORT = 2;
    private static final int INT = 3;
    private static final int LONG = 4;
    private static final int FLOAT = 5;
    private static final int DOUBLE = 6;
    private static final int BYTE_ARRAY = 7;
    private static final int STRING = 8;
    private static final int LIST = 9;
    private static final int COMPOUND = 10;
    private static final int INT_ARRAY = 11;
    private static final int LONG_ARRAY = 12;

    /**
     * How deep a file may nest.
     *
     * <p>A file is something the player was handed, and a hand-made one can nest until the
     * stack gives out. A limit turns a crash into a message.
     */
    private static final int MAX_DEPTH = 64;

    /** How many entries one array or list may hold, so a bad length cannot claim the heap. */
    private static final int MAX_ENTRIES = 1 << 26;

    private Nbt() {
    }

    /** Reads a file, gzipped or not, and gives back the root compound. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(Path path) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(path))) {
            raw.mark(2);
            int first = raw.read();
            int second = raw.read();
            raw.reset();

            // 0x1f8b is gzip. Schematics are written compressed, but one that has been
            // through a tool that unpacked it should still open.
            boolean zipped = first == 0x1F && second == 0x8B;
            try (DataInputStream in = new DataInputStream(
                    zipped ? new GZIPInputStream(raw) : raw)) {
                int type = in.readUnsignedByte();
                if (type != COMPOUND) {
                    throw new IOException("not an NBT file: it starts with tag " + type
                            + " rather than a compound");
                }
                in.readUTF();
                return (Map<String, Object>) payload(in, COMPOUND, 0);
            }
        }
    }

    /** One tag's contents, once its type is known. */
    private static Object payload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested deeper than " + MAX_DEPTH);

        switch (type) {
            case BYTE: return in.readByte();
            case SHORT: return in.readShort();
            case INT: return in.readInt();
            case LONG: return in.readLong();
            case FLOAT: return in.readFloat();
            case DOUBLE: return in.readDouble();
            case STRING: return in.readUTF();

            case BYTE_ARRAY: {
                byte[] values = new byte[length(in)];
                in.readFully(values);
                return values;
            }
            case INT_ARRAY: {
                int[] values = new int[length(in)];
                for (int i = 0; i < values.length; i++) values[i] = in.readInt();
                return values;
            }
            case LONG_ARRAY: {
                long[] values = new long[length(in)];
                for (int i = 0; i < values.length; i++) values[i] = in.readLong();
                return values;
            }

            case LIST: {
                int kind = in.readUnsignedByte();
                int count = length(in);
                List<Object> items = new ArrayList<>(Math.min(count, 1024));
                // A list of nothing is written as type 0 with a count, and reading a
                // payload for it would run off the end of the file.
                if (kind == END) return items;
                for (int i = 0; i < count; i++) items.add(payload(in, kind, depth + 1));
                return items;
            }

            case COMPOUND: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int entry = in.readUnsignedByte();
                    if (entry == END) return map;
                    String name = in.readUTF();
                    map.put(name, payload(in, entry, depth + 1));
                }
            }

            default:
                throw new IOException("unknown NBT tag type " + type);
        }
    }

    /** A length from the file, refused if it could not possibly be one. */
    private static int length(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IOException("NBT claims " + count + " entries, which cannot be right");
        }
        return count;
    }

    // ------------------------------------------------------------------ reading out

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Map<String, Object> parent, String key) {
        Object found = parent == null ? null : parent.get(key);
        return found instanceof Map ? (Map<String, Object>) found : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> parent, String key) {
        Object found = parent == null ? null : parent.get(key);
        return found instanceof List ? (List<Object>) found : null;
    }

    public static long[] longs(Map<String, Object> parent, String key) {
        Object found = parent == null ? null : parent.get(key);
        return found instanceof long[] ? (long[]) found : null;
    }

    public static String string(Map<String, Object> parent, String key, String fallback) {
        Object found = parent == null ? null : parent.get(key);
        return found instanceof String ? (String) found : fallback;
    }

    /** Any of the number tags as an int, since a size may be written as any width. */
    public static int number(Map<String, Object> parent, String key, int fallback) {
        Object found = parent == null ? null : parent.get(key);
        return found instanceof Number ? ((Number) found).intValue() : fallback;
    }
}
