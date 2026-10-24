import { test, expect } from '@playwright/test';

test('technical diagnostics copy only approved fields and identifiers', async ({ page }) => {
  await page.clock.install();
  await page.addInitScript(() => Object.defineProperty(navigator, 'clipboard', { value: {
    writeText: async (text: string) => { (window as unknown as { diagnosticCopy: string }).diagnosticCopy = text; },
  } }));
  let diagnostic: Record<string, unknown> = { telegramCode: 400, identifier: 'PHONE_CODE_INVALID', state: 'WaitCode', retryAfterSeconds: 0 };
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return route.fulfill({ json: { authorizationState: 'WaitCode', authorized: false } });
    if (path === '/api/onboarding') return route.fulfill({ json: { state: 'WaitCode', csrfToken: 'synthetic-session-secret',
      delivery: { method: 'sms', destination: '••••90' }, resendSupported: false } });
    if (path === '/api/onboarding/login') return route.fulfill({ status: diagnostic.telegramCode === 429 ? 429 : 400, json: {
      error: 'Проверьте код.', kind: diagnostic.telegramCode === 429 ? 'rate_limited' : 'invalid_code', retryAfterSeconds: diagnostic.retryAfterSeconds,
      diagnostics: { ...diagnostic, phone: '+1234567890', email: 'synthetic@example.invalid', token: 'synthetic-token', message: 'raw-secret-error', value: 'synthetic-login-code' },
    } });
    if (path === '/api/notifications') return route.fulfill({ json: { items: [], unread: 0 } });
    if (['/api/channels', '/api/templates', '/api/monitoring', '/api/history/syncs'].includes(path)) return route.fulfill({ json: [] });
    throw new Error(`Unmocked synthetic route ${path}`);
  });
  await page.goto('/');
  await page.getByLabel('Код Telegram').fill('synthetic-login-code');
  await page.getByRole('button', { name: 'Продолжить', exact: true }).click();
  await page.getByText('Технические подробности', { exact: true }).click();
  const details = page.getByTestId('login-diagnostics');
  await expect(details).toContainText('PHONE_CODE_INVALID');
  await expect(details).toContainText('400');
  for (const expected of [
    { telegramCode: 400, identifier: 'PHONE_CODE_INVALID', state: 'WaitCode', retryAfterSeconds: 0 },
    { telegramCode: null, identifier: 'UNKNOWN', state: 'WaitCode', retryAfterSeconds: 0 },
    { telegramCode: 429, identifier: 'FLOOD_WAIT', state: 'WaitCode', retryAfterSeconds: 3 },
  ]) {
    if (expected.identifier === 'UNKNOWN') {
      diagnostic = { telegramCode: '400 raw-secret-error', identifier: 'PHONE_CODE_INVALID synthetic@example.invalid', state: 'raw-secret-state', retryAfterSeconds: 0 };
      await page.getByRole('button', { name: 'Продолжить', exact: true }).click();
      await expect(details).toContainText('UNKNOWN');
    }
    if (expected.identifier === 'FLOOD_WAIT') {
      diagnostic = { ...expected };
      await page.getByRole('button', { name: 'Продолжить', exact: true }).click();
      await expect(details).toContainText('FLOOD_WAIT');
    }
    await page.getByRole('button', { name: 'Скопировать диагностику', exact: true }).click();
    const copy = await page.evaluate(() => (window as unknown as { diagnosticCopy: string }).diagnosticCopy);
    expect(JSON.parse(copy)).toEqual(expected);
    for (const secret of ['+1234567890', 'synthetic@', 'synthetic-token', 'raw-secret', 'synthetic-login-code', 'synthetic-session-secret', '••••90']) {
      expect(copy).not.toContain(secret);
      await expect(details).not.toContainText(secret);
    }
  }
  await page.evaluate(() => { Object.defineProperty(navigator.clipboard, 'writeText', { value: async () => { throw new Error('private clipboard details'); } }); });
  await page.getByRole('button', { name: 'Скопировать диагностику', exact: true }).click();
  await expect(details).toContainText('Не удалось скопировать');
  await expect(details).not.toContainText('private clipboard');
});

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
  await expect(page.getByLabel('Код Telegram')).toHaveValue('wrong');
  for (const [label, value] of [['Код Telegram','12345'], ['Пароль 2FA','fake-only'], ['Email для входа','fake@example.invalid'], ['Код из email','12345']]) {
    await page.getByLabel(label).fill(value);
    await page.getByRole('button', { name: 'Продолжить', exact: true }).click();
  }
  await expect(page.getByRole('link', { name: 'Открыть подтверждение в Telegram' })).toHaveAttribute('href','tg://login?token=fake');
  state = 'Ready';
  await expect(page.getByRole('status')).toHaveText('Telegram подключён');
});

test('delivery countdown resend and phone replacement follow server state', async ({ page }) => {
  await page.clock.install();
  let state = 'WaitCode';
  let seconds = 2;
  let resends = 0;
  let release: (() => void) | undefined;
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return route.fulfill({ json: { authorizationState: state, authorized: false, connectionState: 'Ready' } });
    if (path === '/api/onboarding') return route.fulfill({ json: { state, revision: 1, csrfToken: 'fake-csrf', canChangePhone: true,
      delivery: { method: 'telegram', destination: '••••90', length: 5 }, nextDelivery: { method: 'sms' }, resendSupported: true, resendAfterSeconds: seconds } });
    if (path === '/api/onboarding/resend') {
      resends++;
      expect(route.request().headers()['x-hradar-csrf']).toBe('fake-csrf');
      if (resends === 1) return route.fulfill({ status: 429, json: { kind: 'wait', error: 'Повторная отправка пока недоступна.', retryAfterSeconds: 1 } });
      seconds = 30;
      return route.fulfill({ json: { ok: true } });
    }
    if (path === '/api/onboarding/phone') {
      expect(route.request().postDataJSON()).toMatchObject({ state: 'WaitCode', value: '+19876543210' });
      await new Promise<void>(resolve => { release = resolve; });
      state = 'WaitEmailCode';
      return route.fulfill({ json: { ok: true } });
    }
    if (path === '/api/notifications') return route.fulfill({ json: { items: [], unread: 0, cursor: null } });
    if (['/api/channels', '/api/templates', '/api/monitoring', '/api/history/syncs'].includes(path)) return route.fulfill({ json: [] });
    throw new Error(`Unmocked synthetic route ${path}`);
  });
  await page.goto('/');
  await expect(page.getByText('В приложении Telegram', { exact: true })).toBeVisible();
  await expect(page.getByText('••••90', { exact: false })).toBeVisible();
  const resend = page.getByRole('button', { name: 'Отправить повторно', exact: true });
  await expect(resend).toBeDisabled();
  await page.clock.fastForward(1000);
  await expect(page.getByTestId('resend-countdown')).toContainText('1 сек');
  seconds = 0;
  await page.clock.fastForward(1000);
  await expect(resend).toBeEnabled();
  await resend.click();
  await expect(resend).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Продолжить', exact: true })).toBeEnabled();
  await page.clock.fastForward(1100);
  await expect(resend).toBeEnabled();
  await resend.click();
  await expect(resend).toBeDisabled();
  expect(resends).toBe(2);
  await page.getByRole('button', { name: 'Изменить номер', exact: true }).click();
  await page.getByLabel('Новый номер телефона').fill('+19876543210');
  await page.getByRole('button', { name: 'Получить код', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Отправляем…', exact: true })).toBeDisabled();
  await expect(page.getByLabel('Новый номер телефона')).toHaveValue('+19876543210');
  release!();
  await page.clock.fastForward(1600);
  await expect(page.getByLabel('Код из email')).toBeVisible();
});

test('onboarding keeps failed input supports keyboard and fits both themes on a small window', async ({ page }) => {
  await page.clock.install();
  let limited = false;
  await page.setViewportSize({ width: 390, height: 680 });
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return route.fulfill({ json: { authorizationState: 'WaitEmailCode', authorized: false } });
    if (path === '/api/onboarding') return route.fulfill({ json: { state: 'WaitEmailCode', csrfToken: 'fake-csrf', delivery: { method: 'email', destination: '•••@example.invalid' }, canChangePhone: true, resendSupported: true, resendAfterSeconds: 0 } });
    if (path === '/api/onboarding/login') return route.fulfill(limited
      ? { status: 429, json: { kind: 'rate_limited', error: 'Слишком много попыток.', retryAfterSeconds: 3 } }
      : { status: 400, json: { kind: 'expired_code', error: 'Срок действия кода истёк. Запросите новый код.' } });
    if (path === '/api/notifications') return route.fulfill({ json: { items: [], unread: 0, cursor: null } });
    if (['/api/channels', '/api/templates', '/api/monitoring', '/api/history/syncs'].includes(path)) return route.fulfill({ json: [] });
    throw new Error(`Unmocked synthetic route ${path}`);
  });
  await page.goto('/');
  await expect(page.getByText('По электронной почте', { exact: true })).toBeVisible();
  await page.getByLabel('Код из email').fill('123456');
  await page.getByLabel('Код из email').press('Enter');
  await expect(page.getByRole('alert')).toContainText('Срок действия кода истёк');
  await expect(page.getByLabel('Код из email')).toHaveValue('123456');
  limited = true;
  await page.getByLabel('Код из email').press('Enter');
  await expect(page.getByRole('alert')).toContainText('Слишком много попыток');
  await expect(page.getByRole('button', { name: 'Продолжить', exact: true })).toBeDisabled();
  await page.clock.fastForward(3100);
  await expect(page.getByRole('button', { name: 'Продолжить', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: 'Изменить номер', exact: true }).click();
  await page.getByRole('button', { name: 'Назад к коду', exact: true }).click();
  await expect(page.getByLabel('Код из email')).toHaveValue('123456');
  for (const theme of ['light', 'dark']) {
    await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, theme);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  }
});
