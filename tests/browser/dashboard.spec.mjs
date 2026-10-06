import { readFile } from 'node:fs/promises';
import { test, expect, startRun } from './fixtures.mjs';

test('real experiment, request identities, JSON export and refreshed persisted totals', async ({ page, lab }) => {
  await expect(page.getByRole('button', { name: 'Run experiment', exact: true })).toBeEnabled();
  await page.getByLabel('Buyers', { exact: true }).fill('5');
  await page.getByLabel('Concurrency', { exact: true }).fill('2');
  await page.getByLabel('Starting stock').fill('3');
  await startRun(page);
  await expect(page.locator('#run-state')).toHaveText('COMPLETED / inventory PASS');
  await expect(page.locator('#sales')).toHaveText('3');
  await expect(page.locator('#stock')).toHaveText('0');
  const runId = new URL(page.url()).searchParams.get('run');
  const exported = await (await page.request.get(`${lab.url}/demo/runs/${runId}/export`)).json();
  expect(exported.run.result.soldQuantity).toBe(3);
  expect(exported.run.result.cases[0].conservation).toBe(true);
  await page.getByRole('button', { name: 'Load persisted requests' }).click();
  await expect(page.locator('#request-details')).toContainText('"requestId"');
  await expect(page.locator('#request-details')).toContainText(runId);
  const downloaded = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export JSON' }).click();
  const download = await downloaded;
  const saved = JSON.parse(await readFile(await download.path(), 'utf8'));
  expect(saved.run.result.soldQuantity).toBe(3);
  expect(saved.attempts).toHaveLength(5);
  await page.reload();
  await expect(page.locator('#run-state')).toHaveText('COMPLETED / inventory PASS');
  await expect(page.locator('#sales')).toHaveText('3');
});
