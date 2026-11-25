import { test, expect } from './fixtures';

test('offline profile bootstrap is independent of a stalled browser Telegram request', async ({ page }) => {
  await page.clock.install();
  await page.addInitScript(() => {
    const fetch = window.fetch.bind(window);
    window.fetch = (input, init) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      if (new URL(url, location.origin).pathname === '/api/telegram/status') return new Promise<Response>(() => {});
      return fetch(input, init);
    };
  });
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/events') return route.abort();
    if (path === '/api/onboarding') return route.fulfill({ json: { csrfToken: 'synthetic' } });
    if (path === '/api/notifications') return route.fulfill({ json: { items: [], unread: 0 } });
    return route.fulfill({ json: [] });
  });
  await page.goto('/?screen=accounts');
  await expect(page.getByRole('heading', { level: 1, name: 'Аккаунты Telegram' })).toBeVisible();
  await page.clock.runFor(65_000);
  await expect(page.getByRole('heading', { level: 1, name: 'Аккаунты Telegram' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { level: 1, name: 'Аккаунты Telegram' })).toBeVisible();
});
