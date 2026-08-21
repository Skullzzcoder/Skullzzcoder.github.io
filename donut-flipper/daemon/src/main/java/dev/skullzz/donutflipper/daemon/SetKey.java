package dev.skullzz.donutflipper.daemon;

import dev.skullzz.donutflipper.api.DonutApiClient;
import dev.skullzz.donutflipper.api.RateLimiter;
import dev.skullzz.donutflipper.config.FlipperConfig;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stores the API key, then immediately proves it works.
 *
 * <p>Exists so nobody has to locate a dot-folder in their home directory and
 * hand-edit JSON. That step is easy to get subtly wrong -- a stray quote, a
 * missing comma, an editor appending {@code .txt} -- and each mistake produces a
 * different confusing error much later.
 *
 * <p>Prompts rather than taking the key as an argument by default, because a key
 * passed on the command line is written into shell history in plain text.
 *
 * <pre>{@code
 * java -jar daemon-all.jar set-key              prompt (input hidden)
 * java -jar daemon-all.jar set-key --visible    prompt, showing what you type
 * java -jar daemon-all.jar set-key --clipboard  read straight from the clipboard
 * java -jar daemon-all.jar set-key --file k.txt read from a text file
 * }</pre>
 */
final class SetKey {

    static void run(String[] args) throws Exception {
        FlipperConfig config = FlipperConfig.load();

        String mode = args.length > 1 ? args[1].toLowerCase() : "";
        String key;

        switch (mode) {
            case "--clipboard", "-c" -> key = fromClipboard();
            case "--file", "-f" -> {
                if (args.length < 3) {
                    System.out.println("Usage: set-key --file <path-to-file-containing-key>");
                    return;
                }
                key = fromFile(Path.of(args[2]));
            }
            case "--visible", "-v" -> key = prompt(true);
            case "" -> key = prompt(false);
            // Anything else is treated as the key itself, for scripts.
            default -> key = args[1].trim();
        }

        if (key == null || key.isBlank()) {
            System.out.println("No key found, nothing changed.");
            return;
        }
        key = key.trim();

        if (key.length() < 8) {
            // Almost always a paste that lost most of its content, or an empty
            // clipboard. Saving it would fail confusingly much later.
            System.out.println("That looks too short to be an API key ("
                    + key.length() + " characters). Nothing saved.");
            System.out.println("If your paste did not register, try:  set-key --clipboard");
            return;
        }

        config.setApiKey(key);
        config.save();

        Path file = FlipperConfig.configFile();
        System.out.println("Saved to " + file);
        System.out.println("  key length " + key.length()
                + ", ends ..." + key.substring(Math.max(0, key.length() - 4)));
        hardenPermissions(file);

        if (FlipperConfig.envKeyPresent()) {
            System.out.println();
            System.out.println("NOTE: the DONUTSMP_API_KEY environment variable is also set,");
            System.out.println("and it takes precedence over the config file. If the check");
            System.out.println("below fails, that stale variable is the likely reason.");
        }

        verify(config);
    }

    /**
     * Reads the key straight from the system clipboard.
     *
     * <p>The most reliable route on Windows, where pasting into a console is
     * genuinely awkward: the classic console ignores Ctrl+V, and a hidden-input
     * prompt shows nothing when a paste does land, so a successful paste and a
     * failed one look identical. Copy the key, run this, done.
     */
    private static String fromClipboard() {
        try {
            Object data = java.awt.Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            String text = data == null ? null : data.toString().trim();
            if (text == null || text.isBlank()) {
                System.out.println("The clipboard is empty, or holds something that is not text.");
                return null;
            }
            System.out.println("Read " + text.length() + " characters from the clipboard.");
            return text;
        } catch (java.awt.HeadlessException e) {
            System.out.println("No desktop session, so the clipboard is unavailable here.");
            System.out.println("Use:  set-key --file <path>   or   set-key --visible");
            return null;
        } catch (Exception e) {
            System.out.println("Could not read the clipboard: " + e);
            return null;
        }
    }

    /**
     * Reads the key from a text file. The escape hatch when the terminal will
     * not cooperate at all -- paste into Notepad, save, point this at it.
     */
    private static String fromFile(Path path) {
        try {
            String text = Files.readString(path).trim();
            if (text.isBlank()) {
                System.out.println("That file is empty: " + path.toAbsolutePath());
                return null;
            }
            System.out.println("Read " + text.length() + " characters from "
                    + path.toAbsolutePath());
            return text;
        } catch (Exception e) {
            System.out.println("Could not read " + path.toAbsolutePath() + ": " + e.getMessage());
            return null;
        }
    }

    private static String prompt(boolean visible) throws Exception {
        System.out.println("Paste your DonutSMP API key (run /api in game to get one).");
        System.out.println();
        System.out.println("  Windows Terminal : Ctrl+V");
        System.out.println("  Classic console  : right-click");
        System.out.println();

        Console console = visible ? null : System.console();

        if (console != null) {
            // Hidden input shows nothing at all when you paste. Say so plainly --
            // otherwise a paste that worked looks exactly like one that failed,
            // and people retry until they give up.
            System.out.println("Your key will NOT appear as you paste. That is normal.");
            System.out.println("Paste, then press Enter. Or re-run with --visible to see it.");
            System.out.print("Key: ");
            System.out.flush();
            char[] chars = console.readPassword();
            return chars == null ? null : new String(chars);
        }

        if (visible) {
            System.out.println("(visible mode -- your key will be shown on screen)");
        }
        System.out.print("Key: ");
        System.out.flush();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        return reader.readLine();
    }

    /**
     * Restricts the config file to the current user where the filesystem supports
     * it. Best-effort: this is a convenience, not a security boundary, and a
     * filesystem that cannot express it is not a reason to fail.
     */
    private static void hardenPermissions(Path file) {
        try {
            java.io.File f = file.toFile();
            boolean ok = f.setReadable(false, false) && f.setReadable(true, true);
            if (ok) {
                System.out.println("  file restricted to your user account");
            }
        } catch (Exception ignored) {
            // Windows ACLs and some network filesystems reject this. Not fatal.
        }
    }

    /** One live call, so a bad key is caught now rather than three days later. */
    private static void verify(FlipperConfig config) {
        System.out.println();
        System.out.println("Checking the key against the API...");
        try {
            RateLimiter limiter = new RateLimiter(250 * config.rateLimitUtilisation());
            DonutApiClient client =
                    new DonutApiClient(config.apiKey(), limiter, config.apiBaseUrl());
            client.auctionList(1);
            System.out.println("  the API accepted it.");
            System.out.println();
            System.out.println("Next:  java -jar daemon-all.jar probe");
        } catch (DonutApiClient.ApiException e) {
            System.out.println("  REJECTED: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("  could not reach the API: " + e);
            System.out.println("  The key is saved; this may just be a network problem.");
        }
    }

    /** Prints where the config lives, for anyone who wants to edit it by hand. */
    static void where() throws Exception {
        FlipperConfig.load();   // creates it if missing, so the path always exists
        Path file = FlipperConfig.configFile();
        System.out.println("Config file: " + file);
        System.out.println("Exists:      " + Files.exists(file));
        System.out.println("Key set:     " + FlipperConfig.load().hasApiKey());
        System.out.println();
        System.out.println("Open it with:");
        System.out.println("  Windows      notepad \"" + file + "\"");
        System.out.println("  macOS        open -e \"" + file + "\"");
        System.out.println("  Linux        xdg-open \"" + file + "\"");
        System.out.println();
        System.out.println("Or skip editing entirely:  java -jar daemon-all.jar set-key");
    }

    private SetKey() {
    }
}
