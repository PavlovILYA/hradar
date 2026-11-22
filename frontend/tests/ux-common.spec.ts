import { test, expect, type Page } from '@playwright/test';
import { openSection } from './navigation-helpers';

const source = { key: 'synthetic-key', chatId: -1001, title: 'Synthetic hiring group', scope: { kind: 'CHAT' }, type: 'SUPERGROUP', access: 'AVAILABLE' };
const message = { id: 42, channelId: -1001, date: 1700000000, title: source.title, sourceKey: source.key, text: 'Synthetic Java publication', link: null };
const template = { id: 't1', name: 'Java', sourceMode: 'selected', sources: [-1001], telegramQuery: 'Java', required: ['Java'], any: [], excluded: [], active: true, subscribed: false };
const match = { id: 'n1', templateId: 't1', templateName: 'Java', message, read: true, sequence: 1 };
async function fixture(page: Page) {
  const searches: any[] = [], writes: string[] = [];
  let linkCalls = 0;
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname, method = route.request().method();
    const reply = (json: unknown) => route.fulfill({ json });
    if (method !== 'GET') writes.push(path);
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return reply({ authorized: true, authorizationState: 'Ready', connectionState: 'Ready' });
    if (path === '/api/channels') return reply([{ id: -1001, title: source.title, username: 'synthetic_group' }]);
    if (path === '/api/templates') return reply([template]);
    if (path === '/api/sources' || path === '/api/source-catalog') return reply({ items: [source] });
    if (path === '/api/notifications') return reply({ items: [match], unread: 0, cursor: null });
    if (path === '/api/onboarding') return reply({ csrfToken: 'synthetic' });
    if (path === '/api/workspace/desktop') return reply({ enabled: false, hidePreview: true });
    if (path === '/api/workspace/monitoring') return reply([]);
    if (path === '/api/workspace/saved') return reply(method === 'GET' ? { items: [{ id: 's1', message }], nextCursor: null } : { saved: true });
    if (path === '/api/saved') return reply({ items: [], nextCursor: null });
    if (path === '/api/projects') return reply({ items: [], total: 0 });
    if (path === '/api/sources/synthetic-key/messages/42') { linkCalls++; return reply({ ...message, link: 'https://t.me/synthetic_group/42', attachments: [{ kind: 'document', fileName: 'synthetic-cv.pdf', mimeType: 'application/pdf', size: 4096 }] }); }
    if (path === '/api/search') { searches.push(route.request().postDataJSON()); return reply({ messages: [message], state: 'ok', cursor: '' }); }
    if (path === '/api/workspace/search') { searches.push(route.request().postDataJSON()); return reply({ messages: [message], state: 'ok', cursor: '', fetchedAt: Date.now(), origin: 'telegram' }); }
    if (path === '/api/integrations/mcp') return reply({ enabled: false, tools: [], allowedSources: [], availableSources: [], endpoint: 'http://127.0.0.1/mcp', csrfToken: 'synthetic', readOnly: true });
    throw new Error(`Unexpected offline request ${method} ${path}`);
  });
  return { searches, writes, get linkCalls() { return linkCalls; } };
}

test('inbox and bookmarks share a lazy dialog without losing navigation or keyboard focus', async ({ page }) => {
  const data = await fixture(page);
  await page.goto('/?screen=notifications');
  expect(data.linkCalls).toBe(0);
  const opener = page.getByRole('button', { name: 'Открыть публикацию', exact: true });
  await opener.click();
  const dialog = page.getByRole('dialog', { name: 'Детали публикации' });
  await expect(dialog.getByRole('link', { name: 'Открыть в Telegram' })).toHaveAttribute('href', 'https://t.me/synthetic_group/42');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Совпадения');
  await expect(dialog.getByText('synthetic-cv.pdf', { exact: false })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(opener).toBeFocused();
  await openSection(page, 'Сохранённые публикации');
  await page.getByRole('button', { name: 'Открыть публикацию', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Сохранённые публикации');
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: 'Сохранить публикацию', exact: true }).click();
  await expect(dialog.getByText('Публикация сохранена.', { exact: true })).toBeVisible();
  expect(data.writes).toContain('/api/workspace/saved');
});

test('legacy saved scope overrides previous chat mode and a validation error can be dismissed', async ({ page }) => {
  const data = await fixture(page);
  await page.goto('/?screen=search');
  await openSection(page, 'Сохранённые поиски');
  await page.getByRole('button', { name: 'Искать сейчас', exact: true }).click();
  await expect.poll(() => data.searches.length).toBe(1);
  expect(data.searches[0]).toMatchObject({ templateId: 't1', mode: 'tracked', query: 'Java' });
  await expect(page.getByRole('alert')).toHaveCount(0);
  await page.getByLabel('Область поиска', { exact: true }).selectOption('channel');
  await page.getByLabel('Username канала', { exact: true }).fill('bad!');
  await page.getByRole('button', { name: 'Получить сообщения', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Введите username');
  await page.getByRole('button', { name: 'Закрыть ошибку', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await page.getByRole('button', { name: 'Получить сообщения', exact: true }).click();
  await openSection(page, 'MCP');
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('search steps retain query and inline conditions, with bounded catalog and responsive themes', async ({ page }) => {
  const data = await fixture(page);
  await page.goto('/?screen=search');
  await page.getByRole('checkbox', { name: source.title, exact: true }).check();
  await page.getByRole('button', { name: 'Далее: что искать' }).click();
  await page.getByLabel('Запрос в чатах', { exact: true }).fill('Java');
  await page.getByText('Дополнительные условия · AND / OR / NOT', { exact: true }).click();
  await page.getByLabel('Все слова / фразы (AND)', { exact: true }).fill('Java\nSpring');
  await page.getByLabel('Исключения (NOT)', { exact: true }).fill('junior');
  await page.getByRole('button', { name: '1. Где искать', exact: true }).click();
  await expect(page.getByRole('checkbox', { name: source.title, exact: true })).toBeChecked();
  await page.getByRole('button', { name: 'Далее: что искать' }).click();
  await expect(page.getByLabel('Запрос в чатах', { exact: true })).toHaveValue('Java');
  await page.getByRole('button', { name: 'Искать в выбранных чатах', exact: true }).click();
  await expect.poll(() => data.searches.length).toBe(1);
  expect(data.searches[0].search.conditions).toEqual({ required: ['Java', 'Spring'], any: [], excluded: ['junior'] });
  await expect(page.getByText(message.text, { exact: true })).toBeVisible();
  for (const width of [1600, 1280, 390]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: `/tmp/hradar-ux-search-${width}-light.png`, fullPage: true, animations: 'disabled' });
  }
  if (!(await page.getByRole('navigation', { name: 'Основная навигация' }).isVisible())) await page.getByRole('button', { name: 'Открыть навигацию', exact: true }).click();
  await page.getByRole('button', { name: 'Тёмная тема', exact: true }).click();
  await page.keyboard.press('Escape');
  await expect(page.locator('.search-stepper button').first()).toHaveCSS('background-color', 'rgb(23, 35, 47)');
  await page.screenshot({ path: '/tmp/hradar-ux-search-390-dark.png', fullPage: true, animations: 'disabled' });
});

test('popup consent stays consistent across screens and denial never changes tracking', async ({ page }) => {
  const data = await fixture(page);
  let prefs = { enabled: false, hidePreview: true };
  await page.route('**/api/workspace/desktop', route => {
    if (route.request().method() === 'PUT') prefs = route.request().postDataJSON();
    return route.fulfill({ json: prefs });
  });
  await page.addInitScript(() => {
    class DeniedNotification { static permission = 'denied'; static requestPermission() { throw new Error('Permission must never be requested automatically or after denial'); } }
    Object.defineProperty(window, 'Notification', { value: DeniedNotification });
  });
  await page.goto('/?screen=templates');
  await page.getByText('Как включить всплывающие уведомления', { exact: true }).click();
  const globalToggle = page.locator('.workspace-screen:not([hidden])').getByLabel('Включить всплывающие уведомления для hradar', { exact: true });
  await expect(globalToggle).not.toBeChecked();
  await openSection(page, 'Совпадения');
  await page.locator('.delivery-settings > summary').click();
  await globalToggle.check();
  await expect(page.getByRole('button', { name: 'Уведомления запрещены в настройках браузера', exact: true })).toBeDisabled();
  await openSection(page, 'Сохранённые поиски');
  await expect(globalToggle).toBeChecked();
  expect(prefs.enabled).toBe(true);
  expect(data.writes.some(path => path.includes('/templates'))).toBe(false);
});

test('equal publication identities group matching rules without grouping equal text from another chat', async ({ page }) => {
  await fixture(page);
  await page.route('**/api/notifications', route => route.fulfill({ json: { items: [match, { ...match, id: 'n2', templateId: 't2' }, { ...match, id: 'n3', templateId: 't2', message: { ...message, channelId: -1002, title: 'Another synthetic chat' } }], unread: 0, cursor: null } }));
  await page.goto('/?screen=notifications');
  await expect(page.locator('.notification-card')).toHaveCount(2);
  await expect(page.locator('.notification-card').first()).toContainText('Подошло поисков: 2');
  await expect(page.locator('.notification-card').last()).toContainText('Another synthetic chat');
});

test('common details add owner publication to an eligible project and never report a failed write as success', async ({ page }) => {
  await fixture(page);
  let attempts = 0;
  const bodies: unknown[] = [];
  await page.route('**/api/projects?**', route => route.fulfill({ json: { items: [
    { id: 'local-project', criteria: { name: 'Local hiring', sourceKeys: [source.key], sources: [] } },
    { id: 'legacy-project', criteria: { name: 'Legacy hiring', sources: [message.channelId] } },
  ], total: 2 } }));
  await page.route('**/api/projects/local-project/cards', route => {
    bodies.push(route.request().postDataJSON()); attempts++;
    return route.fulfill(attempts === 1 ? { status: 409, json: { error: 'Синтетическая ошибка добавления' } } : { status: 201, json: { id: 'c1' } });
  });
  await page.goto('/?screen=notifications');
  await page.getByRole('button', { name: 'Открыть публикацию', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Детали публикации' });
  await dialog.getByRole('button', { name: 'Добавить в проект', exact: true }).click();
  await expect(dialog.getByRole('option', { name: 'Legacy hiring', exact: true })).toHaveCount(1);
  await dialog.getByRole('combobox', { name: 'Проект найма', exact: true }).selectOption('local-project');
  await dialog.getByRole('button', { name: 'Добавить публикацию', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('Синтетическая ошибка добавления');
  await expect(dialog.getByText('Публикация добавлена на доску проекта.', { exact: true })).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Добавить публикацию', exact: true }).click();
  await expect(dialog.getByText('Публикация добавлена на доску проекта.', { exact: true })).toBeVisible();
  expect(bodies).toEqual(Array(2).fill({ sourceKey: source.key, channelId: message.channelId, messageId: message.id }));
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Совпадения');
});
