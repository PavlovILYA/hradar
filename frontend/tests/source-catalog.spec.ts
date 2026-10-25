import { expect, test, type Page } from '@playwright/test';
import { openSection } from './navigation-helpers';

const candidate = (chatId: number, title: string, extra: Record<string, unknown> = {}) => ({
  key: '', chatId, title, type: 'SUPERGROUP', username: null, scope: { kind: 'CHAT' },
  isPublic: false, access: 'AVAILABLE', isForum: false, protectedContent: false,
  legacyChannel: false, monitoringEnabled: false, ...extra,
});
const group = candidate(-2001, 'Synthetic community');
const forum = candidate(-2002, 'Synthetic forum', { isForum: true });
const topic = candidate(-2002, 'Synthetic forum', { isForum: true, scopeTitle: 'Synthetic topic', scope: { kind: 'FORUM_TOPIC', forumTopicId: 17 } });

async function fixture(page: Page, options: { saved?: any[]; fail?: boolean; delayed?: boolean; delayedSaved?: boolean } = {}) {
  let saved = [...(options.saved ?? [])];
  let savedReads = 0;
  const writes: { path: string; body: any; csrf: string | undefined }[] = [];
  const catalogQueries: URLSearchParams[] = [];
  const topicQueries: URLSearchParams[] = [];
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname;
    const reply = (json: unknown) => route.fulfill({ json });
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return reply({ authorized: true, authorizationState: 'Ready', connectionState: 'Ready' });
    if (path === '/api/onboarding') return reply({ state: 'Ready', csrfToken: 'synthetic-source-csrf' });
    if (path === '/api/channels') return reply([{ id: -1001, title: 'Synthetic public channel', username: 'synthetic_jobs' }]);
    if (path === '/api/sources') {
      if (request.method() === 'POST') {
        const body = request.postDataJSON();
        writes.push({ path, body, csrf: request.headers()['x-hradar-csrf'] });
        const source = { ...(body.scope.kind === 'FORUM_TOPIC' ? topic : group), ...body, key: `saved-${writes.length}` };
        saved.push(source); return reply(source);
      }
      const snapshot = [...saved];
      if (options.delayedSaved && savedReads++ === 0) await new Promise(resolve => setTimeout(resolve, 600));
      return reply({ items: snapshot, cursor: null });
    }
    if (path.startsWith('/api/sources/')) {
      writes.push({ path, body: null, csrf: request.headers()['x-hradar-csrf'] });
      const key = path.split('/')[3];
      if (request.method() === 'DELETE') { saved = saved.filter(source => source.key !== key); return reply({ deleted: true }); }
      if (path.endsWith('/refresh')) { saved = saved.map(source => source.key === key ? { ...source, access: 'UNAVAILABLE' } : source); return reply(saved.find(source => source.key === key)); }
    }
    if (path === '/api/source-catalog') {
      catalogQueries.push(url.searchParams);
      if (options.fail) return route.fulfill({ status: 503, json: { error: 'Synthetic untrusted diagnostic' } });
      if (options.delayed && url.searchParams.get('query') === 'old') await new Promise(resolve => setTimeout(resolve, 350));
      if (url.searchParams.get('filter') === 'private') return reply({ items: [candidate(3001, 'Synthetic private chat', { type: 'PRIVATE' })], cursor: null });
      if (url.searchParams.get('query') === 'old') return reply({ items: [candidate(-4001, 'Stale synthetic result')], cursor: null });
      if (url.searchParams.get('query') === 'new') return reply({ items: [candidate(-4002, 'Latest synthetic result')], cursor: null });
      if (url.searchParams.get('cursor')) return reply({ items: [candidate(-2003, 'Synthetic unavailable group', { access: 'UNAVAILABLE' }), candidate(-2004, 'Synthetic unknown group', { access: 'UNKNOWN' })], cursor: null });
      return reply({ items: [group, forum], cursor: 'synthetic-next', notice: 'Показана ограниченная страница чатов.' });
    }
    if (path === '/api/source-catalog/-2002/topics') {
      topicQueries.push(url.searchParams);
      if (url.searchParams.get('forumTopicId') === '17') return reply({ items: [candidate(-2002, 'Synthetic forum', { scopeTitle: 'Second topic', scope: { kind: 'FORUM_TOPIC', forumTopicId: 23 } })], next: null });
      return reply({ items: [topic], next: { date: 1700000000, messageId: 900, forumTopicId: 17 } });
    }
    if (path === '/api/templates' || path === '/api/monitoring' || path === '/api/history/syncs') return reply([]);
    if (path === '/api/notifications') return reply({ items: [], unread: 0, cursor: null });
    if (path === '/api/notifications/summary') return reply({ unread: 0, revision: 0, byChannel: {} });
    if (path === '/api/projects') return reply({ items: [], total: 0 });
    if (path === '/api/saved') return reply({ items: [], nextCursor: null });
    throw new Error(`Unmocked offline source request: ${request.method()} ${path}`);
  });
  await page.goto('/'); await openSection(page, 'Источники');
  await page.getByRole('button', { name: 'Добавить из Telegram', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Выбор Telegram-источника' })).toBeVisible();
  return { writes, catalogQueries, topicQueries };
}

test('catalog lists bounded metadata, paginates and saves only on explicit action with CSRF', async ({ page }) => {
  const data = await fixture(page);
  await expect(page.getByTestId('catalog-source')).toHaveCount(2);
  const row = page.getByTestId('catalog-source').filter({ hasText: 'Synthetic community' });
  await expect(row).toContainText('Непубличный'); await expect(row).toContainText('Доступен');
  expect(data.writes).toHaveLength(0);
  await page.getByRole('button', { name: 'Ещё чаты', exact: true }).click();
  await expect(page.getByTestId('catalog-source')).toHaveCount(4);
  await expect(page.getByTestId('catalog-source').filter({ hasText: 'Synthetic unavailable group' }).getByRole('button', { name: 'Сохранить источник' })).toBeDisabled();
  expect(data.catalogQueries[1].get('cursor')).toBe('synthetic-next');
  await row.getByRole('button', { name: 'Сохранить источник', exact: true }).click();
  await expect(row.getByRole('button', { name: 'Уже сохранён' })).toBeDisabled();
  expect(data.writes).toEqual([{ path: '/api/sources', body: { chatId: -2001, scope: { kind: 'CHAT' } }, csrf: 'synthetic-source-csrf' }]);
  await expect(page.getByText('Источник сохранён. Чтение сообщений и мониторинг не запускались.')).toBeVisible();
  expect(await page.evaluate(() => Object.values(localStorage).includes('synthetic-source-csrf'))).toBe(false);
  const unknown = page.getByTestId('catalog-source').filter({ hasText: 'Synthetic unknown group' });
  await expect(unknown).toContainText('Доступ к сообщениям не подтверждён');
  await unknown.getByRole('button', { name: 'Сохранить источник', exact: true }).click();
  await expect(unknown.getByRole('button', { name: 'Уже сохранён' })).toBeDisabled();
  expect(data.writes[1].body).toEqual({ chatId: -2004, scope: { kind: 'CHAT' } });
});

test('forum and topic are separate native scopes and topic pagination preserves all cursor fields', async ({ page }) => {
  const data = await fixture(page, { saved: [{ ...forum, key: 'saved-forum' }] });
  const row = page.getByTestId('catalog-source').filter({ hasText: 'Synthetic forum' });
  await expect(row.getByRole('button', { name: 'Уже сохранён' })).toBeDisabled();
  await row.getByRole('button', { name: 'Выбрать тему' }).click();
  const topicRow = page.getByTestId('catalog-topic').filter({ hasText: 'Synthetic topic' });
  await expect(topicRow).toBeVisible();
  await topicRow.getByRole('button', { name: 'Сохранить тему' }).click();
  await expect(topicRow.getByRole('button', { name: 'Уже сохранён' })).toBeDisabled();
  expect(data.writes[0].body).toEqual({ chatId: -2002, scope: { kind: 'FORUM_TOPIC', forumTopicId: 17 } });
  expect(data.writes[0].body.scope).not.toHaveProperty('messageThreadId');
  await page.getByRole('button', { name: 'Ещё темы' }).click();
  await expect(page.getByTestId('catalog-topic')).toHaveCount(2);
  expect(Object.fromEntries(data.topicQueries[1])).toEqual({ date: '1700000000', messageId: '900', forumTopicId: '17' });
});

test('search waits for submit and stale requests cannot replace the latest query or filter', async ({ page }) => {
  const data = await fixture(page, { delayed: true });
  await expect(page.getByTestId('catalog-source')).toHaveCount(2);
  await page.getByLabel('Поиск в Telegram', { exact: true }).fill('old');
  expect(data.catalogQueries).toHaveLength(1);
  await page.getByRole('button', { name: 'Найти чаты' }).click();
  await expect.poll(() => data.catalogQueries.length).toBe(2);
  await page.getByLabel('Поиск в Telegram', { exact: true }).fill('new');
  await page.getByRole('button', { name: 'Найти чаты' }).click();
  await expect(page.getByTestId('catalog-source')).toContainText('Latest synthetic result');
  await page.getByLabel('Тип чата', { exact: true }).selectOption('private');
  await expect(page.getByTestId('catalog-source')).toContainText('Synthetic private chat');
  await expect(page.getByTestId('catalog-source')).toHaveCount(1);
  await expect(page.getByText('Stale synthetic result')).toHaveCount(0);
  expect(data.writes).toHaveLength(0);
});

test('catalog errors show safe retry and browser closes with Escape', async ({ page }) => {
  await fixture(page, { fail: true });
  await expect(page.getByRole('alert')).toContainText('Telegram недоступен');
  await expect(page.getByText('Synthetic untrusted diagnostic')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Повторить загрузку источников' })).toBeVisible();
  await page.getByLabel('Поиск в Telegram', { exact: true }).focus(); await page.keyboard.press('Escape');
  await expect(page.getByRole('region', { name: 'Выбор Telegram-источника' })).toHaveCount(0);
});

test('catalog fits a narrow screen in both themes', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await fixture(page);
  await expect(page.getByTestId('catalog-source')).toHaveCount(2);
  for (const theme of ['light', 'dark']) {
    await page.evaluate(value => document.documentElement.dataset.theme = value, theme);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await expect(page.getByRole('button', { name: 'Найти чаты' })).toBeVisible();
  }
});


test('saved access can be rechecked and revoked with guarded mutations', async ({ page }) => {
  const data = await fixture(page, { saved: [{ ...group, key: 'synthetic-saved' }] });
  await page.getByRole('button', { name: 'Закрыть выбор источника' }).click();
  const saved = page.getByTestId('saved-catalog-source');
  await expect(saved).toHaveCount(1);
  await saved.getByRole('button', { name: 'Проверить доступ' }).click();
  await expect(saved.getByText('Доступ потерян', { exact: true })).toBeVisible();
  page.once('dialog', dialog => dialog.accept());
  await saved.getByRole('button', { name: 'Удалить из каталога' }).click();
  await expect(saved).toHaveCount(0);
  expect(data.writes.map(write => write.path)).toEqual(['/api/sources/synthetic-saved/refresh', '/api/sources/synthetic-saved']);
  expect(data.writes.every(write => write.csrf === 'synthetic-source-csrf')).toBe(true);
});

test('older saved-list responses cannot erase a newly selected source', async ({ page }) => {
  await fixture(page, { delayedSaved: true });
  const row = page.getByTestId('catalog-source').filter({ hasText: 'Synthetic community' });
  await row.getByRole('button', { name: 'Сохранить источник', exact: true }).click();
  await page.getByRole('button', { name: 'Закрыть выбор источника' }).click();
  await expect(page.getByTestId('saved-catalog-source')).toContainText('Synthetic community');
  await page.waitForTimeout(700); // Deliberately let the older server snapshot arrive last.
  await expect(page.getByTestId('saved-catalog-source')).toContainText('Synthetic community');
});
