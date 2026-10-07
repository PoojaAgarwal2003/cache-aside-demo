import { test } from 'node:test';
import assert from 'node:assert/strict';
import { options, percentileSummary, summarize } from '../../scripts/benchmark.mjs';
import { localClient } from '../../scripts/lab-client.mjs';

test('nearest ranks preserve empty/one/small samples and refuse fabricated zeros', () => {
  assert.equal(percentileSummary([]).p99, null);
  assert.equal(percentileSummary([7]).p50, 7);
  const result = percentileSummary(Array.from({ length: 100 }, (_, i) => 100 - i));
  assert.deepEqual([result.p50, result.p95, result.p99], [50, 95, 99]);
  assert.match(percentileSummary([1, 2]).warning, /maximum/);
  for (const sample of [null, NaN, Infinity, -1, '2']) assert.throws(() => percentileSummary([sample]));
});

test('bounded options require real warmups/trials and reject external targets', () => {
  assert.equal(options().trials, 5);
  assert.equal(options({ scenario: 'COLD_WARM' }).buyers, 2);
  for (const input of [{ warmups: 0 }, { trials: 1 }, { trials: 21 }, { buyers: 101 }, { concurrency: 51 },
    { scenario: 'OUTAGE' }, { target: 'https://example.com' }, { scenario: 'COLD_WARM', buyers: 50 }]) {
    assert.throws(() => options(input));
  }
  for (const port of ['8080/x', 'https://example.com', '65536', '1023', '80.1']) assert.throws(() => localClient(port));
});

test('raw latency pooling separates successful attempts, cases and trial identity', () => {
  const run = id => ({
    run: { runId: id, result: { elapsedMs: 99, cases: [{ caseIndex: 0, label: 'ATOMIC_SQL', strategy: 'ATOMIC_SQL', http: {} }] } },
    attempts: [
      { caseIndex: 0, phase: 'MEASURED', state: 'RESPONSE', httpStatus: 200, elapsedMs: 10 },
      { caseIndex: 0, phase: 'MEASURED', state: 'RESPONSE', httpStatus: 409, elapsedMs: 20 },
      { caseIndex: 0, phase: 'SETUP', state: 'RESPONSE', httpStatus: 200, elapsedMs: 999 },
      { caseIndex: 1, phase: 'MEASURED', state: 'RESPONSE', httpStatus: 200, elapsedMs: 999 }
    ]
  });
  const [result] = summarize([run('a'), run('b')]);
  assert.equal(result.trialCount, 2);
  assert.equal(result.allAttemptLatency.sampleCount, 4);
  assert.equal(result.successLatency.sampleCount, 2);
  assert.equal(result.successLatency.p99, 10);
  assert.equal(result.allAttemptLatency.p99, 20);
  assert.deepEqual(result.trials.map(t => t.runId), ['a', 'b']);
});
