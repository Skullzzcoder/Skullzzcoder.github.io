import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, existsSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { loadConfig } from '../src/config.js';
import { runScan } from '../src/pipeline.js';
import { History } from '../src/store.js';
import { setLevel } from '../src/log.js';

setLevel('silent');

function tempCfg(overrides = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'donut-flip-'));
  return {
    dir,
    cfg: loadConfig({
      file: null,
      env: {},
      overrides: {
        output: {
          json: join(dir, 'out.json'),
          history: join(dir, 'history.jsonl'),
        },
        ...overrides,
      },
    }),
  };
}

test('a mock scan ranks the liquid staple first and rejects the traps', async (t) => {
  const { dir, cfg } = tempCfg();
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const result = await runScan(cfg, { mock: true });

  const keys = result.eligible.map((o) => o.key);
  assert.ok(keys.length >= 4, 'the mock market should yield several flips');
  assert.ok(keys.includes('totem_of_undying'), 'the liquid staple must be recommended');
  assert.ok(!keys.includes('beacon'), 'an item trading once a week must not be recommended');
  assert.ok(!keys.includes('dirt'), 'a 12-coin item cannot clear the profit floor');

  for (const o of result.eligible) {
    assert.ok(o.buyPrice < o.expectedSellPrice, `${o.key}: bid must sit below the resale price`);
    assert.ok(o.unitProfit > 0 && o.roiPct > 0, `${o.key}: profit must be positive`);
    assert.ok(o.netProceeds <= o.expectedSellPrice, `${o.key}: proceeds must be net of tax`);
    assert.ok(o.confidence > 0 && o.confidence <= 1);
  }
  assert.ok(
    result.eligible[0].score >= result.eligible.at(-1).score,
    'results must be sorted by score',
  );
});

test('enchanted variants are tracked as their own market', async (t) => {
  const { dir, cfg } = tempCfg();
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const result = await runScan(cfg, { mock: true });
  const sword = result.opportunities.find((o) => o.key.startsWith('netherite_sword'));
  assert.ok(sword, 'the enchanted sword should be priced');
  assert.ok(sword.key.includes('sharpness:5'));
  assert.ok(sword.risks.includes('enchanted_variant'));
});

test('scanning writes a report and appends history that trend can read back', async (t) => {
  const { dir, cfg } = tempCfg();
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const { writeReport } = await import('../src/report.js');
  const result = await runScan(cfg, { mock: true });
  writeReport(cfg.output.json, result, cfg);

  assert.ok(existsSync(cfg.output.json));
  const report = JSON.parse(readFileSync(cfg.output.json, 'utf8'));
  assert.ok(report.opportunities.length > 0);
  assert.ok(report.assumptions.ahTaxPct >= 0);

  const history = new History(cfg.output.history, 14).load();
  const price = history.priceAt('totem_of_undying', Date.now());
  assert.ok(price > 0, 'the scan should have recorded a price for the staple');
});

test('without an order book every row is advisory and flagged as such', async (t) => {
  const { dir, cfg } = tempCfg();
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const result = await runScan(cfg, { mock: true, useOrders: false });
  assert.equal(result.ordersPresent, false);
  for (const o of result.eligible) {
    assert.equal(o.basis, 'derived_max_bid');
    assert.ok(o.risks.includes('bid_not_observed'));
    assert.ok(Math.abs(o.roiPct - cfg.economics.targetRoiPct) < 0.01);
  }
});

test('a real scan refuses to run without an API key', async () => {
  const cfg = loadConfig({ file: null, env: {} });
  await assert.rejects(() => runScan(cfg, { mock: false }), /API key/);
});

test('the mock market is deterministic', async (t) => {
  const a = tempCfg();
  const b = tempCfg();
  t.after(() => {
    rmSync(a.dir, { recursive: true, force: true });
    rmSync(b.dir, { recursive: true, force: true });
  });
  const one = await runScan(a.cfg, { mock: true, seed: 42 });
  const two = await runScan(b.cfg, { mock: true, seed: 42 });
  assert.deepEqual(
    one.eligible.map((o) => [o.key, o.unitProfit]),
    two.eligible.map((o) => [o.key, o.unitProfit]),
  );
});
