import { createServer } from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { log } from './log.js';
import { runScan } from './pipeline.js';

const here = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = join(here, '..', 'web');

/**
 * Local dashboard. Scans are cached for a minute so a browser refresh (or a second
 * viewer) cannot burn through the API key's rate limit.
 */
export function serve(cfg, { port = 8787, mock = false, useOrders = true, cacheMs = 60_000 } = {}) {
  let cache = { at: 0, payload: null };
  let inflight = null;

  async function snapshot() {
    if (cache.payload && Date.now() - cache.at < cacheMs) return cache.payload;
    if (inflight) return inflight;
    inflight = runScan(cfg, { mock, useOrders })
      .then((result) => {
        const payload = {
          generatedAt: new Date(result.market.fetchedAt).toISOString(),
          assumptions: cfg.economics,
          filters: cfg.filters,
          orderBook: { present: result.ordersPresent, updatedAt: result.ordersUpdatedAt },
          counts: {
            variants: result.market.rows.length,
            priced: result.opportunities.length,
            eligible: result.eligible.length,
          },
          opportunities: result.eligible.slice(0, 100),
          plan: result.plan,
          budget: result.budget,
        };
        cache = { at: Date.now(), payload };
        return payload;
      })
      .finally(() => {
        inflight = null;
      });
    return inflight;
  }

  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    try {
      if (url.pathname === '/api/opportunities') {
        const payload = await snapshot();
        res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
        res.end(JSON.stringify(payload));
        return;
      }
      const file = url.pathname === '/' ? 'index.html' : url.pathname.replace(/^\/+/, '');
      const path = join(WEB_DIR, file);
      if (!path.startsWith(WEB_DIR) || !existsSync(path)) {
        res.writeHead(404, { 'content-type': 'text/plain' });
        res.end('Not found');
        return;
      }
      const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json' };
      const ext = path.slice(path.lastIndexOf('.'));
      res.writeHead(200, { 'content-type': types[ext] ?? 'application/octet-stream' });
      res.end(readFileSync(path));
    } catch (err) {
      log.error(err.message);
      res.writeHead(500, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: err.message }));
    }
  });

  return new Promise((resolve) => {
    server.listen(port, () => {
      log.info(`Dashboard on http://localhost:${port}${mock ? ' (mock data)' : ''}`);
      resolve(server);
    });
  });
}
