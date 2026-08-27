import { stripNamespace } from './items.js';

/**
 * Turn the market model into ranked order-flip advice.
 *
 * The chain for one item variant is:
 *   expected sell price  →  minus AH tax  →  net proceeds
 *   net proceeds         ÷  (1 + target ROI)  →  the most you should ever bid
 *   observed top order   ×  (1 + outbid)      →  what it actually costs you today
 *   profit per unit      ×  realistic throughput  →  profit per hour
 *   profit per hour      ×  confidence         →  score
 *
 * Ranking on raw margin is the classic way to lose money here: a 400% spread on
 * an item that trades twice a week is worth less than 12% on totems, and a spread
 * measured off one manipulated listing is not a spread at all.
 */
export function findOpportunities(market, orders, cfg, history = null) {
  const e = cfg.economics;
  const out = [];

  for (const row of market.rows) {
    const sell = expectedSellPrice(row, e);
    if (!sell.price || !(sell.price > 0)) continue;

    const netProceeds = sell.price * (1 - e.ahTaxPct);
    const maxBid = netProceeds / (1 + e.targetRoiPct / 100);

    const order = orders.book.get(row.key);
    const buyPrice = order ? order.price * (1 + e.outbidPct) : maxBid;
    const buyCost = buyPrice * (1 + e.orderFeePct);
    if (!(buyCost > 0)) continue;

    const unitProfit = netProceeds - buyCost;
    const roiPct = (unitProfit / buyCost) * 100;
    const throughput = throughputPerHour(row, e);
    const trend = priceTrend(row, history);
    const confidence = confidenceOf(row, sell, cfg, !!order);
    const risks = riskFlags(row, sell, order, trend, cfg);

    out.push({
      key: row.key,
      item: stripNamespace(row.item.id),
      label: row.item.label,
      basis: order ? 'observed_order' : 'derived_max_bid',
      buyPrice: round(buyPrice),
      maxBid: round(maxBid),
      observedTopOrder: order ? round(order.price) : null,
      expectedSellPrice: round(sell.price),
      sellBasis: sell.basis,
      askFloor: round(row.askFloor),
      saleRef: Number.isFinite(row.saleRef) ? round(row.saleRef) : null,
      netProceeds: round(netProceeds),
      unitProfit: round(unitProfit),
      roiPct: round(roiPct, 2),
      marginPct: round((unitProfit / netProceeds) * 100, 2),
      velocityPerHour: round(row.velocityPerHour, 2),
      salesCount: row.salesCount,
      unitsSold: row.unitsSold,
      listingCount: row.listingCount,
      listedUnits: row.listedUnits,
      competitionAtFloor: row.depth,
      throughputPerHour: round(throughput, 2),
      profitPerHour: round(throughput * unitProfit),
      capitalPerHour: round(throughput * buyCost),
      trendPct24h: trend == null ? null : round(trend * 100, 2),
      confidence: round(confidence, 3),
      score: round(throughput * unitProfit * confidence),
      risks,
    });
  }

  return out.sort((a, b) => b.score - a.score);
}

/**
 * What you can realistically sell for — not the asking price of the cheapest
 * listing. Asks are aspirational; recent sales are evidence. Take the lower.
 */
function expectedSellPrice(row, e) {
  const undercut = Number.isFinite(row.askFloor) ? row.askFloor * (1 - e.undercutPct) : NaN;
  const sale = Number.isFinite(row.saleRef) ? row.saleRef : NaN;
  if (Number.isFinite(undercut) && Number.isFinite(sale)) {
    return sale <= undercut
      ? { price: sale, basis: 'recent_sales' }
      : { price: undercut, basis: 'undercut_ask_floor' };
  }
  if (Number.isFinite(sale)) return { price: sale, basis: 'recent_sales_only' };
  if (Number.isFinite(undercut)) return { price: undercut, basis: 'ask_floor_only' };
  return { price: NaN, basis: 'none' };
}

/**
 * Units per hour you can actually turn over. Bounded by observed flow, the share
 * of it one participant captures, and how many sellers are already camped on the
 * floor you have to undercut.
 */
function throughputPerHour(row, e) {
  const flow = row.velocityPerHour;
  if (!(flow > 0)) return 0;
  const competitionPenalty = 1 / (1 + 0.15 * Math.max(0, row.depth - 1));
  return flow * e.captureShare * competitionPenalty;
}

/** Fractional price change over the last 24h, from stored snapshots. */
function priceTrend(row, history) {
  if (!history) return null;
  const past = history.priceAt(row.key, Date.now() - 24 * 3_600_000);
  const now = Number.isFinite(row.saleRef) ? row.saleRef : row.askFloor;
  if (!(past > 0) || !(now > 0)) return null;
  return (now - past) / past;
}

/**
 * How much to believe the numbers above, in [0,1]. Every factor is a multiplier,
 * so one badly-evidenced input drags the whole row down rather than being averaged
 * away by the others.
 */
function confidenceOf(row, sell, cfg, hasObservedOrder) {
  const sample = 1 - Math.exp(-row.salesCount / 8);
  const stability = 1 / (1 + 2 * (row.salePriceSpread || 0));
  const liquidity = 1 - Math.exp(-row.listingCount / 4);
  const freshness = row.newestSaleAgeH == null
    ? 0.35
    : Math.max(0.2, 1 - row.newestSaleAgeH / (cfg.windows.salesLookbackHours * 1.5));
  const concentration = 1 - Math.min(0.5, Math.max(0, row.sellerConcentration - 0.5));
  const variant = row.item.flags?.length ? 0.75 : 1;
  const evidence = sell.basis === 'ask_floor_only' ? 0.5 : 1;
  // A derived max bid is advice, not an observed spread: its profit is assumed by
  // construction, so it must not outrank a row backed by a real top bid.
  const bidEvidence = hasObservedOrder ? 1 : 0.8;
  const value = sample * stability * liquidity * freshness * concentration * variant * evidence * bidEvidence;
  return Math.max(0, Math.min(1, value));
}

function riskFlags(row, sell, order, trend, cfg) {
  const flags = [];
  if (row.salesCount < cfg.filters.minSales) flags.push('thin_sale_history');
  if (!Number.isFinite(row.saleRef)) flags.push('no_recent_sales');
  if (row.salePriceSpread > 0.35) flags.push('volatile_prices');
  if (row.sellerConcentration > 0.6 && row.listingCount >= 4) flags.push('one_seller_dominates');
  if (Number.isFinite(row.rawFloor) && row.rawFloor < row.askFloor * 0.5) flags.push('suspicious_low_listing');
  if (row.listingCount <= 1) flags.push('almost_no_listings');
  if (trend != null && trend < -0.15) flags.push('price_falling');
  if (row.item.flags?.includes('enchanted')) flags.push('enchanted_variant');
  if (row.item.flags?.includes('container_contents')) flags.push('contents_priced');
  if (!order) flags.push('bid_not_observed');
  if (row.listedUnits > row.unitsSold * 10 && row.unitsSold > 0) flags.push('oversupplied');
  return flags;
}

/** Filter and budget-allocate the ranked list into an actionable plan. */
export function planTrades(opportunities, cfg) {
  const f = cfg.filters;
  const include = f.include.map((s) => s.toLowerCase());
  const exclude = f.exclude.map((s) => s.toLowerCase());

  const eligible = opportunities.filter((o) => {
    if (!(o.unitProfit >= f.minUnitProfit)) return false;
    if (!(o.roiPct >= f.minRoiPct)) return false;
    if (!(o.confidence >= f.minConfidence)) return false;
    if (o.salesCount < f.minSales) return false;
    if (f.maxUnitPrice != null && o.buyPrice > f.maxUnitPrice) return false;
    if (include.length && !include.some((s) => o.key.includes(s))) return false;
    if (exclude.length && exclude.some((s) => o.key.includes(s))) return false;
    return o.score > 0;
  });

  if (f.budget == null) return { eligible, plan: [], budget: null };

  // Greedy by score, capped per item so one illiquid row cannot eat the bankroll,
  // and never buying more units than the market plausibly absorbs in a day.
  const perItemCap = f.budget * f.maxExposurePct;
  let remaining = f.budget;
  const plan = [];
  for (const o of eligible) {
    if (remaining <= 0) break;
    const dailyAbsorb = Math.max(1, Math.floor(o.throughputPerHour * 24));
    const spend = Math.min(perItemCap, remaining, dailyAbsorb * o.buyPrice);
    const units = Math.floor(spend / o.buyPrice);
    if (units < 1) continue;
    const outlay = units * o.buyPrice;
    plan.push({
      key: o.key,
      label: o.label,
      units,
      bidAt: o.buyPrice,
      outlay: round(outlay),
      listAt: o.expectedSellPrice,
      expectedProfit: round(units * o.unitProfit),
      expectedHours: round(units / Math.max(o.throughputPerHour, 0.01), 1),
    });
    remaining -= outlay;
  }
  return { eligible, plan, budget: f.budget, deployed: round(f.budget - remaining) };
}

function round(n, dp = 0) {
  if (!Number.isFinite(n)) return null;
  const f = 10 ** dp;
  return Math.round(n * f) / f;
}
