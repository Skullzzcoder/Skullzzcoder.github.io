package dev.skullzz.mirage.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.skullzz.mirage.Mirage;

/**
 * A small page on localhost showing which dispenser preset is currently selected, and letting
 * you pick another. Useful on a second screen or a phone.
 *
 * <p>The game state is never touched from an HTTP thread. The client tick publishes a
 * snapshot here, and a click parks an index that the next tick picks up.
 */
public final class WebDashboard {
    private static final AtomicReference<String> snapshot =
            new AtomicReference<>("{\"rig\":\"\",\"rigs\":[],\"presets\":[],\"active\":-1,\"fixed\":[]}");
    /** Index a browser asked for, or -1. Consumed by the client tick. */
    private static final AtomicInteger requested = new AtomicInteger(-1);
    /** Rig a browser asked for, or null. Consumed by the client tick. */
    private static final AtomicReference<String> requestedRig = new AtomicReference<>(null);

    private static HttpServer server;
    private static int boundPort = -1;

    private static boolean enabled = true;
    private static String host = "127.0.0.1";
    private static int configuredPort = 25599;

    private WebDashboard() {
    }

    /** Reads the dashboard block from the client config and starts or stops accordingly. */
    public static void configure(com.google.gson.JsonObject root) {
        if (root.has("dashboard")) {
            com.google.gson.JsonObject json = root.getAsJsonObject("dashboard");
            enabled = !json.has("enabled") || json.get("enabled").getAsBoolean();
            if (json.has("port")) configuredPort = json.get("port").getAsInt();
            if (json.has("host")) host = json.get("host").getAsString();
        }

        if (enabled) {
            start(host, configuredPort);
        } else {
            stop();
        }
    }

    public static void writeConfig(com.google.gson.JsonObject root) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("port", configuredPort);
        json.addProperty("host", host);
        json.addProperty("_comment", "host 127.0.0.1 keeps this to this machine. Setting it to "
                + "0.0.0.0 exposes the page, and its controls, to everything on your network.");
        root.add("dashboard", json);
    }

    public static boolean isRunning() {
        return server != null;
    }

    public static int port() {
        return boundPort;
    }

    public static void start(String host, int port) {
        stop();
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/", WebDashboard::handlePage);
            server.createContext("/state", WebDashboard::handleState);
            server.createContext("/select", WebDashboard::handleSelect);
            server.createContext("/rig", WebDashboard::handleRig);
            server.setExecutor(null);
            server.start();

            boundPort = server.getAddress().getPort();
            Mirage.LOGGER.info("Mirage dashboard on http://{}:{}", host, boundPort);
        } catch (IOException e) {
            server = null;
            Mirage.LOGGER.error("Mirage could not open the dashboard on {}:{} -- {}",
                    host, port, e.toString());
        }
    }

    public static void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            boundPort = -1;
        }
    }

    /** Called from the client tick with the current state as JSON. */
    public static void publish(String json) {
        snapshot.set(json);
    }

    /** @return an index a browser picked, or -1. Clears it. */
    public static int pollSelection() {
        return requested.getAndSet(-1);
    }

    /** @return a rig name a browser picked, or null. Clears it. */
    public static String pollRig() {
        return requestedRig.getAndSet(null);
    }

    // ----------------------------------------------------------------- handlers

    private static void handleState(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "application/json", snapshot.get());
    }

    private static void handleSelect(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        int index = -1;
        if (query != null && query.startsWith("i=")) {
            try {
                index = Integer.parseInt(query.substring(2));
            } catch (NumberFormatException ignored) {
                index = -1;
            }
        }

        if (index < 0) {
            respond(exchange, 400, "text/plain", "bad index");
            return;
        }
        requested.set(index);
        respond(exchange, 200, "application/json", "{\"ok\":true}");
    }

    private static void handleRig(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || !query.startsWith("name=")) {
            respond(exchange, 400, "text/plain", "bad rig");
            return;
        }

        String name = java.net.URLDecoder.decode(query.substring(5), StandardCharsets.UTF_8);
        requestedRig.set(name);
        respond(exchange, 200, "application/json", "{\"ok\":true}");
    }

    private static void handlePage(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            respond(exchange, 404, "text/plain", "not found");
            return;
        }
        respond(exchange, 200, "text/html; charset=utf-8", PAGE);
    }

    private static void respond(HttpExchange exchange, int status, String type, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Escapes a string for embedding in the JSON snapshot. */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static final String PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Mirage</title>
            <style>
              :root { color-scheme: dark; }
              * { box-sizing: border-box; }
              body { margin: 0; min-height: 100vh; display: flex; flex-direction: column;
                     align-items: center; justify-content: center; gap: 24px;
                     background: #14161a; color: #e8eaed;
                     font: 16px/1.5 system-ui, -apple-system, Segoe UI, sans-serif; }
              #card { width: min(90vw, 420px); border-radius: 18px; padding: 40px 24px;
                      text-align: center; background: #1e2127; border: 2px solid #2b2f37;
                      transition: background .18s, border-color .18s; }
              #name { font-size: 34px; font-weight: 650; letter-spacing: -.02em; }
              #price { margin-top: 6px; font-size: 20px; color: #9aa0a6; font-variant-numeric: tabular-nums; }
              #label { font-size: 13px; text-transform: uppercase; letter-spacing: .1em;
                       color: #9aa0a6; margin-bottom: 14px; }
              .row { display: flex; flex-wrap: wrap; gap: 10px; justify-content: center;
                     width: min(90vw, 420px); }
              button { flex: 1 1 auto; min-width: 120px; padding: 14px 18px; border-radius: 12px;
                       border: 1px solid #343a44; background: #1e2127; color: #e8eaed;
                       font: inherit; font-weight: 550; cursor: pointer; }
              button:hover { background: #262a31; }
              button[aria-pressed="true"] { border-color: currentColor; }
              #none { color: #9aa0a6; font-size: 14px; }
              #fixed { color: #9aa0a6; font-size: 13px; text-align: center; line-height: 1.7;
                       font-variant-numeric: tabular-nums; }
              #rigs button { flex: 0 0 auto; min-width: 0; padding: 8px 16px; font-size: 14px;
                             border-radius: 999px; }
            </style>
            </head>
            <body>
              <div class="row" id="rigs"></div>
              <div id="card">
                <div id="label">Rigged toward</div>
                <div id="name">--</div>
                <div id="price"></div>
              </div>
              <div class="row" id="buttons"></div>
              <div id="fixed"></div>
              <div id="none" hidden>No presets in this rig. Use /fake preset add in game.</div>
            <script>
            const tint = name => {
              const n = name.toLowerCase();
              if (n.includes('gold')) return ['#3a2f12', '#f0b429'];
              if (n.includes('diamond')) return ['#123437', '#4dd0e1'];
              if (n.includes('emerald')) return ['#12331d', '#4caf50'];
              if (n.includes('netherite')) return ['#241f1d', '#a1887f'];
              return ['#1e2127', '#e8eaed'];
            };
            let last = '';
            async function refresh() {
              let s;
              try { s = await (await fetch('/state')).json(); } catch { return; }
              const key = JSON.stringify(s);
              if (key === last) return;
              last = key;

              const active = s.presets[s.active];
              const card = document.getElementById('card');
              document.getElementById('none').hidden = s.presets.length > 0;

              if (active) {
                const [bg, fg] = tint(active.name);
                card.style.background = bg;
                card.style.borderColor = fg;
                document.getElementById('name').textContent = active.name;
                document.getElementById('name').style.color = fg;
                document.getElementById('price').textContent = active.price || '';
              } else {
                card.style.background = '#1e2127';
                card.style.borderColor = '#2b2f37';
                document.getElementById('name').textContent = '--';
                document.getElementById('name').style.color = '#e8eaed';
                document.getElementById('price').textContent = '';
              }

              const rigs = document.getElementById('rigs');
              rigs.replaceChildren();
              (s.rigs || []).forEach(name => {
                const b = document.createElement('button');
                b.textContent = name;
                b.setAttribute('aria-pressed', String(name === s.rig));
                if (name === s.rig) b.style.color = '#e8eaed';
                b.onclick = async () => {
                  await fetch('/rig?name=' + encodeURIComponent(name));
                  last = '';
                  refresh();
                };
                rigs.append(b);
              });

              const row = document.getElementById('buttons');
              row.replaceChildren();
              s.presets.forEach((p, i) => {
                const b = document.createElement('button');
                b.textContent = p.name;
                b.setAttribute('aria-pressed', String(i === s.active));
                b.style.color = tint(p.name)[1];
                b.onclick = async () => {
                  await fetch('/select?i=' + i);
                  last = '';
                  refresh();
                };
                row.append(b);
              });

              document.getElementById('fixed').replaceChildren(
                ...(s.fixed || []).map(f => {
                  const d = document.createElement('div');
                  d.textContent = f.pos + '  fires  ' + f.name;
                  return d;
                }));
            }
            refresh();
            setInterval(refresh, 1000);
            </script>
            </body>
            </html>
            """;
}
