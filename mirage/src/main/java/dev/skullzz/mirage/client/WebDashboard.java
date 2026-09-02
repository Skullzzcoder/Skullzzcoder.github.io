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
                     align-items: center; justify-content: center; gap: 20px; padding: 24px;
                     background: #14161a; color: #e8eaed;
                     font: 16px/1.5 system-ui, -apple-system, Segoe UI, sans-serif; }
              #card { width: min(90vw, 420px); border-radius: 18px; padding: 36px 24px;
                      text-align: center; background: #1e2127; border: 2px solid #2b2f37;
                      transition: background .18s, border-color .18s; }
              #name { font-size: 34px; font-weight: 650; letter-spacing: -.02em;
                      font-variant-numeric: tabular-nums; }
              #price { margin-top: 6px; font-size: 17px; color: #9aa0a6; }
              #label { font-size: 13px; text-transform: uppercase; letter-spacing: .1em;
                       color: #9aa0a6; margin-bottom: 12px; }
              .row { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center;
                     width: min(92vw, 460px); }
              button { flex: 1 1 auto; min-width: 110px; padding: 13px 16px; border-radius: 12px;
                       border: 1px solid #343a44; background: #1e2127; color: #e8eaed;
                       font: inherit; font-weight: 550; cursor: pointer; }
              button:hover { background: #262a31; }
              button[aria-pressed="true"] { border-color: currentColor; }
              #rigs button { flex: 0 0 auto; min-width: 0; padding: 8px 16px; font-size: 14px;
                             border-radius: 999px; }
              #arm { width: min(92vw, 460px); padding: 22px; font-size: 19px; font-weight: 650;
                     letter-spacing: .01em; }
              #arm.ready { background: #3a1414; border-color: #ff5252; color: #ff5252; }
              #chambers button { flex: 0 0 auto; min-width: 46px; padding: 10px 0; }
              #chambers button.loaded { background: #3a2416; border-color: #ff7043; color: #ff7043; }
              #chambers button.spent { opacity: .4; }
              #fixed { color: #9aa0a6; font-size: 13px; text-align: center; line-height: 1.7;
                       font-variant-numeric: tabular-nums; }
              #none { color: #9aa0a6; font-size: 14px; }
              #head { text-align: center; }
              #game { font-size: 20px; font-weight: 650; }
              #keys { color: #9aa0a6; font-size: 14px; margin-top: 4px; }
              #alert { width: min(92vw, 460px); padding: 12px 16px; border-radius: 12px;
                       background: #3a1414; border: 1px solid #ff5252; color: #ff8a80;
                       font-size: 14px; text-align: center; }
              .panel { width: min(92vw, 460px); }
              .panel h3 { font-size: 12px; text-transform: uppercase; letter-spacing: .1em;
                          color: #9aa0a6; font-weight: 600; margin: 0 0 8px; }
              .machine { display: flex; justify-content: space-between; gap: 12px;
                         padding: 9px 12px; border-radius: 10px; background: #1e2127;
                         border: 1px solid #2b2f37; margin-bottom: 6px; font-size: 13px; }
              .machine.bad { border-color: #ff5252; color: #ff8a80; }
              .machine .where { color: #9aa0a6; font-variant-numeric: tabular-nums;
                                white-space: nowrap; }
              .machine .what { text-align: right; }
              .log { font: 12px/1.7 ui-monospace, SFMono-Regular, Menlo, monospace;
                     color: #9aa0a6; background: #1a1d22; border: 1px solid #2b2f37;
                     border-radius: 10px; padding: 10px 12px; max-height: 190px;
                     overflow-y: auto; white-space: pre-wrap; }
              .log div.stopped { color: #ff8a80; }
              #settings { color: #9aa0a6; font-size: 13px; line-height: 1.8;
                          text-align: center; font-variant-numeric: tabular-nums; }
            </style>
            </head>
            <body>
              <div class="row" id="rigs"></div>
              <div id="head">
                <div id="game">--</div>
                <div id="keys"></div>
              </div>
              <div id="alert" hidden></div>
              <div id="card">
                <div id="label">Rigged toward</div>
                <div id="name">--</div>
                <div id="price"></div>
              </div>
              <div class="row" id="buttons"></div>
              <button id="arm" hidden></button>
              <button id="power"></button>
              <button id="fire">Fire the watched dispensers</button>
              <button id="refill">Refill them</button>
              <div class="row" id="chambers"></div>
              <div class="row" id="winners"></div>
              <div id="fixed"></div>
              <div id="settings"></div>
              <div id="none" hidden>Nothing set in this rig.</div>
              <div class="panel"><h3>Machines</h3><div id="machines"></div></div>
              <div class="panel"><h3>What the machines did</h3><div id="fires" class="log"></div></div>
              <div class="panel"><h3>Messages</h3><div id="notices" class="log"></div></div>
            <script>
            const el = id => document.getElementById(id);

            const tint = name => {
              const n = (name || '').toLowerCase();
              if (n.includes('gold')) return ['#3a2f12', '#f0b429'];
              if (n.includes('diamond')) return ['#123437', '#4dd0e1'];
              if (n.includes('emerald')) return ['#12331d', '#4caf50'];
              if (n.includes('netherite')) return ['#241f1d', '#a1887f'];
              return ['#1e2127', '#e8eaed'];
            };

            const post = async url => { await fetch(url); last = ''; refresh(); };

            function renderRigs(s) {
              const rigs = el('rigs');
              rigs.replaceChildren();
              (s.rigs || []).forEach(name => {
                const b = document.createElement('button');
                b.textContent = name;
                b.setAttribute('aria-pressed', String(name === s.rig));
                b.onclick = () => post('/rig?name=' + encodeURIComponent(name));
                rigs.append(b);
              });
            }

            function renderCard(s) {
              const card = el('card');
              const r = s.roulette;

              if (r && r.on) {
                if (r.armed) {
                  card.style.background = '#3a1414';
                  card.style.borderColor = '#ff5252';
                  el('label').textContent = 'Armed';
                  el('name').textContent = 'NEXT SPIN';
                  el('name').style.color = '#ff5252';
                  el('price').textContent = r.bullet || '';
                  return;
                }

                const onTheNumber = !r.manual && r.shot === r.bulletAt;
                card.style.background = onTheNumber ? '#2a1a14' : '#1e2127';
                card.style.borderColor = onTheNumber ? '#ff7043' : '#2b2f37';
                el('label').textContent = r.manual ? 'Spins so far' : 'Chamber';
                el('name').textContent = r.manual ? String(r.shot) : r.shot + ' / ' + r.chambers;
                el('name').style.color = onTheNumber ? '#ff7043' : '#e8eaed';
                el('price').textContent = r.manual
                    ? 'arm it when it is their turn'
                    : 'loaded on ' + r.bulletAt + ' \u2014 ' + (r.bullet || 'nothing');
                return;
              }

              const active = s.presets[s.active];
              el('label').textContent = 'Rigged toward';
              if (active) {
                const [bg, fg] = tint(active.name);
                card.style.background = bg;
                card.style.borderColor = fg;
                el('name').textContent = active.name;
                el('name').style.color = fg;
                el('price').textContent = active.price || '';
              } else {
                card.style.background = '#1e2127';
                card.style.borderColor = '#2b2f37';
                el('name').textContent = '--';
                el('name').style.color = '#e8eaed';
                el('price').textContent = '';
              }
            }

            function renderPresets(s) {
              const row = el('buttons');
              row.replaceChildren();
              const roulette = s.roulette && s.roulette.on;
              if (roulette) return;

              s.presets.forEach((p, i) => {
                const b = document.createElement('button');
                b.textContent = p.name;
                b.setAttribute('aria-pressed', String(i === s.active));
                b.style.color = tint(p.name)[1];
                b.onclick = () => post('/select?i=' + i);
                row.append(b);
              });
            }

            function renderPower(s) {
              const button = el('power');
              button.textContent = s.on ? 'Turn everything off' : 'OFF \u2014 turn it back on';
              button.className = s.on ? '' : 'ready';
              button.onclick = () => post('/power?on=' + (s.on ? '0' : '1'));

              // Nothing else means anything while it is off.
              for (const id of ['fire', 'refill', 'arm'])
                el(id).disabled = !s.on;
            }

            function wireButtons() {
              el('fire').onclick = () => post('/fire');
              el('refill').onclick = () => post('/refill');
            }

            function renderArm(s) {
              const button = el('arm');
              const r = s.roulette;
              button.hidden = !(r && r.on);
              if (button.hidden) return;

              button.textContent = r.armed ? 'Armed \u2014 tap to cancel' : 'Arm next spin';
              button.className = r.armed ? 'ready' : '';
              button.onclick = () => post('/arm' + (r.armed ? '?off=1' : ''));
            }

            function renderWinners(s) {
              const row = el('winners');
              row.replaceChildren();
              const p = s.paper;
              if (!p || !p.on) return;

              for (const side of p.sides.concat(['*'])) {
                const b = document.createElement('button');
                b.textContent = side === '*' ? 'Leave it to chance' : side + ' wins';
                b.setAttribute('aria-pressed',
                    String(side === '*' ? p.winner === '' : p.winner === side));
                b.onclick = () => post('/winner?side=' + encodeURIComponent(side));
                row.append(b);
              }
            }

            function renderChambers(s) {
              const chambers = el('chambers');
              chambers.replaceChildren();
              const r = s.roulette;
              // Counting positions means nothing when the shot is chosen by hand.
              if (!r || !r.on || r.manual) return;

              for (let n = 1; n <= r.chambers; n++) {
                const b = document.createElement('button');
                b.textContent = n;
                if (n === r.bulletAt) b.classList.add('loaded');
                if (n <= r.shot) b.classList.add('spent');
                b.title = n === r.bulletAt ? 'loaded' : 'blank';
                b.onclick = () => post('/shot?n=' + n);
                chambers.append(b);
              }

              const reset = document.createElement('button');
              reset.textContent = 'Reset';
              reset.onclick = () => post('/reset');
              chambers.append(reset);
            }

            function renderFixed(s) {
              el('fixed').replaceChildren(...(s.fixed || []).map(f => {
                const d = document.createElement('div');
                d.textContent = f.pos + '  fires  ' + f.name;
                return d;
              }));
            }

            function renderHead(s) {
              el('game').textContent = (s.rig || '--') + '  \u00b7  ' + (s.mode || '');
              el('keys').textContent = 'F  ' + (s.forward || '') + '     R  ' + (s.back || '')
                + (s.quiet ? '     \u00b7  quiet: nothing shows in game' : '');

              // The one line that means the rig cannot produce anything at all.
              const bad = s.answer && s.answer !== 'yes';
              el('alert').hidden = !bad;
              if (bad) el('alert').textContent = s.answer;
            }

            function renderSettings(s) {
              const bits = [];
              if (s.place) bits.push('answers are placed as blocks, breaking in '
                + (s.breakSeconds || 0) + 's');
              if (s.tower && s.tower.on) {
                bits.push(s.tower.floors + ' floors, they called ' + s.tower.called
                  + (s.tower.armed ? ', ARMED' : s.tower.bustAt
                    ? ', ends on floor ' + s.tower.bustAt : ''));
              }
              if (s.mix && s.mix.on) {
                bits.push((s.mix.items || []).map(i =>
                  i.held + 'x ' + i.name + ' (' + i.chance + '%, pays ' + i.pays + 'x)').join('   '));
              }
              if (s.paper && s.paper.on) {
                bits.push('winner: ' + (s.winner || s.paper.winner || 'chance'));
              }
              el('settings').replaceChildren(...bits.map(t => {
                const d = document.createElement('div');
                d.textContent = t;
                return d;
              }));
            }

            function renderMachines(s) {
              el('machines').replaceChildren(...(s.machines || []).map(m => {
                const row = document.createElement('div');
                row.className = 'machine' + (m.state === 'ok' ? '' : ' bad');

                const where = document.createElement('span');
                where.className = 'where';
                where.textContent = m.pos;

                const what = document.createElement('span');
                what.className = 'what';
                what.textContent = m.state === 'ok'
                  ? 'fires ' + m.fires + '   \u00b7   holds ' + m.holds
                  : m.state;

                row.append(where, what);
                return row;
              }));
            }

            function renderLog(id, lines) {
              el(id).replaceChildren(...(lines || []).slice().reverse().map(line => {
                const d = document.createElement('div');
                // The ones that mean nothing came out, marked so they are found by eye.
                if (line.includes('STOPPED')) d.className = 'stopped';
                d.textContent = line;
                return d;
              }));
            }

            let last = '';
            async function refresh() {
              let s;
              try { s = await (await fetch('/state')).json(); } catch (e) { return; }

              const key = JSON.stringify(s);
              if (key === last) return;
              last = key;

              const roulette = s.roulette && s.roulette.on;
              el('none').hidden = roulette || s.presets.length > 0;

              renderPower(s);
              renderRigs(s);
              renderCard(s);
              renderPresets(s);
              renderArm(s);
              renderChambers(s);
              renderWinners(s);
              renderFixed(s);
              renderHead(s);
              renderSettings(s);
              renderMachines(s);
              renderLog('fires', s.fires);
              renderLog('notices', s.notices);
            }

            wireButtons();
            refresh();
            setInterval(refresh, 1000);
            </script>
            </body>
            </html>
            """;
}
