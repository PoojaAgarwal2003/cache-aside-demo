import { test, expect, startRun, exportRun } from './fixtures.mjs';

async function longRun(page) {
  await expect(page.locator('#start')).toBeEnabled();
  await page.getByLabel('Buyers', { exact: true }).fill('100');
  await page.getByLabel('Concurrency', { exact: true }).fill('1');
  await page.getByText('Dispatch settings and simulation delays', { exact: true }).click();
  await page.getByLabel('Dispatch jitter (ms)').fill('100');
  const id = await startRun(page);
  await expect(page.locator('#run-state')).toContainText('RUNNING');
  return id;
}

test('cancel seals genuine active dispatch and waits for final ledger evidence', async ({ page }) => {
  const id = await longRun(page);
  await expect(page.locator('#start')).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Run cold / warm' })).toBeDisabled();
  await page.getByRole('button', { name: 'Cancel and drain' }).click();
  await expect(page.locator('#run-state')).toHaveText('CANCELLED / inventory PASS');
  await expect(page.locator('#start')).toBeEnabled();
  const exported = await exportRun(page, id);
  expect(exported.run.result.quiescent).toBe(true);
  expect(exported.run.result.completion).toBe('PARTIAL');
  expect(exported.run.result.http.buyersDispatched).toBeLessThan(100);
  expect(exported.run.result.cases[0].conservation).toBe(true);
  expect((await exportRun(page, id)).attempts).toHaveLength(exported.attempts.length);
});

test('a real Redis stop exposes fallback/admission differences and recovers', async ({ page, lab }) => {
  let stopped = false;
  try {
    await lab.command('STOP_REDIS'); stopped = true;
    const id = await startRun(page, 'Observe Redis outage');
    await expect(page.locator('#events')).toContainText('WAITING_FOR_REDIS');
    const interrupted = await exportRun(page, id);
    expect(interrupted.attempts.some(attempt => attempt.response?.body?.source === 'DATABASE_FALLBACK')).toBe(true);
    expect(interrupted.attempts.some(attempt => attempt.response?.body?.code === 'SOLD')).toBe(true);
    expect(interrupted.attempts.some(attempt => attempt.response?.body?.code === 'REDIS_UNAVAILABLE')).toBe(true);
    await page.getByRole('button', { name: 'Load persisted requests' }).click();
    const fallback = interrupted.attempts.find(attempt => attempt.response?.body?.source === 'DATABASE_FALLBACK');
    await page.locator('#request-select').selectOption(String(fallback.attemptId));
    await expect(page.locator('#request-flow')).toContainText('DATABASE_FALLBACK');
    await lab.command('START_REDIS'); stopped = false;
    await expect(page.locator('#run-state')).toContainText('COMPLETED', { timeout: 40_000 });
    await expect(page.locator('#scenario-explanation')).toContainText('Real Redis initially unavailable: true');
    await expect(page.locator('#scenario-explanation')).toContainText('Recovery observed: true');
    expect((await exportRun(page, id)).run.result.observations.recoveryObserved).toBe(true);
  } finally { if (stopped) await lab.command('START_REDIS'); }
});

test('backend loss shows stale evidence and bounded retries, then preserves final export', async ({ page, lab }) => {
  const id = await startRun(page, 'Run lost-response replay');
  await expect(page.locator('#run-state')).toHaveText('COMPLETED / inventory PASS');
  const before = await exportRun(page, id);
  let stopped = false;
  try {
    await lab.command('STOP_APP'); stopped = true;
    await expect(page.locator('#connection-error')).toContainText('not fresh');
    await expect(page.locator('#start')).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Export JSON' })).toBeDisabled();
    const requests = [];
    const record = request => { if (request.url().endsWith('/status')) requests.push(Date.now()); };
    page.on('request', record);
    await page.waitForTimeout(10_500); // Observe the explicit ten-second retry bound, not a readiness sleep.
    page.off('request', record);
    expect(requests.length).toBeGreaterThan(0);
    expect(requests.length).toBeLessThanOrEqual(3);
    await lab.command('START_APP'); stopped = false;
    await page.getByRole('button', { name: 'Refresh connection and runs' }).click();
    await expect(page.locator('#connection-error')).toBeHidden();
    await expect(page.locator('#run-state')).toHaveText('COMPLETED / inventory PASS');
    expect((await exportRun(page, id)).run.result).toEqual(before.run.result);
    await page.reload();
    await expect(page.locator('#sales')).toHaveText('1');
  } finally { if (stopped) await lab.command('START_APP'); }
});

test('killing an active owned app produces INTERRUPTED, never a rerun or fake PASS', async ({ page, lab }) => {
  const id = await longRun(page);
  await lab.command('RESTART_APP');
  await page.getByRole('button', { name: 'Refresh connection and runs' }).click();
  await expect(page.locator('#run-state')).toHaveText('INTERRUPTED / inventory INCONCLUSIVE', { timeout: 25_000 });
  await expect(page.locator('#run-warning')).toContainText('Process interrupted');
  const exported = await exportRun(page, id);
  expect(exported.run.result.elapsedMs).toBeNull();
  expect(exported.run.result.http.httpAttemptsPerSecond).toBeNull();
  const ids = exported.attempts.map(attempt => attempt.attemptId);
  await page.reload();
  await expect(page.locator('#run-state')).toContainText('INTERRUPTED');
  expect((await exportRun(page, id)).attempts.map(attempt => attempt.attemptId)).toEqual(ids);
  await expect(page.locator('#db-work')).toHaveText('--');
});

test('failed final persistence fences new work; retry finalization dispatches no purchase', async ({ page, lab }) => {
  let blocked = false;
  try {
    await lab.command('BLOCK_FINALIZATION'); blocked = true;
    const id = await startRun(page, 'Run lost-response replay');
    await expect(page.getByRole('button', { name: 'Retry finalization' })).toBeVisible();
    await expect(page.locator('#start')).toBeDisabled();
    await expect(page.locator('#run-warning')).toContainText('finalization');
    const before = await exportRun(page, id);
    expect(before.run.active).toBe(true);
    expect(before.run.result).toBeNull();
    await lab.command('UNBLOCK_FINALIZATION'); blocked = false;
    await page.getByRole('button', { name: 'Retry finalization' }).click();
    await expect(page.locator('#run-state')).toHaveText('INCONCLUSIVE / inventory INCONCLUSIVE');
    const after = await exportRun(page, id);
    expect(after.attempts).toEqual(before.attempts);
    expect(after.run.result.uniqueSales).toBe(1);
    await expect(page.locator('#start')).toBeEnabled();
  } finally { if (blocked) await lab.command('UNBLOCK_FINALIZATION'); }
});

test('default profile renders the dashboard but disables mutations and private run reporting', async ({ page, lab }) => {
  try {
    await lab.command('DEFAULT_PROFILE');
    await page.reload();
    await expect(page.locator('#default-notice')).toBeVisible();
    await expect(page.locator('#start')).toBeDisabled();
    await expect(page.locator('[data-scenario="COLD_WARM"]')).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Cancel and drain' })).toBeDisabled();
    expect((await page.request.post(`${lab.url}/demo/runs`, { data: {}, headers: { Origin: lab.url } })).status()).toBe(403);
  } finally { await lab.command('RESTART_APP'); }
});

test('backend eviction and the 200-event view are explicit without losing authoritative totals', async ({ page, lab }) => {
  await page.goto('about:blank');
  const response = await page.request.post(`${lab.url}/demo/runs`, {
    data: { scenario: 'COMPARE', buyers: 100, concurrency: 25, stock: 10 }, headers: { Origin: lab.url }
  });
  expect(response.status()).toBe(202);
  const id = (await response.json()).runId;
  await expect.poll(async () => (await (await page.request.get(`${lab.url}/demo/runs/${id}`)).json()).active,
    { timeout: 60_000 }).toBe(false);
  await page.goto(`${lab.url}/?run=${id}`);
  await expect(page.locator('#run-state')).toContainText('COMPLETED');
  await expect(page.locator('#event-gap')).toContainText('evicted by backend retention');
  await expect(page.locator('#events li')).toHaveCount(200);
  const exported = await exportRun(page, id);
  expect(exported.events.droppedEvents).toBeGreaterThan(0);
  expect(exported.attempts.length).toBeGreaterThanOrEqual(500);
  expect(await page.locator('#sales').textContent()).toBe(String(exported.run.result.uniqueSales));
});
