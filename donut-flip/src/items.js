// Canonical item identity + per-unit pricing.
//
// Two listings are only comparable if they are the same item *variant*: a Sharpness V
// netherite sword is not a plain one, and a 64-stack listed for 1.6M is not "1.6M an
// item". Every price in this bot is per single unit of a canonical key.

const SUFFIXES = { k: 1e3, m: 1e6, b: 1e9, t: 1e12, q: 1e15 };

/** Parse coin amounts that arrive as numbers, "1,234,567", "$1.5m", or "2.3B". */
export function parseNumber(value) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : NaN;
  if (typeof value === 'bigint') return Number(value);
  if (value == null) return NaN;
  if (typeof value === 'object') {
    for (const k of ['amount', 'value', 'price', 'coins']) {
      if (k in value) return parseNumber(value[k]);
    }
    return NaN;
  }
  let s = String(value).trim().toLowerCase().replace(/[$,_\s]/g, '');
  if (!s) return NaN;
  const m = /^(-?\d*\.?\d+)([kmbtq])?$/.exec(s);
  if (!m) {
    const loose = parseFloat(s.replace(/[^0-9.\-]/g, ''));
    return Number.isFinite(loose) ? loose : NaN;
  }
  const n = parseFloat(m[1]);
  return m[2] ? n * SUFFIXES[m[2]] : n;
}

export function stripNamespace(id) {
  return String(id ?? '').toLowerCase().replace(/^minecraft:/, '');
}

export function titleCase(id) {
  return stripNamespace(id)
    .split(/[_\s]+/)
    .filter(Boolean)
    .map((w) => w[0].toUpperCase() + w.slice(1))
    .join(' ');
}

/** Normalise an enchantment blob (array or map, several key spellings) to `id:level`. */
export function normalizeEnchants(raw) {
  if (!raw) return [];
  const out = [];
  const push = (id, level) => {
    const name = stripNamespace(id);
    if (!name) return;
    const lvl = Math.round(parseNumber(level));
    out.push(`${name}:${Number.isFinite(lvl) && lvl > 0 ? lvl : 1}`);
  };
  if (Array.isArray(raw)) {
    for (const e of raw) {
      if (typeof e === 'string') {
        const m = /^(.*?)[\s:]+(\d+|[ivx]+)$/i.exec(e.trim());
        if (m) push(m[1], romanToInt(m[2]));
        else push(e, 1);
      } else if (e && typeof e === 'object') {
        push(e.id ?? e.name ?? e.type ?? e.enchantment, e.level ?? e.lvl ?? e.amount ?? 1);
      }
    }
  } else if (typeof raw === 'object') {
    for (const [id, level] of Object.entries(raw)) push(id, level);
  }
  return [...new Set(out)].sort();
}

function romanToInt(token) {
  const s = String(token).trim();
  if (/^\d+$/.test(s)) return Number(s);
  const map = { i: 1, v: 5, x: 10 };
  const chars = s.toLowerCase().split('');
  let total = 0;
  for (let i = 0; i < chars.length; i += 1) {
    const cur = map[chars[i]] ?? 0;
    const next = map[chars[i + 1]] ?? 0;
    total += cur < next ? -cur : cur;
  }
  return total || 1;
}

/**
 * Build the canonical key. Anything that changes what a buyer is willing to pay has
 * to be in the key, or the bot will average incomparable items together and invent
 * margins that do not exist.
 */
export function itemKey(item) {
  const parts = [stripNamespace(item.id)];
  if (item.enchants?.length) parts.push(`e[${item.enchants.join(',')}]`);
  if (item.potion) parts.push(`p[${stripNamespace(item.potion)}]`);
  if (item.trim) parts.push(`t[${stripNamespace(item.trim)}]`);
  if (item.customName) parts.push(`n[${item.customName.toLowerCase()}]`);
  if (item.containerHash) parts.push(`c[${item.containerHash}]`);
  return parts.join('|');
}

const FIELD = (obj, ...names) => {
  for (const n of names) {
    if (obj && obj[n] != null && obj[n] !== '') return obj[n];
  }
  return undefined;
};

/**
 * Turn a raw API item blob into a normalised record. Tolerant about field names
 * because the upstream shape is not guaranteed stable.
 */
export function normalizeItem(raw) {
  const item = raw?.item && typeof raw.item === 'object' ? raw.item : raw ?? {};
  const id = stripNamespace(
    FIELD(item, 'id', 'type', 'material', 'item_id', 'itemId', 'name_id') ?? 'unknown',
  );
  const rawCount = parseNumber(
    FIELD(item, 'count', 'amount', 'quantity', 'stack_size', 'stackSize') ??
      FIELD(raw, 'count', 'amount', 'quantity'),
  );
  const count = Number.isFinite(rawCount) && rawCount > 0 ? Math.round(rawCount) : 1;
  const enchants = normalizeEnchants(
    FIELD(item, 'enchantments', 'enchants', 'enchantment') ?? undefined,
  );
  const displayNameRaw = FIELD(item, 'display_name', 'displayName', 'name', 'title');
  const customNameRaw = FIELD(item, 'custom_name', 'customName');

  const contents = FIELD(item, 'contents', 'items', 'container');
  const containerHash = Array.isArray(contents) && contents.length
    ? `${contents.length}:${contents
        .map((c) => stripNamespace(FIELD(c, 'id', 'type') ?? '?'))
        .sort()
        .join('+')
        .slice(0, 60)}`
    : undefined;

  const normalized = {
    id,
    count,
    enchants,
    potion: FIELD(item, 'potion', 'potion_type', 'potionType'),
    trim: FIELD(item, 'trim', 'armor_trim'),
    customName: customNameRaw ? stripFormatting(customNameRaw) : undefined,
    containerHash,
    displayName: displayNameRaw ? stripFormatting(displayNameRaw) : titleCase(id),
    durability: parseNumber(FIELD(item, 'damage', 'durability')),
  };
  normalized.key = itemKey(normalized);
  normalized.label = buildLabel(normalized);
  normalized.flags = variantFlags(normalized);
  return normalized;
}

/** Strip Minecraft §/& colour codes so keys and output are not full of control chars. */
export function stripFormatting(text) {
  return String(text).replace(/[§&][0-9a-fk-or]/gi, '').trim();
}

function buildLabel(item) {
  let label = item.displayName || titleCase(item.id);
  if (item.enchants.length) {
    label += ` (${item.enchants
      .map((e) => {
        const [name, lvl] = e.split(':');
        return `${titleCase(name)} ${lvl}`;
      })
      .join(', ')})`;
  }
  if (item.containerHash) label += ' [filled container]';
  return label;
}

/** Traits that make a price estimate less trustworthy, surfaced later as risk. */
function variantFlags(item) {
  const flags = [];
  if (item.enchants.length) flags.push('enchanted');
  if (item.containerHash) flags.push('container_contents');
  if (item.customName) flags.push('custom_named');
  if (Number.isFinite(item.durability) && item.durability > 0) flags.push('used_durability');
  return flags;
}
