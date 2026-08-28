package dev.skullzz.mirage.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.skullzz.mirage.Mirage;

/**
 * Optional live prices from whatever HTTP API you point it at.
 *
 * <p>Deliberately generic: the URL, headers and the path to the number inside the response
 * are all configuration, because this was written without sight of the API it would talk to.
 * Anything that answers with JSON containing a number will work.
 *
 * <p>Fetches happen on a background thread and never block the game. A lookup returns what is
 * already cached, queues a fetch if not, and the next tick picks up the answer.
 */
public final class PriceApi {
    private static final ExecutorService FETCHERS =
            Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "mirage-price-fetch");
                thread.setDaemon(true);
                return thread;
            });

    private static final Map<String, Double> cache = new ConcurrentHashMap<>();
    private static final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> fetchedAt = new ConcurrentHashMap<>();
    /** Set when a fetch lands, so the fakes get rebuilt once rather than every tick. */
    private static final AtomicBoolean dirty = new AtomicBoolean(false);

    private static boolean enabled;
    private static String urlTemplate = "";
    private static String jsonPath = "";
    private static Map<String, String> headers = new LinkedHashMap<>();
    private static long cacheMillis = Duration.ofMinutes(30).toMillis();

    private static HttpClient client;

    private PriceApi() {
    }

    public static boolean isEnabled() {
        return enabled && !urlTemplate.isEmpty();
    }

    public static void configure(JsonObject api) {
        enabled = api.has("enabled") && api.get("enabled").getAsBoolean();
        urlTemplate = api.has("url") ? api.get("url").getAsString() : "";
        jsonPath = api.has("path") ? api.get("path").getAsString() : "";

        headers = new LinkedHashMap<>();
        if (api.has("headers")) {
            for (Map.Entry<String, JsonElement> entry : api.getAsJsonObject("headers").entrySet()) {
                headers.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        if (api.has("cacheMinutes")) {
            cacheMillis = Duration.ofMinutes(api.get("cacheMinutes").getAsLong()).toMillis();
        }

        cache.clear();
        fetchedAt.clear();
        if (isEnabled()) {
            Mirage.LOGGER.info("Mirage price API enabled for {}", urlTemplate);
        }
    }

    /** @return true once after a fetch has landed, so callers rebuild exactly once. */
    public static boolean consumeDirty() {
        return dirty.getAndSet(false);
    }

    /**
     * @return the cached price, or null. A miss queues a background fetch; nothing here
     *         waits on the network.
     */
    public static Double lookup(String itemId) {
        if (!isEnabled()) return null;

        Long when = fetchedAt.get(itemId);
        boolean stale = when == null || System.currentTimeMillis() - when > cacheMillis;
        if (!stale) return cache.get(itemId);

        if (inFlight.add(itemId)) {
            FETCHERS.submit(() -> fetch(itemId));
        }
        return cache.get(itemId);
    }

    private static void fetch(String itemId) {
        try {
            String shortId = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
            String url = urlTemplate.replace("%item%", itemId).replace("%item_short%", shortId);

            if (client == null) {
                client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            }

            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET();
            for (Map.Entry<String, String> header : headers.entrySet()) {
                request.header(header.getKey(), header.getValue());
            }

            HttpResponse<String> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                // Do not log the response body: it can echo the request, key included.
                Mirage.LOGGER.warn("Mirage price lookup for {} returned HTTP {}",
                        itemId, response.statusCode());
                remember(itemId, null);
                return;
            }

            Double price = extract(JsonParser.parseString(response.body()));
            remember(itemId, price);
        } catch (Exception e) {
            Mirage.LOGGER.warn("Mirage price lookup for {} failed: {}", itemId, e.toString());
            remember(itemId, null);
        } finally {
            inFlight.remove(itemId);
        }
    }

    private static void remember(String itemId, Double price) {
        fetchedAt.put(itemId, System.currentTimeMillis());
        if (price == null) {
            cache.remove(itemId);
        } else {
            cache.put(itemId, price);
            dirty.set(true);
        }
    }

    /** Walks a dotted path such as {@code result.price}; [n] indexes an array. */
    private static Double extract(JsonElement root) {
        JsonElement current = root;

        if (!jsonPath.isEmpty()) {
            for (String rawStep : jsonPath.split("\\.")) {
                String step = rawStep.trim();
                if (step.isEmpty() || current == null) continue;

                if (step.endsWith("]") && step.contains("[")) {
                    String name = step.substring(0, step.indexOf('['));
                    int index = Integer.parseInt(step.substring(step.indexOf('[') + 1, step.length() - 1));
                    if (!name.isEmpty()) current = child(current, name);
                    if (current == null || !current.isJsonArray()) return null;
                    if (index >= current.getAsJsonArray().size()) return null;
                    current = current.getAsJsonArray().get(index);
                } else {
                    current = child(current, step);
                }
            }
        }

        if (current == null || !current.isJsonPrimitive()) return null;
        try {
            return current.getAsDouble();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonElement child(JsonElement parent, String name) {
        if (parent == null || !parent.isJsonObject()) return null;
        JsonObject object = parent.getAsJsonObject();
        return object.has(name) ? object.get(name) : null;
    }

    /** Only for tests of the path walker. */
    static void setPathForTesting(String path) {
        jsonPath = path;
    }

    static Double extractForTesting(String json) {
        return extract(JsonParser.parseString(json));
    }

    static Map<String, Double> cacheView() {
        return new HashMap<>(cache);
    }
}
