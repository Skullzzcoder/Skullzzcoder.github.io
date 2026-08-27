import test from 'node:test';
import assert from 'node:assert/strict';
import { median, quantile, rejectOutliers, relativeSpread, weightedMedian, trendPerHour, scaleOf } from '../src/stats.js';

test('median and quantiles interpolate', () => {
  assert.equal(median([1, 2, 3, 4]), 2.5);
  assert.equal(quantile([10, 20, 30, 40], 0.25), 17.5);
  assert.ok(Number.isNaN(median([])));
});

test('outlier rejection survives a zero MAD (identical wall listings)', () => {
  const kept = rejectOutliers([10, 10, 10, 10, 10, 10, 100000]);
  assert.ok(!kept.includes(100000), 'whale price should be dropped');
  assert.ok(scaleOf([10, 10, 10, 10]) > 0, 'scale must not collapse to zero');
});

test('outlier rejection never guts the sample below its documented floor', () => {
  // Property: whatever the distribution, keep at least 3 points and at least 40%
  // of them, so a volatile item degrades into a low-confidence estimate rather
  // than an estimate built on one survivor.
  let seed = 1;
  const rnd = () => {
    seed = (seed * 1103515245 + 12345) % 2147483648;
    return seed / 2147483648;
  };
  for (let trial = 0; trial < 200; trial += 1) {
    const n = 4 + Math.floor(rnd() * 20);
    const xs = Array.from({ length: n }, () => 10 ** (rnd() * 6));
    const kept = rejectOutliers(xs);
    assert.ok(kept.length >= 3, `kept ${kept.length} of ${n}`);
    assert.ok(kept.length >= Math.ceil(n * 0.4), `kept ${kept.length} of ${n}`);
    assert.ok(kept.every((x) => xs.includes(x)));
  }
});

test('weighted median follows the units that actually moved', () => {
  // One unit at 10, a hundred at 20: the market price is 20, not 15.
  assert.equal(weightedMedian([[10, 1], [20, 100]]), 20);
});

test('relative spread separates a tight market from a chaotic one', () => {
  assert.ok(relativeSpread([100, 101, 99, 100]) < 0.1);
  assert.ok(relativeSpread([100, 900, 20, 500, 60]) > 0.5);
});

test('trend is a fractional change per hour', () => {
  const t = trendPerHour([
    { at: 0, value: 100 },
    { at: 3_600_000, value: 110 },
    { at: 7_200_000, value: 120 },
  ]);
  assert.ok(Math.abs(t - 0.0909) < 0.01);
  assert.equal(trendPerHour([{ at: 0, value: 1 }]), null);
});
