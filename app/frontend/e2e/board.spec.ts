import { expect, test } from '@playwright/test';
import { CACHE_KEY, MAX_AGE_MS } from '../src/model';
import { makeBoard, makeFlight, NOW } from '../unit/fixtures';
test.beforeEach(async ({ page }) => { await page.clock.install({ time: new Date(NOW - 1000) }); await page.clock.pauseAt(new Date(NOW)); });
test('36 slots, correct columns, colours, clock, attribution and chronological blocks', async ({ page }) => {
  const flights = Array.from({ length: 25 }, (_, index) => makeFlight(index)).reverse();
  await page.route('**/api/departures', route => route.fulfill({ json: makeBoard({ flights }) }));
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'DEPARTURES FROM TERMINAL 2' })).toBeVisible();
  await expect(page.locator('table')).toHaveCount(3);
  await expect(page.locator('tbody tr')).toHaveCount(36);
  await expect(page.locator('tbody tr').first()).toContainText('ZZ1200');
  await expect(page.locator('table').nth(1).locator('tbody tr').first()).toContainText('ZZ1212');
  await expect(page.locator('table').nth(2).locator('tbody tr').first()).toContainText('ZZ1224');
  await expect(page.locator('tbody tr').last()).toHaveText('');
  for (const name of ['Sched.', 'Exp.', 'Destination', 'Flight', 'Bag Drop', 'Check-in', 'Remark'])
    await expect(page.getByRole('columnheader', { name, exact: true })).toHaveCount(3);
  await expect(page.getByText('Cancelled', { exact: true })).toHaveCSS('color', 'rgb(255, 85, 85)');
  await expect(page.getByText('Boarding', { exact: true })).toHaveCSS('color', 'rgb(255, 224, 0)');
  await expect(page.locator('.clock')).toHaveText('23:40:00');
  await page.clock.runFor(1000); await expect(page.locator('.clock')).toHaveText('23:40:01');
  await expect(page.getByRole('link', { name: 'data: AeroDataBox' })).toHaveAttribute('href', 'https://aerodatabox.com/');
  const boxes = await page.locator('table').evaluateAll(elements => elements.map(element => element.getBoundingClientRect().top));
  expect(new Set(boxes).size).toBe(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(1280);
  await page.evaluate(() => document.fonts.ready);
  const clipped = await page.locator('th, td:nth-child(1), td:nth-child(2), td:nth-child(4), td:nth-child(6), td:nth-child(7)').evaluateAll(elements => elements.filter(element => element.scrollWidth > element.clientWidth).map(element => element.textContent));
  expect(clipped).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollHeight)).toBe(720);
  await page.screenshot({ path: 'test-results/board-1280x720.png', fullPage: true });
  await page.setViewportSize({ width: 800, height: 720 });
  const wrapped = await page.locator('table').evaluateAll(elements => elements.map(element => element.getBoundingClientRect().top));
  expect(wrapped[1]).toBeGreaterThan(wrapped[0]!); expect(wrapped[2]).toBeGreaterThan(wrapped[1]!);
  await page.screenshot({ path: 'test-results/board-800x720.png', fullPage: true });
});
test('stale and valid empty states use their exact messages and replace previous flights', async ({ page }) => {
  let empty = false;
  await page.route('**/api/departures', route => route.fulfill({ json: empty ? makeBoard({ flights: [], publishedAt: new Date(NOW + 30_000).toISOString() })
    : makeBoard({ publishedAt: new Date(NOW - 76 * 60_000).toISOString(), stale: true, dataAgeSeconds: 4560 }) }));
  await page.goto('/'); await expect(page.getByRole('status')).toHaveText('Information may not be up to date');
  empty = true; await page.clock.runFor(30_000);
  await expect(page.getByText('No departures in the next hours')).toBeVisible();
  await expect(page.getByText('ZZ1200')).toHaveCount(0);
  expect(await page.evaluate(key => JSON.parse(localStorage.getItem(key)!).flights.length, CACHE_KEY)).toBe(0);
});
test('API outage uses the last copy after reload, then expires it while the page stays open', async ({ page }) => {
  const cached = makeBoard({ publishedAt: new Date(NOW - MAX_AGE_MS + 2000).toISOString() });
  await page.addInitScript(({ key, cached }) => localStorage.setItem(key, JSON.stringify(cached)), { key: CACHE_KEY, cached });
  await page.route('**/api/departures', route => route.abort('failed'));
  await page.goto('/'); await expect(page.getByRole('status')).toHaveText('Connection lost');
  await expect(page.getByText('ZZ1200')).toBeVisible();
  await page.clock.runFor(3000);
  await expect(page.getByRole('status')).toHaveText('Flight information is temporarily unavailable');
  await expect(page.getByText('ZZ1200')).toHaveCount(0);
  expect(await page.evaluate(key => localStorage.getItem(key), CACHE_KEY)).toBeNull();
});
test('NO_DATA clears a usable cache, while first-visit API failure has no flights', async ({ page }) => {
  await page.addInitScript(({ key, cached }) => localStorage.setItem(key, JSON.stringify(cached)), { key: CACHE_KEY, cached: makeBoard() });
  await page.route('**/api/departures', route => route.fulfill({ status: 503, json: { code: 'NO_DATA' } }));
  await page.goto('/'); await expect(page.getByRole('status')).toHaveText('Flight information is temporarily unavailable');
  await expect(page.getByText('ZZ1200')).toHaveCount(0);
  expect(await page.evaluate(key => localStorage.getItem(key), CACHE_KEY)).toBeNull();
});
test('first visit during an outage and recovery on the next 30 second poll', async ({ page }) => {
  let recovered = false;
  await page.route('**/api/departures', route => recovered ? route.fulfill({ json: makeBoard() }) : route.abort('failed'));
  await page.goto('/'); await expect(page.getByRole('status')).toHaveText('Flight information is temporarily unavailable');
  recovered = true; await page.clock.runFor(30_000);
  await expect(page.getByText('ZZ1200')).toBeVisible(); await expect(page.getByRole('status')).toHaveText('\u00a0');
});
test('source strings are rendered as text and cache can be disabled', async ({ page }) => {
  await page.addInitScript(() => Object.defineProperty(window, 'localStorage', { get() { throw Error('storage disabled'); } }));
  await page.route('**/api/departures', route => route.fulfill({ json: makeBoard({ flights: [{ ...makeFlight(), destination: '<img src=x onerror=alert(1)>' }] }) }));
  await page.goto('/'); await expect(page.getByText('<img src=x onerror=alert(1)>', { exact: true })).toBeVisible();
  await expect(page.locator('tbody img')).toHaveCount(0);
});
