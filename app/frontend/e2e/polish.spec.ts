import { expect, test } from '@playwright/test';
import { makeBoard, makeFlight, NOW } from '../unit/fixtures';

const destinations = ['PALMA DE MALLORCA', 'BRUSSELS CHARLEROI', 'PARIS BEAUVAIS', 'FRANKFURT',
  'RHODES', 'KOS', 'MILAN BERGAMO', 'ROME CIAMPINO', 'ROME', 'PARIS', 'LONDON', 'PORT EXAMPLE'];
function demoBoard() {
  const remarks = ['', 'Boarding', 'Gate closed', 'Delayed', 'Cancelled'];
  return makeBoard({ flights: Array.from({ length: 36 }, (_, index) => {
    const remark = remarks[index % remarks.length]!;
    return { ...makeFlight(index), destination: destinations[index % destinations.length]!,
      expected: index % 3 === 0 ? '23:55' : '', remark,
      remarkColor: remark === 'Cancelled' ? 'RED' : remark === 'Boarding' ? 'YELLOW' : 'WHITE' };
  }) });
}
test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(NOW - 1000) });
  await page.clock.pauseAt(new Date(NOW));
});
for (const viewport of [{ width: 1920, height: 1080 }, { width: 1280, height: 720 }]) {
  test('demo board fits and matches screenshot at ' + viewport.width + 'x' + viewport.height, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    await page.route('**/api/departures', route => route.fulfill({ json: demoBoard() }));
    await page.goto('/');
    await expect(page.getByRole('heading')).toHaveText('PRAGUE AIRPORT (PRG) · DEPARTURES · TERMINAL 2');
    await expect(page.locator('tbody tr')).toHaveCount(36);
    await expect(page.locator('.publication')).toHaveText('Last update 23:40');
    await expect(page.locator('.clock')).toHaveText('23:40:00');
    await page.evaluate(() => document.fonts.ready);
    await expect.poll(() => page.locator('.destination-text').evaluateAll(elements =>
      elements.filter(element => element.scrollWidth > element.clientWidth).map(element => element.textContent))).toEqual([]);
    const geometry = await page.evaluate(() => {
      const rows = Array.from(document.querySelectorAll('tbody tr')).map(row => row.getBoundingClientRect().height);
      const tables = Array.from(document.querySelectorAll('table')).map(table => table.getBoundingClientRect().top);
      const footer = document.querySelector('footer')!.getBoundingClientRect();
      const clock = document.querySelector('.clock')!.getBoundingClientRect();
      const heading = document.querySelector('h1')!.getBoundingClientRect();
      const clipped = Array.from(document.querySelectorAll('th, td:not(.destination)'))
        .filter(element => element.scrollWidth > element.clientWidth).map(element => element.textContent);
      const names = Array.from(document.querySelectorAll('.destination-text')).map(element => ({
        height: element.getBoundingClientRect().height, style: getComputedStyle(element),
      })).map(({ height, style }) => ({ height, lineHeight: Number.parseFloat(style.lineHeight), whiteSpace: style.whiteSpace }));
      return { rows, tables, bottom: footer.bottom, headingRight: heading.right, clockLeft: clock.left,
        width: document.documentElement.scrollWidth, height: document.documentElement.scrollHeight, clipped, names };
    });
    expect(geometry.width).toBe(viewport.width); expect(geometry.height).toBe(viewport.height);
    expect(geometry.bottom).toBeLessThanOrEqual(viewport.height);
    expect(geometry.headingRight).toBeLessThan(geometry.clockLeft);
    expect(Math.max(...geometry.rows) - Math.min(...geometry.rows)).toBeLessThan(1);
    expect(new Set(geometry.tables).size).toBe(1);
    expect(geometry.clipped).toEqual([]);
    expect(geometry.names.every(name => name.whiteSpace === 'nowrap' && name.height <= name.lineHeight + 1)).toBe(true);
    await expect(page).toHaveScreenshot('board-' + viewport.width + 'x' + viewport.height + '.png',
      { animations: 'disabled', maxDiffPixelRatio: .001 });
    await testInfo.attach('Board ' + viewport.width + 'x' + viewport.height,
      { body: await page.screenshot(), contentType: 'image/png' });
  });
}
test('destination fitting reacts to resize and new data, with bounded last-resort ellipsis', async ({ page }) => {
  let destination = 'PALMA DE MALLORCA';
  await page.route('**/api/departures', route => route.fulfill({ json: makeBoard({ flights: [{ ...makeFlight(), destination }] }) }));
  await page.goto('/');
  const text = page.locator('.destination-text').first();
  await expect(text).toHaveText(destination); await page.evaluate(() => document.fonts.ready);
  await expect.poll(() => text.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
  await page.setViewportSize({ width: 1920, height: 1080 });
  await expect.poll(() => text.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
  await page.setViewportSize({ width: 1280, height: 720 });
  destination = 'A VERY LONG SYNTHETIC DESTINATION NAME THAT CANNOT FIT';
  await page.clock.runFor(30_000); await expect(text).toHaveText(destination);
  const bounded = await text.evaluate(element => ({ font: Number.parseFloat(getComputedStyle(element).fontSize),
    base: Number.parseFloat(getComputedStyle(element.parentElement!).fontSize),
    overflow: element.scrollWidth > element.clientWidth, whiteSpace: getComputedStyle(element).whiteSpace,
    ellipsis: getComputedStyle(element).textOverflow }));
  expect(bounded.font).toBeCloseTo(bounded.base * .8, 1);
  expect(bounded.overflow).toBe(true); expect(bounded.whiteSpace).toBe('nowrap'); expect(bounded.ellipsis).toBe('ellipsis');
  destination = 'KOS'; await page.clock.runFor(30_000); await expect(text).toHaveText(destination);
  await expect.poll(() => text.evaluate(element =>
    Number.parseFloat(getComputedStyle(element).fontSize) === Number.parseFloat(getComputedStyle(element.parentElement!).fontSize))).toBe(true);
});
