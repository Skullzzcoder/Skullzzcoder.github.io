import { DonutApi } from './api.js';
import { buildMarket } from './market.js';
import { loadOrders } from './orders.js';
import { findOpportunities, planTrades } from './flip.js';
import { History } from './store.js';
import { mockMarket, mockOrders } from './mock.js';
import { toListings, toSales } from './extract.js';
import { log } from './log.js';

/** One full pass: fetch → model → price → rank → allocate → persist. */
export async function runScan(cfg, { mock = false, seed = 7, useOrders = true } = {}) {
  const history = new History(cfg.output.history, cfg.windows.historyDays).load();

  let raw;
  let orders;
  if (mock) {
    const m = mockMarket({ seed });
    raw = { listings: toListings(m.listings, m.now), sales: toSales(m.sales, m.now), fetchedAt: m.now };
    orders = useOrders ? bookFrom(mockOrders({ seed, now: m.now })) : emptyBook();
    log.info(`Mock market: ${raw.listings.length} listings, ${raw.sales.length} sales`);
  } else {
    if (!cfg.apiKey) {
      throw new Error(
        'No API key. Set DONUT_API_KEY (or apiKey in config.json), or run with --mock to try the bot offline.',
      );
    }
    raw = await new DonutApi(cfg).fetchMarket();
    orders = useOrders ? loadOrders(cfg) : emptyBook();
  }

  const market = buildMarket(raw, cfg);
  const opportunities = findOpportunities(market, orders, cfg, history);
  const { eligible, plan, budget, deployed } = planTrades(opportunities, cfg);

  history.append(market.rows);
  history.compact();

  return {
    market,
    opportunities,
    eligible,
    plan,
    budget,
    deployed,
    history,
    ordersPresent: orders.present,
    ordersUpdatedAt: orders.updatedAt ? new Date(orders.updatedAt).toISOString() : null,
  };
}

function bookFrom(snapshot) {
  const book = new Map();
  for (const o of snapshot.orders) book.set(o.item, { ...o, key: o.item, price: o.price });
  return { book, updatedAt: Date.parse(snapshot.updatedAt) || null, present: true };
}

const emptyBook = () => ({ book: new Map(), updatedAt: null, present: false });
