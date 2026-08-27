// Tolerant readers for upstream JSON.
//
// The public DonutSMP API is not versioned in a way we control and third-party
// mirrors reshape it, so nothing here assumes an exact envelope. We locate the
// record array, then read each field by trying known spellings in priority order.
// `donut-flip probe` prints what this saw, so a shape change is a config fix
// rather than a code change.

import { normalizeItem, parseNumber } from './items.js';

const PRICE_FIELDS = [
  'price', 'buy_now', 'buyNow', 'buy_it_now', 'cost', 'sale_price', 'salePrice',
  'total_price', 'totalPrice', 'coins', 'value', 'amount',
];
const TIME_FIELDS = [
  'listed_time', 'listedTime', 'listed_at', 'time', 'timestamp', 'created_at',
  'sold_at', 'soldAt', 'date', 'end', 'listed',
];

/** Depth-first search for the most plausible array of records in an unknown payload. */
export function findRecordArray(payload) {
  if (Array.isArray(payload)) return payload;
  if (!payload || typeof payload !== 'object') return [];
  const preferred = ['result', 'results', 'data', 'listings', 'auctions', 'items', 'transactions', 'entries'];
  for (const key of preferred) {
    const v = payload[key];
    if (Array.isArray(v)) return v;
    if (v && typeof v === 'object') {
      const nested = findRecordArray(v);
      if (nested.length) return nested;
    }
  }
  let best = [];
  for (const v of Object.values(payload)) {
    if (Array.isArray(v) && v.length > best.length && v.some((x) => x && typeof x === 'object')) {
      best = v;
    } else if (v && typeof v === 'object') {
      const nested = findRecordArray(v);
      if (nested.length > best.length) best = nested;
    }
  }
  return best;
}

function pick(obj, fields) {
  for (const f of fields) {
    if (obj && obj[f] != null && obj[f] !== '') return obj[f];
  }
  return undefined;
}

/** Epoch seconds, epoch millis and ISO strings all arrive in the wild. */
export function toMillis(value) {
  if (value == null) return undefined;
  if (typeof value === 'string' && /[-:tz]/i.test(value) && !/^\d+$/.test(value.trim())) {
    const parsed = Date.parse(value);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
  const n = parseNumber(value);
  if (!Number.isFinite(n) || n <= 0) return undefined;
  if (n > 1e17) return Math.round(n / 1e6); // microseconds
  if (n > 1e14) return Math.round(n / 1e3); // nanos-ish / already too large
  if (n > 1e11) return Math.round(n); // millis
  return Math.round(n * 1000); // seconds
}

function readPrice(raw, count) {
  // `amount` doubles as a quantity in some shapes: only trust it as a price when
  // nothing better exists and it is not simply echoing the stack size.
  for (const field of PRICE_FIELDS) {
    if (raw?.[field] == null || raw[field] === '') continue;
    const n = parseNumber(raw[field]);
    if (!Number.isFinite(n) || n <= 0) continue;
    if (field === 'amount' && count > 1 && Math.round(n) === count) continue;
    return n;
  }
  const nested = raw?.item ?? raw?.auction;
  if (nested && nested !== raw) return readPrice(nested, count);
  return NaN;
}

function playerName(value) {
  if (!value) return undefined;
  if (typeof value === 'string') return value;
  return value.name ?? value.username ?? value.player ?? value.uuid ?? undefined;
}

/**
 * @param {'listing'|'sale'} kind
 * @returns {object|null} normalised record, or null when the row is unusable
 */
export function toRecord(raw, kind, now = Date.now()) {
  if (!raw || typeof raw !== 'object') return null;
  const item = normalizeItem(raw);
  if (!item.id || item.id === 'unknown') return null;

  const total = readPrice(raw, item.count);
  if (!Number.isFinite(total) || total <= 0) return null;

  const unitPrice = total / item.count;
  if (!Number.isFinite(unitPrice) || unitPrice <= 0) return null;

  const at = toMillis(pick(raw, TIME_FIELDS)) ?? now;
  const record = {
    kind,
    key: item.key,
    item,
    count: item.count,
    totalPrice: total,
    unitPrice,
    at,
    seller: playerName(pick(raw, ['seller', 'lister', 'owner', 'player'])),
  };
  if (kind === 'sale') record.buyer = playerName(pick(raw, ['buyer', 'purchaser']));
  return record;
}

export const toListings = (rows, now) =>
  rows.map((r) => toRecord(r, 'listing', now)).filter(Boolean);
export const toSales = (rows, now) => rows.map((r) => toRecord(r, 'sale', now)).filter(Boolean);
