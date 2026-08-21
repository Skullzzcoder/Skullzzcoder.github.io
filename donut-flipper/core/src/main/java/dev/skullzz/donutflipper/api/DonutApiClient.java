package dev.skullzz.donutflipper.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP client for the DonutSMP public API.
 *
 * <p>Base URL {@code https://api.donutsmp.net/v1}. The key comes from running
 * {@code /api} in game (which requires a linked Discord account) and is passed
 * as a bearer token.
 *
 * <p>Every call goes through the shared {@link RateLimiter}. On a 429 the client
 * drains the bucket and backs off exponentially rather than retrying immediately:
 * hammering a rate limit is how a key gets throttled harder, and a collector that
 * loses sweeps produces gaps in the sale history that silently degrade every
 * valuation downstream.
 */
public final class DonutApiClient {

    public static final String DEFAULT_BASE_URL = "https://api.donutsmp.net/v1";
    private static final int MAX_ATTEMPTS = 5;

    /**
     * Per-request ceiling. A full auction page can be large, and the server is
     * shared with every other player's tooling, so this is deliberately patient.
     */
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final RateLimiter limiter;
    private final Duration timeout;

    public DonutApiClient(String apiKey, RateLimiter limiter) {
        this(apiKey, limiter, DEFAULT_BASE_URL);
    }

    public DonutApiClient(String apiKey, RateLimiter limiter, String baseUrl) {
        this(apiKey, limiter, baseUrl, DEFAULT_TIMEOUT_SECONDS);
    }

    public DonutApiClient(String apiKey, RateLimiter limiter, String baseUrl, int timeoutSeconds) {
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "No DonutSMP API key. Run /api in game, then set DONUTSMP_API_KEY "
                            + "or the apiKey field in ~/.donutflipper/config.json");
        }
        this.apiKey = apiKey.trim();
        this.limiter = limiter;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** One page of current auction listings. Pages are 1-indexed. */
    public JsonElement auctionList(int page) throws IOException, InterruptedException {
        return get("/auction/list/" + page);
    }

    /** One page of completed transactions -- the basis for every valuation. */
    public JsonElement auctionTransactions(int page) throws IOException, InterruptedException {
        return get("/auction/transactions/" + page);
    }

    public JsonElement lookup(String username) throws IOException, InterruptedException {
        return get("/lookup/" + username);
    }

    public JsonElement stats(String username) throws IOException, InterruptedException {
        return get("/stats/" + username);
    }

    /**
     * Issues a rate-limited GET with retry.
     *
     * <p>Retries only on 429 and 5xx. A 401/403 means the key is wrong or
     * expired and retrying just burns budget against a request that will never
     * succeed, so those fail immediately with a message that says what to fix.
     */
    public JsonElement get(String path) throws IOException, InterruptedException {
        IOException last = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            limiter.acquire();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .header("User-Agent", "donut-flipper/0.1")
                    .GET()
                    .build();

            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (java.net.http.HttpTimeoutException timeout) {
                // A bare "request timed out" tells you nothing about which of the
                // several possible causes you have. Name them.
                last = new ApiException(0, "Timed out after " + this.timeout.toSeconds()
                        + "s on " + path + ". Possible causes: the endpoint path is wrong "
                        + "and the server is not answering; a firewall or VPN is blocking "
                        + "the connection; or the API is slow right now. "
                        + "Run `net-test` to tell these apart.");
                if (attempt < MAX_ATTEMPTS) {
                    Thread.sleep(1000L * (1L << (attempt - 1)));
                    continue;
                }
                throw last;
            }
            int status = response.statusCode();

            if (status >= 200 && status < 300) {
                return JsonParser.parseString(response.body());
            }

            if (status == 401 || status == 403) {
                throw new ApiException(status,
                        "DonutSMP rejected the API key (HTTP " + status + "). Re-run /api in game "
                                + "and update your config -- retrying will not help.");
            }

            if (status == 429) {
                limiter.penalise();
                last = new ApiException(status, "Rate limited on " + path);
            } else if (status >= 500) {
                last = new ApiException(status, "Server error " + status + " on " + path);
            } else {
                throw new ApiException(status,
                        "HTTP " + status + " on " + path + ": " + truncate(response.body()));
            }

            if (attempt < MAX_ATTEMPTS) {
                // 1s, 2s, 4s, 8s. Bounded because the poller runs on a schedule
                // and a sweep that backs off past its own interval is just skipped.
                Thread.sleep(1000L * (1L << (attempt - 1)));
            }
        }
        throw last == null ? new IOException("Request failed: " + path) : last;
    }

    private static String truncate(String body) {
        if (body == null) return "";
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }

    /** Non-2xx response from the API. */
    public static class ApiException extends IOException {
        private final int statusCode;

        public ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}
