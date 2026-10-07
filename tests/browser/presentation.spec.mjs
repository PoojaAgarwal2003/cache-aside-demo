import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { test, expect, startRun, exportRun } from './fixtures.mjs';
import { collectBenchmark } from '../../scripts/benchmark.mjs';
import { localClient } from '../../scripts/lab-client.mjs';

test('capture actual dashboard views and separate zero-delay raw benchmark evidence', async ({ page, lab }) => {
  test.setTimeout(240_000);
  const directory = join('test-results', 'presentation');
  await mkdir(directory, { recursive: true });
  await page.setViewportSize({ width: 1440, height: 1050 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const comparisonId = await startRun(page, 'Run guided comparison');
  await expect(page.locator('#run-state')).toContainText('COMPLETED', { timeout: 60_000 });
  await expect(page.locator('#comparison-body tr')).toHaveCount(5);
  const comparison = await exportRun(page, comparisonId);
  await page.getByRole('button', { name: 'Showcase layout' }).click();
  await page.screenshot({ path: join(directory, 'comparison.png'), fullPage: true });
  await writeFile(join(directory, 'comparison.json'), JSON.stringify(comparison, null, 2) + '\n');
  await page.getByRole('button', { name: 'Exit showcase' }).click();
  const coldId = await startRun(page, 'Run cold / warm');
  await expect(page.locator('#run-state')).toContainText('COMPLETED');
  const cold = await exportRun(page, coldId);
  expect(cold.run.result.observations.coldThenWarmObserved).toBe(true);
  await page.getByRole('button', { name: 'Load persisted requests' }).click();
  await page.locator('#request-select').selectOption(String(cold.attempts[1].attemptId));
  await expect(page.locator('#request-flow')).toContainText('REDIS_CACHE');
  await page.screenshot({ path: join(directory, 'cold-warm.png'), fullPage: true });
  await writeFile(join(directory, 'cold-warm.json'), JSON.stringify(cold, null, 2) + '\n');
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: join(directory, 'mobile.png'), fullPage: true });
  const manifest = {
    capturedAt: new Date().toISOString(), comparisonRunId: comparisonId, coldWarmRunId: coldId,
    appVersion: comparison.run.environment.appVersion,
    syntheticDelays: { readMs: comparison.run.environment.readDelayMs, purchaseMs: comparison.run.environment.purchaseDelayMs },
    screenshots: []
  };
  for (const name of ['comparison.png', 'cold-warm.png', 'mobile.png']) {
    const bytes = await readFile(join(directory, name));
    manifest.screenshots.push({ name, sha256: createHash('sha256').update(bytes).digest('hex'), bytes: bytes.length });
  }
  await writeFile(join(directory, 'capture.json'), JSON.stringify(manifest, null, 2) + '\n');
  try {
    await page.goto('about:blank'); // Keep dashboard polling out of the repeated benchmark window.
    await lab.command('BENCHMARK_PROFILE');
    const request = localClient(new URL(lab.url).port);
    const report = await collectBenchmark({ out: join(directory, 'benchmark') }, request);
    expect(report.runs).toHaveLength(7);
    expect(report.cases.every(item => item.trialCount === 5 && item.allAttemptLatency.sampleCount === 250)).toBe(true);
    const out = resolve(directory, 'wrapper-benchmark');
    const command = process.platform === 'win32' ? 'powershell.exe' : 'bash';
    const args = process.platform === 'win32'
      ? ['-NoProfile', '-File', resolve('scripts', 'benchmark.ps1'), '-Warmups', '1', '-Trials', '2', '-Scenario', 'COLD_WARM', '-OutDirectory', out]
      : [resolve('scripts', 'benchmark.sh'), '--warmups', '1', '--trials', '2', '--scenario', 'COLD_WARM', '--out', out];
    const result = await promisify(execFile)(command, args, {
      env: { ...process.env, APP_PORT: new URL(lab.url).port }, timeout: 60_000, windowsHide: true
    });
    expect(result.stdout).toContain('COMPLETED');
    expect(JSON.parse(await readFile(join(out, 'benchmark.json'), 'utf8')).cases[0].trialCount).toBe(2);
  } finally { await lab.command('RESTART_APP'); }
});
