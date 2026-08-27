import test from 'node:test';
import assert from 'node:assert/strict';
import { loadConfig } from '../src/config.js';
import { buildMarket } from '../src/market.js';
import { findOpportunities, planTrades } from '../src/flip.js';

const cfg = (overrides = {}) => loadConfig({ file: null, env: {}, overrides });
const book = (entries) => ({
  book: new Map(Object.entries(entries).map(([k, v]) => [k, { key: k, price: v }])),
  updatedAt: Date.now(),
  present: true,
});
const noBook = { book: new Map(), updatedAt: null, present: false };

function market(rows, c = cfg()) {
  const now = Date.now();
  const listings = rows.listings.map((l) => ({
    kind: 'listing', key: l.key ?? l.id, item: { id: l.id, key: l.key ?? l.id, label: l.id, flags: [] },
    count: 1, unitPrice: l.price, totalPrice: l.price, at: now, seller: l.seller ?? 'a',
  }));
  const sales = (rows.sales ?? []).map((s) => ({
    kind: 'sale', key: s.key ?? s.id, item: { id: s.id, key: s.key ?? s.id, label: s.id, flags: [] },
    count: s.count ?? 1, unitPrice: s.price, totalPrice: s.price * (s.count ?? 1),
    at: now - (s.agoH ?? 1) * 3_600_000,
  }));
  return buildMarket({ listings, sales, fetchedAt: now }, c);
}

test('profit is net of AH tax and of the undercut needed to actually sell', () => {
  const c = cfg({ economics: { ahTaxPct: 0.1, undercutPct: 0.05, outbidPct: 0 } });
  const m = market({
    listings: Array.from({ length: 6 }, (_, i) => ({ id: 'x', price: 1000 + i })),
    sales: Array.from({ length: 10 }, () => ({ id: 'x', price: 1000 })),
  }, c);
  const [o] = findOpportunities(m, book({ x: 500 }), c);
  // Floor 1000 → undercut to 950; sales say 1000, so the lower (950) is used.
  assert.equal(o.expectedSellPrice, 950);
  assert.equal(o.netProceeds, 855); // 950 less 10% tax
  assert.equal(o.buyPrice, 500);
  assert.equal(o.unitProfit, 355);
});

test('what items sold for beats what sellers are asking', () => {
  const c = cfg();
  // Asks are 10x the price anything has actually traded at: a wall, not a market.
  const m = market({
    listings: Array.from({ length: 8 }, () => ({ id: 'x', price: 100000 })),
    sales: Array.from({ length: 12 }, () => ({ id: 'x', price: 10000 })),
  }, c);
  const [o] = findOpportunities(m, book({ x: 5000 }), c);
  assert.equal(o.sellBasis, 'recent_sales');
  assert.equal(o.expectedSellPrice, 10000);
});

test('a single bait listing does not become the ask floor', () => {
  const c = cfg();
  const m = market({
    listings: [{ id: 'x', price: 1 }, ...Array.from({ length: 8 }, () => ({ id: 'x', price: 50000 }))],
    sales: Array.from({ length: 8 }, () => ({ id: 'x', price: 60000 })),
  }, c);
  const [o] = findOpportunities(m, book({ x: 20000 }), c);
  assert.equal(o.askFloor, 50000);
  assert.ok(o.risks.includes('suspicious_low_listing'));
});

test('the derived max bid is exactly the bid that clears target ROI', () => {
  const c = cfg({ economics: { targetRoiPct: 25, ahTaxPct: 0, undercutPct: 0, orderFeePct: 0 } });
  const m = market({
    listings: Array.from({ length: 5 }, () => ({ id: 'x', price: 1250 })),
    sales: Array.from({ length: 8 }, () => ({ id: 'x', price: 1250 })),
  }, c);
  const [o] = findOpportunities(m, noBook, c);
  assert.equal(o.basis, 'derived_max_bid');
  assert.equal(o.buyPrice, 1000);
  assert.ok(Math.abs(o.roiPct - 25) < 0.001);
  assert.ok(o.risks.includes('bid_not_observed'));
});

test('a fat percentage on a slow cheap item loses to a thin one on a fast item', () => {
  const c = cfg();
  const m = market({
    listings: [
      ...Array.from({ length: 10 }, () => ({ id: 'liquid', price: 1000 })),
      ...Array.from({ length: 10 }, () => ({ id: 'slowcheap', price: 1200 })),
    ],
    sales: [
      ...Array.from({ length: 60 }, () => ({ id: 'liquid', price: 1000, count: 4 })),
      ...Array.from({ length: 6 }, () => ({ id: 'slowcheap', price: 1200 })),
    ],
  }, c);
  const opps = findOpportunities(m, book({ liquid: 600, slowcheap: 200 }), c);
  const liquid = opps.find((o) => o.key === 'liquid');
  const slow = opps.find((o) => o.key === 'slowcheap');
  assert.ok(slow.roiPct > liquid.roiPct * 3, 'the trap advertises a far fatter spread');
  assert.ok(liquid.score > slow.score, 'but ranking must follow profit per hour, not ROI');
});

test('turnover, not spread, is what ranking rewards', () => {
  // Same item, same margin, one trades ten times as often.
  const c = cfg();
  const m = market({
    listings: [
      ...Array.from({ length: 10 }, () => ({ id: 'fast', price: 1000 })),
      ...Array.from({ length: 10 }, () => ({ id: 'slow', price: 1000 })),
    ],
    sales: [
      ...Array.from({ length: 50 }, () => ({ id: 'fast', price: 1000, count: 10 })),
      ...Array.from({ length: 50 }, () => ({ id: 'slow', price: 1000, count: 1 })),
    ],
  }, c);
  const opps = findOpportunities(m, book({ fast: 600, slow: 600 }), c);
  const fast = opps.find((o) => o.key === 'fast');
  const slow = opps.find((o) => o.key === 'slow');
  assert.equal(fast.roiPct, slow.roiPct);
  assert.ok(fast.score > slow.score * 5);
});

test('thin history is flagged and filtered out', () => {
  const c = cfg({ filters: { minSales: 5 } });
  const m = market({
    listings: Array.from({ length: 4 }, () => ({ id: 'rare', price: 100000 })),
    sales: [{ id: 'rare', price: 100000 }],
  }, c);
  const opps = findOpportunities(m, book({ rare: 10000 }), c);
  assert.ok(opps[0].risks.includes('thin_sale_history'));
  assert.equal(planTrades(opps, c).eligible.length, 0);
});

test('one seller owning the listings is called out', () => {
  const c = cfg();
  const m = market({
    listings: Array.from({ length: 8 }, () => ({ id: 'x', price: 50000, seller: 'whale' })),
    sales: Array.from({ length: 8 }, () => ({ id: 'x', price: 50000 })),
  }, c);
  const [o] = findOpportunities(m, book({ x: 20000 }), c);
  assert.ok(o.risks.includes('one_seller_dominates'));
});

test('the budget plan respects the bankroll and the per-item cap', () => {
  const c = cfg({ filters: { budget: 1_000_000, maxExposurePct: 0.25, minUnitProfit: 1, minRoiPct: 1, minConfidence: 0, minSales: 1 } });
  const m = market({
    listings: Array.from({ length: 40 }, (_, i) => ({ id: `item${i % 4}`, price: 10000 })),
    sales: Array.from({ length: 80 }, (_, i) => ({ id: `item${i % 4}`, price: 10000, count: 8 })),
  }, c);
  const opps = findOpportunities(m, book({ item0: 5000, item1: 5000, item2: 5000, item3: 5000 }), c);
  const { plan, deployed } = planTrades(opps, c);
  assert.ok(plan.length > 1, 'capital should spread across items');
  assert.ok(deployed <= 1_000_000, `deployed ${deployed} exceeds budget`);
  for (const p of plan) {
    assert.ok(p.outlay <= 1_000_000 * 0.25 + 1e-6, `${p.key} took ${p.outlay}, over the per-item cap`);
    assert.ok(p.units >= 1);
  }
});

test('items with no sales at all never produce a confident recommendation', () => {
  const c = cfg();
  const m = market({ listings: Array.from({ length: 5 }, () => ({ id: 'x', price: 1000 })), sales: [] }, c);
  const [o] = findOpportunities(m, book({ x: 100 }), c);
  assert.ok(o.risks.includes('no_recent_sales'));
  assert.equal(o.velocityPerHour, 0);
  assert.equal(o.profitPerHour, 0, 'no observed flow means no expected profit');
});
