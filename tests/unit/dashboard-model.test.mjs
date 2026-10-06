import test from 'node:test';
import assert from 'node:assert/strict';
import { appendEvents, EVENT_LIMIT, latency, number, sqlWork, runWarning } from '../../src/main/resources/static/dashboard-model.js';

test('missing measurements remain unknown instead of zero or PASS', () => {
  assert.equal(number(null), '--');
  assert.equal(sqlWork('Pre-crash counters unavailable'), null);
  assert.equal(latency({ sampleCount: 0, p50: null }), 'No latency samples.');
  assert.match(runWarning({ active: false, state: 'INTERRUPTED', parameters: {} }), /not automatically replayed/);
});
test('bounded cursor events deduplicate, order and report local eviction', () => {
  const result = appendEvents([{ sequence: 1 }], { events: Array.from({ length: 250 }, (_, i) => ({ sequence: i + 1 })) });
  assert.equal(result.events.length, EVENT_LIMIT);
  assert.equal(result.locallyDropped, 50);
  assert.equal(result.events[0].sequence, 51);
});
test('SQL counters and unsafe warnings reflect actual fields, not HTTP inference', () => {
  assert.deepEqual(sqlWork({ USER_READ: { reads: 2, writes: 0, control: 1, executeMs: 4 } }), { calls: 3, ms: 4 });
  assert.match(runWarning({ parameters: { scenario: 'COMPARE' }, result: { cases: [{ finalStock: -1 }] } }), /NEGATIVE DATABASE STOCK/);
  assert.match(latency({ sampleCount: 2, p50: 1, p95: 4, p99: 4 }), /n=2/);
});
