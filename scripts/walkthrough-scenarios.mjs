import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { setTimeout as pause } from 'node:timers/promises';
import { waitForRun } from './lab-client.mjs';

export async function exerciseLifecycle(request, control, directory, { signal } = {}) {
  await mkdir(directory, { recursive: true });
  const evidence = { startedAt: new Date().toISOString(), state: 'RUNNING', runs: [] };
  let active;
  const save = async (label, data) => {
    await writeFile(join(directory, `${label}.json`), JSON.stringify(data, null, 2) + '\n', { flag: 'wx' });
    evidence.runs.push({ label, runId: data.run.runId, state: data.run.state, verdict: data.run.result?.invariantVerdict });
    return data;
  };
  const begin = async parameters => {
    signal?.throwIfAborted();
    active = (await request('/demo/runs', parameters)).runId;
    return active;
  };
  const finish = async label => {
    const data = await waitForRun(request, active, { signal });
    await save(label, data);
    active = undefined;
    return data;
  };
  const run = async (label, parameters) => {
    await begin(parameters);
    const data = await finish(label);
    assert.equal(data.run.state, 'COMPLETED', label);
    assert.equal(data.run.result.completion, 'ALL_BUYERS_DISPATCHED', label);
    for (const item of data.run.result.cases) if (!item.unsafeStrategy) {
      assert.equal(item.inventoryVerdict, 'PASS', `${label}/${item.label}`);
      assert.equal(item.soldQuantity + item.finalStock, item.initialStock);
    }
    return data;
  };
  const until = async (description, predicate) => {
    const deadline = performance.now() + 40_000;
    while (performance.now() < deadline) {
      signal?.throwIfAborted();
      if (await predicate()) return;
      await pause(200, undefined, { signal });
    }
    throw new Error(`Timed out awaiting ${description}.`);
  };
  try {
    evidence.status = await request('/status');
    const comparison = await run('comparison', { scenario: 'COMPARE', buyers: 50, concurrency: 10, stock: 10 });
    assert.equal(comparison.run.result.cases.length, 5);
    for (const item of comparison.run.result.cases.filter(item => ['ATOMIC_SQL', 'PESSIMISTIC'].includes(item.strategy))) {
      assert.equal(item.uniqueSales, 10); assert.equal(item.finalStock, 0);
      assert.equal(item.http.errorsOrUnknown, 0);
    }
    const reads = await run('cold-warm', { scenario: 'COLD_WARM' });
    assert.equal(reads.run.result.observations.coldThenWarmObserved, true);
    await run('stampede', { scenario: 'STAMPEDE', buyers: 20, concurrency: 8 });
    const stale = await run('stale-fill', { scenario: 'STALE_FILL' });
    assert.equal(stale.run.result.observations.stalePublicationRejected, true);
    const lost = await run('lost-response', { scenario: 'LOST_RESPONSE', quantity: 2 });
    assert.equal(lost.run.result.uniqueSales, 1); assert.equal(lost.run.result.soldQuantity, 2);
    assert.equal(lost.run.result.http.replays, 1);
    assert.equal(lost.run.result.observations.sameKeyReplayObserved, true);

    evidence.redisBefore = (await control('REDIS_MARK')).redis;
    assert.ok(evidence.redisBefore.runId); assert.ok(evidence.redisBefore.marker);
    await control('STOP_REDIS');
    let stopped = true;
    try {
      await begin({ scenario: 'OUTAGE', durationSeconds: 120 });
      await until('real Redis outage observation', async () =>
        (await request(`/demo/runs/${active}/events`)).events.some(event => event.kind === 'WAITING_FOR_REDIS'));
      const during = await request(`/demo/runs/${active}/export`);
      assert.ok(during.attempts.some(a => a.response?.body?.source === 'DATABASE_FALLBACK'));
      assert.ok(during.attempts.some(a => a.response?.body?.code === 'SOLD'));
      assert.ok(during.attempts.some(a => a.response?.body?.code === 'REDIS_UNAVAILABLE'));
      assert.ok(during.attempts.some(a => a.response?.rateLimit === 'BYPASSED'));
      await control('START_REDIS'); stopped = false;
      evidence.redisAfter = (await control('REDIS_IDENTITY')).redis;
      assert.notEqual(evidence.redisAfter.runId, evidence.redisBefore.runId);
      assert.equal(evidence.redisAfter.marker, evidence.redisBefore.marker);
      const outage = await finish('outage');
      assert.equal(outage.run.state, 'COMPLETED');
      assert.equal(outage.run.result.observations.recoveryObserved, true);
      assert.equal(outage.run.result.invariantVerdict, 'PASS');
    } finally { if (stopped) await control('START_REDIS'); }

    await begin({ scenario: 'PURCHASE', buyers: 100, concurrency: 1, jitterMs: 100 });
    await until('an accepted purchase before process interruption', async () =>
      (await request(`/demo/runs/${active}`)).httpResponses >= 1);
    const interruptedId = active;
    await control('RESTART_APP');
    const interrupted = await finish('interrupted');
    assert.equal(interrupted.run.state, 'INTERRUPTED');
    assert.equal(interrupted.run.result.invariantVerdict, 'INCONCLUSIVE');
    assert.equal(interrupted.run.result.elapsedMs, null);
    assert.equal(interrupted.run.result.http.httpAttemptsPerSecond, null);
    const retained = await request(`/demo/runs/${comparison.run.runId}/export`);
    assert.deepEqual(retained, comparison);
    assert.deepEqual(await request(`/demo/runs/${lost.run.runId}/export`), lost);
    await control('RESTART_APP');
    assert.deepEqual(await request(`/demo/runs/${interruptedId}/export`), interrupted);
    evidence.exportSurvivedTwoRestarts = true;
    evidence.state = 'COMPLETED';
    return evidence;
  } catch (error) {
    evidence.state = 'FAILED'; evidence.error = error.message;
    if (active) {
      evidence.unsettledRunId = active;
      try {
        await request(`/demo/runs/${active}/cancel`, {});
        await save('failed-run', await waitForRun(request, active, { timeoutMs: 45_000 }));
        delete evidence.unsettledRunId;
      } catch (cleanup) { evidence.cleanupError = cleanup.message; }
    }
    throw error;
  } finally {
    evidence.endedAt = new Date().toISOString();
    await writeFile(join(directory, 'lifecycle.json'), JSON.stringify(evidence, null, 2) + '\n');
  }
}
