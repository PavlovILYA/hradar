import { test, expect, type Page } from './fixtures';

async function fixture(page: Page) {
  let sources = [{ id: -1001, title: 'Synthetic source', username: 'synthetic_jobs' }];
  let templates: any[] = [];
  const writes: { path: string; method: string; body: any }[] = [];
  const message = { id: 42, channelId: -1001, date: 1700000000, title: 'Synthetic source', username: 'synthetic_jobs', text: 'Synthetic Java publication', link: 'https://t.me/synthetic_jobs/42' };
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url()), path = url.pathname, method = route.request().method();
    if (method !== 'GET') writes.push({ path, method, body: route.request().postData() ? route.request().postDataJSON() : null });
    const reply = (json: unknown) => route.fulfill({ json });
    if (path === '/api/sources') return route.fulfill({ json: method === 'POST' ? { key: 'synthetic-key', chatId: -1001, title: 'Synthetic source', scope: { kind: 'CHAT' }, type: 'CHANNEL', access: 'AVAILABLE' } : { items: [{ key: 'synthetic-key', chatId: -1001, title: 'Synthetic source', scope: { kind: 'CHAT' }, type: 'CHANNEL', access: 'AVAILABLE' }] } });
    if (path === '/api/source-catalog') return route.fulfill({ json: { items: [{ key: 'synthetic-key', chatId: -1001, title: 'Synthetic source', scope: { kind: 'CHAT' }, type: 'CHANNEL', access: 'AVAILABLE' }], cursor: null } });
    if (path === '/api/workspace/desktop') return route.fulfill({ json: { enabled: false, hidePreview: true } });
    if (path === '/api/workspace/saved' || path === '/api/workspace/monitoring') return route.fulfill({ json: path.endsWith('saved') ? { items: [], nextCursor: null } : [] });
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return reply({ authorized: true, authorizationState: 'Ready', connectionState: 'Ready' });
    if (path === '/api/onboarding') return reply({ state: 'Ready', csrfToken: 'synthetic-csrf' });
    if (path === '/api/desktop/shutdown') { expect(route.request().headers()['x-hradar-csrf']).toBe('synthetic-csrf'); return reply({ ok: true }); }
    if (path === '/api/channels') {
      if (method === 'POST') sources = [...sources, { id: -1002, title: 'New synthetic source', username: route.request().postDataJSON().username }];
      return reply(sources);
    }
    if (path.startsWith('/api/channels/') && method === 'DELETE') { sources = sources.filter(s => s.id !== Number(path.split('/').pop())); return reply({ deleted: true }); }
    if (path === '/api/templates' || path === '/api/workspace/templates') {
      if (method === 'POST') { const saved = { ...route.request().postDataJSON(), id: 't1' }; templates = [saved]; return reply(saved); }
      return reply(templates);
    }
    if (path === '/api/templates/t1' || path === '/api/workspace/templates/t1') {
      if (method === 'DELETE') { templates = []; return reply({ deleted: true }); }
      templates = [route.request().postDataJSON()]; return reply(templates[0]);
    }
    if (path === '/api/monitoring' || path === '/api/history/syncs') return reply([]);
    if (path === '/api/notifications') return reply({ items: [], unread: 0, cursor: null });
    if (path === '/api/projects') return reply({ items: [], total: 0 });
    if (path === '/api/saved') return reply({ items: [], nextCursor: null });
    if (path.startsWith('/api/telegram/chats/')) return reply([message]);
    if (path === '/api/workspace/search') return reply({ messages: [], cursor: '', state: 'ok', fetchedAt: Date.now(), origin: 'telegram' });
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
const navigate = async (page: Page, name: string) => { await page.getByRole('navigation', { name: 'Основная навигация' }).getByRole('button', { name, exact: true }).click(); if (name === 'Поиск') await page.getByLabel('Область поиска', { exact: true }).selectOption('channel'); };

test('all eleven destinations work with a single sidebar and relevant publication context', async ({ page }) => {
  await fixture(page);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Обзор');
  await page.getByRole('button', { name: 'Начать поиск', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Поиск');
  for (const name of ['Обзор', 'Проекты', 'Поиск', 'Сохранённые публикации', 'Совпадения', 'Управление чатами', 'Локальный архив', 'Сохранённые поиски', 'Telegram Bot', 'MCP', 'Состояние приложения']) {
    await navigate(page, name);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(name);
    await expect(page.getByRole('navigation', { name: 'Основная навигация' }).locator('[aria-current="page"]')).toHaveCount(1);
    await expect(page.getByRole('dialog', { name: 'Детали публикации' })).toHaveCount(0);
  }
  await expect(page.locator('.sources-pane, .workspace-navigation')).toHaveCount(0);
  await expect(page.getByText('Инструменты исследования', { exact: true })).toHaveCount(0);
});

test('source CRUD and recent messages lead to contextual details without losing results', async ({ page }) => {
  const data = await fixture(page);
  await navigate(page, 'Управление чатами');
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
  await page.locator('.source-record').filter({ hasText: '@synthetic_jobs' }).getByRole('button', { name: 'Последние сообщения', exact: true }).click();
  await expect(page.locator('.search-screen .message-card')).toHaveCount(1);
  await page.getByRole('button', { name: 'Детали ↗' }).click();
  await expect(page.getByRole('dialog', { name: 'Детали публикации' })).toBeVisible();
  await page.keyboard.press('Escape');
  await navigate(page, 'MCP');
  await expect(page.getByRole('button', { name: 'Открыть детали', exact: true })).toHaveCount(0);
  await expect(page.getByRole('dialog', { name: 'Детали публикации' })).toHaveCount(0);
  await navigate(page, 'Поиск');
  await expect(page.locator('.search-screen .message-card')).toHaveCount(1);
  await expect(page.getByRole('dialog', { name: 'Детали публикации' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Открыть детали' }).click();
  await expect(page.getByRole('dialog', { name: 'Детали публикации' })).toBeVisible();
});

test('project template search archive and integration drafts survive switching screens', async ({ page }) => {
  await fixture(page);
  await navigate(page, 'Проекты'); await page.getByRole('button', { name: 'Создать проект', exact: true }).click();
  await page.getByLabel('Название проекта').fill('Unsaved project');
  await navigate(page, 'Сохранённые поиски'); await page.getByRole('button', { name: 'Создать сохранённый поиск' }).click();
  await page.getByLabel('Название сохранённого поиска').fill('Unsaved template');
  await navigate(page, 'Поиск'); await page.getByLabel('Поисковый запрос').fill('Unsaved query');
  await navigate(page, 'Локальный архив'); await page.getByLabel('Поиск по всей сохранённой истории').fill('Unsaved archive query');
  await page.getByLabel('Лимит сохранённых сообщений').selectOption('5000');
  await navigate(page, 'MCP'); await page.getByLabel('Разрешить публичный источник по username').fill('@unsaved_source');
  await navigate(page, 'Telegram Bot'); await page.getByLabel('Токен бота').fill('123:synthetic_unsaved');
  for (const [name, label, value] of [ ['Проекты', 'Название проекта', 'Unsaved project'], ['Сохранённые поиски', 'Название сохранённого поиска', 'Unsaved template'],
    ['Поиск', 'Поисковый запрос', 'Unsaved query'], ['Локальный архив', 'Поиск по всей сохранённой истории', 'Unsaved archive query'],
    ['MCP', 'Разрешить публичный источник по username', '@unsaved_source'], ['Telegram Bot', 'Токен бота', '123:synthetic_unsaved'] ]) {
    await navigate(page, name); await expect(page.getByLabel(label, { exact: true })).toHaveValue(value);
  }
  await navigate(page, 'Локальный архив'); await expect(page.getByLabel('Лимит сохранённых сообщений')).toHaveValue('5000');
});

test('template CRUD subscription and execution remain reachable in the templates screen', async ({ page }) => {
  const data = await fixture(page);
  await navigate(page, 'Сохранённые поиски'); await page.getByRole('button', { name: 'Создать сохранённый поиск' }).click();
  await page.getByLabel('Название сохранённого поиска').fill('Synthetic rule');
  await page.getByLabel('Запрос Telegram', { exact: true }).fill('java');
  await page.getByText('Дополнительные условия · AND / OR / NOT', { exact: true }).click();
  await page.getByLabel('Все слова / фразы (AND)').fill('Java\nSpring');
  await page.getByRole('checkbox', { name: 'Synthetic source', exact: true }).check();
  await page.getByRole('button', { name: 'Сохранить настройки' }).click();
  const card = page.locator('.template-card');
  await card.getByRole('button', { name: 'Включить отслеживание', exact: true }).click();
  await expect(card).toContainText('Отслеживание включено');
  await card.getByRole('button', { name: 'Выключить отслеживание', exact: true }).click();
  await expect(card).toContainText('Отслеживание выключено');
  await expect(card.getByRole('button', { name: 'Искать сейчас', exact: true })).toBeEnabled();
  await card.getByRole('button', { name: 'Настроить', exact: true }).click();
  await page.getByLabel('Название сохранённого поиска').fill('Edited synthetic rule');
  await page.getByRole('button', { name: 'Сохранить настройки' }).click();
  await card.getByRole('button', { name: 'Искать сейчас', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Поиск');
  await page.getByRole('button', { name: '2. Что искать', exact: true }).click();
  await expect(page.getByLabel('Запрос в чатах')).toHaveValue('java');
  await page.getByRole('button', { name: '1. Где искать', exact: true }).click();
  await expect(page.getByRole('checkbox', { name: 'Synthetic source', exact: true })).toBeChecked();
  await navigate(page, 'Сохранённые поиски'); await card.getByRole('button', { name: 'Удалить', exact: true }).click();
  await page.getByRole('button', { name: 'Удалить сохранённый поиск', exact: true }).click();
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
  await navigate(page, 'Локальный архив');
  await page.getByLabel('Лимит сохранённых сообщений').selectOption('5000');
  await navigate(page, 'Поиск');
  const response = page.waitForResponse('**/api/history/telegram/sync');
  release(); await response;
  await expect(page.locator('#screen-archive .import-status')).toContainText('Импорт не запускался');
  await navigate(page, 'Локальный архив');
  await expect(page.getByLabel('Лимит сохранённых сообщений')).toHaveValue('5000');
});

test('an untouched archive limit initializes from the server for bounded and unlimited imports', async ({ page }) => {
  await fixture(page);
  let limit: number | null = 10000;
  await page.route('**/api/history/telegram/sync', route => route.fulfill({ json: { channelId: -1001, username: 'telegram', title: 'Synthetic archive', status: 'IDLE', messageCount: 0, maxMessages: limit, nextRetryAt: 0 } }));
  await navigate(page, 'Локальный архив');
  await expect(page.getByLabel('Лимит сохранённых сообщений')).toHaveValue('10000');
  limit = null; await page.reload(); await navigate(page, 'Локальный архив');
  await expect(page.getByLabel('Лимит сохранённых сообщений')).toHaveValue('all');
});


test('native settings destination survives desktop launch handshake', async ({ page }) => {
  await fixture(page);
  let launches = 0;
  let recoveries = 0;
  await page.route('**/desktop/recover', route => {
    expect(route.request().headers()['x-hradar-recovery']).toBe('1');
    recoveries++;
    return route.fulfill({ json: { ok: true } });
  });
  await page.route('**/desktop/launch', route => {
    expect(route.request().postDataJSON()).toEqual({ token: 'synthetic-native-token' });
    launches++;
    return route.fulfill({ json: { ok: true } });
  });
  await page.goto('/?screen=application#launch=synthetic-native-token');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Состояние приложения');
  await expect(page.getByRole('button', { name: 'Завершить hradar', exact: true })).toBeVisible();
  expect(new URL(page.url()).hash).toBe('');
  expect(new URL(page.url()).search).toBe('?screen=application');
  expect(launches).toBe(1);
  await page.reload();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Состояние приложения');
  expect(launches).toBe(1);
  expect(recoveries).toBe(1);
  await page.goto('/?screen=untrusted');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Обзор');
});
