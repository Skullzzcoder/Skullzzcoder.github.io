import test from 'node:test';
import assert from 'node:assert/strict';
import { loadConfig, deepMerge, DEFAULTS } from '../src/config.js';

test('env and overrides layer over file defaults', () => {
  const cfg = loadConfig({
    file: null,
    env: { DONUT_API_KEY: 'abc', DONUT_BUDGET: '25000000' },
    overrides: { filters: { minRoiPct: 40 } },
  });
  assert.equal(cfg.apiKey, 'abc');
  assert.equal(cfg.filters.budget, 25000000);
  assert.equal(cfg.filters.minRoiPct, 40);
  assert.equal(cfg.economics.ahTaxPct, DEFAULTS.economics.ahTaxPct, 'untouched keys keep defaults');
});

test('deepMerge does not mutate the defaults', () => {
  const before = JSON.stringify(DEFAULTS);
  deepMerge(DEFAULTS, { economics: { ahTaxPct: 0.9 } });
  assert.equal(JSON.stringify(DEFAULTS), before);
});

test('nonsense economics are rejected up front', () => {
  assert.throws(() => loadConfig({ file: null, env: {}, overrides: { economics: { ahTaxPct: 1.5 } } }), /ahTaxPct/);
  assert.throws(() => loadConfig({ file: null, env: {}, overrides: { economics: { undercutPct: -1 } } }), /undercutPct/);
  assert.throws(() => loadConfig({ file: null, env: {}, overrides: { filters: { budget: 0 } } }), /budget/);
});
