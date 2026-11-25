import { test as base, expect, type Route } from '@playwright/test';
// Existing offline specs describe one synthetic account. Keep this compatibility
// fixture in tests; production bootstrap never accepts missing profile status.
export const test = base.extend<{ syntheticProfile: { authorized: boolean } }>({
  syntheticProfile: async ({}, use) => { await use({ authorized: true }); },
  page: async ({ page, syntheticProfile }, use) => {
    const original = page.route.bind(page);
    page.route = async (url, handler, options) => original(url, async (route: Route) => {
      if (new URL(route.request().url()).pathname === '/api/profiles/status') {
        // Explicit synthetic state, independent of browser JavaScript, fake clocks,
        // Telegram polling and page teardown. Real profile tests use their own routes.
        const identity = { userId: 12345, name: 'Тестовый аккаунт', username: 'synthetic' };
        const active = { id: 'synthetic-profile', identity, confirmed: true, legacy: false, loggedOut: false };
        await route.fulfill({ json: { generation: 'synthetic-generation', active, profiles: [active], phase: syntheticProfile.authorized ? 'READY' : 'AUTHORIZING', identity: syntheticProfile.authorized ? identity : null, credentialsSource: 'saved', legacyAvailable: false } });
        return;
      }
      await handler(route, route.request());
    }, options);
    await use(page);
  },
});
export { expect };
export type { Page } from '@playwright/test';
