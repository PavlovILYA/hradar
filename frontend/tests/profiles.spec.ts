import { test, expect, type Page } from '@playwright/test';
import type { ProfileStatus } from '../src/Profiles';
const a = { id: 'a', identity: { userId: 101, name: 'Synthetic Alpha', username: 'alpha' }, legacy: false, confirmed: true, loggedOut: false };
const b = { id: 'b', identity: { userId: 202, name: 'Synthetic Beta', username: 'beta' }, legacy: false, confirmed: true, loggedOut: false };
async function fixture(page: Page, phase: ProfileStatus['phase'] = 'READY', credentialsSource: ProfileStatus['credentialsSource'] = 'environment') {
  let active = a, generation = 1, currentPhase = phase;
  const writes: string[] = [], workspaceReads: string[] = [];
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname;
    if (path === '/api/profiles/status') return route.fulfill({ json: { generation: String(generation), active, profiles: [a, b], phase: currentPhase, identity: active.identity, credentialsSource, legacyAvailable: false } });
    expect(request.headers()['x-hradar-workspace'] || new URL(request.url()).searchParams.get('workspace')).toBe(String(generation));
    if (path === '/api/events') return route.abort();
    if (request.method() === 'POST') {
      expect(request.headers()['x-hradar-csrf']).toBe('synthetic-csrf'); writes.push(path);
      if (path.endsWith('/switch')) { active = path.includes('/b/') ? b : a; generation++; }
      if (path.endsWith('/confirm-owner')) { expect(request.postDataJSON()).toEqual({ userId: 101 }); currentPhase = 'READY'; }
      if (path.endsWith('/logout')) { currentPhase = 'AUTHORIZING'; active = { ...active, loggedOut: true }; generation++; }
      return route.fulfill({ json: { ok: true } });
    }
    if (path === '/api/onboarding') return route.fulfill({ json: { csrfToken: 'synthetic-csrf', state: currentPhase === 'AUTHORIZING' ? 'WaitPhoneNumber' : 'Ready' } });
    workspaceReads.push(path);
    if (path === '/api/telegram/status') return route.fulfill({ json: { authorized: currentPhase !== 'AUTHORIZING', authorizationState: 'Ready', connectionState: 'Ready' } });
    if (path === '/api/projects') return route.fulfill({ json: { items: [], total: 0 } });
    if (path === '/api/notifications') return route.fulfill({ json: { items: [], unread: 0, cursor: null } });
    return route.fulfill({ json: [] });
  });
  await page.goto('/?screen=accounts');
  return { writes, workspaceReads, setPhase: (value: typeof phase) => { currentPhase = value; } };
}
test('A → B → A discards workspace and pins API/SSE generations', async ({ page }) => {
  const data = await fixture(page);
  await expect(page.getByRole('button', { name: 'Аккаунты Telegram', exact: true }).last()).toContainText('Synthetic Alpha');
  page.on('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Переключить', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Аккаунты Telegram', exact: true }).last()).toContainText('Synthetic Beta');
  await page.getByRole('button', { name: 'Аккаунты Telegram', exact: true }).last().click();
  await page.getByRole('button', { name: 'Переключить', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Аккаунты Telegram', exact: true }).last()).toContainText('Synthetic Alpha');
  expect(data.writes).toEqual(['/api/profiles/b/switch', '/api/profiles/a/switch']);
});
test('legacy data remains quarantined until explicit owner confirmation', async ({ page }) => {
  const data = await fixture(page, 'CONFIRM_OWNER');
  await expect(page.getByRole('heading', { name: 'Подтвердите владельца данных' })).toBeVisible();
  expect(data.workspaceReads).toEqual([]);
  await page.getByRole('button', { name: 'Подтверждаю: это мои данные' }).click();
  await expect(page.getByRole('heading', { name: 'Аккаунты Telegram', level: 1 })).toBeVisible();
  expect(data.writes).toEqual(['/api/profiles/confirm-owner']);
});
test('identity mismatch blocks workspace and offers no owner override', async ({ page }) => {
  const data = await fixture(page, 'MISMATCH');
  await expect(page.getByRole('heading', { name: 'Аккаунт не совпадает' })).toBeVisible();
  expect(data.workspaceReads).toEqual([]);
  await expect(page.getByRole('button', { name: 'Подтверждаю: это мои данные' })).toHaveCount(0);
});
test('logout confirms retention and opens account login without old workspace', async ({ page }) => {
  const data = await fixture(page);
  page.on('dialog', async dialog => { expect(dialog.message()).toContain('Локальные данные сохранятся'); await dialog.accept(); });
  await page.getByRole('button', { name: 'Выйти из Telegram', exact: true }).click();
  await expect(page.getByLabel('Номер телефона', { exact: true })).toBeVisible();
  await expect(page.getByRole('navigation')).toHaveCount(0);
  expect(data.writes).toEqual(['/api/profiles/a/logout']);
});
test('mobile account controls are keyboard accessible and shared API hash stays masked', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await fixture(page);
  await expect(page.getByText('Настройки заданы окружением', { exact: false })).toBeVisible();
  await expect(page.getByLabel('Новый API hash')).toHaveCount(0);
  const button = page.getByRole('button', { name: 'Переключить', exact: true });
  await button.focus(); await expect(button).toBeFocused();
  const bounds = await button.boundingBox(); expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390);
});
test('bootstrap failure never mounts the previous workspace', async ({ page }) => {
  await page.route('**/api/**', route => route.fulfill({ status: 503, json: { error: 'offline' } }));
  await page.goto('/');
  await expect(page.getByRole('alert')).toContainText('Не удалось проверить аккаунт');
  await expect(page.getByRole('navigation')).toHaveCount(0);
});
for (const failure of ['unavailable', 'network'] as const) {
  test(`verified workspace and drafts survive ${failure} profile polling and recover`, async ({ page }) => {
    await page.clock.install();
    await fixture(page, 'READY', 'saved');
    const draft = page.getByLabel('API ID', { exact: true });
    await draft.fill('12345');
    await page.route('**/api/profiles/status', route => failure === 'network'
      ? route.abort('failed') : route.fulfill({ status: 503, json: { error: 'Synthetic unavailable' } }));
    await page.clock.runFor(1500);
    await expect(page.getByRole('alert')).toContainText('Не удалось проверить аккаунт');
    await expect(page.getByRole('heading', { level: 1, name: 'Аккаунты Telegram' })).toBeVisible();
    await expect(draft).toHaveValue('12345');
    await page.unroute('**/api/profiles/status');
    await page.clock.runFor(1500);
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(draft).toHaveValue('12345');
  });
}
test('explicit status access denial discards a previously verified workspace', async ({ page }) => {
  await page.clock.install();
  await fixture(page);
  await expect(page.getByRole('navigation')).toBeVisible();
  await page.route('**/api/profiles/status', route => route.fulfill({ status: 403, json: { error: 'Synthetic denied' } }));
  await page.clock.runFor(1500);
  await expect(page.getByRole('alert')).toContainText('Не удалось проверить аккаунт');
  await expect(page.getByRole('navigation')).toHaveCount(0);
  await expect(page.getByLabel('API ID', { exact: true })).toHaveCount(0);
});
for (const phase of ['AUTHORIZING', 'MISMATCH', 'CONFIRM_OWNER', 'ERROR', 'SWITCHING'] as const) {
  test(`confirmed workspace is removed when status becomes ${phase}`, async ({ page }) => {
    await page.clock.install();
    const data = await fixture(page);
    await expect(page.getByRole('navigation')).toBeVisible();
    data.setPhase(phase);
    await page.clock.runFor(1500);
    await expect(page.getByRole('navigation')).toHaveCount(0);
    await expect(page.getByLabel('API ID', { exact: true })).toHaveCount(0);
  });
}
test('another tab freezes stale responses and closes the previous SSE subscription', async ({ page }) => {
  await page.addInitScript(() => {
    class FakeEvents {
      static current: FakeEvents;
      onmessage: ((event: { data: string }) => void) | null = null;
      onopen: (() => void) | null = null;
      onerror: (() => void) | null = null;
      closed = false;
      constructor(public url: string) { FakeEvents.current = this; }
      close() { this.closed = true; }
    }
    class FakeNotice {
      static permission = 'granted';
      static instances: FakeNotice[] = [];
      closed = false;
      onclick: (() => void) | null = null;
      constructor() { FakeNotice.instances.push(this); }
      close() { this.closed = true; }
    }
    Object.assign(window, { EventSource: FakeEvents, syntheticEvents: FakeEvents, Notification: FakeNotice, syntheticNotices: FakeNotice });
  });
  await fixture(page);
  await expect(page.getByRole('heading', { level: 1, name: 'Аккаунты Telegram' })).toBeVisible();
  let staleClicks = 0;
  await page.route('**/api/workspace/deliveries/claim', route => route.fulfill({ json: [{ id: 'old-notice', title: 'Synthetic Alpha', body: 'Private preview' }] }));
  await page.route('**/api/workspace/inbox/old-notice', route => { staleClicks++; return route.fulfill({ json: {} }); });
  await page.evaluate(() => {
    const events = (window as unknown as { syntheticEvents: { current: { onmessage: (event: { data: string }) => void } } }).syntheticEvents.current;
    events.onmessage({ data: JSON.stringify({ unread: 1, revision: 1, byChannel: {} }) });
  });
  await expect.poll(() => page.evaluate(() => (window as unknown as { syntheticNotices: { instances: unknown[] } }).syntheticNotices.instances.length)).toBe(1);
  let release!: () => void; let entered!: () => void;
  const pending = new Promise<void>(resolve => { entered = resolve; });
  const held = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/channels', async route => { entered(); await held; await route.fulfill({ json: [{ title: 'STALE PRIVATE DATA' }] }); });
  const requestResult = page.evaluate(async () => {
    // @ts-expect-error Vite serves the application module in this browser test.
    const workspace = await import('/src/workspace.ts');
    try { const response = await workspace.workspaceFetch('/api/channels'); await response.json(); return 'accepted'; } catch { return 'rejected'; }
  });
  await pending;
  await page.evaluate(() => { const channel = new BroadcastChannel('hradar-workspace'); channel.postMessage('changing'); channel.close(); });
  await expect(page.getByRole('heading', { name: 'Переключаем аккаунт' })).toBeVisible();
  release(); expect(await requestResult).toBe('rejected');
  expect(await page.evaluate(() => {
    const events = (window as unknown as { syntheticEvents: { current: { closed: boolean; onmessage: (event: { data: string }) => void } } }).syntheticEvents.current;
    events.onmessage({ data: JSON.stringify({ unread: 777, revision: 1, byChannel: {}, telegram: { authorized: true } }) });
    return events.closed;
  })).toBe(true);
  await expect(page.getByRole('navigation')).toHaveCount(0);
  await expect(page.getByText('STALE PRIVATE DATA')).toHaveCount(0);
  expect(await page.evaluate(() => {
    const notice = (window as unknown as { syntheticNotices: { instances: { closed: boolean; onclick: () => void }[] } }).syntheticNotices.instances[0];
    notice.onclick(); return notice.closed;
  })).toBe(true);
  expect(staleClicks).toBe(0);
});
test('rejected profile mutation reloads verified workspace and allows a later switch', async ({ page }) => {
  await fixture(page);
  let attempts = 0;
  await page.route('**/api/profiles/b/switch', async route => {
    attempts++; expect(route.request().headers()['x-hradar-workspace']).toBe('1');
    if (attempts === 1) return route.fulfill({ status: 403, json: { error: 'Синтетический отказ' } });
    await route.fallback();
  });
  page.on('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Переключить', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Подключение проверено заново');
  await expect(page.getByRole('button', { name: 'Аккаунты Telegram', exact: true }).last()).toContainText('Synthetic Alpha');
  await page.getByRole('button', { name: 'Переключить', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Аккаунты Telegram', exact: true }).last()).toContainText('Synthetic Beta');
  expect(attempts).toBe(2);
});
