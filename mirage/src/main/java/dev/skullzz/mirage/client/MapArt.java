package dev.skullzz.mirage.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

import dev.skullzz.mirage.Mirage;

/**
 * Numbers and short words drawn onto a map, on your screen only.
 *
 * <p>A map's picture belongs to the server: the client renders map N from pixels the server
 * sent it, and has none for an id it has never been told about. So this does not invent a
 * map -- it repaints one you already own. Craft a map, look at it once so the client has it,
 * and its picture becomes whatever you say.
 *
 * <p>The drawing is ordinary arithmetic on a 128x128 grid of map colour indices, and is
 * checked as such. Handing that grid to the client is the only part that touches Minecraft,
 * and the call for it has moved between versions, so it is found by reflection rather than
 * named: a wrong guess would be a mod that does not build, and this way it is at worst a mod
 * that says exactly what it could not find.
 */
public final class MapArt {
    /** Maps are 128 by 128, and have been since they existed. */
    public static final int SIZE = 128;

    /**
     * Map colour bytes, not block colours.
     *
     * <p>A map pixel is {@code colour * 4 + shade}, and the numbers are the on-disk format
     * rather than an API, which is why they can be written down. 8 is white and 29 is the
     * near-black; both at shade 2, the unshaded one. Configurable because a wrong-looking
     * colour should be one number to change rather than a new build.
     */
    public static final byte WHITE = (byte) (8 * 4 + 2);
    public static final byte BLACK = (byte) (29 * 4 + 2);
    public static final byte RED = (byte) (16 * 4 + 2);
    public static final byte GREEN = (byte) (4 * 4 + 2);

    /** Five wide, seven tall, one bit per pixel, top row first. */
    private static final Map<Character, int[]> FONT = new LinkedHashMap<>();

    static {
        glyph('0', 0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110);
        glyph('1', 0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110);
        glyph('2', 0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111);
        glyph('3', 0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110);
        glyph('4', 0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010);
        glyph('5', 0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110);
        glyph('6', 0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110);
        glyph('7', 0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000);
        glyph('8', 0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110);
        glyph('9', 0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100);
        glyph('A', 0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001);
        glyph('J', 0b00111, 0b00010, 0b00010, 0b00010, 0b00010, 0b10010, 0b01100);
        glyph('K', 0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001);
        glyph('Q', 0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101);
        glyph('X', 0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001);
        glyph('?', 0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b00000, 0b00100);
        glyph('-', 0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000);
        glyph(' ', 0, 0, 0, 0, 0, 0, 0);
    }

    private static void glyph(char letter, int... rows) {
        FONT.put(letter, rows);
    }

    private MapArt() {
    }

    /** Whether anything can be drawn for a character. */
    public static boolean canDraw(char letter) {
        return FONT.containsKey(Character.toUpperCase(letter));
    }

    /**
     * Draws up to a few characters as large as they will go, centred.
     *
     * <p>The scale is worked out from the text rather than fixed, so one character fills the
     * map and three share it, and both are as big as the space allows.
     */
    public static byte[] render(String text, byte ink, byte paper) {
        byte[] pixels = new byte[SIZE * SIZE];
        java.util.Arrays.fill(pixels, paper);

        String upper = text == null ? "" : text.toUpperCase(java.util.Locale.ROOT);
        StringBuilder drawable = new StringBuilder();
        for (int i = 0; i < upper.length(); i++) {
            if (canDraw(upper.charAt(i))) drawable.append(upper.charAt(i));
        }
        if (drawable.length() == 0) return pixels;

        // A margin so nothing touches the edge, then the largest whole scale that fits both
        // ways. Whole rather than fractional: half a pixel of a five-wide glyph is a smudge.
        int margin = 8;
        int room = SIZE - margin * 2;
        int wide = drawable.length() * 5 + (drawable.length() - 1);
        int scale = Math.max(1, Math.min(room / wide, room / 7));

        int drawnWidth = wide * scale;
        int drawnHeight = 7 * scale;
        int left = (SIZE - drawnWidth) / 2;
        int top = (SIZE - drawnHeight) / 2;

        for (int index = 0; index < drawable.length(); index++) {
            int[] rows = FONT.get(drawable.charAt(index));
            int originX = left + index * 6 * scale;

            for (int row = 0; row < 7; row++) {
                for (int column = 0; column < 5; column++) {
                    // Rows are written most significant bit first, so column 0 is bit 4.
                    if ((rows[row] & (1 << (4 - column))) == 0) continue;
                    fill(pixels, originX + column * scale, top + row * scale, scale, ink);
                }
            }
        }
        return pixels;
    }

    private static void fill(byte[] pixels, int x, int y, int size, byte colour) {
        for (int dy = 0; dy < size; dy++) {
            int row = y + dy;
            if (row < 0 || row >= SIZE) continue;

            for (int dx = 0; dx < size; dx++) {
                int column = x + dx;
                if (column < 0 || column >= SIZE) continue;
                pixels[row * SIZE + column] = colour;
            }
        }
    }

    // ------------------------------------------------------------------ handing it over

    /** What the last attempt to hand a picture over did, for the doctor to read back. */
    private static String lastReason = "nothing painted yet";

    public static String lastReason() {
        return lastReason;
    }

    /**
     * Puts a picture onto a map the client already has.
     *
     * <p>Everything here is reflective on purpose. Fetching a map's state and marking it
     * changed have both moved between versions, and the pixel array is a field rather than
     * anything with a name worth relying on -- but it is the only {@code byte[]} on the
     * class, which is a stronger test than its name anyway. Nothing throws: a version this
     * cannot find leaves the map alone and says so.
     *
     * @return whether the map now shows it.
     */
    public static boolean paint(int mapId, byte[] pixels) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) {
            lastReason = "not in a world";
            return false;
        }
        if (pixels.length != SIZE * SIZE) {
            lastReason = "a map is " + SIZE + "x" + SIZE + ", not " + pixels.length + " pixels";
            return false;
        }

        try {
            byte[] live = pixelsOf(world, mapId);
            if (live == null) return false;

            System.arraycopy(pixels, 0, live, 0, pixels.length);
            markChanged(mapState(world, mapId));

            lastReason = "painted map " + mapId;
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            lastReason = "could not paint map " + mapId + ": " + failure;
            Mirage.LOGGER.warn("Mirage could not paint a map", failure);
            return false;
        }
    }

    /**
     * A copy of what a map is showing, or null if the client does not have it.
     *
     * <p>The same lookup as painting, read rather than written. Copying a design out of a map
     * you made is the point: the picture is already there, and the mod has no business
     * inventing what it can simply take.
     */
    public static byte[] read(int mapId) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) {
            lastReason = "not in a world";
            return null;
        }

        try {
            byte[] live = pixelsOf(world, mapId);
            if (live == null) return null;

            lastReason = "read map " + mapId;
            return live.clone();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            lastReason = "could not read map " + mapId + ": " + failure;
            Mirage.LOGGER.warn("Mirage could not read a map", failure);
            return null;
        }
    }

    /**
     * The live pixel array of a map the client has, or null with a reason set.
     *
     * <p>Live rather than a copy on purpose: painting writes straight into it, which is what
     * makes the change show without having to put a whole map state back.
     */
    private static byte[] pixelsOf(ClientWorld world, int mapId)
            throws ReflectiveOperationException {
        Object state = mapState(world, mapId);
        if (state == null) {
            lastReason = "the client has no map " + mapId + " yet. Hold a real map, look"
                    + " at it once so the server sends it, then use that map's id.";
            return null;
        }

        Field colours = colourField(state.getClass());
        if (colours == null) {
            lastReason = "found map " + mapId + " but not its pixels (no byte[] field on "
                    + state.getClass().getSimpleName() + ")";
            return null;
        }

        colours.setAccessible(true);
        byte[] live = (byte[]) colours.get(state);
        if (live == null || live.length != SIZE * SIZE) {
            lastReason = "map " + mapId + " has " + (live == null ? "no" : live.length)
                    + " pixels, not " + (SIZE * SIZE);
            return null;
        }
        return live;
    }

    /** The client's copy of a map, by whichever name this version asks for it. */
    private static Object mapState(ClientWorld world, int mapId) throws ReflectiveOperationException {
        for (Method method : world.getClass().getMethods()) {
            if (!method.getName().toLowerCase(java.util.Locale.ROOT).contains("mapstate")) continue;
            if (method.getParameterCount() != 1) continue;

            Class<?> parameter = method.getParameterTypes()[0];
            Object key = keyFor(parameter, mapId);
            if (key == null) continue;

            Object found = method.invoke(world, key);
            if (found != null) return found;
        }
        return null;
    }

    /** A map id in whatever shape the lookup wants: the wrapper, a string, or the number. */
    private static Object keyFor(Class<?> parameter, int mapId) {
        if (parameter == int.class || parameter == Integer.class) return mapId;
        if (parameter == String.class) return "map_" + mapId;

        for (Method factory : parameter.getMethods()) {
            if (factory.getParameterCount() == 1 && factory.getReturnType() == parameter
                    && factory.getParameterTypes()[0] == int.class) {
                try {
                    return factory.invoke(null, mapId);
                } catch (ReflectiveOperationException ignored) {
                    // not the factory we hoped for; try the next
                }
            }
        }
        try {
            return parameter.getConstructor(int.class).newInstance(mapId);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /** The pixels: the only byte[] the class has, which beats trusting a field name. */
    private static Field colourField(Class<?> type) {
        for (Class<?> level = type; level != null; level = level.getSuperclass()) {
            for (Field field : level.getDeclaredFields()) {
                if (field.getType() == byte[].class) return field;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------ the library

    /**
     * Designs taken off maps and kept, by name.
     *
     * <p>The reason this exists rather than a bigger font: real map art is made by placing
     * thousands of blocks, which you can do in a world of your own and cannot do on somebody
     * else's. So a design is lifted from the map you built it on, kept in a file, and put
     * back onto a map you own wherever you are -- the same trade the whole builds feature
     * makes, one map at a time.
     */
    private static final Map<String, byte[]> designs = new LinkedHashMap<>();

    public static java.util.Set<String> names() {
        return designs.keySet();
    }

    public static boolean has(String name) {
        return designs.containsKey(name);
    }

    public static boolean forget(String name) {
        boolean had = designs.remove(name) != null;
        if (had) persist();
        return had;
    }

    /** Takes what a map is showing and keeps it under a name. */
    public static boolean save(String name, int mapId) {
        byte[] pixels = read(mapId);
        if (pixels == null) return false;

        designs.put(name, pixels);
        persist();
        lastReason = "saved map " + mapId + " as '" + name + "'";
        return true;
    }

    /** Puts a kept design onto a map the client has. */
    public static boolean load(String name, int mapId) {
        byte[] pixels = designs.get(name);
        if (pixels == null) {
            lastReason = "no design called '" + name + "'";
            return false;
        }
        return paint(mapId, pixels);
    }

    /** Copies one map's picture straight onto another, without keeping it. */
    public static boolean copy(int from, int to) {
        byte[] pixels = read(from);
        return pixels != null && paint(to, pixels);
    }

    private static java.nio.file.Path file() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("mirage-maps.json");
    }

    /**
     * Writes the designs out.
     *
     * <p>Base64 rather than a list of numbers: a design is sixteen thousand pixels, and as
     * text that is a file nobody can open and a parse nobody should pay for.
     */
    public static void persist() {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        for (Map.Entry<String, byte[]> entry : designs.entrySet()) {
            root.addProperty(entry.getKey(),
                    java.util.Base64.getEncoder().encodeToString(entry.getValue()));
        }

        try {
            java.nio.file.Files.createDirectories(file().getParent());
            java.nio.file.Files.writeString(file(), root.toString());
        } catch (java.io.IOException failure) {
            Mirage.LOGGER.warn("Mirage could not write the map designs", failure);
        }
    }

    public static void load() {
        designs.clear();
        if (!java.nio.file.Files.exists(file())) return;

        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser
                    .parseString(java.nio.file.Files.readString(file()));
            if (!parsed.isJsonObject()) return;

            for (Map.Entry<String, com.google.gson.JsonElement> entry
                    : parsed.getAsJsonObject().entrySet()) {
                byte[] pixels = java.util.Base64.getDecoder()
                        .decode(entry.getValue().getAsString());
                // A design that is not a map is not a design. Dropping it beats painting a
                // stripe of whatever length it happened to be.
                if (pixels.length == SIZE * SIZE) designs.put(entry.getKey(), pixels);
            }
            Mirage.LOGGER.info("Mirage loaded {} map designs", designs.size());
        } catch (java.io.IOException | RuntimeException failure) {
            Mirage.LOGGER.warn("Mirage could not read the map designs", failure);
        }
    }

    /** Tells the renderer the picture changed, by whichever name this version uses. */
    private static void markChanged(Object state) {
        for (String name : new String[] { "markDirty", "setDirty", "setColor" }) {
            try {
                Method method = state.getClass().getMethod(name);
                method.invoke(state);
                return;
            } catch (ReflectiveOperationException ignored) {
                // Not this one. A version with none of them still repaints on its own when
                // the map is next drawn, so this is an improvement rather than a necessity.
            }
        }
    }
}
