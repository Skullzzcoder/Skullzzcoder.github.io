import { appendFileSync, readFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { log } from './log.js';

/**
 * Append-only price history, one JSON object per line. Kept deliberately dumb:
 * no database to install, cheap to append every scan, and trivially greppable.
 * Trend detection is what stops the bot recommending an item whose floor is in
 * free fall — the spread looks great right up until nobody buys it back.
 */
export class History {
  constructor(file, retentionDays = 14) {
    this.file = file;
    this.retentionMs = retentionDays * 86_400_000;
    this.byKey = new Map();
  }

  load() {
    if (!this.file || !existsSync(this.file)) return this;
    const cutoff = Date.now() - this.retentionMs;
    let dropped = 0;
    for (const line of readFileSync(this.file, 'utf8').split('\n')) {
      if (!line.trim()) continue;
      let row;
      try {
        row = JSON.parse(line);
      } catch {
        dropped += 1;
        continue;
      }
      if (!row.k || !Number.isFinite(row.t) || row.t < cutoff) continue;
      const list = this.byKey.get(row.k) ?? [];
      list.push(row);
      this.byKey.set(row.k, list);
    }
    for (const list of this.byKey.values()) list.sort((a, b) => a.t - b.t);
    if (dropped) log.warn(`Skipped ${dropped} unreadable history lines`);
    return this;
  }

  /**
   * Price recorded at or before `at`, or null when history does not reach back
   * that far. Deliberately does not fall back to the earliest record: comparing
   * against a price from five minutes ago and calling it a 24h trend is worse
   * than reporting no trend at all.
   */
  priceAt(key, at) {
    const list = this.byKey.get(key);
    if (!list?.length) return null;
    let best = null;
    for (const row of list) {
      if (row.t <= at) best = row;
      else break;
    }
    return best ? best.s ?? best.a ?? null : null;
  }

  series(key) {
    return (this.byKey.get(key) ?? []).map((r) => ({ at: r.t, sale: r.s, ask: r.a, vel: r.v }));
  }

  append(marketRows) {
    if (!this.file) return;
    mkdirSync(dirname(this.file), { recursive: true });
    const t = Date.now();
    const lines = marketRows
      .filter((r) => Number.isFinite(r.askFloor) || Number.isFinite(r.saleRef))
      .map((r) =>
        JSON.stringify({
          t,
          k: r.key,
          a: finite(r.askFloor),
          s: finite(r.saleRef),
          v: round2(r.velocityPerHour),
        }),
      );
    if (lines.length) appendFileSync(this.file, `${lines.join('\n')}\n`);
  }

  /** Rewrite the file without rows past the retention window. */
  compact() {
    if (!this.file || !existsSync(this.file)) return 0;
    const cutoff = Date.now() - this.retentionMs;
    const kept = readFileSync(this.file, 'utf8')
      .split('\n')
      .filter((line) => {
        if (!line.trim()) return false;
        try {
          return JSON.parse(line).t >= cutoff;
        } catch {
          return false;
        }
      });
    writeFileSync(this.file, kept.length ? `${kept.join('\n')}\n` : '');
    return kept.length;
  }
}

const finite = (n) => (Number.isFinite(n) ? Math.round(n) : null);
const round2 = (n) => (Number.isFinite(n) ? Math.round(n * 100) / 100 : null);

export function writeJson(file, data) {
  if (!file) return;
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, `${JSON.stringify(data, null, 2)}\n`);
}
