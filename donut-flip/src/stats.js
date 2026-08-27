// Robust statistics. Market data is full of typo listings ("64 diamonds for 1 coin"),
// price-fixing walls and single whale sales, so nothing here may use a plain mean.

export const asc = (xs) => [...xs].sort((a, b) => a - b);

export function quantile(sorted, q) {
  if (!sorted.length) return NaN;
  if (sorted.length === 1) return sorted[0];
  const pos = (sorted.length - 1) * q;
  const lo = Math.floor(pos);
  const hi = Math.ceil(pos);
  if (lo === hi) return sorted[lo];
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
}

export const median = (xs) => quantile(asc(xs), 0.5);

/** Median absolute deviation, scaled so it is comparable to a standard deviation. */
export function mad(xs) {
  if (xs.length < 2) return 0;
  const m = median(xs);
  return 1.4826 * median(xs.map((x) => Math.abs(x - m)));
}

/**
 * Robust scale estimate. MAD collapses to zero whenever a majority of the sample
 * shares one price (very common: dozens of identical wall listings), which would
 * disable outlier rejection entirely — so fall back to the IQR, then to a small
 * fraction of the median.
 */
export function scaleOf(xs) {
  const d = mad(xs);
  if (d > 0) return d;
  const sorted = asc(xs);
  const iqr = quantile(sorted, 0.75) - quantile(sorted, 0.25);
  if (iqr > 0) return iqr / 1.349;
  const m = median(xs);
  return m > 0 ? m * 0.02 : 0;
}

/**
 * Drop points more than `threshold` robust deviations from the median. Keeps the
 * raw set when that would throw away most of the sample.
 */
export function rejectOutliers(xs, threshold = 3) {
  if (xs.length < 4) return [...xs];
  const m = median(xs);
  const scale = scaleOf(xs);
  if (!(scale > 0)) return [...xs];
  const kept = xs.filter((x) => Math.abs(x - m) <= threshold * scale);
  return kept.length >= Math.max(3, Math.ceil(xs.length * 0.4)) ? kept : [...xs];
}

/** Dispersion as a fraction of the median. 0 = every price identical. */
export function relativeSpread(xs) {
  if (xs.length < 2) return 0;
  const m = median(xs);
  if (!(m > 0)) return 0;
  return scaleOf(xs) / m;
}

/** Weighted median — used to price sales by how many units actually moved. */
export function weightedMedian(pairs) {
  const rows = pairs.filter(([v, w]) => Number.isFinite(v) && w > 0).sort((a, b) => a[0] - b[0]);
  if (!rows.length) return NaN;
  const total = rows.reduce((s, [, w]) => s + w, 0);
  let seen = 0;
  for (const [value, weight] of rows) {
    seen += weight;
    if (seen >= total / 2) return value;
  }
  return rows[rows.length - 1][0];
}

/** Least-squares slope of y over x, expressed as fractional change per hour. */
export function trendPerHour(points) {
  const rows = points.filter((p) => Number.isFinite(p.value) && p.value > 0 && Number.isFinite(p.at));
  if (rows.length < 3) return null;
  const hours = rows.map((p) => p.at / 3_600_000);
  const meanX = hours.reduce((a, b) => a + b, 0) / hours.length;
  const meanY = rows.reduce((a, b) => a + b.value, 0) / rows.length;
  let num = 0;
  let den = 0;
  rows.forEach((row, i) => {
    num += (hours[i] - meanX) * (row.value - meanY);
    den += (hours[i] - meanX) ** 2;
  });
  if (!(den > 0) || !(meanY > 0)) return null;
  return num / den / meanY;
}
