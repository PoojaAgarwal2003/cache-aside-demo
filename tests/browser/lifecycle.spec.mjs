import { test, expect } from './fixtures.mjs';
import { exerciseLifecycle } from '../../scripts/walkthrough-scenarios.mjs';
import { localClient } from '../../scripts/lab-client.mjs';

test('whole packaged app lifecycle retains real ledger exports and Redis data across process restarts', async ({ lab }, info) => {
  test.setTimeout(240_000);
  const evidence = await exerciseLifecycle(localClient(new URL(lab.url).port), lab.command, info.outputPath('lifecycle'));
  expect(evidence.state).toBe('COMPLETED');
  expect(evidence.exportSurvivedTwoRestarts).toBe(true);
  expect(evidence.runs).toHaveLength(7);
});
