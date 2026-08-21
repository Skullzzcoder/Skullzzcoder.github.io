package dev.skullzz.donutflipper.daemon;

import dev.skullzz.donutflipper.config.FlipperConfig;

import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Isolates <em>why</em> a request failed.
 *
 * <p>"Request timed out" is one message covering several unrelated problems: the
 * host may be unreachable, a firewall may be dropping the connection, the
 * endpoint path may be wrong, or the method may be wrong. Each needs a different
 * fix and guessing between them wastes an evening.
 *
 * <p>This walks the stack from the bottom up -- DNS, TCP, TLS, then HTTP -- and
 * stops being useful only once something succeeds. The first failing layer is
 * the answer.
 *
 * <pre>{@code java -jar daemon-all.jar net-test}</pre>
 */
final class NetTest {

    private static final Duration STEP_TIMEOUT = Duration.ofSeconds(20);

    static void run() throws Exception {
        FlipperConfig config = FlipperConfig.load();
        String base = config.apiBaseUrl();
        URI uri = URI.create(base);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 443;

        System.out.println("Network diagnostics for " + base);
        System.out.println("=".repeat(72));

        if (!dns(host)) return;
        if (!tcp(host, port)) return;

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(STEP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        String key = config.apiKey();
        boolean haveKey = config.hasApiKey();

        System.out.println("\nHTTP");

        // Root, unauthenticated: proves the host serves HTTP at all, separately
        // from whether our specific path or key is right.
        probe(http, "GET", uri.getScheme() + "://" + host + "/", null);
        probe(http, "GET", base, haveKey ? key : null);

        if (!haveKey) {
            System.out.println("\nNo API key configured, so the authenticated checks are skipped.");
            System.out.println("Run:  set-key --clipboard");
            return;
        }

        System.out.println("\nENDPOINTS (authenticated)");
        probe(http, "GET", base + "/auction/list/1", key);
        // The method is not confirmed. If GET hangs or 405s and POST answers,
        // that is the finding, and it changes DonutApiClient.
        probe(http, "POST", base + "/auction/list/1", key);
        probe(http, "GET", base + "/auction/transactions/1", key);

        System.out.println("\n" + "=".repeat(72));
        System.out.println("Reading the result:");
        System.out.println("  200            -> works. Send me the body shape via `probe`.");
        System.out.println("  401 / 403      -> the key is wrong or not accepted.");
        System.out.println("  404            -> the path is wrong; the endpoint moved.");
        System.out.println("  405            -> right path, wrong method. Tell me which verb answered.");
        System.out.println("  timeout on all -> firewall, VPN, or the host is not answering.");
    }

    private static boolean dns(String host) {
        System.out.println("\nDNS");
        long start = System.currentTimeMillis();
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, addresses.length); i++) {
                if (i > 0) sb.append(", ");
                sb.append(addresses[i].getHostAddress());
            }
            System.out.printf("  [ ok ] %s resolves to %s (%d ms)%n",
                    host, sb, System.currentTimeMillis() - start);
            return true;
        } catch (Exception e) {
            System.out.printf("  [FAIL] cannot resolve %s: %s%n", host, e.getMessage());
            System.out.println("         DNS problem, or no internet on this machine.");
            return false;
        }
    }

    private static boolean tcp(String host, int port) {
        System.out.println("\nTCP");
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port),
                    (int) STEP_TIMEOUT.toMillis());
            System.out.printf("  [ ok ] connected to %s:%d (%d ms)%n",
                    host, port, System.currentTimeMillis() - start);
            return true;
        } catch (Exception e) {
            System.out.printf("  [FAIL] cannot connect to %s:%d: %s%n", host, port, e.getMessage());
            System.out.println("         A firewall, antivirus, or VPN is the usual cause.");
            System.out.println("         Try disabling a VPN, or allow java.exe through the firewall.");
            return false;
        }
    }

    /** Issues one request and reports status and timing without dumping the body. */
    private static void probe(HttpClient http, String method, String url, String key) {
        long start = System.currentTimeMillis();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(STEP_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "donut-flipper/0.1");

            if (key != null) {
                builder.header("Authorization", "Bearer " + key);
            }
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"));
            } else {
                builder.GET();
            }

            HttpResponse<String> response =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long ms = System.currentTimeMillis() - start;
            String body = response.body() == null ? "" : response.body().trim();

            System.out.printf("  %s %-5s %-52s -> %d (%d ms, %d bytes)%n",
                    response.statusCode() < 400 ? "[ ok ]" : "[    ]",
                    method, shorten(url), response.statusCode(), ms, body.length());

            // A one-line peek is enough to tell JSON from an HTML error page,
            // and short enough that a key could not hide in it.
            if (!body.isEmpty()) {
                String peek = body.replaceAll("\\s+", " ");
                System.out.println("         " + peek.substring(0, Math.min(120, peek.length())));
            }
        } catch (java.net.http.HttpTimeoutException e) {
            System.out.printf("  [FAIL] %-5s %-52s -> TIMEOUT after %ds%n",
                    method, shorten(url), STEP_TIMEOUT.toSeconds());
        } catch (Exception e) {
            System.out.printf("  [FAIL] %-5s %-52s -> %s%n",
                    method, shorten(url), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String shorten(String url) {
        return url.length() <= 52 ? url : "..." + url.substring(url.length() - 49);
    }

    private NetTest() {
    }
}
