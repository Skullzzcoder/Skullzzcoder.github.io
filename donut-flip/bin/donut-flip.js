#!/usr/bin/env node
import { loadConfig } from '../src/config.js';
import { log, setLevel } from '../src/log.js';
import { runScan } from '../src/pipeline.js';
import { DonutApi } from '../src/api.js';
import { renderTable, renderPlan, renderSummary, writeReport, sendAlerts } from '../src/report.js';
import { serve } from '../src/serve.js';
import { parseNumber } from '../src/items.js';

const USAGE = `donut-flip — DonutSMP order-flip tracker and advisor

Usage
  donut-flip scan [options]      One market pass; prints ranked flips
  donut-flip watch [options]     Rescan on an interval, alerting on new flips
  donut-flip serve [options]     Serve the dashboard over HTTP
  donut-flip probe               Dump raw API responses (diagnose shape changes)

Options
  --mock              Run against the built-in synthetic market (no API key needed)
  --no-orders         Ignore the order book; advise a max bid from target ROI instead
  --budget <coins>    Bankroll to allocate a concrete buy plan against
  --top <n>           Rows to print (default 25)
  --min-roi <pct>     Minimum ROI to report
  --min-profit <n>    Minimum profit per unit
  --min-confidence <n> Minimum confidence, 0-1
  --include <a,b>     Only items whose key contains one of these
  --exclude <a,b>     Drop items whose key contains one of these
  --interval <min>    watch: minutes between scans (default 10)
  --port <n>          serve: port (default 8787)
  --config <file>     Config file (default config.json)
  --json <file>       Where to write the JSON report
  --quiet | --verbose Log level
  -h, --help

Coin amounts accept suffixes: --budget 25m
`;

function parseArgs(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (!a.startsWith('--')) {
      if (a === '-h') args.help = true;
      else args._.push(a);
      continue;
    }
    const [flag, inline] = a.slice(2).split('=');
    const camel = flag.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
    const next = argv[i + 1];
    if (inline !== undefined) args[camel] = inline;
    else if (next && !next.startsWith('--')) {
      args[camel] = next;
      i += 1;
    } else args[camel] = true;
  }
  return args;
}

function overridesFrom(args) {
  const o = { filters: {}, output: {}, watch: {} };
  const num = (v) => {
    const n = parseNumber(v);
    return Number.isFinite(n) ? n : undefined;
  };
  if (args.budget) o.filters.budget = num(args.budget);
  if (args.minRoi) o.filters.minRoiPct = Number(args.minRoi);
  if (args.minProfit) o.filters.minUnitProfit = num(args.minProfit);
  if (args.minConfidence) o.filters.minConfidence = Number(args.minConfidence);
  if (args.include) o.filters.include = String(args.include).split(',').filter(Boolean);
  if (args.exclude) o.filters.exclude = String(args.exclude).split(',').filter(Boolean);
  if (args.top) o.output.top = Number(args.top);
  if (args.json) o.output.json = args.json;
  if (args.interval) o.watch.intervalMinutes = Number(args.interval);
  if (args.quiet) o.logLevel = 'warn';
  if (args.verbose) o.logLevel = 'debug';
  return o;
}

async function scanOnce(cfg, args) {
  const result = await runScan(cfg, { mock: !!args.mock, useOrders: !args.noOrders });
  console.log('');
  console.log(renderTable(result.eligible, { top: cfg.output.top }));
  if (result.budget) console.log(renderPlan(result.plan, result.budget, result.deployed));
  console.log('');
  console.log(renderSummary(result));
  if (!result.ordersPresent) {
    console.log(
      '\x1b[90mNo order book supplied — "BID ≤" is the highest bid that still clears ' +
        `${cfg.economics.targetRoiPct}% ROI after ${(cfg.economics.ahTaxPct * 100).toFixed(0)}% AH tax.\x1b[0m`,
    );
  }
  writeReport(cfg.output.json, result, cfg);
  return result;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const command = args._[0] ?? 'scan';
  if (args.help || command === 'help') {
    console.log(USAGE);
    return;
  }

  const cfg = loadConfig({ file: args.config ?? 'config.json', overrides: overridesFrom(args) });
  setLevel(cfg.logLevel);

  if (command === 'scan') {
    const result = await scanOnce(cfg, args);
    await sendAlerts(result.eligible, cfg);
    return;
  }

  if (command === 'watch') {
    const everyMs = Math.max(1, cfg.watch.intervalMinutes) * 60_000;
    log.info(`Watching; rescanning every ${cfg.watch.intervalMinutes} minutes. Ctrl-C to stop.`);
    for (;;) {
      try {
        const result = await scanOnce(cfg, args);
        await sendAlerts(result.eligible, cfg);
      } catch (err) {
        log.error(err.message);
      }
      await new Promise((r) => setTimeout(r, everyMs));
    }
  }

  if (command === 'serve') {
    await serve(cfg, { port: Number(args.port ?? 8787), mock: !!args.mock, useOrders: !args.noOrders });
    return;
  }

  if (command === 'probe') {
    const raw = await new DonutApi(cfg).probe();
    console.log(JSON.stringify(raw, null, 2).slice(0, 8000));
    return;
  }

  console.error(`Unknown command: ${command}\n`);
  console.log(USAGE);
  process.exitCode = 1;
}

main().catch((err) => {
  log.error(err.message);
  process.exitCode = 1;
});
