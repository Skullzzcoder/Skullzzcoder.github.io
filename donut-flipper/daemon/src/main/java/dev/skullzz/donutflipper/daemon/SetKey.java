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
 * <pre>{@code java -jar daemon-all.jar set-key}</pre>
 */
final class SetKey {

    static void run(String[] args) throws Exception {
        FlipperConfig config = FlipperConfig.load();

        String key = args.length > 1 ? args[1].trim() : prompt();
        if (key == null || key.isBlank()) {
            System.out.println("No key entered, nothing changed.");
            return;
        }
        if (key.length() < 8) {
            // Almost always a paste that lost most of its content.
            System.out.println("That looks too short to be an API key ("
                    + key.length() + " characters). Nothing saved.");
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

    private static String prompt() throws Exception {
        System.out.println("Paste your DonutSMP API key (run /api in game to get one).");
        System.out.print("Key: ");
        System.out.flush();

        // Console hides typing, but is null when stdout is piped or when run
        // through a Gradle daemon. Fall back to a visible read rather than failing.
        Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword();
            return chars == null ? null : new String(chars);
        }
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
