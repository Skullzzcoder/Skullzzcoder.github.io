import { readFileSync, existsSync } from 'node:fs';
import { log } from './log.js';
import { parseNumber, stripNamespace, normalizeEnchants, itemKey } from './items.js';

/**
 * The buy-order book (`/order` in game) has no public read API, so the bot treats
 * it as an optional input you supply yourself. With it, margins are measured
 * against the real top bid. Without it, the bot works the problem backwards and
 * tells you the highest bid that still clears your target ROI — which is the
 * number you actually type into `/order` either way.
 *
 * Accepted shapes:
 *   { "updatedAt": "2026-08-27T12:00:00Z",
 *     "orders": [ { "item": "totem_of_undying", "price": 17000, "quantity": 512 } ] }
 *   { "totem_of_undying": 17000, "netherite_ingot": 220000 }
 *   CSV: item,price,quantity
 */
export function loadOrders(cfg) {
  const { source, file } = cfg.orders;
  if (source === 'none' || !source) return { book: new Map(), updatedAt: null, present: false };
  if (!existsSync(file)) {
    log.warn(`orders.source is "${source}" but ${file} does not exist — falling back to derived max-bid advice.`);
    return { book: new Map(), updatedAt: null, present: false };
  }
  const text = readFileSync(file, 'utf8');
  const parsed = file.endsWith('.csv') ? parseCsv(text) : parseJson(text);
  const book = new Map();
  for (const entry of parsed.orders) {
    const key = orderKey(entry);
    if (!key || !(entry.price > 0)) continue;
    // Several orders can exist for one item; only the highest bid sets the price
    // you have to beat.
    const prev = book.get(key);
    if (!prev || entry.price > prev.price) book.set(key, { ...entry, key });
  }
  const ageH = parsed.updatedAt ? (Date.now() - parsed.updatedAt) / 3_600_000 : null;
  if (ageH != null && ageH > 12) {
    log.warn(`Order book snapshot is ${ageH.toFixed(1)}h old — margins from it may be stale.`);
  }
  log.info(`Loaded ${book.size} buy orders from ${file}`);
  return { book, updatedAt: parsed.updatedAt, present: book.size > 0 };
}

function parseJson(text) {
  const raw = JSON.parse(text);
  const updatedAt = raw?.updatedAt ? Date.parse(raw.updatedAt) || null : null;
  if (Array.isArray(raw)) return { updatedAt, orders: raw.map(normalizeOrder).filter(Boolean) };
  if (Array.isArray(raw?.orders)) {
    return { updatedAt, orders: raw.orders.map(normalizeOrder).filter(Boolean) };
  }
  const orders = Object.entries(raw)
    .filter(([k]) => k !== 'updatedAt')
    .map(([item, price]) => normalizeOrder({ item, price }))
    .filter(Boolean);
  return { updatedAt, orders };
}

function parseCsv(text) {
  const lines = text.split(/\r?\n/).filter((l) => l.trim());
  if (!lines.length) return { updatedAt: null, orders: [] };
  const header = lines[0].toLowerCase().split(',').map((h) => h.trim());
  const hasHeader = header.includes('item') || header.includes('price');
  const cols = hasHeader ? header : ['item', 'price', 'quantity'];
  const orders = lines.slice(hasHeader ? 1 : 0).map((line) => {
    const cells = line.split(',').map((c) => c.trim());
    const row = {};
    cols.forEach((c, i) => { row[c] = cells[i]; });
    return normalizeOrder(row);
  });
  return { updatedAt: null, orders: orders.filter(Boolean) };
}

function normalizeOrder(row) {
  if (!row) return null;
  const id = stripNamespace(row.item ?? row.id ?? row.name ?? row.type);
  const price = parseNumber(row.price ?? row.unit_price ?? row.unitPrice ?? row.bid);
  if (!id || !Number.isFinite(price) || price <= 0) return null;
  return {
    id,
    price,
    quantity: Math.max(0, Math.round(parseNumber(row.quantity ?? row.amount ?? 0)) || 0),
    enchants: normalizeEnchants(row.enchantments ?? row.enchants),
  };
}

const orderKey = (entry) => itemKey({ id: entry.id, enchants: entry.enchants });
