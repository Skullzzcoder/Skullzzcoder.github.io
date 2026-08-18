package dev.skullzz.donutflipper.daemon;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.skullzz.donutflipper.config.Profile;
import dev.skullzz.donutflipper.service.FlipDto;
import dev.skullzz.donutflipper.service.FlipService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Tiny HTTP server the Minecraft mod reads flips from.
 *
 * <p>Bound explicitly to the loopback address, not to {@code 0.0.0.0}. There is
 * no authentication here and none is wanted -- the correct security boundary for
 * a personal tool is "only this machine can reach it", and binding to all
 * interfaces would quietly publish your trading signals to your whole network.
 *
 * <p>Uses the JDK's built-in server rather than a framework: the entire surface
 * is three read-only endpoints, and adding a web stack to a Minecraft mod's
 * dependency tree buys nothing.
 */
public final class LocalServer {

    private static final Logger LOG = Logger.getLogger(LocalServer.class.getName());
    private static final Gson GSON = new Gson();

    private final FlipService flips;
    private final int port;
    private HttpServer server;

    public LocalServer(FlipService flips, int port) {
        this.flips = flips;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);

        server.createContext("/flips", this::handleFlips);
        server.createContext("/health", this::handleHealth);
        server.createContext("/profile", this::handleProfile);

        server.setExecutor(null);
        server.start();
        LOG.info("Local API listening on http://127.0.0.1:" + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleFlips(HttpExchange ex) throws IOException {
        try {
            respond(ex, 200, GSON.toJson(FlipDto.from(flips.currentFlips(), Instant.now())));
        } catch (Exception e) {
            respond(ex, 500, GSON.toJson(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        respond(ex, 200, GSON.toJson(Map.of(
                "status", "ok",
                "profile", flips.profile().name(),
                "valuedItems", flips.valuations().size(),
                "lastValuationRefresh", flips.valuations().lastRefresh().toString())));
    }

    /** {@code /profile?name=whale} -- lets the in-game UI switch strategy. */
    private void handleProfile(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        if (query != null && query.startsWith("name=")) {
            flips.setProfile(Profile.byName(query.substring(5)));
        }
        respond(ex, 200, GSON.toJson(Map.of("profile", flips.profile().name())));
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
