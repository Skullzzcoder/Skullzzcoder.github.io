import test from 'node:test';
import assert from 'node:assert/strict';
import { findRecordArray, toMillis, toRecord } from '../src/extract.js';

test('the record array is found under any common envelope', () => {
  assert.equal(findRecordArray({ status: 200, result: [{ a: 1 }, { a: 2 }] }).length, 2);
  assert.equal(findRecordArray({ data: { listings: [{ a: 1 }] } }).length, 1);
  assert.equal(findRecordArray([{ a: 1 }]).length, 1);
  assert.deepEqual(findRecordArray({ status: 200 }), []);
});

test('timestamps normalise from seconds, millis and ISO', () => {
  assert.equal(toMillis(1700000000), 1700000000000);
  assert.equal(toMillis(1700000000000), 1700000000000);
  assert.equal(toMillis('2026-01-02T03:04:05Z'), Date.parse('2026-01-02T03:04:05Z'));
  assert.equal(toMillis(0), undefined);
});

test('a stack listing reduces to a per-unit price', () => {
  const rec = toRecord({ item: { id: 'totem_of_undying', count: 64 }, price: '1.6m' }, 'listing');
  assert.equal(rec.unitPrice, 25000);
  assert.equal(rec.totalPrice, 1600000);
});

test('"amount" is not mistaken for a price when it echoes the stack size', () => {
  // Some shapes use `amount` for quantity. Reading 16 coins as the price of a
  // 16-stack of netherite would invent an infinite margin.
  const rec = toRecord({ item: { id: 'netherite_ingot', count: 16 }, amount: 16, cost: 3200000 }, 'listing');
  assert.equal(rec.unitPrice, 200000);
});

test('unusable rows are dropped rather than priced at zero', () => {
  assert.equal(toRecord({ item: { id: 'dirt' } }, 'listing'), null, 'no price');
  assert.equal(toRecord({ item: { id: 'dirt' }, price: 0 }, 'listing'), null, 'zero price');
  assert.equal(toRecord({ price: 100 }, 'listing'), null, 'no item id');
  assert.equal(toRecord(null, 'listing'), null);
});
