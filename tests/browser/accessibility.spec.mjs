import { test, expect, startRun } from './fixtures.mjs';

test('keyboard controls, narrow layout, reduced motion, bounded panels and real screenshot', async ({ page }, testInfo) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to experiments' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.locator('#start')).toBeEnabled();
  await page.getByRole('combobox', { name: 'Strategy', exact: true }).focus();
  await page.keyboard.press('End');
  await expect(page.locator('#unsafe-warning')).toBeVisible();
  await page.keyboard.press('Home');
  await expect(page.locator('#unsafe-warning')).toBeHidden();
  const id = await startRun(page, 'Run guided comparison');
  await expect(page.locator('#run-state')).toContainText('COMPLETED', { timeout: 60_000 });
  expect(id).toMatch(/^[a-f0-9-]{36}$/);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await expect(page.getByRole('region', { name: 'Scrollable strategy comparison' })).toBeVisible();
  await page.getByRole('region', { name: 'Scrollable strategy comparison' }).focus();
  await page.keyboard.press('ArrowRight');
  await expect.poll(() => page.locator('.table-scroll').evaluate(element => element.scrollLeft)).toBeGreaterThan(0);
  expect(await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches)).toBe(true);
  expect(await page.locator('.counters').evaluate(element => getComputedStyle(element).animationName)).toBe('none');
  await testInfo.attach('real-mobile-dashboard', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
});

test('invalid parameters and missing run selection stay actionable rather than fake connection loss', async ({ page, lab }) => {
  await expect(page.locator('#start')).toBeEnabled();
  await page.getByLabel('Buyers', { exact: true }).fill('1');
  await page.getByLabel('Concurrency', { exact: true }).fill('2');
  await page.getByRole('button', { name: 'Run experiment', exact: true }).click();
  await expect(page.locator('#action-error')).toContainText('Concurrency cannot exceed buyers');
  await expect(page.locator('#start')).toBeEnabled();
  await page.goto(`${lab.url}/?run=11111111-1111-4111-8111-111111111111`);
  await expect(page.locator('#run-state')).toContainText('Run not found');
  await expect(page.locator('#connection-error')).toBeHidden();
  await expect(page.locator('#start')).toBeEnabled();
  await startRun(page, 'Run cold / warm');
  await expect(page.locator('#run-state')).toHaveText('COMPLETED / inventory PASS');
  await expect(page.locator('#action-error')).toBeHidden();
});
