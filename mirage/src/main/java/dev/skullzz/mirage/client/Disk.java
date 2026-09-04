package dev.skullzz.mirage.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Finding a file the player dropped somewhere, without making them find a folder.
 *
 * <p>One copy, two callers. Pictures and schematics both want the same answer -- take a
 * whole path as it stands, otherwise look in our own folder and then the three places a
 * download actually lands -- and the last time the same rule lived in two places, one copy
 * was fixed and the other was not.
 */
public final class Disk {

    /** How many names to offer before a list stops being a help. */
    public static final int MOST = 60;

    private Disk() {
    }

    /** A folder of ours under the config directory, made if it is not there. */
    public static Path folder(String name) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(name);
        try {
            // Made on the way in so there is somewhere obvious to drop a file, rather than
            // a folder you have to be told the name of and create yourself.
            Files.createDirectories(path);
        } catch (IOException | RuntimeException ignored) {
            // It can be made by hand; not being able to is not a reason to stop.
        }
        return path;
    }

    /**
     * Every folder a file is looked for in, best first.
     *
     * <p>Finding one folder on disk is a worse problem than it sounds, and it is not the
     * player's problem to solve: ours is first, then the three places a downloaded file
     * lands, then the home folder itself.
     */
    public static List<Path> places(Path own) {
        List<Path> folders = new ArrayList<>();
        folders.add(own);

        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            Path base = Paths.get(home);
            folders.add(base.resolve("Desktop"));
            folders.add(base.resolve("Downloads"));
            folders.add(base.resolve("Pictures"));
            folders.add(base);
        }
        return folders;
    }

    /** The same folders, written out for a message that has to be actionable. */
    public static String describe(Path own) {
        List<String> shown = new ArrayList<>();
        for (Path folder : places(own)) shown.add(folder.toString());
        return String.join(", ", shown);
    }

    /**
     * Turns whatever was typed into a file on disk, or nothing.
     *
     * <p>A whole path is taken as it stands, so a file never has to be moved at all;
     * otherwise the name is looked for in each of the usual folders. An unusable path is
     * the same answer as a missing file -- on Windows a stray character throws rather than
     * returning, and a thrown import helps nobody.
     */
    public static Path find(Path own, String fileName) {
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
            Path given = Paths.get(cleaned);
            if (given.isAbsolute() && Files.isRegularFile(given)) return given;

            for (Path folder : places(own)) {
                Path candidate = folder.resolve(cleaned);
                if (Files.isRegularFile(candidate)) return candidate;
            }
        } catch (RuntimeException ignored) {
            // Not a path this system can express, which is a missing file by another name.
            // Caught as one type: InvalidPathException is a RuntimeException, and naming
            // both in a multi-catch does not compile.
        }
        return null;
    }

    /**
     * What files of these kinds are sitting where one can be loaded from.
     *
     * <p>Every folder, not just ours, so a file that was just downloaded turns up under
     * tab-complete without being moved first. A cap, because a home folder can hold
     * thousands and a list that long answers nothing.
     */
    public static List<String> list(Path own, List<String> kinds) {
        List<String> found = new ArrayList<>();

        for (Path folder : places(own)) {
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(fileName -> looksRight(fileName, kinds))
                        .forEach(fileName -> {
                            if (!found.contains(fileName) && found.size() < MOST) {
                                found.add(fileName);
                            }
                        });
            } catch (IOException | RuntimeException ignored) {
                // A folder that is not there, or not ours to read, simply holds nothing.
            }
        }

        Collections.sort(found);
        return found;
    }

    /** Whether a name ends in one of the endings asked for. */
    public static boolean looksRight(String fileName, List<String> kinds) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String kind : kinds) {
            if (lower.endsWith(kind)) return true;
        }
        return false;
    }
}
