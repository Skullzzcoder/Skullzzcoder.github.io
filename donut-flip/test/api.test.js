import test from 'node:test';
import assert from 'node:assert/strict';
import { DonutApi } from '../src/api.js';
import { loadConfig } from '../src/config.js';
import { History } from '../src/store.js';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setLevel } from '../src/log.js';

setLevel('silent');

const cfg = (overrides = {}) =>
  loadConfig({ file: null, env: { DONUT_API_KEY: 'k' }, overrides });

const row = (i) => ({ item: { id: 'totem_of_undying', count: 1 }, price: 20000 + i });

function fakeApi(pagesByPath) {
  return async (url) => {
    const [, path, page] = /\/v1\/(auction\/[a-z]+)\/(\d+)/.exec(url) ?? [];
    const rows = pagesByPath[path]?.[Number(page)] ?? [];
    return { ok: true, status: 200, json: async () => ({ status: 200, result: rows }) };
  };
}

test('endpoints with different page sizes both paginate fully', async () => {
  // The listings endpoint pages 100 at a time and transactions 20: neither may
  // truncate the other.
  const listings = { 1: Array.from({ length: 100 }, (_, i) => row(i)), 2: Array.from({ length: 40 }, (_, i) => row(i)) };
  const transactions = { 1: Array.from({ length: 20 }, (_, i) => row(i)), 2: Array.from({ length: 20 }, (_, i) => row(i)), 3: Array.from({ length: 5 }, (_, i) => row(i)) };
  const api = new DonutApi(cfg({ api: { requestsPerMinute: 0 } }), {
    fetchImpl: fakeApi({ 'auction/list': listings, 'auction/transactions': transactions }),
  });
  const market = await api.fetchMarket();
  assert.equal(market.listings.length, 140);
  assert.equal(market.sales.length, 45);
});

test('pagination stops at maxPages', async () => {
  const full = Object.fromEntries(
    Array.from({ length: 20 }, (_, i) => [i + 1, Array.from({ length: 50 }, (_, j) => row(j))]),
  );
  const api = new DonutApi(cfg({ api: { requestsPerMinute: 0, maxPages: 3 } }), {
    fetchImpl: fakeApi({ 'auction/list': full, 'auction/transactions': full }),
  });
  const market = await api.fetchMarket();
  assert.equal(market.listings.length, 150);
});

test('a rejected API key fails fast instead of burning retries', async () => {
  let calls = 0;
  const api = new DonutApi(cfg({ api: { requestsPerMinute: 0 } }), {
    fetchImpl: async () => {
      calls += 1;
      return { ok: false, status: 401, headers: { get: () => null } };
    },
  });
  await assert.rejects(() => api.fetchMarket(), /API key was rejected/);
  assert.equal(calls, 1);
});

test('server errors are retried, then surface', async () => {
  let calls = 0;
  const api = new DonutApi(cfg({ api: { requestsPerMinute: 0, retries: 2, timeoutMs: 50 } }), {
    fetchImpl: async () => {
      calls += 1;
      return { ok: false, status: 503, headers: { get: () => '0' } };
    },
  });
  await assert.rejects(() => api.fetchMarket(), /503/);
  assert.equal(calls, 3, 'one attempt plus two retries');
});

test('trend reports nothing until history actually reaches back', () => {
  const dir = mkdtempSync(join(tmpdir(), 'donut-hist-'));
  try {
    const file = join(dir, 'h.jsonl');
    const h = new History(file, 14);
    h.append([{ key: 'x', askFloor: 100, saleRef: 100, velocityPerHour: 1 }]);
    const loaded = new History(file, 14).load();
    // A price recorded seconds ago must not be passed off as yesterday's price.
    assert.equal(loaded.priceAt('x', Date.now() - 24 * 3_600_000), null);
    assert.equal(loaded.priceAt('x', Date.now()), 100);
    assert.equal(loaded.priceAt('missing', Date.now()), null);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('history drops rows past the retention window and survives corrupt lines', () => {
  const dir = mkdtempSync(join(tmpdir(), 'donut-hist-'));
  try {
    const file = join(dir, 'h.jsonl');
    const old = Date.now() - 30 * 86_400_000;
    writeFileSync(file, [
      JSON.stringify({ t: old, k: 'ancient', a: 10, s: 10, v: 1 }),
      '{ this is not json',
      JSON.stringify({ t: Date.now() - 3_600_000, k: 'fresh', a: 20, s: 20, v: 1 }),
    ].join('\n') + '\n');

    const h = new History(file, 14).load();
    assert.equal(h.priceAt('ancient', Date.now()), null, 'a 30-day-old row is outside a 14-day window');
    assert.equal(h.priceAt('fresh', Date.now()), 20, 'the fresh row loads despite the corrupt line');
    assert.equal(h.compact(), 1, 'compaction keeps only the in-window row');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
