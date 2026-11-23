import { test as base, expect, type Route } from '@playwright/test';
// Existing offline specs describe one synthetic account. Keep this compatibility
// fixture in tests; production bootstrap never accepts missing profile status.
export const test = base.extend({
  page: async ({ page }, use) => {
    const original = page.route.bind(page);
    page.route = async (url, handler, options) => original(url, async (route: Route) => {
      if (new URL(route.request().url()).pathname === '/api/profiles/status') {
        const telegram = await page.evaluate(async () => {
          const response = await fetch('/api/telegram/status');
          return response.json().catch(() => ({})) as Promise<{ authorized?: boolean }>;
        });
        const identity = { userId: 12345, name: 'Тестовый аккаунт', username: 'synthetic' };
        const active = { id: 'synthetic-profile', identity, confirmed: true, legacy: false, loggedOut: false };
        await route.fulfill({ json: { generation: 'synthetic-generation', active, profiles: [active], phase: telegram.authorized === false ? 'AUTHORIZING' : 'READY', identity: telegram.authorized === false ? null : identity, credentialsSource: 'saved', legacyAvailable: false } });
        return;
      }
      await handler(route, route.request());
    }, options);
    await use(page);
  },
});
export { expect };
export type { Page } from '@playwright/test';
