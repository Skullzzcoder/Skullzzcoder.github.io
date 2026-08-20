package dev.skullzz.donutflipper.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * User configuration, loaded from {@code ~/.donutflipper/config.json}.
 *
 * <p>The config lives outside the project directory on purpose. The API key is
 * account-scoped, and the surest way to never commit a secret is for it to never
 * sit inside a git working tree in the first place.
 *
 * <p>Resolution order for the key, first hit wins:
 * <ol>
 *   <li>{@code DONUTSMP_API_KEY} environment variable</li>
 *   <li>{@code apiKey} in the config file</li>
 * </ol>
 * The env var takes priority so CI and throwaway shells can override without
 * editing anything on disk.
 */
public final class FlipperConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ENV_KEY = "DONUTSMP_API_KEY";

    /** DonutSMP API key, obtained once by running {@code /api} in game. */
    private String apiKey = "";

    /** Seconds between full sweeps of the auction listing pages. */
    private int listingPollSeconds = 60;

    /** Seconds between sweeps of the completed-transaction feed. */
    private int transactionPollSeconds = 45;

    /**
     * Safety margin under the documented 250 req/min ceiling. We spend at most
     * this fraction of the budget so a manual probe or the mod polling alongside
     * the daemon can't tip the account into 429s.
     */
    private double rateLimitUtilisation = 0.75;

    /** Active strategy profile name: balanced, volume, or whale. */
    private String activeProfile = "balanced";

    /**
     * Auction house cut taken off a sale, as a fraction. Unverified until the
     * first live probe -- see docs/OPEN-QUESTIONS.md. Set deliberately high so
     * that being wrong makes the tool too cautious rather than too greedy.
     */
    private double auctionTaxRate = 0.05;

    /** Port the mod uses to read flips from a running daemon. Localhost only. */
    private int localPort = 8731;

    /**
     * API base URL. Overridable so the endpoint can be repointed without a
     * rebuild if it ever moves, and so diagnostics can be exercised against a
     * local stub.
     */
    private String apiBaseUrl = dev.skullzz.donutflipper.api.DonutApiClient.DEFAULT_BASE_URL;

    public static Path configDir() {
        String override = System.getenv("DONUTFLIPPER_HOME");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".donutflipper");
    }

    public static Path configFile() {
        return configDir().resolve("config.json");
    }

    public static Path databaseFile() {
        return configDir().resolve("flipper.db");
    }

    /**
     * Loads config, writing a commented template on first run so the user has
     * something concrete to edit rather than a stack trace.
     */
    public static FlipperConfig load() throws IOException {
        Path file = configFile();
        if (!Files.exists(file)) {
            Files.createDirectories(configDir());
            FlipperConfig template = new FlipperConfig();
            Files.writeString(file, GSON.toJson(template));
            return template;
        }
        FlipperConfig cfg = GSON.fromJson(Files.readString(file), FlipperConfig.class);
        return cfg == null ? new FlipperConfig() : cfg;
    }

    public void save() throws IOException {
        Files.createDirectories(configDir());
        Files.writeString(configFile(), GSON.toJson(this));
    }

    public String apiKey() {
        String env = System.getenv(ENV_KEY);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return apiKey == null ? "" : apiKey.trim();
    }

    public boolean hasApiKey() {
        return !apiKey().isEmpty();
    }

    public int listingPollSeconds() {
        return Math.max(10, listingPollSeconds);
    }

    public int transactionPollSeconds() {
        return Math.max(10, transactionPollSeconds);
    }

    public double rateLimitUtilisation() {
        // Clamped: above 1.0 would guarantee 429s, at or below 0 would stall the poller.
        return Math.min(1.0, Math.max(0.05, rateLimitUtilisation));
    }

    public String activeProfile() {
        return activeProfile == null || activeProfile.isBlank() ? "balanced" : activeProfile;
    }

    public void setActiveProfile(String name) {
        this.activeProfile = name;
    }

    public double auctionTaxRate() {
        return Math.min(0.5, Math.max(0.0, auctionTaxRate));
    }

    public int localPort() {
        return localPort;
    }

    public String apiBaseUrl() {
        return apiBaseUrl == null || apiBaseUrl.isBlank()
                ? dev.skullzz.donutflipper.api.DonutApiClient.DEFAULT_BASE_URL
                : apiBaseUrl.trim();
    }
}
