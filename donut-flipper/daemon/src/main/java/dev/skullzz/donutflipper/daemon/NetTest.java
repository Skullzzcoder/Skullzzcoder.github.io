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
        if (!tcpPerAddress(host, port)) return;

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
            System.out.printf("  [ ok ] %s resolves to %d address(es) in %d ms%n",
                    host, addresses.length, System.currentTimeMillis() - start);
            for (InetAddress a : addresses) {
                System.out.printf("         %-6s %s%n",
                        a instanceof java.net.Inet6Address ? "IPv6" : "IPv4",
                        a.getHostAddress());
            }
            return true;
        } catch (Exception e) {
            System.out.printf("  [FAIL] cannot resolve %s: %s%n", host, e.getMessage());
            System.out.println("         DNS problem, or no internet on this machine.");
            return false;
        }
    }

    /**
     * Connects to each resolved address separately.
     *
     * <p>This is the check that catches broken IPv6, which is a common and
     * badly-disguised fault: the machine prefers an AAAA record, the SYN goes
     * nowhere, and the connection hangs until it times out with zero bytes. It
     * looks like the server is down. Browsers hide it by racing both families
     * and falling back in milliseconds; Java and curl do not, so the same host
     * that loads fine in a browser times out from a program.
     *
     * <p>Testing families separately turns "timed out" into "IPv4 works, IPv6
     * does not", which is a one-flag fix rather than an evening of guessing.
     */
    private static boolean tcpPerAddress(String host, int port) {
        System.out.println("\nTCP (each address tested separately)");
        boolean anyOk = false;
        boolean v4Ok = false;
        boolean v6Failed = false;

        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                boolean isV6 = address instanceof java.net.Inet6Address;
                long start = System.currentTimeMillis();
                try (Socket socket = new Socket()) {
                    socket.connect(new java.net.InetSocketAddress(address, port),
                            (int) STEP_TIMEOUT.toMillis());
                    System.out.printf("  [ ok ] %-6s %-40s connected (%d ms)%n",
                            isV6 ? "IPv6" : "IPv4", address.getHostAddress(),
                            System.currentTimeMillis() - start);
                    anyOk = true;
                    if (!isV6) {
                        v4Ok = true;
                    }
                } catch (Exception e) {
                    System.out.printf("  [FAIL] %-6s %-40s %s%n",
                            isV6 ? "IPv6" : "IPv4", address.getHostAddress(), e.getMessage());
                    if (isV6) {
                        v6Failed = true;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("  [FAIL] " + e);
            return false;
        }

        if (v6Failed && v4Ok) {
            System.out.println();
            System.out.println("  >>> IPv6 is broken on this network but IPv4 works.");
            System.out.println("  >>> That is almost certainly the whole problem.");
            System.out.println("  >>> Fix: re-run any command with --ipv4, e.g.");
            System.out.println("  >>>   java -jar daemon-all.jar collect --ipv4");
            System.out.println("  >>> Or set \"preferIpv4\": true in config.json to make it permanent.");
        } else if (!anyOk) {
            System.out.println();
            System.out.println("  >>> No address accepted a connection.");
            System.out.println("  >>> A firewall, antivirus or VPN is the usual cause;");
            System.out.println("  >>> otherwise the host is genuinely down.");
        }
        return anyOk;
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
