import { test, expect, type Page } from '@playwright/test';

async function fixture(page: Page) {
  let sources = [{ id: -1001, title: 'Synthetic source', username: 'synthetic_jobs' }];
  let templates: any[] = [];
  const writes: { path: string; method: string; body: any }[] = [];
  const message = { id: 42, channelId: -1001, date: 1700000000, title: 'Synthetic source', username: 'synthetic_jobs', text: 'Synthetic Java publication', link: 'https://t.me/synthetic_jobs/42' };
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url()), path = url.pathname, method = route.request().method();
    if (method !== 'GET') writes.push({ path, method, body: route.request().postData() ? route.request().postDataJSON() : null });
    const reply = (json: unknown) => route.fulfill({ json });
    if (path === '/api/sources') return route.fulfill({ json: { items: [] } });
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return reply({ authorized: true, authorizationState: 'Ready', connectionState: 'Ready' });
    if (path === '/api/onboarding') return reply({ state: 'Ready', csrfToken: 'synthetic-csrf' });
    if (path === '/api/desktop/shutdown') { expect(route.request().headers()['x-hradar-csrf']).toBe('synthetic-csrf'); return reply({ ok: true }); }
    if (path === '/api/channels') {
      if (method === 'POST') sources = [...sources, { id: -1002, title: 'New synthetic source', username: route.request().postDataJSON().username }];
      return reply(sources);
    }
    if (path.startsWith('/api/channels/') && method === 'DELETE') { sources = sources.filter(s => s.id !== Number(path.split('/').pop())); return reply({ deleted: true }); }
    if (path === '/api/templates') {
      if (method === 'POST') { const saved = { ...route.request().postDataJSON(), id: 't1' }; templates = [saved]; return reply(saved); }
      return reply(templates);
    }
    if (path === '/api/templates/t1') {
      if (method === 'DELETE') { templates = []; return reply({ deleted: true }); }
      templates = [route.request().postDataJSON()]; return reply(templates[0]);
    }
    if (path === '/api/monitoring' || path === '/api/history/syncs') return reply([]);
    if (path === '/api/notifications') return reply({ items: [], unread: 0, cursor: null });
    if (path === '/api/projects') return reply({ items: [], total: 0 });
    if (path === '/api/saved') return reply({ items: [], nextCursor: null });
    if (path.startsWith('/api/telegram/chats/')) return reply([message]);
    if (path === '/api/search') return reply({ messages: [message], cursor: null, state: 'ok' });
    if (path.startsWith('/api/history/') && path.endsWith('/sync')) return reply({ channelId: -1001, username: path.split('/')[3], title: 'Synthetic archive', status: 'IDLE', messageCount: 0, maxMessages: 1000, nextRetryAt: 0 });
    if (path.startsWith('/api/history/') && path.endsWith('/messages')) return reply({ messages: [], total: 0, nextCursor: null, query: '' });
    if (path === '/api/integrations/mcp') return reply({ enabled: false, endpoint: 'http://127.0.0.1:8080/mcp', tools: [], allowedSources: [], availableSources: sources, csrfToken: 'synthetic-csrf', readOnly: true });
    if (path === '/api/integrations/bot/settings') return reply({ configured: false, enabled: false, identity: null, owner: null, users: [], invitations: [], legacy: false,
      status: { state: 'disabled', notice: null, allowedUsers: 0, registeredUsers: 0, delivery: { pending: 0, sent: 0, failed: 0 }, retryAt: 0, lastPollAt: 0 } });
    throw new Error(`Unmocked offline navigation request: ${method} ${path}`);
  });
  await page.goto('/');
  return { writes };
}
const navigate = (page: Page, name: string) => page.getByRole('navigation', { name: 'Основная навигация' }).getByRole('button', { name, exact: true }).click();

test('all eleven destinations work with a single sidebar and relevant publication context', async ({ page }) => {
  await fixture(page);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Обзор');
  await page.getByRole('button', { name: 'Начать поиск', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Поиск');
  for (const name of ['Обзор', 'Проекты', 'Поиск', 'Избранное', 'Уведомления', 'Источники', 'Архив', 'Шаблоны', 'Telegram Bot', 'MCP', 'Состояние приложения']) {
    await navigate(page, name);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(name);
    await expect(page.getByRole('navigation', { name: 'Основная навигация' }).locator('[aria-current="page"]')).toHaveCount(1);
    await expect(page.getByRole('complementary', { name: 'Детали публикации' })).toHaveCount(0);
  }
  await expect(page.locator('.sources-pane, .workspace-navigation')).toHaveCount(0);
  await expect(page.getByText('Инструменты исследования', { exact: true })).toHaveCount(0);
});

test('source CRUD and recent messages lead to contextual details without losing results', async ({ page }) => {
  const data = await fixture(page);
  await navigate(page, 'Источники');
  await page.getByLabel('Username нового источника').fill('@synthetic_new');
  await page.getByRole('button', { name: 'Добавить источник', exact: true }).click();
  const added = page.locator('.source-record').filter({ hasText: '@synthetic_new' });
  await expect(added).toBeVisible();
  expect(data.writes[0]).toMatchObject({ path: '/api/channels', method: 'POST', body: { username: 'synthetic_new' } });
  await page.getByLabel('Найти источник').fill('synthetic_jobs');
  await expect(added).not.toBeVisible();
  await page.getByLabel('Найти источник').clear();
  page.once('dialog', dialog => dialog.accept());
  await added.getByRole('button', { name: 'Удалить источник', exact: true }).click();
  await expect(added).toHaveCount(0);
  await page.getByRole('button', { name: 'Последние сообщения', exact: true }).click();
  await expect(page.locator('.search-screen .message-card')).toHaveCount(1);
  await page.getByRole('button', { name: 'Детали ↗' }).click();
  await expect(page.getByRole('complementary', { name: 'Детали публикации' })).toBeVisible();
  await navigate(page, 'MCP');
  await expect(page.getByRole('complementary', { name: 'Детали публикации' })).toHaveCount(0);
  await navigate(page, 'Поиск');
  await expect(page.locator('.search-screen .message-card')).toHaveCount(1);
  await expect(page.getByRole('complementary', { name: 'Детали публикации' })).toBeVisible();
});

test('project template search archive and integration drafts survive switching screens', async ({ page }) => {
  await fixture(page);
  await navigate(page, 'Проекты'); await page.getByRole('button', { name: 'Создать проект', exact: true }).click();
  await page.getByLabel('Название проекта').fill('Unsaved project');
  await navigate(page, 'Шаблоны'); await page.getByRole('button', { name: 'Создать шаблон' }).click();
  await page.getByLabel('Название шаблона').fill('Unsaved template');
  await navigate(page, 'Поиск'); await page.getByLabel('Поисковый запрос').fill('Unsaved query');
  await navigate(page, 'Архив'); await page.getByLabel('Поиск по всей сохранённой истории').fill('Unsaved archive query');
  await page.getByLabel('Лимит сохранённых сообщений').selectOption('5000');
  await navigate(page, 'MCP'); await page.getByLabel('Разрешить публичный источник по username').fill('@unsaved_source');
  await navigate(page, 'Telegram Bot'); await page.getByLabel('Токен бота').fill('123:synthetic_unsaved');
  for (const [name, label, value] of [ ['Проекты', 'Название проекта', 'Unsaved project'], ['Шаблоны', 'Название шаблона', 'Unsaved template'],
    ['Поиск', 'Поисковый запрос', 'Unsaved query'], ['Архив', 'Поиск по всей сохранённой истории', 'Unsaved archive query'],
    ['MCP', 'Разрешить публичный источник по username', '@unsaved_source'], ['Telegram Bot', 'Токен бота', '123:synthetic_unsaved'] ]) {
    await navigate(page, name); await expect(page.getByLabel(label, { exact: true })).toHaveValue(value);
  }
  await navigate(page, 'Архив'); await expect(page.getByLabel('Лимит сохранённых сообщений')).toHaveValue('5000');
});

test('template CRUD subscription and execution remain reachable in the templates screen', async ({ page }) => {
  const data = await fixture(page);
  await navigate(page, 'Шаблоны'); await page.getByRole('button', { name: 'Создать шаблон' }).click();
  await page.getByLabel('Название шаблона').fill('Synthetic rule');
  await page.getByLabel('Запрос Telegram', { exact: true }).fill('java');
  await page.getByLabel('Все слова / фразы (AND)').fill('Java\nSpring');
  await page.getByRole('button', { name: 'Сохранить шаблон' }).click();
  const card = page.locator('.template-card');
  await card.getByRole('button', { name: 'Уведомлять', exact: true }).click();
  await expect(card).toContainText('Мониторинг включён');
  await card.getByRole('button', { name: 'Пауза', exact: true }).click();
  await expect(card.getByRole('button', { name: 'Запустить', exact: true })).toBeDisabled();
  await card.getByRole('button', { name: 'Активировать', exact: true }).click();
  await card.getByRole('button', { name: 'Изменить', exact: true }).click();
  await page.getByLabel('Название шаблона').fill('Edited synthetic rule');
  await page.getByRole('button', { name: 'Сохранить шаблон' }).click();
  await card.getByRole('button', { name: 'Запустить', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Поиск');
  expect(data.writes.find(w => w.path === '/api/search')?.body.templateId).toBe('t1');
  await navigate(page, 'Шаблоны'); await card.getByRole('button', { name: 'Удалить', exact: true }).click();
  await expect(card).toHaveCount(0);
});

test('mobile navigation supports keyboard Escape focus restoration and themes', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 }); await fixture(page);
  const toggle = page.getByRole('button', { name: 'Открыть навигацию', exact: true });
  await toggle.click();
  await expect(page.getByRole('navigation', { name: 'Основная навигация' })).toBeVisible();
  await page.keyboard.press('Escape'); await expect(toggle).toBeFocused();
  await page.keyboard.press('Enter'); await navigate(page, 'Поиск');
  await expect(page.getByRole('navigation', { name: 'Основная навигация' })).not.toBeVisible();
  await toggle.click(); await page.getByRole('button', { name: 'Тёмная тема' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.keyboard.press('Escape');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('desktop shutdown stays in application settings and uses the existing csrf flow', async ({ page }) => {
  await page.route('**/desktop/recover', route => {
    expect(route.request().headers()['x-hradar-recovery']).toBe('1');
    return route.fulfill({ json: { ok: true } });
  });
  await page.addInitScript(() => sessionStorage.setItem('hradar-desktop', 'true'));
  const data = await fixture(page); await navigate(page, 'Состояние приложения');
  await page.getByRole('button', { name: 'Завершить hradar', exact: true }).click();
  await expect(page.locator('#root')).toHaveText('hradar завершён. Теперь можно закрыть вкладку.');
  expect(data.writes.filter(w => w.path === '/api/desktop/shutdown')).toHaveLength(1);
});

test('a late archive initialization cannot overwrite a selected import limit', async ({ page }) => {
  await fixture(page);
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/history/telegram/sync', async route => {
    await gate;
    await route.fulfill({ json: { channelId: -1001, username: 'telegram', title: 'Delayed synthetic archive', status: 'IDLE', messageCount: 0, maxMessages: 1000, nextRetryAt: 0 } });
  });
  await navigate(page, 'Архив');
  await page.getByLabel('Лимит сохранённых сообщений').selectOption('5000');
  await navigate(page, 'Поиск');
  const response = page.waitForResponse('**/api/history/telegram/sync');
  release(); await response;
  await expect(page.locator('#screen-archive .import-status')).toContainText('Импорт не запускался');
  await navigate(page, 'Архив');
  await expect(page.getByLabel('Лимит сохранённых сообщений')).toHaveValue('5000');
});

test('an untouched archive limit initializes from the server for bounded and unlimited imports', async ({ page }) => {
  await fixture(page);
  let limit: number | null = 10000;
  await page.route('**/api/history/telegram/sync', route => route.fulfill({ json: { channelId: -1001, username: 'telegram', title: 'Synthetic archive', status: 'IDLE', messageCount: 0, maxMessages: limit, nextRetryAt: 0 } }));
  await navigate(page, 'Архив');
  await expect(page.getByLabel('Лимит сохранённых сообщений')).toHaveValue('10000');
  limit = null; await page.reload(); await navigate(page, 'Архив');
  await expect(page.getByLabel('Лимит сохранённых сообщений')).toHaveValue('all');
});
