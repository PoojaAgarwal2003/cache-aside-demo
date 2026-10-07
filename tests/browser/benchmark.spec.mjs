import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { test, expect } from './fixtures.mjs';
import { collectBenchmark } from '../../scripts/benchmark.mjs';
import { localClient } from '../../scripts/lab-client.mjs';

test('zero-delay repeated trials exclude warmups and preserve raw persisted evidence', async ({ lab }, info) => {
  test.setTimeout(150_000);
  const request = localClient(new URL(lab.url).port);
  await expect(collectBenchmark({ out: info.outputPath('wrong-profile') }, request)).rejects.toThrow(/benchmark profile/);
  try {
    await lab.command('BENCHMARK_PROFILE');
    const directory = info.outputPath('benchmark');
    const report = await collectBenchmark({ warmups: 1, trials: 2, buyers: 8, concurrency: 4, stock: 3, out: directory }, request);
    expect(report.state).toBe('COMPLETED');
    expect(report.runs).toHaveLength(3);
    expect(report.cases).toHaveLength(5);
    expect(report.server.readDelayMs).toBe(0);
    expect(report.server.purchaseDelayMs).toBe(0);
    for (const item of report.cases) {
      expect(item.trialCount).toBe(2);
      expect(item.allAttemptLatency.sampleCount).toBe(16);
      expect(item.trials.map(t => t.runId)).not.toContain(report.runs[0].runId);
      if (!item.unsafe) for (const trial of item.trials) expect(trial.inventoryVerdict).toBe('PASS');
    }
    for (const record of report.runs) {
      const raw = JSON.parse(await readFile(join(directory, record.file), 'utf8'));
      expect(raw).toEqual(await request(`/demo/runs/${record.runId}/export`));
    }
    await expect(collectBenchmark({ warmups: 1, trials: 2, out: directory }, request)).rejects.toThrow(/EEXIST/);
  } finally { await lab.command('RESTART_APP'); }
});
