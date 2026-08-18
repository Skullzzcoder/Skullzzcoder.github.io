package dev.skullzz.donutflipper.mod;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.skullzz.donutflipper.config.FlipperConfig;
import dev.skullzz.donutflipper.service.FlipDto;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Holds the current flip list and keeps it fresh.
 *
 * <p>Contains no Minecraft imports at all, which is deliberate: it can be
 * unit-tested without a game, and a Minecraft version bump cannot break it. The
 * only classes in this mod that touch Minecraft are the two that draw pixels.
 *
 * <p>Polling happens on a background thread and the result is published to a
 * volatile field. Doing the HTTP call on the render thread would stall the game
 * for the duration of every request -- brief, but a visible hitch several times
 * a minute is exactly the kind of thing that makes people uninstall a mod.
 */
public final class FlipperState {

    /** How often to ask the daemon for a refreshed list. */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(10);

    private static final Gson GSON = new Gson();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final String baseUrl;
    private ScheduledExecutorService pool;

    private volatile List<FlipDto> flips = List.of();
    private volatile Instant lastUpdate = Instant.EPOCH;
    private volatile String status = "starting";
    private volatile boolean connected = false;

    /** Highest score seen so far, so a genuinely new best flip can chime once. */
    private volatile double bestScoreSeen = 0;
    private volatile boolean newBestAvailable = false;

    public FlipperState() {
        int port;
        try {
            port = FlipperConfig.load().localPort();
        } catch (Exception e) {
            port = 8731;
        }
        this.baseUrl = "http://127.0.0.1:" + port;
    }

    public void start() {
        pool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "donutflipper-poll");
            // Daemon thread so a stuck poll can never keep the game process alive
            // after the player quits.
            t.setDaemon(true);
            return t;
        });
        pool.scheduleAtFixedRate(this::poll, 0, POLL_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    public void stop() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void poll() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/flips"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                fail("collector returned HTTP " + response.statusCode());
                return;
            }

            List<FlipDto> parsed = GSON.fromJson(response.body(),
                    new TypeToken<List<FlipDto>>() {}.getType());
            if (parsed == null) {
                parsed = List.of();
            }
            parsed.sort(Comparator.comparingDouble(FlipDto::score).reversed());

            detectNewBest(parsed);

            this.flips = List.copyOf(parsed);
            this.lastUpdate = Instant.now();
            this.connected = true;
            this.status = parsed.isEmpty()
                    ? "connected - no flips right now"
                    : parsed.size() + " flips";

        } catch (Exception e) {
            // Almost always "the daemon is not running". Say so plainly rather
            // than showing an empty list, which reads as "no opportunities".
            fail("collector not reachable - is the daemon running?");
        }
    }

    private void detectNewBest(List<FlipDto> parsed) {
        if (parsed.isEmpty()) {
            return;
        }
        double top = parsed.get(0).score();
        if (top > bestScoreSeen * 1.2) {
            newBestAvailable = true;
            bestScoreSeen = top;
        }
    }

    private void fail(String message) {
        this.connected = false;
        this.status = message;
        // Deliberately keeps the last known list rather than clearing it. A brief
        // blip should not wipe the screen you are reading mid-decision.
    }

    /** Consumes the "new best flip" flag; returns true only once per new best. */
    public boolean consumeNewBestFlag() {
        if (newBestAvailable) {
            newBestAvailable = false;
            return true;
        }
        return false;
    }

    public List<FlipDto> flips() {
        return flips;
    }

    public List<FlipDto> top(int n) {
        List<FlipDto> current = flips;
        return current.subList(0, Math.min(n, current.size()));
    }

    public boolean connected() {
        return connected;
    }

    public String status() {
        return status;
    }

    public long secondsSinceUpdate() {
        if (lastUpdate.equals(Instant.EPOCH)) {
            return -1;
        }
        return Duration.between(lastUpdate, Instant.now()).toSeconds();
    }

    /** Asks the daemon to switch strategy profile. */
    public void setProfile(String name) {
        try {
            http.send(HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/profile?name=" + name))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Non-critical: the next poll reflects whatever the daemon decided.
        }
    }
}
