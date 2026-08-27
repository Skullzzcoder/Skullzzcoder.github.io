import test from 'node:test';
import assert from 'node:assert/strict';
import { parseNumber, normalizeItem, normalizeEnchants, itemKey, stripFormatting } from '../src/items.js';

test('coin amounts parse from every shape the API uses', () => {
  assert.equal(parseNumber(42), 42);
  assert.equal(parseNumber('1,250,000'), 1250000);
  assert.equal(parseNumber('1.5m'), 1500000);
  assert.equal(parseNumber('2B'), 2000000000);
  assert.equal(parseNumber({ amount: '3k' }), 3000);
  assert.ok(Number.isNaN(parseNumber(null)));
});

test('stack size is kept so prices can be reduced to per-unit', () => {
  const item = normalizeItem({ item: { id: 'minecraft:totem_of_undying', count: 64 } });
  assert.equal(item.count, 64);
  assert.equal(item.id, 'totem_of_undying');
});

test('enchantments are normalised and become part of the key', () => {
  assert.deepEqual(normalizeEnchants([{ id: 'minecraft:sharpness', level: 5 }, 'unbreaking III']),
    ['sharpness:5', 'unbreaking:3']);
  const plain = normalizeItem({ item: { id: 'netherite_sword' } });
  const ench = normalizeItem({ item: { id: 'netherite_sword', enchantments: { sharpness: 5 } } });
  assert.notEqual(plain.key, ench.key, 'enchanted and plain must never share a price');
});

test('enchant order does not change the key', () => {
  const a = itemKey({ id: 'bow', enchants: normalizeEnchants(['power V', 'infinity I']) });
  const b = itemKey({ id: 'bow', enchants: normalizeEnchants(['infinity I', 'power V']) });
  assert.equal(a, b);
});

test('colour codes are stripped from names', () => {
  assert.equal(stripFormatting('§6§lGolden §rApple'), 'Golden Apple');
});

test('filled containers are keyed apart and flagged', () => {
  const empty = normalizeItem({ item: { id: 'shulker_box' } });
  const full = normalizeItem({ item: { id: 'shulker_box', contents: [{ id: 'diamond' }] } });
  assert.notEqual(empty.key, full.key);
  assert.ok(full.flags.includes('container_contents'));
});
