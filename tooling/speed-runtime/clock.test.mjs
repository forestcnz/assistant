import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ScaledClock } from './clock.mjs';
test('speed changes and reset keep clocks continuous', () => {
  const clock = new ScaledClock();
  assert.equal(clock.read(1, 1000), 1000);
  clock.setFactor(2, () => 1001);
  assert.equal(clock.read(1, 1002), 1003);
  clock.setFactor(3, () => 1002);
  assert.equal(clock.read(1, 1002), 1003);
  assert.equal(clock.read(1, 1003), 1006);
  clock.setFactor(1, () => 1003);
  assert.equal(clock.read(1, 1004), 1007);
});
test('different clock domains do not share an epoch', () => {
  const clock = new ScaledClock();
  clock.read(1, 100); clock.read(7, 10000);
  clock.setFactor(2, id => id === 1 ? 101 : 10001);
  assert.equal(clock.read(1, 102), 103);
  assert.equal(clock.read(7, 10002), 10003);
});
test('invalid factors leave all clock state unchanged', () => {
  const clock = new ScaledClock(); clock.read(1, 100);
  for (const factor of [0, -1, NaN, Infinity, 11]) assert.throws(() => clock.setFactor(factor, () => 101));
  assert.equal(clock.read(1, 102), 102);
});
