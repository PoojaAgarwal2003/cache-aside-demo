import { test, expect, startRun, exportRun } from './fixtures.mjs';

async function guided(page, label, scenario) {
  const id = await startRun(page, label);
  await expect(page.locator('#run-title')).toHaveText(`${scenario} experiment`);
  await expect(page.locator('#run-state')).toContainText('COMPLETED', { timeout: 60_000 });
  return exportRun(page, id);
}

test('all six guides render; cold/warm and stale-fill show recorded cache paths', async ({ page }) => {
  await expect(page.locator('[data-scenario]')).toHaveCount(6);
  const cold = await guided(page, 'Run cold / warm', 'COLD_WARM');
  expect(cold.run.result.observations.coldThenWarmObserved).toBe(true);
  await expect(page.locator('#scenario-explanation')).toContainText('Cold then warm observed: true');
  await page.getByRole('button', { name: 'Load persisted requests' }).click();
  await expect(page.locator('#request-flow')).toContainText('source DATABASE');
  await page.locator('#request-select').selectOption(String(cold.attempts[1].attemptId));
  await expect(page.locator('#request-flow')).toContainText('source REDIS_CACHE');
  const stale = await guided(page, 'Run stale-fill race', 'STALE_FILL');
  expect(stale.run.result.observations.stalePublicationRejected).toBe(true);
  await expect(page.locator('#scenario-explanation')).toContainText('Injected bounded pause');
  await expect(page.locator('#scenario-explanation')).toContainText('Stale publication rejected: true');
});

test('stampede cases report measurements, not a promised hit distribution', async ({ page }) => {
  const exported = await guided(page, 'Run stampede comparison', 'STAMPEDE');
  expect(exported.run.result.cases.map(row => row.label)).toEqual(['PROTECTION_ON', 'PROTECTION_OFF']);
  await expect(page.locator('#comparison-body tr')).toHaveCount(2);
  await expect(page.locator('#comparison-panel')).toContainText('50 / 50 buyers dispatched');
  await expect(page.locator('#scenario-explanation')).toContainText('actual USER_READ SQL counts');
});

test('guided comparison uses isolated 50/10 cases and marks NONE unsafe', async ({ page }) => {
  const exported = await guided(page, 'Run guided comparison', 'COMPARE');
  expect(exported.run.parameters.buyers).toBe(50);
  expect(exported.run.parameters.stock).toBe(10);
  await expect(page.locator('#comparison-body tr')).toHaveCount(5);
  for (const strategy of ['NONE', 'ATOMIC_SQL', 'PESSIMISTIC', 'OPTIMISTIC', 'REDIS_ASSISTED']) {
    await expect(page.locator('#comparison-body')).toContainText(strategy);
  }
  await expect(page.locator('#run-warning')).toContainText('unsafe');
  expect(exported.run.result.cases.filter(row => !row.unsafeStrategy).every(row => row.conservation && row.finalStock >= 0)).toBe(true);
  await page.getByRole('button', { name: 'Showcase layout' }).click();
  await expect(page.locator('#parameters')).not.toBeVisible();
  await expect(page.getByRole('button', { name: 'Export JSON' })).toBeVisible();
  await expect(page.locator('#comparison-panel')).toBeVisible();
  await page.getByRole('button', { name: 'Exit showcase' }).click();
});

test('lost response retains a single sale and exposes the actual replay identity', async ({ page }) => {
  const exported = await guided(page, 'Run lost-response replay', 'LOST_RESPONSE');
  expect(exported.run.result.uniqueSales).toBe(1);
  expect(exported.run.result.http.attempts).toBe(2);
  expect(exported.run.result.http.buyersDispatched).toBe(1);
  await expect(page.locator('#replays')).toHaveText('1');
  await expect(page.locator('#sales')).toHaveText('1');
  await expect(page.locator('#scenario-explanation')).toContainText('Same-key replay observed: true');
  await page.getByRole('button', { name: 'Load persisted requests' }).click();
  const retry = exported.attempts.find(attempt => attempt.phase === 'RETRY');
  await page.locator('#request-select').selectOption(String(retry.attemptId));
  await expect(page.locator('#request-details')).toContainText('"replayed": true');
  await expect(page.locator('#request-details')).toContainText(retry.response.body.purchaseId);
});

test('healthy Redis is not presented as an outage and terminal commands stay inert', async ({ page }) => {
  const exported = await guided(page, 'Observe Redis outage', 'OUTAGE');
  expect(exported.run.result.observations.redisCallUnavailableInitially).toBe(false);
  await expect(page.locator('#scenario-explanation')).toContainText('Redis was healthy');
  await page.getByText('Terminal-only Redis outage controls', { exact: true }).click();
  await expect(page.locator('#guides')).toContainText('docker compose stop redis');
});
