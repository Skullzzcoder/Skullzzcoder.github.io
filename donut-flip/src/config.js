import { readFileSync, existsSync } from 'node:fs';

/**
 * Every economic assumption lives here rather than in the scoring code, because
 * server tax rates and order mechanics change and must be adjustable without a
 * code edit. The defaults are conservative: they under-state profit rather than
 * over-state it, so a wrong assumption costs you a missed flip, not coins.
 */
export const DEFAULTS = {
  apiKey: null,
  api: {
    baseUrl: 'https://api.donutsmp.net/v1',
    authHeader: 'Authorization',
    authPrefix: 'Bearer ',
    listings: {
      method: 'POST',
      path: '/auction/list/{page}',
      body: { search: '', sort: 'recently_listed' },
    },
    transactions: {
      method: 'POST',
      path: '/auction/transactions/{page}',
      body: { search: '', sort: 'recently_sold' },
    },
    firstPage: 1,
    maxPages: 10,
    requestsPerMinute: 180,
    timeoutMs: 20000,
    retries: 3,
  },
  economics: {
    ahTaxPct: 0.05, // cut taken out of your sale proceeds when the listing sells
    orderFeePct: 0, // anything skimmed when a buy order fills
    outbidPct: 0.01, // how far over the current top buy order you must sit to fill
    undercutPct: 0.02, // how far under the ask floor you must list to sell promptly
    captureShare: 0.35, // share of observed item flow one order realistically catches
    targetRoiPct: 25, // ROI used to derive a max bid when no order book is supplied
  },
  filters: {
    minUnitProfit: 500,
    minRoiPct: 8,
    minSales: 4,
    minConfidence: 0.3,
    maxUnitPrice: null,
    budget: null,
    maxExposurePct: 0.25,
    include: [],
    exclude: [],
  },
  market: {
    // Listings priced below this fraction of the median are treated as typos or
    // snipe bait rather than as the floor you would have to undercut.
    typoFloorRatio: 0.25,
  },
  windows: { salesLookbackHours: 24, historyDays: 14 },
  output: {
    top: 25,
    json: 'data/opportunities.json',
    history: 'data/history.jsonl',
    snapshots: 'data/snapshots',
  },
  alerts: { discordWebhook: null, minScore: 0, cooldownMinutes: 90 },
  orders: { source: 'none', file: 'data/orders.json' },
  watch: { intervalMinutes: 10 },
  logLevel: 'info',
};

const isPlainObject = (v) => v && typeof v === 'object' && !Array.isArray(v);

export function deepMerge(base, patch) {
  if (!isPlainObject(patch)) return patch === undefined ? base : patch;
  const out = { ...base };
  for (const [k, v] of Object.entries(patch)) {
    if (v === undefined) continue;
    out[k] = isPlainObject(v) && isPlainObject(base?.[k]) ? deepMerge(base[k], v) : v;
  }
  return out;
}

function fromEnv(env) {
  const patch = {};
  const set = (path, value, cast = (x) => x) => {
    if (value == null || value === '') return;
    let node = patch;
    const keys = path.split('.');
    keys.slice(0, -1).forEach((k) => {
      node[k] ??= {};
      node = node[k];
    });
    node[keys[keys.length - 1]] = cast(value);
  };
  const num = (v) => Number(v);
  set('apiKey', env.DONUT_API_KEY ?? env.DONUTSMP_API_KEY);
  set('api.baseUrl', env.DONUT_API_BASE);
  set('filters.budget', env.DONUT_BUDGET, num);
  set('alerts.discordWebhook', env.DONUT_DISCORD_WEBHOOK);
  set('logLevel', env.DONUT_LOG_LEVEL);
  return patch;
}

export function loadConfig({ file = 'config.json', env = process.env, overrides = {} } = {}) {
  let fileCfg = {};
  if (file && existsSync(file)) {
    try {
      fileCfg = JSON.parse(readFileSync(file, 'utf8'));
    } catch (err) {
      throw new Error(`Could not parse ${file}: ${err.message}`);
    }
  }
  const cfg = deepMerge(deepMerge(deepMerge(DEFAULTS, fileCfg), fromEnv(env)), overrides);
  validate(cfg);
  return cfg;
}

function validate(cfg) {
  const problems = [];
  const { economics: e, filters: f } = cfg;
  const pct = (name, v) => {
    if (typeof v !== 'number' || !Number.isFinite(v) || v < 0 || v >= 1) {
      problems.push(`economics.${name} must be a fraction between 0 and 1 (got ${v})`);
    }
  };
  pct('ahTaxPct', e.ahTaxPct);
  pct('orderFeePct', e.orderFeePct);
  pct('undercutPct', e.undercutPct);
  pct('captureShare', e.captureShare);
  if (e.outbidPct < 0) problems.push('economics.outbidPct must not be negative');
  if (f.budget != null && !(f.budget > 0)) problems.push('filters.budget must be positive');
  if (cfg.api.maxPages < 1) problems.push('api.maxPages must be at least 1');
  if (problems.length) throw new Error(`Invalid config:\n  - ${problems.join('\n  - ')}`);
}
