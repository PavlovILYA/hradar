import { expect, test, type Page } from '@playwright/test';
import { openSection } from './navigation-helpers';

const source = { key: 'synthetic-topic', chatId: -2002, type: 'SUPERGROUP', title: 'Synthetic local forum', username: null, scope: { kind: 'FORUM_TOPIC', forumTopicId: 17 }, scopeTitle: 'Synthetic hiring topic', isPublic: false, access: 'AVAILABLE', isForum: true, protectedContent: false, legacyChannel: false, monitoringEnabled: false };
const personal = { ...source, key: 'synthetic-personal', chatId: 3001, type: 'PRIVATE', title: 'Synthetic cloud chat', scope: { kind: 'CHAT' }, scopeTitle: null, isForum: false };
const message = { id: 900, channelId: -2002, date: 1700000000, text: 'Synthetic Java developer looking for work with experience', title: source.title, username: null, link: null, sourceKey: source.key, forumTopicId: 17, scopeTitle: source.scopeTitle, availability: 'available', explanation: { matches: true, foundRequired: ['Java'], foundAny: [], foundExcluded: [], missingRequired: [] } };

async function fixture(page: Page, rateLimited = false) {
  const fixtureUrl = process.env.SOURCE_FIXTURE_URL;
  if (fixtureUrl) {
    const response = await page.request.get(`${fixtureUrl}/api/sources`, { headers: { host: '127.0.0.1:8080', origin: 'http://127.0.0.1:5173' } });
    expect(response.ok()).toBe(true);
    const catalog = await response.json();
    Object.assign(source, catalog.items.find((s: any) => s.scope.forumTopicId === 17));
    Object.assign(personal, catalog.items.find((s: any) => s.type === 'PRIVATE'));
    message.sourceKey = source.key;
  }
  let project: any = null; let card: any = null; let imported = false;
  const writes: { path: string; body: any; csrf?: string }[] = [];
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname, method = req.method();
    const reply = (json: unknown) => route.fulfill({ json });
    if (fixtureUrl && (path === '/api/search' || path === '/api/sources' || path.startsWith('/api/sources/') || path === '/api/projects' || path.startsWith('/api/projects/'))) {
      if (method !== 'GET') writes.push({ path, body: req.postData() ? req.postDataJSON() : null, csrf: req.headers()['x-hradar-csrf'] });
      const forwarded = await route.fetch({ url: `${fixtureUrl}${path}${url.search}`, headers: { ...req.headers(), host: '127.0.0.1:8080', origin: 'http://127.0.0.1:5173' } });
      return route.fulfill({ response: forwarded });
    }
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return reply({ authorized: true, authorizationState: 'Ready', connectionState: 'Ready' });
    if (path === '/api/onboarding') return reply({ state: 'Ready', csrfToken: 'synthetic-local-csrf' });
    if (path === '/api/sources') return reply({ items: [source, personal] });
    if (path === '/api/channels' || path === '/api/templates' || path === '/api/monitoring') return reply([]);
    if (path === '/api/notifications') return reply({ items: [], unread: 0, cursor: null });
    if (path === '/api/notifications/summary') return reply({ unread: 0, revision: 0, byChannel: {} });
    if (path === `/api/sources/${source.key}/messages`) return reply({ messages: [message], nextCursor: url.searchParams.has('before') ? null : 900, state: 'ok' });
    if (path === `/api/sources/${source.key}/messages/900`) return reply(message);
    if (path === '/api/search') {
      const body = req.postDataJSON(); writes.push({ path, body, csrf: req.headers()['x-hradar-csrf'] });
      return reply({ messages: [message], cursor: '', state: rateLimited ? 'rate_limited' : 'ok', notice: rateLimited ? 'Сохранён частичный результат.' : null });
    }
    if (path === `/api/sources/${source.key}/sync`) {
      if (method === 'POST') { writes.push({ path, body: req.postDataJSON(), csrf: req.headers()['x-hradar-csrf'] }); imported = true; }
      return reply({ sourceKey: source.key, channelId: source.chatId, title: source.title, username: '', status: imported ? 'COMPLETED' : 'IDLE', messageCount: imported ? 1 : 0, maxMessages: 1000, cursor: imported ? 900 : 0, pauseReason: null, error: null });
    }
    if (path === `/api/sources/${source.key}/archive`) return reply({ messages: imported ? [message] : [], nextCursor: null, total: imported ? 1 : 0, query: url.searchParams.get('query') ?? '' });
    if (path === '/api/projects') {
      if (method === 'POST') { const body = req.postDataJSON(); writes.push({ path, body }); project = { id: 'synthetic-local-project', criteria: body, createdAt: 1, updatedAt: 1 }; return reply(project); }
      return reply({ items: project ? [project] : [], total: project ? 1 : 0 });
    }
    if (path === '/api/projects/synthetic-local-project') return reply(project);
    if (path === '/api/projects/synthetic-local-project/statistics') return reply({ total: card ? 1 : 0, stages: { NEW: card?.stage === 'NEW' ? 1 : 0, INTERESTING: card?.stage === 'INTERESTING' ? 1 : 0 } });
    if (path === '/api/projects/synthetic-local-project/search') return reply({ messages: [message], cursor: '', state: 'ok', scanned: 1, matched: 1, exhausted: true, alreadyAdded: card ? ['-2002:900'] : [] });
    if (path === '/api/projects/synthetic-local-project/cards') {
      if (method === 'POST') { writes.push({ path, body: req.postDataJSON() }); card = { id: 'synthetic-card', projectId: project.id, message, stage: 'NEW', notes: '', interest: 0, classification: { kind: 'CANDIDATE', reason: 'Synthetic explicit job search' }, manualKind: null, createdAt: 1, updatedAt: 1 }; return reply(card); }
      const cards = card && (!url.searchParams.get('stage') || url.searchParams.get('stage') === card.stage) ? [card] : []; return reply({ items: cards, total: cards.length });
    }
    if (path === '/api/projects/synthetic-local-project/cards/synthetic-card' && method === 'PUT') { const body = req.postDataJSON(); writes.push({ path, body }); card = { ...card, ...body, updatedAt: card.updatedAt + 1 }; return reply(card); }
    if (path === '/api/saved') return reply({ items: [], nextCursor: null });
    throw new Error(`Unmocked local-source request: ${method} ${path}`);
  });
  await page.goto('/');
  return { writes };
}

test('selected topic reads searches archives and persists a project card without username', async ({ page }) => {
  const { writes } = await fixture(page);
  await openSection(page, 'Источники');
  const row = page.getByTestId('saved-catalog-source').filter({ hasText: source.scopeTitle });
  await expect(row).toContainText('чтение сообщений, поиск, ручной архив и проекты');
  await row.getByRole('button', { name: 'Последние сообщения' }).click();
  const read = page.getByRole('region', { name: 'Чтение сохранённых источников' });
  await expect(read.getByRole('checkbox', { name: /Synthetic local forum/ })).toBeChecked();
  await read.getByRole('button', { name: 'Получить сообщения источника' }).click();
  await expect(read.getByText(message.text)).toBeVisible();
  if (!process.env.SOURCE_FIXTURE_URL) await read.getByRole('button', { name: 'Ещё сообщения источника' }).click();
  await expect(read.locator('.message-card')).toHaveCount(1);
  await read.getByRole('button', { name: 'Детали ↗' }).click();
  await expect(page.getByRole('complementary', { name: 'Детали публикации' })).toContainText(source.scopeTitle);
  await expect(page.getByRole('link', { name: 'Открыть в Telegram ↗' })).toHaveCount(0);
  await read.getByLabel('Поисковый запрос источников').fill('Java');
  await read.getByRole('button', { name: 'Искать в выбранных источниках' }).click();
  await expect(read.getByText(message.text)).toBeVisible();
  expect(writes.find(w => w.path === '/api/search')).toMatchObject({ body: { mode: 'sources', sourceKeys: [source.key], usernames: [], query: 'Java' }, csrf: 'synthetic-local-csrf' });
  await openSection(page, 'Источники');
  await page.getByTestId('saved-catalog-source').filter({ hasText: source.scopeTitle }).getByRole('button', { name: 'Открыть архив' }).click();
  const archive = page.getByRole('region', { name: 'Архив сохранённого источника' });
  await expect(archive.getByLabel('Сохранённый источник для архива')).toHaveValue(source.key);
  await archive.getByRole('button', { name: 'Начать импорт источника' }).click();
  await expect(archive.getByText(message.text)).toBeVisible();
  expect(writes.find(w => w.path.endsWith('/sync'))).toMatchObject({ body: { maxMessages: 1000 }, csrf: 'synthetic-local-csrf' });
  await openSection(page, 'Проекты');
  await page.getByRole('button', { name: 'Создать проект', exact: true }).click();
  await page.getByLabel('Название проекта').fill('Synthetic local hiring');
  await page.getByRole('checkbox', { name: /Synthetic local forum/ }).check();
  await page.getByLabel('Обязательные технологии').fill('Java');
  await page.getByRole('button', { name: 'Сохранить проект', exact: true }).click();
  expect(writes.find(w => w.path === '/api/projects')).toMatchObject({ body: { sourceKeys: [source.key], sources: [] } });
  await page.getByText('Найти и добавить публикации', { exact: true }).click();
  await expect(page.getByRole('button', { name: 'Включить мониторинг по критериям' })).toBeDisabled();
  await page.getByRole('button', { name: 'Найти публикации', exact: true }).click();
  await page.getByRole('button', { name: 'Добавить на доску', exact: true }).click();
  const card = page.locator('.recruitment-card');
  await card.getByLabel('Заметки').fill('Synthetic local note');
  await card.getByLabel('Интерес', { exact: true }).selectOption('4');
  await card.getByLabel('Стадия', { exact: true }).selectOption('INTERESTING');
  await card.getByRole('button', { name: 'Сохранить карточку' }).click();
  await expect(page.getByRole('region', { name: 'Интересные', exact: true }).locator('.recruitment-card')).toContainText(message.text);
  await page.reload(); await openSection(page, 'Проекты');
  await page.getByRole('button', { name: /Synthetic local hiring/ }).click();
  await expect(page.locator('.recruitment-card').getByLabel('Заметки')).toHaveValue('Synthetic local note');
  await expect(page.locator('.recruitment-card').getByLabel('Интерес', { exact: true })).toHaveValue('4');
  await expect(page.locator('.recruitment-card').getByLabel('Стадия', { exact: true })).toHaveValue('INTERESTING');
});

test('multiple selected source search retains partial rate-limit results and never requires username', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const { writes } = await fixture(page, true);
  await openSection(page, 'Поиск');
  await page.getByLabel('Область поиска').selectOption('sources');
  const read = page.getByRole('region', { name: 'Чтение сохранённых источников' });
  await read.getByRole('checkbox', { name: /Synthetic local forum/ }).check();
  await read.getByRole('checkbox', { name: /Synthetic cloud chat/ }).check();
  await expect(read.getByRole('button', { name: 'Получить сообщения источника' })).toBeDisabled();
  await read.getByLabel('Поисковый запрос источников').fill('Java');
  await read.getByRole('button', { name: 'Искать в выбранных источниках' }).click();
  await expect(read.getByText(message.text)).toBeVisible();
  await expect(read.getByText('Telegram ограничил частоту запросов. Повторите позже.')).toBeVisible();
  expect(writes.find(w => w.path === '/api/search')?.body.sourceKeys).toEqual([source.key, personal.key]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
