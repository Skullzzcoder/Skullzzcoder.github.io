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
    /** Loaded shot a browser asked for, or -1. */
    private static final AtomicInteger requestedShot = new AtomicInteger(-1);
    /** Whether a browser asked to reset the chamber count. */
    private static final java.util.concurrent.atomic.AtomicBoolean requestedReset =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** What a browser asked of arming: 1 arm, 0 disarm, -1 nothing. */
    private static final AtomicInteger requestedArm = new AtomicInteger(-1);
    /** Whether a browser asked to set the watched dispensers off by hand. */
    private static final java.util.concurrent.atomic.AtomicBoolean requestedFire =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** Side a browser rigged the paper game for; "*" means chance. Null when unasked. */
    private static final AtomicReference<String> requestedWinner = new AtomicReference<>(null);
    /** Which way a browser threw the master switch, or null when unasked. */
    private static final AtomicReference<Boolean> requestedPower = new AtomicReference<>(null);

    /** The rig half's own switch, asked for from the dashboard. */
    private static final AtomicReference<Boolean> requestedRigs = new AtomicReference<>(null);
    /** Whether a browser asked for the watched dispensers to be laid out again. */
    private static final java.util.concurrent.atomic.AtomicBoolean requestedRefill =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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
            server.createContext("/shot", WebDashboard::handleShot);
            server.createContext("/reset", WebDashboard::handleReset);
            server.createContext("/arm", WebDashboard::handleArm);
            server.createContext("/fire", WebDashboard::handleFire);
            server.createContext("/refill", WebDashboard::handleRefill);
            server.createContext("/winner", WebDashboard::handleWinner);
            server.createContext("/power", WebDashboard::handlePower);
            server.createContext("/rigs", WebDashboard::handleRigs);
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

    /** @return a loaded-shot number a browser picked, or -1. Clears it. */
    public static int pollShot() {
        return requestedShot.getAndSet(-1);
    }

    /** @return true once if a browser asked for the chamber count to be reset. */
    public static boolean pollReset() {
        return requestedReset.getAndSet(false);
    }

    /** @return 1 to arm, 0 to disarm, -1 if nothing was asked. Clears it. */
    public static int pollArm() {
        return requestedArm.getAndSet(-1);
    }

    /** @return true once if a browser asked to fire the watched dispensers. */
    public static boolean pollFire() {
        return requestedFire.getAndSet(false);
    }

    /** @return which way a browser threw the master switch, or null if it did not. */
    public static Boolean pollRigs() {
        return requestedRigs.getAndSet(null);
    }

    public static Boolean pollPower() {
        return requestedPower.getAndSet(null);
    }

    /** @return a side a browser rigged the paper game for, "*" for chance, or null. */
    public static String pollWinner() {
        return requestedWinner.getAndSet(null);
    }

    /** @return true once if a browser asked for the dispensers to be laid out again. */
    public static boolean pollRefill() {
        return requestedRefill.getAndSet(false);
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

    private static void handlePower(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        requestedPower.set(query != null && query.contains("on=1"));
        respond(exchange, 200, "application/json", "{\"ok\":true}");
    }

    private static void handleRigs(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        requestedRigs.set(query != null && query.contains("on=1"));
        respond(exchange, 200, "application/json", "{\"ok\":true}");
    }

    private static void handleWinner(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || !query.startsWith("side=")) {
            respond(exchange, 400, "text/plain", "bad side");
            return;
        }

        requestedWinner.set(
                java.net.URLDecoder.decode(query.substring(5), StandardCharsets.UTF_8));
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

    private static void handleShot(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        int shot = -1;
        if (query != null && query.startsWith("n=")) {
            try {
                shot = Integer.parseInt(query.substring(2));
            } catch (NumberFormatException ignored) {
                shot = -1;
            }
        }

        if (shot < 1) {
            respond(exchange, 400, "text/plain", "bad shot");
            return;
        }
        requestedShot.set(shot);
        respond(exchange, 200, "application/json", "{\"ok\":true}");
    }

    private static void handleFire(HttpExchange exchange) throws IOException {
        requestedFire.set(true);
        respond(exchange, 200, "application/json", "{\"ok\":true}");
    }

    private static void handleRefill(HttpExchange exchange) throws IOException {
        requestedRefill.set(true);
        respond(exchange, 200, "application/json", "{\"ok\":true}");
    }

    private static void handleArm(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        requestedArm.set(query != null && query.contains("off=1") ? 0 : 1);
        respond(exchange, 200, "application/json", "{\"ok\":true}");
    }

    private static void handleReset(HttpExchange exchange) throws IOException {
        requestedReset.set(true);
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

    /**
     * The dashboard, as one page.
     *
     * <p>A sidebar of sections, a search box, and cards for whichever is open -- the same
     * shape as the in-game menu so the two do not have to be learnt separately. It reads
     * the state the client publishes and posts back to the same handful of endpoints that
     * were already here.
     *
     * <p>Plain HTML, CSS and JavaScript with nothing fetched from anywhere. The page is
     * served from the client's own loopback port, so a page that needed the internet would
     * be a page that stops working exactly when Minecraft is the only thing running.
     */
    private static final String PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Ryne Client</title>
            <style>
              :root {
                color-scheme: dark;
                --bg: #0b0d12; --panel: #121620; --card: #171c28; --hover: #1e2432;
                --line: #262d3d; --text: #e8eaed; --dim: #8a90a0; --accent: #8b5cf6;
              }
              * { box-sizing: border-box; }
              body { margin: 0; min-height: 100vh; background: var(--bg); color: var(--text);
                     font: 14px/1.55 system-ui, -apple-system, Segoe UI, Roboto, sans-serif; }
              #app { display: grid; grid-template-columns: 232px 1fr; min-height: 100vh; }

              #side { background: var(--panel); border-right: 1px solid var(--line);
                      padding: 20px 14px; display: flex; flex-direction: column; gap: 4px; }
              #brand { display: flex; align-items: center; gap: 11px; padding: 4px 8px 20px; }
              #mark { width: 30px; height: 30px; border-radius: 9px; background: var(--accent);
                      display: grid; place-items: center; font-weight: 700; font-size: 13px;
                      color: #0b0d12; }
              #brand b { font-size: 15px; letter-spacing: .01em; }
              .grouphead { font-size: 10.5px; letter-spacing: .13em; color: var(--dim);
                           padding: 16px 10px 6px; text-transform: uppercase; }
              .nav { display: flex; align-items: center; gap: 10px; width: 100%;
                     padding: 9px 11px; border-radius: 10px; border: 1px solid transparent;
                     background: none; color: var(--text); font: inherit; cursor: pointer;
                     text-align: left; }
              .nav:hover { background: var(--hover); }
              .nav.on { background: var(--accent); color: #0b0d12; font-weight: 600; }
              .nav .tag { margin-left: auto; font-size: 11px; opacity: .65;
                          font-variant-numeric: tabular-nums; }
              #sidefoot { margin-top: auto; padding-top: 16px; }

              #main { padding: 22px 26px 40px; max-width: 1180px; }
              #top { display: flex; gap: 12px; align-items: center; margin-bottom: 22px; }
              #search { flex: 1; padding: 11px 15px; border-radius: 11px;
                        border: 1px solid var(--line); background: var(--panel);
                        color: var(--text); font: inherit; }
              #search::placeholder { color: var(--dim); }
              #search:focus { outline: none; border-color: var(--accent); }

              h2 { margin: 0 0 14px; font-size: 19px; font-weight: 650;
                   letter-spacing: -.01em; }
              .sub { color: var(--dim); font-size: 12.5px; margin: -8px 0 16px; }

              .pill { padding: 9px 15px; border-radius: 999px; border: 1px solid var(--line);
                      background: var(--panel); color: var(--text); font: inherit;
                      font-size: 13px; cursor: pointer; white-space: nowrap; }
              .pill:hover { border-color: var(--accent); }
              .pill.bad { border-color: #e0655f; color: #e0655f; }
              .pill.good { border-color: var(--accent); color: var(--accent); }

              .grid { display: grid; gap: 12px;
                      grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); }
              .card { background: var(--card); border: 1px solid var(--line);
                      border-radius: 13px; padding: 15px 17px; }
              .card.sel { border-color: var(--accent); background: var(--hover); }
              .card h3 { margin: 0 0 4px; font-size: 14px; font-weight: 620;
                         display: flex; align-items: center; gap: 8px; }
              .card p { margin: 0; color: var(--dim); font-size: 12.5px; }
              .dot { width: 9px; height: 9px; border-radius: 3px; background: var(--accent);
                     flex: none; }
              button.card { cursor: pointer; font: inherit; text-align: left; width: 100%;
                            color: var(--text); }
              button.card:hover { background: var(--hover); }

              .row { display: flex; flex-wrap: wrap; gap: 9px; margin-bottom: 16px; }
              .stat { background: var(--card); border: 1px solid var(--line);
                      border-radius: 12px; padding: 13px 16px; min-width: 128px; }
              .stat b { display: block; font-size: 21px; font-weight: 650;
                        font-variant-numeric: tabular-nums; }
              .stat span { color: var(--dim); font-size: 11.5px; }
              .stat.warn b { color: #e0a55f; }

              .log { font: 12px/1.7 ui-monospace, SFMono-Regular, Consolas, monospace;
                     background: var(--card); border: 1px solid var(--line);
                     border-radius: 12px; padding: 13px 15px; max-height: 320px;
                     overflow: auto; white-space: pre-wrap; word-break: break-word; }
              .path { font: 12px/1.6 ui-monospace, SFMono-Regular, Consolas, monospace;
                      user-select: all; background: var(--bg); border: 1px solid var(--line);
                      border-radius: 9px; padding: 9px 12px; word-break: break-all;
                      margin-bottom: 9px; }
              .none { color: var(--dim); font-size: 13px; padding: 10px 2px; }
              table { width: 100%; border-collapse: collapse; font-size: 13px; }
              th { text-align: left; color: var(--dim); font-weight: 500; font-size: 11px;
                   letter-spacing: .07em; text-transform: uppercase;
                   padding: 0 12px 8px 0; }
              td { padding: 7px 12px 7px 0; border-top: 1px solid var(--line);
                   vertical-align: top; }
              td.num { font-variant-numeric: tabular-nums; }
              tr.bad td { color: #e0655f; }
              .logline.stopped { color: #e0a55f; }
            </style>
            </head>
            <body>
            <div id="app">
              <nav id="side">
                <div id="brand"><div id="mark">R</div><b>Ryne Client</b></div>
                <div id="nav"></div>
                <div id="sidefoot"><div class="grouphead">Theme</div>
                  <div class="none" id="themename">--</div></div>
              </nav>
              <main id="main">
                <div id="top">
                  <input id="search" placeholder="Search" autocomplete="off">
                  <button class="pill" id="power"></button>
                  <button class="pill" id="rigs"></button>
                </div>
                <div id="page"></div>
              </main>
            </div>
            <script>
            const el = id => document.getElementById(id);
            const make = (tag, cls, text) => {
              const node = document.createElement(tag);
              if (cls) node.className = cls;
              if (text !== undefined) node.textContent = text;
              return node;
            };

            const PAGES = [
              { id: 'overview',   label: 'Overview',   group: 'CLIENT' },
              { id: 'rigs',       label: 'Rigs',       group: 'MODULES' },
              { id: 'machines',   label: 'Machines',   group: 'MODULES' },
              { id: 'builds',     label: 'Builds',     group: 'MODULES' },
              { id: 'schematics', label: 'Schematics', group: 'MODULES' },
              { id: 'mapart',     label: 'Map art',    group: 'MODULES' },
              { id: 'tracker',    label: 'Tracker',    group: 'MODULES' },
              { id: 'log',        label: 'Activity',   group: 'CLIENT' },
            ];

            let state = {};
            let open = 'overview';
            let query = '';

            const post = async url => {
              try { await fetch(url); } catch (failure) { /* the client went away */ }
              last = '';
              refresh();
            };

            // ------------------------------------------------------------- the sidebar

            const shown = () => PAGES.filter(p =>
                !query || p.label.toLowerCase().includes(query.toLowerCase()));

            const counts = {
              rigs: s => (s.rigs || []).length,
              machines: s => (s.machines || []).length,
              builds: s => ((s.schematics || {}).builds || []).length,
              schematics: s => ((s.schematics || {}).files || []).length,
              mapart: s => ((s.pictures || {}).files || []).length,
            };

            const renderNav = () => {
              const host = el('nav');
              host.replaceChildren();
              let group = '';
              for (const page of shown()) {
                if (page.group !== group) {
                  group = page.group;
                  host.appendChild(make('div', 'grouphead', group));
                }
                const button = make('button', 'nav' + (page.id === open ? ' on' : ''));
                button.appendChild(make('span', '', page.label));
                const count = counts[page.id] ? counts[page.id](state) : null;
                if (count !== null) button.appendChild(make('span', 'tag', String(count)));
                button.onclick = () => { open = page.id; draw(); };
                host.appendChild(button);
              }
              if (!shown().length) host.appendChild(make('div', 'none', 'Nothing matches.'));
            };

            // --------------------------------------------------------------- the pages

            const stat = (host, value, label, warn) => {
              const box = make('div', 'stat' + (warn ? ' warn' : ''));
              box.appendChild(make('b', '', String(value)));
              box.appendChild(make('span', '', label));
              host.appendChild(box);
            };

            const card = (host, title, blurb, selected, onclick) => {
              const node = make(onclick ? 'button' : 'div',
                  'card' + (selected ? ' sel' : ''));
              const head = make('h3');
              head.appendChild(make('span', 'dot'));
              head.appendChild(make('span', '', title));
              node.appendChild(head);
              if (blurb) node.appendChild(make('p', '', blurb));
              if (onclick) node.onclick = onclick;
              host.appendChild(node);
              return node;
            };

            const logInto = (host, lines) => {
              const box = make('div', 'log');
              const rows = (lines && lines.length ? lines : ['Nothing yet.'])
                  .slice().reverse();
              for (const line of rows) {
                box.appendChild(make('div',
                    'logline' + (line.includes('STOPPED') ? ' stopped' : ''), line));
              }
              host.appendChild(box);
            };

            const table = (host, heads, rows, classFor) => {
              const node = make('table');
              const head = make('tr');
              for (const h of heads) head.appendChild(make('th', '', h));
              node.appendChild(head);
              for (let i = 0; i < rows.length; i++) {
                const line = make('tr', classFor ? classFor(i) : '');
                for (const cell of rows[i]) {
                  line.appendChild(make('td', typeof cell === 'number' ? 'num' : '',
                      String(cell)));
                }
                node.appendChild(line);
              }
              host.appendChild(node);
            };

            const pages = {};

            pages.overview = (host, s) => {
              host.appendChild(make('h2', '', 'Overview'));
              const row = make('div', 'row');
              stat(row, s.on === false ? 'OFF' : 'ON', 'Mirage', s.on === false);
              stat(row, s.rigsOn === false ? 'OFF' : 'ON', 'Rigs', s.rigsOn === false);
              const bad = (s.machines || []).filter(m => m.state !== 'ok').length;
              stat(row, (s.machines || []).length, 'Machines watched');
              if (bad) stat(row, bad, 'Machines not ready', true);
              stat(row, ((s.schematics || {}).builds || []).length, 'Builds loaded');
              stat(row, s.answer && s.answer !== 'yes' ? 'NO' : 'YES', 'Rig has answer',
                  s.answer && s.answer !== 'yes');
              host.appendChild(row);

              const grid = make('div', 'grid');
              card(grid, s.rig || '--', 'Rig - ' + (s.mode || ''), true);
              card(grid, s.name || 'nothing set',
                  'Answers with this' + (s.price ? '  -  ' + s.price : ''));
              card(grid, s.forward || '--', 'F does this');
              card(grid, s.back || '--', 'R does this');
              host.appendChild(grid);

              const t = s.tracker || {};
              if (t.session) {
                const money = make('div', 'row');
                stat(money, t.session.net, 'Session net', !t.session.up);
                stat(money, t.session.wins + 'W / ' + t.session.losses + 'L', 'Trades');
                if (t.session.streak >= (t.alertAfter || 5)) {
                  stat(money, t.session.streak, 'Out in a row', true);
                }
                host.appendChild(money);
              }

              if (s.answer && s.answer !== 'yes') {
                host.appendChild(make('div', 'sub', 'Why nothing will fire: ' + s.answer));
              }
            };

            pages.rigs = (host, s) => {
              host.appendChild(make('h2', '', 'Rigs'));
              host.appendChild(make('div', 'sub',
                  'The one in colour is running. Click another to switch to it.'));

              const grid = make('div', 'grid');
              for (const name of (s.rigs || [])) {
                card(grid, name, name === s.rig ? (s.mode || 'running') : 'click to use',
                    name === s.rig, () => post('/rig?name=' + encodeURIComponent(name)));
              }
              if (!(s.rigs || []).length) grid.appendChild(make('div', 'none', 'No rigs.'));
              host.appendChild(grid);

              const row = make('div', 'row');
              const fire = make('button', 'pill', 'Fire the watched machines');
              fire.onclick = () => post('/fire');
              row.appendChild(fire);
              const refill = make('button', 'pill', 'Refill them');
              refill.onclick = () => post('/refill');
              row.appendChild(refill);
              if (s.roulette && s.roulette.on) {
                const arm = make('button', 'pill' + (s.roulette.armed ? ' good' : ''),
                    s.roulette.armed ? 'Armed - tap to cancel' : 'Arm the next shot');
                arm.onclick = () => post('/arm' + (s.roulette.armed ? '?off=1' : ''));
                row.appendChild(arm);
              }
              host.appendChild(row);

              if (s.paper && s.paper.on) {
                host.appendChild(make('div', 'grouphead', 'Who wins'));
                const winners = make('div', 'row');
                for (const side of (s.paper.sides || []).concat(['*'])) {
                  const label = side === '*' ? 'Leave to chance' : side;
                  const chosen = (s.winner || '') === (side === '*' ? '' : side);
                  const button = make('button', 'pill' + (chosen ? ' good' : ''), label);
                  button.onclick = () => post('/winner?side=' + encodeURIComponent(side));
                  winners.appendChild(button);
                }
                host.appendChild(winners);
              }

              if (s.blackjack && s.blackjack.on) {
                host.appendChild(make('div', 'grouphead', 'Hands'));
                table(host, ['Side', 'Cards', 'Total'],
                    (s.blackjack.hands || []).map(h => [h.side, h.cards, h.total]));
              }

              if (s.mix && s.mix.on) {
                host.appendChild(make('div', 'grouphead', 'What is in the mix'));
                table(host, ['Item', 'Held', 'Chance', 'Pays'],
                    (s.mix.items || []).map(i => [i.name, i.held, i.chance + '%',
                        i.pays + 'x']));
              }
            };

            pages.machines = (host, s) => {
              host.appendChild(make('h2', '', 'Machines'));
              host.appendChild(make('div', 'sub',
                  'Every dispenser being watched, what it will fire and what it holds.'));
              const rows = (s.machines || []).map(m => [m.pos, m.state, m.fires, m.holds]);
              if (!rows.length) {
                host.appendChild(make('div', 'none',
                    'None watched. Look at a dispenser and use /fake dispenser watch.'));
                return;
              }
              const machines = s.machines || [];
              table(host, ['Position', 'State', 'Fires', 'Holds'], rows,
                  i => machines[i].state === 'ok' ? '' : 'bad');
            };

            pages.builds = (host, s) => {
              host.appendChild(make('h2', '', 'Builds'));
              const builds = (s.schematics || {}).builds || [];
              if (!builds.length) {
                host.appendChild(make('div', 'none', 'Nothing loaded.'));
                return;
              }
              table(host, ['Name', 'Blocks', 'Size', 'Standing'],
                  builds.map(b => [b.name, b.blocks, b.size,
                      b.at ? (b.at + (b.here ? '' : '  (another world)')) : 'down']));
            };

            pages.schematics = (host, s) => {
              host.appendChild(make('h2', '', 'Schematics'));
              const schem = s.schematics || {};
              host.appendChild(make('div', 'sub', 'Drop .litematic files in this folder:'));
              const path = make('div', 'path', schem.folder || 'unknown');
              path.id = 'schempath';
              host.appendChild(path);
              copyButton(host, 'schempath');
              const files = schem.files || [];
              if (!files.length) {
                host.appendChild(make('div', 'none',
                    'No .litematic files yet. Drop one in the folder above, or in Desktop, '
                    + 'Downloads or Pictures.'));
                return;
              }
              logInto(host, files.map(f => f + '   -   /fake schem load ' + f + ' <name>'));
            };

            pages.mapart = (host, s) => {
              host.appendChild(make('h2', '', 'Map art'));
              const pics = s.pictures || {};
              host.appendChild(make('div', 'sub',
                  'Pictures here can be imported, but any full path works too:'));
              const path = make('div', 'path', pics.folder || 'unknown');
              path.id = 'picpath';
              host.appendChild(path);
              copyButton(host, 'picpath');
              const files = pics.files || [];
              if (!files.length) {
                host.appendChild(make('div', 'none',
                    'Drop a png or jpg in the folder above, or in Desktop, Downloads or '
                    + 'Pictures, and it will turn up here.'));
                return;
              }
              logInto(host, files.map(f => f + '   -   /fake map import ' + f + ' <name>'));
            };

            pages.tracker = (host, s) => {
              host.appendChild(make('h2', '', 'Tracker'));
              const t = s.tracker || {};

              if (t.hooked === false) {
                host.appendChild(make('div', 'none',
                    'Not reading chat: ' + (t.why || 'unknown') + '. Nothing is being '
                    + 'counted, which is better than counting it wrongly.'));
                return;
              }

              const session = t.session;
              const row = make('div', 'row');
              stat(row, t.tracking ? 'ON' : 'OFF', 'Tracking', !t.tracking);
              if (session) {
                stat(row, session.net, 'Session', !session.up);
                stat(row, session.in, 'In');
                stat(row, session.out, 'Out');
                stat(row, session.wins + 'W / ' + session.losses + 'L', 'Trades');
                stat(row, session.streak, 'Out in a row',
                    session.streak >= (t.alertAfter || 5));
              } else {
                stat(row, '--', 'No session started');
              }
              host.appendChild(row);

              if (!session) {
                host.appendChild(make('div', 'none',
                    'Start a session from the Tracker page in game, or with /fake track '
                    + 'start. Nothing counts until you do.'));
              }

              host.appendChild(make('div', 'grouphead', 'Recent payments'));
              const recent = t.recent || [];
              if (!recent.length) {
                host.appendChild(make('div', 'none',
                    t.tracking ? 'Nothing yet.' : 'Tracking is off, so nothing is recorded.'));
              } else {
                table(host, ['', 'Player', 'Amount'],
                    recent.map(p => [p.in ? 'in' : 'out', p.who, p.amount]),
                    i => recent[i].in ? '' : 'bad');
              }

              const owed = t.owed || [];
              if (owed.length) {
                host.appendChild(make('div', 'grouphead', 'Rakeback owed'));
                table(host, ['Player', 'Sent you', 'You owe'],
                    owed.map(o => [o.who, o.sent, o.owed]));
              }
            };

            pages.log = (host, s) => {
              host.appendChild(make('h2', '', 'Activity'));
              host.appendChild(make('div', 'grouphead', 'What the machines did'));
              logInto(host, s.fires);
              host.appendChild(make('div', 'grouphead', 'Messages'));
              logInto(host, s.notices);
            };

            // Copying beats reading: the paths are long, and typing one by hand is exactly
            // the step that goes wrong.
            const copyButton = (host, pathId) => {
              const button = make('button', 'pill', 'Copy this folder path');
              button.onclick = async () => {
                const text = el(pathId).textContent;
                try {
                  await navigator.clipboard.writeText(text);
                  button.textContent = 'Copied';
                } catch (failure) {
                  // Some browsers refuse the clipboard outright; selecting it is still a copy.
                  const range = document.createRange();
                  range.selectNodeContents(el(pathId));
                  const selection = window.getSelection();
                  selection.removeAllRanges();
                  selection.addRange(range);
                  button.textContent = 'Selected - press Ctrl+C';
                }
                setTimeout(() => { button.textContent = 'Copy this folder path'; }, 2000);
              };
              const row = make('div', 'row');
              row.appendChild(button);
              host.appendChild(row);
            };

            // ------------------------------------------------------------------ drawing

            const draw = () => {
              const s = state;
              if (s.accent) document.documentElement.style.setProperty('--accent', s.accent);
              el('themename').textContent = s.theme || '--';

              const powerOn = s.on !== false;
              el('power').textContent = powerOn ? 'Mirage on' : 'Mirage OFF';
              el('power').className = 'pill ' + (powerOn ? 'good' : 'bad');
              el('power').onclick = () => post('/power?on=' + (powerOn ? '0' : '1'));

              const rigsOn = s.rigsOn !== false;
              el('rigs').textContent = rigsOn ? 'Rigs on' : 'Rigs OFF';
              el('rigs').className = 'pill ' + (rigsOn ? 'good' : 'bad');
              el('rigs').onclick = () => post('/rigs?on=' + (rigsOn ? '0' : '1'));

              renderNav();

              // A search that hides the open page would leave the panel showing something
              // the sidebar no longer offers, so it follows along.
              if (!shown().some(p => p.id === open) && shown().length) open = shown()[0].id;

              const host = el('page');
              host.replaceChildren();
              const page = pages[open];
              if (page) page(host, s);
            };

            let last = '';
            const refresh = async () => {
              try {
                const answer = await fetch('/state');
                const text = await answer.text();
                if (text === last) return;
                last = text;
                state = JSON.parse(text);
                draw();
              } catch (failure) {
                // Minecraft closed, or has not opened the port yet. Left as it was rather
                // than blanked: the last thing it said is more use than an empty page.
              }
            };

            el('search').oninput = event => { query = event.target.value; draw(); };

            draw();
            refresh();
            setInterval(refresh, 1000);
            </script>
            </body>
            </html>
            """;
}
