import { log } from './log.js';
import { findRecordArray, toListings, toSales } from './extract.js';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Spaces requests so we stay under the key's per-minute allowance. */
class RateLimiter {
  constructor(perMinute) {
    this.minGapMs = perMinute > 0 ? 60000 / perMinute : 0;
    this.next = 0;
  }

  async take() {
    if (!this.minGapMs) return;
    const now = Date.now();
    const wait = Math.max(0, this.next - now);
    this.next = Math.max(now, this.next) + this.minGapMs;
    if (wait > 0) await sleep(wait);
  }
}

export class DonutApi {
  constructor(cfg, { fetchImpl = globalThis.fetch } = {}) {
    this.cfg = cfg.api;
    this.apiKey = cfg.apiKey;
    this.fetch = fetchImpl;
    this.limiter = new RateLimiter(this.cfg.requestsPerMinute);
    this.calls = 0;
  }

  headers() {
    const h = { accept: 'application/json', 'content-type': 'application/json' };
    if (this.apiKey) h[this.cfg.authHeader] = `${this.cfg.authPrefix}${this.apiKey}`;
    return h;
  }

  async request(endpoint, page) {
    const path = endpoint.path.replace('{page}', String(page));
    const url = `${this.cfg.baseUrl.replace(/\/$/, '')}${path.startsWith('/') ? path : `/${path}`}`;
    const method = (endpoint.method ?? 'GET').toUpperCase();
    const init = { method, headers: this.headers() };
    if (method !== 'GET' && method !== 'HEAD') {
      init.body = JSON.stringify(endpoint.body ?? {});
    }

    let lastError;
    for (let attempt = 0; attempt <= this.cfg.retries; attempt += 1) {
      await this.limiter.take();
      this.calls += 1;
      try {
        const res = await this.fetch(url, {
          ...init,
          signal: AbortSignal.timeout(this.cfg.timeoutMs),
        });
        if (res.status === 429 || res.status >= 500) {
          const retryAfter = Number(res.headers?.get?.('retry-after'));
          const backoff = Number.isFinite(retryAfter) && retryAfter > 0
            ? retryAfter * 1000
            : Math.min(30000, 2 ** attempt * 1000);
          lastError = new Error(`HTTP ${res.status} from ${url}`);
          log.warn(`${res.status} on page ${page}, retrying in ${Math.round(backoff / 1000)}s`);
          await sleep(backoff);
          continue;
        }
        if (res.status === 401 || res.status === 403) {
          throw new Error(
            `HTTP ${res.status} from ${url} — the API key was rejected. Check apiKey / DONUT_API_KEY.`,
          );
        }
        if (!res.ok) throw new Error(`HTTP ${res.status} from ${url}`);
        return await res.json();
      } catch (err) {
        if (/API key was rejected/.test(err.message)) throw err;
        lastError = err;
        if (attempt === this.cfg.retries) break;
        const backoff = Math.min(30000, 2 ** attempt * 1000);
        log.warn(`${err.message}; retry ${attempt + 1}/${this.cfg.retries} in ${backoff / 1000}s`);
        await sleep(backoff);
      }
    }
    throw lastError ?? new Error(`Request to ${url} failed`);
  }

  /** Walk pages until one comes back short or empty, or maxPages is reached. */
  async collect(endpoint, kind) {
    const rows = [];
    const first = this.cfg.firstPage ?? 1;
    // Page size is per-endpoint: listings and transactions do not have to agree,
    // and sharing one value truncates whichever endpoint pages smaller.
    let pageSize;
    for (let i = 0; i < this.cfg.maxPages; i += 1) {
      const page = first + i;
      const payload = await this.request(endpoint, page);
      const batch = findRecordArray(payload);
      log.debug(`${kind} page ${page}: ${batch.length} rows`);
      if (!batch.length) break;
      rows.push(...batch);
      if (pageSize && batch.length < pageSize) break;
      pageSize ??= batch.length;
    }
    return rows;
  }

  async fetchMarket() {
    const now = Date.now();
    const [listingRows, saleRows] = [
      await this.collect(this.cfg.listings, 'listings'),
      await this.collect(this.cfg.transactions, 'transactions'),
    ];
    const listings = toListings(listingRows, now);
    const sales = toSales(saleRows, now);
    log.info(
      `Fetched ${listings.length} listings and ${sales.length} sales in ${this.calls} API calls`,
    );
    if (listingRows.length && !listings.length) {
      log.warn('Rows arrived but none parsed — run `donut-flip probe` to inspect the shape.');
    }
    return { listings, sales, fetchedAt: now };
  }

  /** Raw first page of each endpoint, for diagnosing a shape change. */
  async probe() {
    return {
      listings: await this.request(this.cfg.listings, this.cfg.firstPage ?? 1),
      transactions: await this.request(this.cfg.transactions, this.cfg.firstPage ?? 1),
    };
  }
}
