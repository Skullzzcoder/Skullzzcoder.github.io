import { existsSync, readFileSync } from 'node:fs';
import { log } from './log.js';
import { writeJson } from './store.js';

export function formatCoins(n) {
  if (n == null || !Number.isFinite(n)) return '-';
  const abs = Math.abs(n);
  const sign = n < 0 ? '-' : '';
  if (abs >= 1e12) return `${sign}${(abs / 1e12).toFixed(2)}t`;
  if (abs >= 1e9) return `${sign}${(abs / 1e9).toFixed(2)}b`;
  if (abs >= 1e6) return `${sign}${(abs / 1e6).toFixed(2)}m`;
  if (abs >= 1e3) return `${sign}${(abs / 1e3).toFixed(1)}k`;
  return `${sign}${Math.round(abs)}`;
}

const RISK_SHORT = {
  thin_sale_history: 'thin',
  no_recent_sales: 'nosales',
  volatile_prices: 'volatile',
  one_seller_dominates: 'monopoly',
  suspicious_low_listing: 'baitlow',
  almost_no_listings: 'nolist',
  price_falling: 'falling',
  enchanted_variant: 'ench',
  contents_priced: 'contents',
  bid_not_observed: 'nobook',
  oversupplied: 'glut',
};

const pad = (s, w, right = false) => {
  const str = String(s ?? '');
  const visible = str.replace(/\x1b\[[0-9;]*m/g, '');
  const gap = Math.max(0, w - visible.length);
  return right ? ' '.repeat(gap) + str : str + ' '.repeat(gap);
};

const dim = (s) => `\x1b[90m${s}\x1b[0m`;
const bold = (s) => `\x1b[1m${s}\x1b[0m`;

function confColour(c) {
  if (c >= 0.6) return `\x1b[32m${c.toFixed(2)}\x1b[0m`;
  if (c >= 0.35) return `\x1b[33m${c.toFixed(2)}\x1b[0m`;
  return `\x1b[31m${c.toFixed(2)}\x1b[0m`;
}

export function renderTable(rows, { top = 25, showRisks = true } = {}) {
  if (!rows.length) return dim('No opportunities cleared the filters.');
  const shown = rows.slice(0, top);
  const cols = [
    ['#', 3, true],
    ['ITEM', 34, false],
    ['BID ≤', 10, true],
    ['LIST AT', 10, true],
    ['PROFIT/U', 10, true],
    ['ROI', 8, true],
    ['SOLD/H', 8, true],
    ['PROFIT/H', 11, true],
    ['CONF', 6, true],
  ];
  if (showRisks) cols.push(['FLAGS', 26, false]);

  const lines = [bold(cols.map(([h, w, r]) => pad(h, w, r)).join(' '))];
  lines.push(dim('─'.repeat(cols.reduce((s, [, w]) => s + w + 1, -1))));

  shown.forEach((o, i) => {
    const cells = [
      pad(i + 1, 3, true),
      pad(o.label.length > 34 ? `${o.label.slice(0, 31)}...` : o.label, 34),
      pad(formatCoins(o.buyPrice), 10, true),
      pad(formatCoins(o.expectedSellPrice), 10, true),
      pad(formatCoins(o.unitProfit), 10, true),
      pad(`${o.roiPct.toFixed(1)}%`, 8, true),
      pad(o.velocityPerHour.toFixed(1), 8, true),
      pad(formatCoins(o.profitPerHour), 11, true),
      pad(confColour(o.confidence), 6, true),
    ];
    if (showRisks) {
      cells.push(pad(dim(o.risks.map((r) => RISK_SHORT[r] ?? r).join(',').slice(0, 26)), 26));
    }
    lines.push(cells.join(' '));
  });
  return lines.join('\n');
}

export function renderPlan(plan, budget, deployed) {
  if (!plan.length) return dim('No trades fit the budget.');
  const lines = [bold(`\nSuggested allocation of ${formatCoins(budget)} (deploying ${formatCoins(deployed)}):`)];
  let profit = 0;
  for (const p of plan) {
    profit += p.expectedProfit;
    lines.push(
      `  ${pad(p.label.slice(0, 30), 31)} order ${pad(`${p.units}x`, 7, true)} @ ≤${pad(formatCoins(p.bidAt), 9, true)}` +
        ` → list @ ${pad(formatCoins(p.listAt), 9, true)}  ` +
        `outlay ${pad(formatCoins(p.outlay), 9, true)}  profit ${pad(formatCoins(p.expectedProfit), 9, true)}` +
        dim(`  ~${p.expectedHours}h`),
    );
  }
  lines.push(bold(`  Expected profit on full turnover: ${formatCoins(profit)}`));
  return lines.join('\n');
}

export function renderSummary(result) {
  const { market, opportunities, eligible } = result;
  return dim(
    `Tracked ${market.rows.length} item variants · ${opportunities.length} priced · ` +
      `${eligible.length} passed filters · sales window ${market.lookbackHours}h`,
  );
}

/** JSON for the dashboard and for anything downstream. */
export function writeReport(file, result, cfg) {
  if (!file) return;
  writeJson(file, {
    generatedAt: new Date(result.market.fetchedAt).toISOString(),
    lookbackHours: result.market.lookbackHours,
    assumptions: cfg.economics,
    filters: cfg.filters,
    orderBook: { present: result.ordersPresent, updatedAt: result.ordersUpdatedAt },
    counts: {
      variants: result.market.rows.length,
      priced: result.opportunities.length,
      eligible: result.eligible.length,
    },
    opportunities: result.eligible.slice(0, Math.max(cfg.output.top, 50)),
    budget: result.budget,
    plan: result.plan,
  });
  log.info(`Wrote ${file}`);
}

/**
 * Discord alerting. Only fires for rows that are new or materially better than the
 * last alert, so a watch loop does not spam the same totem flip every ten minutes.
 */
export async function sendAlerts(rows, cfg, stateFile = 'data/alert-state.json') {
  const hook = cfg.alerts.discordWebhook;
  if (!hook || !rows.length) return { sent: 0 };

  let state = {};
  if (existsSync(stateFile)) {
    try {
      state = JSON.parse(readFileSync(stateFile, 'utf8'));
    } catch {
      state = {};
    }
  }
  const now = Date.now();
  const cooldownMs = cfg.alerts.cooldownMinutes * 60_000;
  const fresh = rows
    .filter((o) => o.score >= cfg.alerts.minScore)
    .filter((o) => {
      const prev = state[o.key];
      if (!prev) return true;
      if (now - prev.at < cooldownMs && o.score <= prev.score * 1.25) return false;
      return true;
    })
    .slice(0, 8);

  if (!fresh.length) return { sent: 0 };

  const content = [
    '**DonutSMP order-flip opportunities**',
    ...fresh.map(
      (o) =>
        `• **${o.label}** — order ≤ \`${formatCoins(o.buyPrice)}\`, list \`${formatCoins(o.expectedSellPrice)}\` ` +
        `→ \`${formatCoins(o.unitProfit)}\`/unit (${o.roiPct.toFixed(0)}% ROI, ${o.velocityPerHour.toFixed(1)}/h sold, conf ${o.confidence.toFixed(2)})` +
        (o.risks.length ? ` _[${o.risks.map((r) => RISK_SHORT[r] ?? r).join(', ')}]_` : ''),
    ),
  ].join('\n');

  try {
    const res = await fetch(hook, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ content: content.slice(0, 1900) }),
      signal: AbortSignal.timeout(10000),
    });
    if (!res.ok) {
      log.warn(`Discord webhook returned HTTP ${res.status}`);
      return { sent: 0 };
    }
  } catch (err) {
    log.warn(`Discord webhook failed: ${err.message}`);
    return { sent: 0 };
  }

  for (const o of fresh) state[o.key] = { at: now, score: o.score };
  writeJson(stateFile, state);
  log.info(`Alerted on ${fresh.length} opportunities`);
  return { sent: fresh.length };
}
