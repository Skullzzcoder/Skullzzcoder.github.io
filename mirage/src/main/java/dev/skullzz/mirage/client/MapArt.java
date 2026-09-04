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

    /**
     * The id of the map in the player's hand, or -1.
     *
     * <p>Finding a map's id otherwise means turning on a debug overlay and squinting, which
     * is a poor first step for the commonest case by far: the art is in your hand and you
     * want that one. The number inside the component is read by type rather than by an
     * accessor name, for the same reason the pixels are.
     */
    public static int heldMapId() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return -1;

        for (net.minecraft.util.Hand hand : net.minecraft.util.Hand.values()) {
            net.minecraft.item.ItemStack stack = client.player.getStackInHand(hand);
            Object component = stack.get(net.minecraft.component.DataComponentTypes.MAP_ID);
            if (component == null) continue;

            int id = numberIn(component);
            if (id >= 0) return id;
        }
        return -1;
    }

    /** The single int a map-id component wraps, whatever its accessor is called here. */
    private static int numberIn(Object component) {
        for (Method method : component.getClass().getMethods()) {
            if (method.getParameterCount() != 0) continue;
            if (method.getReturnType() != int.class) continue;
            // Every object has these and neither is the map id.
            if (method.getName().equals("hashCode")) continue;

            try {
                return (int) method.invoke(component);
            } catch (ReflectiveOperationException ignored) {
                // Try the next candidate.
            }
        }
        return -1;
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

    /** Where pictures to import are looked for. */
    public static java.nio.file.Path pictureFolder() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("mirage-pictures");
    }

    /**
     * Every folder a picture is looked for in, best first.
     *
     * <p>Finding one folder on disk is a worse problem than it sounds, and it is not the
     * player's problem to solve: the mod's own folder is first, then the three places a
     * downloaded picture actually lands, then the home folder itself. A name alone is
     * enough if the file is in any of them.
     */
    public static java.util.List<java.nio.file.Path> places() {
        java.util.List<java.nio.file.Path> folders = new java.util.ArrayList<>();
        folders.add(pictureFolder());

        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            java.nio.file.Path base = java.nio.file.Paths.get(home);
            folders.add(base.resolve("Desktop"));
            folders.add(base.resolve("Downloads"));
            folders.add(base.resolve("Pictures"));
            folders.add(base);
        }
        return folders;
    }

    /** The same folders, written out for a message that has to be actionable. */
    public static String describePlaces() {
        java.util.List<String> shown = new java.util.ArrayList<>();
        for (java.nio.file.Path folder : places()) shown.add(folder.toString());
        return String.join(", ", shown);
    }

    /**
     * Turns whatever was typed into a file on disk, or nothing.
     *
     * <p>A whole path is taken as it stands, so a picture never has to be moved at all;
     * otherwise the name is looked for in each of the usual folders. An unusable path is
     * the same answer as a missing file -- on Windows a stray character throws rather
     * than returning, and a thrown import helps nobody.
     */
    public static java.nio.file.Path findPicture(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;

        String cleaned = fileName.trim();
        // Quotes survive a copied path on both Windows and macOS; drop them rather than
        // failing on a path the player pasted exactly as their file manager gave it.
        if (cleaned.length() > 1 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.startsWith("~")) {
            String home = System.getProperty("user.home");
            if (home != null) cleaned = home + cleaned.substring(1);
        }

        try {
            java.nio.file.Path given = java.nio.file.Paths.get(cleaned);
            if (given.isAbsolute() && java.nio.file.Files.isRegularFile(given)) return given;

            for (java.nio.file.Path folder : places()) {
                java.nio.file.Path candidate = folder.resolve(cleaned);
                if (java.nio.file.Files.isRegularFile(candidate)) return candidate;
            }
        } catch (java.nio.file.InvalidPathException | RuntimeException ignored) {
            // Not a path this system can express, which is a missing file by another name.
        }
        return null;
    }

    /**
     * Turns an image file into a design.
     *
     * <p>The only way in that needs nothing in the game at all. Reading and scaling the image
     * is the JDK's own work, and matching each pixel to a map colour is arithmetic against a
     * written-down table -- so a picture off the internet becomes a design without the game
     * being involved until you put it on a map.
     *
     * <p>Anything see-through stays see-through: a map can hold nothing at a pixel, and a
     * logo with a cut-out background should keep it rather than gain a white square.
     */
    public static boolean importPicture(String fileName, String name) {
        java.nio.file.Path path = findPicture(fileName);
        if (path == null) {
            lastReason = "no picture called " + fileName + ". Looked in: "
                    + describePlaces() + ". Drop it in one of those, or give the whole path"
                    + " in quotes: /fake map import \"C:\\Users\\you\\Desktop\\art.png\" "
                    + name;
            return false;
        }

        try {
            java.awt.image.BufferedImage source = javax.imageio.ImageIO.read(path.toFile());
            if (source == null) {
                lastReason = fileName + " is not a picture this can read (png, jpg, gif, bmp)";
                return false;
            }

            byte[] pixels = quantise(scale(source));
            designs.put(name, pixels);
            persist();

            lastReason = "imported " + fileName + " as '" + name + "'";
            return true;
        } catch (java.io.IOException | RuntimeException failure) {
            lastReason = "could not read " + fileName + ": " + failure;
            Mirage.LOGGER.warn("Mirage could not import a picture", failure);
            return false;
        }
    }

    /** Squashes any picture to the one size a map has, smoothly. */
    private static java.awt.image.BufferedImage scale(java.awt.image.BufferedImage source) {
        java.awt.image.BufferedImage square =
                new java.awt.image.BufferedImage(SIZE, SIZE,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);

        java.awt.Graphics2D pen = square.createGraphics();
        pen.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        pen.drawImage(source, 0, 0, SIZE, SIZE, null);
        pen.dispose();
        return square;
    }

    /** Matches every pixel to the nearest colour a map can hold. */
    private static byte[] quantise(java.awt.image.BufferedImage image) {
        byte[] pixels = new byte[SIZE * SIZE];

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;

                // Mostly see-through is see-through. A map has no half-way.
                pixels[y * SIZE + x] = alpha < 128 ? MapPalette.CLEAR
                        : MapPalette.nearest((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
            }
        }
        return pixels;
    }

    /** The endings worth offering; anything else is not a picture ImageIO will read. */
    private static final java.util.List<String> KINDS =
            java.util.List.of(".png", ".jpg", ".jpeg", ".gif", ".bmp");

    /** How many names to offer before the list stops being a help. */
    private static final int MOST = 60;

    /**
     * What pictures are sitting where one can be imported from.
     *
     * <p>Every folder in {@link #places()}, not just the mod's own, so a picture that was
     * just downloaded turns up under tab-complete without being moved first. Pictures only,
     * and a cap: a home folder can hold thousands of files and a list that long answers
     * nothing.
     */
    public static java.util.List<String> pictures() {
        java.util.List<String> found = new java.util.ArrayList<>();

        for (java.nio.file.Path folder : places()) {
            try (java.util.stream.Stream<java.nio.file.Path> files =
                         java.nio.file.Files.list(folder)) {
                files.filter(java.nio.file.Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(MapArt::looksLikePicture)
                        .forEach(fileName -> {
                            if (!found.contains(fileName) && found.size() < MOST) {
                                found.add(fileName);
                            }
                        });
            } catch (java.io.IOException | RuntimeException ignored) {
                // A folder that is not there, or not ours to read, simply holds nothing.
            }
        }

        java.util.Collections.sort(found);
        return found;
    }

    private static java.util.List<String> cachedPictures = java.util.List.of();
    private static long cachedAt = 0L;

    /** How long a folder listing is trusted, in milliseconds. */
    private static final long CACHE_MS = 3000L;

    /**
     * The same listing, but safe to ask for constantly.
     *
     * <p>The dashboard rebuilds twenty times a second and tab-complete asks whenever it
     * feels like it; walking four folders on disk that often would be a stutter you could
     * feel. Three seconds stale is not stale for a folder a person drops files into by
     * hand, and the explicit command still scans afresh.
     */
    public static java.util.List<String> picturesCached() {
        long now = System.currentTimeMillis();
        if (now - cachedAt > CACHE_MS) {
            cachedPictures = pictures();
            cachedAt = now;
        }
        return cachedPictures;
    }

    /** Whether a name ends in something ImageIO can open. */
    private static boolean looksLikePicture(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        for (String kind : KINDS) {
            if (lower.endsWith(kind)) return true;
        }
        return false;
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
        try {
            // Made on the way in so there is somewhere obvious to drop a picture, rather
            // than a folder you have to be told the name of and create yourself.
            java.nio.file.Files.createDirectories(pictureFolder());
        } catch (java.io.IOException ignored) {
            // It can be made by hand; not being able to is not a reason to stop.
        }
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
