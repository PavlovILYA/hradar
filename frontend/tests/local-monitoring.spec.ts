import { expect, test, type Page } from '@playwright/test';
import { openSection } from './navigation-helpers';

const source = { key: 'synthetic-cloud', chatId: 3001, type: 'PRIVATE', title: 'Synthetic cloud source', username: null, scope: { kind: 'CHAT' }, isPublic: false, access: 'AVAILABLE', isForum: false, protectedContent: false, legacyChannel: false, monitoringEnabled: false };
const publicSource = { ...source, key: 'synthetic-public', chatId: -1001, type: 'CHANNEL', title: 'Synthetic public source', username: 'synthetic_jobs', isPublic: true, legacyChannel: true };
const thread = { ...source, key: 'synthetic-discussion', chatId: -4001, type: 'SUPERGROUP', title: 'Synthetic linked discussion', scope: { kind: 'MESSAGE_THREAD', messageThreadId: 700 }, scopeTitle: 'Synthetic publication discussion' };
const message = { id: 900, channelId: 3001, date: 1700000000, text: 'Synthetic Java developer looking for work', title: source.title, sourceKey: source.key, availability: 'available', link: null, canGetMessageThread: true };
const template = { id: 'synthetic-rule', name: 'Synthetic Java rule', required: ['Java'], any: ['developer', 'engineer'], excluded: ['junior'], telegramQuery: 'Java', sourceMode: 'chats', sources: [], sourceKeys: [source.key], active: true, subscribed: false };

async function fixture(page: Page) {
  const writes: { path: string; body: any; csrf?: string }[] = [];
  let saved = [source, publicSource]; let enabled = false; let status = 'active'; let read = false;
  const subscription = () => ({ id: 'synthetic-subscription', sourceKey: source.key, templateId: template.id, enabled, checkpoint: 100, recoveryCursor: 0, recoveryHead: 0, sinceDate: 1700000000, retryAt: status === 'rate_limited' ? Date.now() + 60_000 : 0, error: null, status: enabled ? status : 'paused' });
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname;
    const reply = (json: unknown) => route.fulfill({ json });
    if (req.method() !== 'GET') writes.push({ path, body: req.postData() ? req.postDataJSON() : null, csrf: req.headers()['x-hradar-csrf'] });
    if (path === '/api/source-catalog') return route.fulfill({ json: { items: [], cursor: null } });
    if (path === '/api/workspace/desktop') return route.fulfill({ json: { enabled: false, hidePreview: true } });
    if (path === '/api/workspace/saved') return reply({ items: [], nextCursor: null });
    if (path === '/api/workspace/monitoring') return reply(enabled ? [subscription()] : []);
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return reply({ authorized: true, authorizationState: 'Ready', connectionState: 'Ready' });
    if (path === '/api/onboarding') return reply({ state: 'Ready', csrfToken: 'synthetic-csrf' });
    if (path === '/api/channels') return reply([{ id: -1001, title: publicSource.title, username: publicSource.username }]);
    if (path === '/api/sources') return reply({ items: saved });
    if (path === '/api/templates') return reply([{ ...template, subscribed: enabled }]);
    if (path === `/api/workspace/templates/${template.id}`) { const body = req.postDataJSON(); enabled = body.subscribed; return reply(body); }
    if (path === '/api/monitoring' || path === '/api/history/syncs') return reply([]);
    if (path === '/api/notifications') return reply({ items: [{ id: 'synthetic-local-match', templateId: template.id, templateName: template.name, message, read, sequence: 1 }], unread: read ? 0 : 1, cursor: null });
    if (path === '/api/notifications/summary') return reply({ unread: 0, revision: 0, byChannel: {} });
    if (path === '/api/projects') return reply({ items: [], total: 0 });
    if (path === '/api/saved') return reply({ items: [], nextCursor: null });
    if (path === `/api/sources/${source.key}/monitoring`) {
      if (req.method() === 'POST') { enabled = req.postDataJSON().enabled; if (enabled) status = 'active'; return reply(subscription()); }
      return reply(writes.some(w => w.path.endsWith('/monitoring')) ? [subscription()] : []);
    }
    if (path === `/api/sources/${publicSource.key}/monitoring`) return reply([]);
    if (path === '/api/local-notifications') return reply({ items: [{ id: 'synthetic-local-match', templateId: template.id, templateName: template.name, message, read, sequence: 1 }], unread: read ? 0 : 1, cursor: null });
    if (path === '/api/notifications/synthetic-local-match/read' || path === '/api/notifications/read-all') { read = true; return reply({ success: true }); }
    if (path === `/api/sources/${source.key}/messages/900/discussion`) { if (!saved.some(s => s.key === thread.key)) saved = [...saved, thread]; return reply(thread); }
    if (path === `/api/sources/${source.key}/messages/900`) return reply(message);
    if (path === `/api/sources/${source.key}/messages`) return reply({ messages: [message], nextCursor: null, state: 'ok' });
    if (path === `/api/sources/${thread.key}/messages/901`) return reply({ ...message, id: 901, channelId: thread.chatId, sourceKey: thread.key, title: thread.title, scopeTitle: thread.scopeTitle, canGetMessageThread: false });
    if (path === `/api/sources/${thread.key}/messages`) return reply({ messages: [{ ...message, id: 901, channelId: thread.chatId, sourceKey: thread.key, title: thread.title, scopeTitle: thread.scopeTitle, canGetMessageThread: false }], nextCursor: null, state: 'ok' });
    if (path === '/api/search') return reply({ messages: [{ ...message, sourceKey: thread.key, channelId: thread.chatId }], cursor: '', state: 'ok' });
    if (path === `/api/sources/${thread.key}/sync`) return reply(null);
    if (path === `/api/sources/${thread.key}/archive`) return reply({ messages: [], nextCursor: null, total: 0, query: '' });
    throw new Error(`Unexpected Stage C offline API: ${req.method()} ${path}`);
  });
  await page.goto('/');
  return { writes, status: (value: string) => { status = value; } };
}

test('template monitoring configures its chats and exposes rate and access states in one screen', async ({ page }) => {
  const data = await fixture(page);
  await openSection(page, 'Сохранённые поиски');
  const card = page.locator('.template-card');
  expect(data.writes).toHaveLength(0);
  await card.getByRole('button', { name: 'Включить отслеживание', exact: true }).click();
  await expect(card).toContainText('Отслеживание включено');
  expect(data.writes[0]).toMatchObject({ path: `/api/workspace/templates/${template.id}`, body: { sourceKeys: [source.key], subscribed: true }, csrf: 'synthetic-csrf' });
  data.status('rate_limited'); await page.getByRole('button', { name: 'Обновить список' }).click();
  await expect(card).toContainText('Лимит Telegram');
  await expect(card).toContainText('Следующая попытка');
  data.status('unavailable'); await page.getByRole('button', { name: 'Обновить список' }).click();
  await expect(card).toContainText('Нет доступа к чату');
  await openSection(page, 'Управление чатами');
  await expect(page.getByText('Локальный мониторинг выбранного источника', { exact: true })).toHaveCount(0);
});

test('failed local read keeps the unread match visible and allows retry before opening', async ({ page }) => {
  await fixture(page);
  await page.route('**/api/notifications/synthetic-local-match/read', route =>
    route.fulfill({ status: 503, json: { error: 'Synthetic read failure' } }), { times: 1 });
  await openSection(page, 'Совпадения');
  const local = page.locator('.notification-center');
  await local.getByRole('button', { name: 'Открыть публикацию' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(local.locator('.notification-card.unread')).toHaveCount(1);
  await local.getByRole('button', { name: 'Открыть публикацию' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Совпадения');
  await expect(page.getByRole('dialog', { name: 'Детали публикации' })).toContainText(message.text);
});

test('one inbox includes local matches and verified discussion opens as a saved thread without typed ids', async ({ page }) => {
  const { writes } = await fixture(page);
  await openSection(page, 'Совпадения');
  const local = page.locator('.notification-center');
  await expect(local.getByText(message.text)).toBeVisible();
  await local.getByRole('button', { name: 'Открыть публикацию' }).click();
  const detail = page.getByRole('dialog', { name: 'Детали публикации' });
  await expect(detail).toContainText(source.title);
  await detail.getByRole('button', { name: 'Сохранить доступное обсуждение' }).click();
  await expect(detail).toContainText(thread.title);
  await detail.getByRole('button', { name: 'Открыть сообщения обсуждения' }).click();
  const read = page.getByRole('region', { name: 'Чтение сохранённых источников' });
  await expect(read.getByRole('checkbox', { name: /Synthetic linked discussion/ })).toBeChecked();
  await expect(read.getByRole('checkbox', { name: /Synthetic linked discussion/ })).toBeEnabled();
  await read.getByRole('button', { name: 'Получить сообщения источника' }).click();
  await expect(read.getByText(thread.title + ' / ' + thread.scopeTitle, { exact: true })).toBeVisible();
  expect(writes).toContainEqual(expect.objectContaining({ path: '/api/notifications/synthetic-local-match/read' }));
  expect(writes).toContainEqual(expect.objectContaining({ path: `/api/sources/${source.key}/messages/900/discussion`, body: null, csrf: 'synthetic-csrf' }));
  await read.getByRole('button', { name: 'Детали ↗' }).click();
  await expect(detail.getByRole('button', { name: 'Сохранить доступное обсуждение' })).toHaveCount(0);
  await page.keyboard.press('Escape');
  await read.getByLabel('Поисковый запрос источников').fill('Java');
  await read.getByRole('button', { name: 'Искать в выбранных источниках' }).click();
  await expect.poll(() => writes.find(w => w.path === '/api/search')?.body.sourceKeys).toEqual([thread.key]);
  await openSection(page, 'Совпадения');
  await local.getByRole('button', { name: 'Открыть публикацию' }).click();
  await detail.getByRole('button', { name: 'Сохранить доступное обсуждение' }).click();
  await detail.getByRole('button', { name: 'Открыть архив обсуждения' }).click();
  await expect(page.getByLabel('Сохранённый источник для архива')).toHaveValue(thread.key);
  await expect(page.getByRole('button', { name: 'Начать импорт источника' })).toBeEnabled();
  expect(writes.some(w => w.path.startsWith('/api/local-notifications/'))).toBe(false);
});
