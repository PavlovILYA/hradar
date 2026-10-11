import { test, expect } from '@playwright/test';

test('local onboarding follows fake TDLib states and clears secret fields', async ({ page }) => {
  let state = 'Credentials';
  let bad = false;
  const expected: Record<string, string> = { Credentials: 'WaitPhoneNumber', WaitPhoneNumber: 'WaitCode', WaitCode: 'WaitPassword', WaitPassword: 'WaitEmailAddress', WaitEmailAddress: 'WaitEmailCode', WaitEmailCode: 'WaitOtherDeviceConfirmation' };
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/telegram/status') return route.fulfill({ json: { authorizationState: state, authorized: state === 'Ready', connectionState: 'Ready' } });
    if (path === '/api/events') return route.abort();
    if (path === '/api/onboarding' && route.request().method() === 'GET') return route.fulfill({ json: { state, csrfToken: 'fake-csrf', link: state === 'WaitOtherDeviceConfirmation' ? 'tg://login?token=fake' : null } });
    if (path.startsWith('/api/onboarding/') && route.request().method() === 'POST') {
      expect(route.request().headers()['x-hradar-csrf']).toBe('fake-csrf');
      if (bad) { bad = false; return route.fulfill({ status: 400, json: { error: 'Telegram отклонил ввод' } }); }
      state = expected[state];
      return route.fulfill({ json: { ok: true } });
    }
    if (path === '/api/notifications') return route.fulfill({ json: { items: [], unread: 0, cursor: null } });
    if (['/api/channels', '/api/templates', '/api/monitoring', '/api/history/syncs'].includes(path)) return route.fulfill({ json: [] });
    throw new Error(`Unmocked onboarding API ${path}`);
  });
  await page.goto('/');
  await page.getByLabel('API ID', { exact: true }).fill('12345');
  await page.getByLabel('API hash', { exact: true }).fill('a'.repeat(32));
  await page.getByRole('button', { name: 'Сохранить и продолжить' }).click();
  await page.getByLabel('Номер телефона').fill('+1234567890');
  await page.getByRole('button', { name: 'Продолжить', exact: true }).click();
  bad = true;
  await page.getByLabel('Код Telegram').fill('wrong');
  await page.getByRole('button', { name: 'Продолжить', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Telegram отклонил ввод');
  await expect(page.getByLabel('Код Telegram')).toHaveValue('');
  for (const [label, value] of [['Код Telegram','12345'], ['Пароль 2FA','fake-only'], ['Email для входа','fake@example.invalid'], ['Код из email','12345']]) {
    await page.getByLabel(label).fill(value);
    await page.getByRole('button', { name: 'Продолжить', exact: true }).click();
  }
  await expect(page.getByRole('link', { name: 'Открыть подтверждение в Telegram' })).toHaveAttribute('href','tg://login?token=fake');
  state = 'Ready';
  await expect(page.getByRole('status')).toHaveText('Telegram подключён');
});
