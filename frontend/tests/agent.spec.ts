import { test, expect } from '@playwright/test';

test('AI consent, background jobs, evidence and cancellation on desktop and mobile', async ({ page }) => {
  let consent: { enabled: boolean; sources: number[]; policy: string } | null = null;
  let jobs: unknown[] = [];
  let completed = false;
  let operator = '';
  await page.route('**/api/**', async route => {
    const req = route.request(); const path = new URL(req.url()).pathname;
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return route.fulfill({ json: { authorized: true, authorizationState: 'Ready', connectionState: 'Ready' } });
    if (path === '/api/channels' || path === '/api/templates' || path === '/api/monitoring') return route.fulfill({ json: [] });
    if (path === '/api/notifications') return route.fulfill({ json: { items: [], cursor: null, unread: 0 } });
    if (path === '/api/agent/settings') {
      operator = req.headers()['x-hradar-agent-operator'] ?? '';
      return route.fulfill({ json: { available: true, limits: { model: 'synthetic-model', maxOutputTokens: 2048, tokenBudget: 32000, budgetUsd: 0.5, timeoutSeconds: 120, maxToolCalls: 12, retentionDays: 30 }, sources: [{ id: -1001, title: 'Synthetic public source', username: 'synthetic_source' }], consent, csrfToken: 'synthetic-csrf', operator: operator ? 'Telegram-оператор' : 'Локальный оператор' } });
    }
    if (path === '/api/agent/consent') {
      expect(req.headers()['x-hradar-csrf']).toBe('synthetic-csrf');
      consent = { ...req.postDataJSON(), policy: 'synthetic-policy' };
      return route.fulfill({ json: consent });
    }
    if (path === '/api/agent/jobs' && req.method() === 'POST') {
      expect(consent?.enabled).toBe(true);
      jobs = [{ id: 'synthetic-job', question: req.postDataJSON().question, state: 'QUEUED', createdAt: 1700000000000, error: null, result: null }];
      return route.fulfill({ status: 202, json: jobs[0] });
    }
    if (path === '/api/agent/jobs/synthetic-job/cancel') {
      jobs = [{ ...(jobs[0] as object), state: 'CANCELLED' }]; return route.fulfill({ json: { cancelled: true } });
    }
    if (path === '/api/agent/jobs') {
      if (completed) jobs = [{ id: 'synthetic-completed', question: 'Java remote за 7 дней', state: 'COMPLETED', createdAt: 1700000000000, error: null, result: { summary: 'Синтетический результат: <script>alert(1)</script>', evidence: [{ reference: 'p1', title: 'Synthetic public source', date: 1700000000, reason: 'Явно указаны Java и удалённая работа.', link: 'https://t.me/synthetic_source/17' }, { reference: 'p2', title: 'Unavailable source', date: 1700000000, reason: 'Ссылка отсутствует.', link: 'javascript:alert(1)' }], notice: 'AI может ошибаться.' } }];
      return route.fulfill({ json: jobs });
    }
    throw new Error(`Unmocked request: ${path}`);
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'AI-исследования', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'AI-исследования', level: 2, exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Запустить исследование' })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Сохранить согласие и источники' })).toBeDisabled();
  await page.getByLabel('Synthetic public source (@synthetic_source)').check();
  await page.getByLabel('Я разрешаю внешнюю AI-обработку', { exact: false }).check();
  await page.getByRole('button', { name: 'Сохранить согласие и источники' }).click();
  await expect(page.getByText('Согласие сохранено.', { exact: false })).toBeVisible();
  await page.getByLabel('Вопрос исследователю').fill('Java remote за 7 дней');
  await page.getByRole('button', { name: 'Запустить исследование' }).click();
  await expect(page.getByText('В очереди', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Запустить исследование' })).toBeDisabled();
  await page.getByRole('button', { name: 'Отменить исследование' }).click();
  await expect(page.getByText('Отменено', { exact: true })).toBeVisible();
  completed = true;
  await page.getByRole('button', { name: 'Обновить исследования' }).click();
  await expect(page.getByText('Синтетический результат: <script>alert(1)</script>', { exact: true })).toBeVisible();
  await expect(page.locator('.agent-job script')).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Исходная публикация' })).toHaveAttribute('href', 'https://t.me/synthetic_source/17');
  await expect(page.getByText('Ссылка недоступна', { exact: true })).toBeVisible();
  await page.screenshot({ path: '/tmp/hradar-ai-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByLabel('Вопрос исследователю')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: '/tmp/hradar-ai-mobile.png', fullPage: true });
  await page.getByLabel('Код оператора').fill('synthetic-private-code');
  await page.getByRole('button', { name: 'Открыть историю оператора' }).click();
  await expect(page.getByRole('heading', { name: 'Telegram-оператор · OpenAI' })).toBeVisible();
  expect(operator).toBe('synthetic-private-code');
  await page.getByRole('button', { name: 'Отозвать согласие' }).click();
  await expect(page.getByText('Внешняя AI-обработка запрещена до согласия.')).toBeVisible();
});

test('AI configuration failure and restart interruption are explicit', async ({ page }) => {
  let unavailable = true;
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return route.fulfill({ json: { authorized: true, authorizationState: 'Ready' } });
    if (path === '/api/channels' || path === '/api/templates') return route.fulfill({ json: [] });
    if (path === '/api/notifications') return route.fulfill({ json: { items: [], cursor: null, unread: 0 } });
    if (path === '/api/agent/settings') return route.fulfill({ json: { available: false, limits: { model: '', maxOutputTokens: 2048, tokenBudget: 32000, budgetUsd: 0.5, timeoutSeconds: 120, maxToolCalls: 12, retentionDays: 30 }, sources: [], consent: null, csrfToken: 'synthetic', operator: 'Локальный оператор' } });
    if (path === '/api/agent/jobs') return unavailable ? route.fulfill({ status: 503, json: { error: 'Хранилище AI временно недоступно.' } }) : route.fulfill({ json: [{ id: 'interrupted', question: 'Synthetic question', state: 'INTERRUPTED', error: 'RESTART_INTERRUPTED', createdAt: 1700000000000, result: null }] });
    throw new Error(`Unmocked request: ${path}`);
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'AI-исследования', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Хранилище AI временно недоступно.');
  unavailable = false;
  // Change the operator to retry settings and history together.
  await page.getByLabel('Код оператора').fill('synthetic');
  await page.getByRole('button', { name: 'Открыть историю оператора' }).click();
  await expect(page.getByText('Production AI не настроен.', { exact: false })).toBeVisible();
  await expect(page.getByText('Прервано при перезапуске.', { exact: false })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Запустить исследование' })).toBeDisabled();
});
