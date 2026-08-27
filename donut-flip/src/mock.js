// Deterministic synthetic market. Lets you try the bot (and run the tests) with no
// API key and no network, and gives the scoring code a world whose right answers
// are known: `totem_of_undying` should rank well, `beacon` should be rejected for
// thin history, and `dirt` should never clear the profit filter.

function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const CATALOGUE = [
  // id, fair unit price, units sold per hour, price noise, typical stack, order discount
  { id: 'totem_of_undying', fair: 26000, perHour: 40, noise: 0.06, stack: 16, orderDiscount: 0.34 },
  { id: 'netherite_ingot', fair: 240000, perHour: 9, noise: 0.08, stack: 4, orderDiscount: 0.22 },
  { id: 'elytra', fair: 900000, perHour: 2.2, noise: 0.12, stack: 1, orderDiscount: 0.18 },
  { id: 'ancient_debris', fair: 42000, perHour: 26, noise: 0.07, stack: 32, orderDiscount: 0.25 },
  { id: 'shulker_shell', fair: 31000, perHour: 18, noise: 0.09, stack: 16, orderDiscount: 0.20 },
  { id: 'enchanted_golden_apple', fair: 155000, perHour: 6, noise: 0.10, stack: 8, orderDiscount: 0.28 },
  { id: 'diamond_block', fair: 88000, perHour: 12, noise: 0.05, stack: 8, orderDiscount: 0.12 },
  { id: 'beacon', fair: 480000, perHour: 0.15, noise: 0.30, stack: 1, orderDiscount: 0.40 }, // illiquid trap
  { id: 'dirt', fair: 12, perHour: 300, noise: 0.05, stack: 64, orderDiscount: 0.30 }, // profitless
  { id: 'sculk_catalyst', fair: 65000, perHour: 3, noise: 0.22, stack: 8, orderDiscount: 0.35 }, // volatile
];

export function mockMarket({ seed = 7, hours = 24, now = Date.now() } = {}) {
  const rnd = mulberry32(seed);
  const jitter = (v, n) => v * (1 + (rnd() * 2 - 1) * n);
  const listings = [];
  const sales = [];

  for (const def of CATALOGUE) {
    // Asks sit above the clearing price; sellers are optimistic.
    const listingCount = Math.max(1, Math.round(def.perHour / 3 + rnd() * 4));
    for (let i = 0; i < listingCount; i += 1) {
      const count = Math.max(1, Math.round(def.stack * (0.25 + rnd() * 0.75)));
      const unit = jitter(def.fair * (1.04 + rnd() * 0.25), def.noise);
      listings.push({
        item: { id: `minecraft:${def.id}`, count, display_name: def.id },
        price: Math.round(unit * count),
        seller: { name: `player${Math.floor(rnd() * 12)}` },
        listed_time: now - Math.floor(rnd() * 6 * 3_600_000),
      });
    }

    // One dumped listing well under the pack, to exercise typo-floor rejection.
    if (def.id === 'ancient_debris') {
      listings.push({
        item: { id: `minecraft:${def.id}`, count: 1 },
        price: 40,
        seller: { name: 'sniperbait' },
        listed_time: now - 60_000,
      });
    }

    const saleEvents = Math.round(def.perHour * hours * 0.35);
    for (let i = 0; i < saleEvents; i += 1) {
      const count = Math.max(1, Math.round(def.stack * (0.2 + rnd() * 0.8)));
      const unit = jitter(def.fair, def.noise);
      sales.push({
        item: { id: `minecraft:${def.id}`, count },
        price: Math.round(unit * count),
        seller: { name: `player${Math.floor(rnd() * 12)}` },
        buyer: { name: `buyer${Math.floor(rnd() * 30)}` },
        sold_at: now - Math.floor(rnd() * hours * 3_600_000),
      });
    }
  }

  // An enchanted variant, to prove it is keyed and priced separately.
  for (let i = 0; i < 6; i += 1) {
    listings.push({
      item: {
        id: 'minecraft:netherite_sword',
        count: 1,
        enchantments: [{ id: 'minecraft:sharpness', level: 5 }],
      },
      price: Math.round(jitter(1_400_000, 0.1)),
      seller: { name: `player${i}` },
      listed_time: now - i * 900_000,
    });
    sales.push({
      item: {
        id: 'minecraft:netherite_sword',
        count: 1,
        enchantments: [{ id: 'minecraft:sharpness', level: 5 }],
      },
      price: Math.round(jitter(1_250_000, 0.1)),
      sold_at: now - i * 2_400_000,
    });
  }

  return { listings, sales, now };
}

/** A matching order book: what the `/order` side would look like for that market. */
export function mockOrders({ seed = 7, now = Date.now() } = {}) {
  const rnd = mulberry32(seed + 1);
  return {
    updatedAt: new Date(now).toISOString(),
    orders: CATALOGUE.map((def) => ({
      item: def.id,
      price: Math.round(def.fair * (1 - def.orderDiscount) * (1 + (rnd() * 2 - 1) * 0.03)),
      quantity: Math.round(64 + rnd() * 640),
    })),
  };
}

export function mockFetchMarket(opts) {
  const { listings, sales, now } = mockMarket(opts);
  return { rawListings: listings, rawSales: sales, now };
}
