import { asc, quantile, median, relativeSpread, rejectOutliers, weightedMedian, trendPerHour } from './stats.js';

/**
 * Collapse raw listings and sales into one row per item variant.
 *
 * The important decisions happen here:
 *  - the ask floor ignores obvious typo/bait listings, because you cannot resell
 *    into a price that will be sniped in ten seconds;
 *  - the reference price prefers what items *actually sold for* over what sellers
 *    are *asking*, since asks are free to post and sales are not.
 */
export function buildMarket({ listings, sales, fetchedAt = Date.now() }, cfg) {
  const lookbackMs = cfg.windows.salesLookbackHours * 3_600_000;
  const groups = new Map();

  const bucket = (rec) => {
    let g = groups.get(rec.key);
    if (!g) {
      g = { key: rec.key, item: rec.item, listings: [], sales: [] };
      groups.set(rec.key, g);
    }
    // Prefer the richest label we have seen for this key.
    if ((rec.item.label?.length ?? 0) > (g.item.label?.length ?? 0)) g.item = rec.item;
    return g;
  };

  for (const l of listings) bucket(l).listings.push(l);
  for (const s of sales) {
    if (fetchedAt - s.at <= lookbackMs) bucket(s).sales.push(s);
  }

  const rows = [];
  for (const g of groups.values()) rows.push(summarise(g, cfg, fetchedAt, lookbackMs));
  return { rows, fetchedAt, lookbackHours: cfg.windows.salesLookbackHours };
}

function summarise(group, cfg, now, lookbackMs) {
  const { listings, sales } = group;
  const askPrices = listings.map((l) => l.unitPrice);
  const sorted = asc(askPrices);

  // A single 1-coin listing is a typo or a snipe-bait: it is not a price you can
  // rely on undercutting, so exclude the bottom tail before taking the floor.
  const askMedian = median(askPrices);
  const floorCut = askMedian > 0 ? askMedian * (cfg.market?.typoFloorRatio ?? 0.25) : 0;
  const credible = sorted.filter((p) => p >= floorCut);
  const askFloor = credible.length ? credible[0] : sorted[0];
  const rawFloor = sorted.length ? sorted[0] : NaN;

  const depth = credible.filter((p) => p <= askFloor * 1.1).length;
  const listedUnits = listings.reduce((s, l) => s + l.count, 0);

  const sellerCounts = new Map();
  for (const l of listings) {
    const name = l.seller ?? '?';
    sellerCounts.set(name, (sellerCounts.get(name) ?? 0) + 1);
  }
  const topSeller = [...sellerCounts.values()].sort((a, b) => b - a)[0] ?? 0;
  const sellerConcentration = listings.length ? topSeller / listings.length : 0;

  const salePrices = sales.map((s) => s.unitPrice);
  const robustSales = rejectOutliers(salePrices);
  const robustSet = new Set(robustSales);
  const saleRef = sales.length
    ? weightedMedian(
        sales.filter((s) => robustSet.has(s.unitPrice)).map((s) => [s.unitPrice, s.count]),
      )
    : NaN;
  const unitsSold = sales.reduce((s, x) => s + x.count, 0);
  const hours = lookbackMs / 3_600_000;
  const newestSaleAgeH = sales.length
    ? (now - Math.max(...sales.map((s) => s.at))) / 3_600_000
    : null;

  return {
    key: group.key,
    item: group.item,
    listingCount: listings.length,
    listedUnits,
    askFloor,
    rawFloor,
    askP25: quantile(sorted, 0.25),
    askMedian,
    depth,
    sellerConcentration,
    salesCount: sales.length,
    unitsSold,
    saleRef: Number.isFinite(saleRef) ? saleRef : NaN,
    salePriceSpread: relativeSpread(robustSales),
    velocityPerHour: unitsSold / hours,
    salesPerHour: sales.length / hours,
    newestSaleAgeH,
    intraWindowTrend: trendPerHour(sales.map((s) => ({ at: s.at, value: s.unitPrice }))),
  };
}
