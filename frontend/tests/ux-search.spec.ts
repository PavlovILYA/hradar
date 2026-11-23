import { test, expect } from './fixtures';

const source = { key: 'qa', chatId: 123, type: 'BASIC_GROUP', title: 'Synthetic hiring', scope: { kind: 'CHAT' }, access: 'AVAILABLE' };
const template = { id: 'saved', name: 'Java hiring', telegramQuery: 'java', required: ['java'], any: [], excluded: [], sourceMode: 'chats', sourceKeys: ['qa'], sources: [], active: true, subscribed: false, notificationsEnabled: false };

test('persistent search steps preserve chosen scopes across catalog filters and explain delivery on save', async ({ page }) => {
  let saved: Record<string, unknown> | undefined;
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const method = route.request().method();
    let json: unknown = {};
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') json = { authorized: true, connectionState: 'Ready' };
    if (path === '/api/templates' || path === '/api/channels' || path === '/api/workspace/monitoring') json = [];
    if (path === '/api/notifications') json = { items: [], unread: 0, cursor: null };
    if (path === '/api/sources') json = { items: [source] };
    if (path === '/api/source-catalog') json = { items: new URL(route.request().url()).searchParams.get('filter') === 'channels' ? [] : [source] };
    if (path === '/api/onboarding') json = { csrfToken: 'synthetic' };
    if (path === '/api/workspace/desktop') json = { enabled: false, hidePreview: true };
    if (path === '/api/workspace/templates' && method === 'POST') { saved = route.request().postDataJSON(); json = { ...saved, id: 'saved' }; }
    if (path === '/api/workspace/search') json = { messages: [{ channelId: 123, id: 4, date: 1, title: source.title, sourceKey: 'qa', text: 'Synthetic java result' }], state: 'partial', cursor: 'next', origin: 'telegram', fetchedAt: Date.now(), notice: 'Один чат недоступен' };
    return route.fulfill({ json });
  });
  await page.goto('/?screen=search');
  await expect(page.getByRole('heading', { name: '1. Чаты и темы' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '2. Поисковый запрос' })).not.toBeVisible();
  await expect(page.getByRole('heading', { name: '3. Результаты' })).not.toBeVisible();
  await page.getByRole('checkbox', { name: source.title, exact: true }).check();
  await page.getByLabel('Тип чатов').selectOption('channels');
  await expect(page.getByRole('checkbox', { name: source.title, exact: true })).toBeChecked();
  await page.getByRole('button', { name: 'Далее: что искать' }).click();
  await page.getByLabel('Запрос в чатах').fill('java');
  await page.getByText('Дополнительные условия · AND / OR / NOT', { exact: true }).click();
  await page.getByLabel('Исключения (NOT)', { exact: true }).fill('junior');
  await page.getByRole('button', { name: 'Искать в выбранных чатах', exact: true }).click();
  await expect(page.getByText('Synthetic java result', { exact: true })).toBeVisible();
  await expect(page.getByText('Поиск неполный:', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Сохранить поиск…', exact: true }).click();
  await page.getByLabel('Название сохранённого поиска').fill('Java hiring');
  await page.getByLabel('Отслеживать новые публикации').check();
  await expect(page.getByText('Всплывающие уведомления требуют', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Сохранить поиск', exact: true }).click();
  await expect.poll(() => saved).toMatchObject({ sourceKeys: ['qa'], required: ['java'], excluded: ['junior'], subscribed: true, active: true, notificationsEnabled: false });
});

test('saved search resolves scopes, has four actions and confirms deletion', async ({ page }) => {
  let deleted = false;
  let toggled: Record<string, unknown> | undefined;
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    let json: unknown = {};
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') json = { authorized: true, connectionState: 'Ready' };
    if (path === '/api/templates') json = deleted ? [] : [template];
    if (path === '/api/channels' || path === '/api/workspace/monitoring') json = [];
    if (path === '/api/sources') json = { items: [source] };
    if (path === '/api/notifications') json = { items: [], unread: 0, cursor: null };
    if (path === '/api/onboarding') json = { csrfToken: 'synthetic' };
    if (path === '/api/workspace/desktop') json = { enabled: false, hidePreview: true };
    if (path === '/api/workspace/templates/saved') { toggled = route.request().postDataJSON(); json = toggled; }
    if (path === '/api/templates/saved' && route.request().method() === 'DELETE') deleted = true;
    return route.fulfill({ json });
  });
  await page.goto('/?screen=templates');
  const card = page.getByRole('article', { name: 'Java hiring' });
  await expect(card.getByText('Synthetic hiring', { exact: true })).toBeVisible();
  await expect(card.getByRole('button')).toHaveCount(4);
  await card.getByRole('button', { name: 'Включить отслеживание' }).click();
  await expect.poll(() => toggled).toMatchObject({ active: true, subscribed: true });
  await card.getByRole('button', { name: 'Удалить', exact: true }).click();
  expect(deleted).toBe(false);
  await page.getByRole('button', { name: 'Удалить сохранённый поиск', exact: true }).click();
  await expect.poll(() => deleted).toBe(true);
});

test('saved-search scope resolution preserves edits and blocks premature save', async ({ page }) => {
  const nextSource = { ...source, key: '', chatId: 124, title: 'Another synthetic group' };
  let saved: any = null;
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/events') return route.abort();
    let json: unknown = {};
    if (path === '/api/telegram/status') json = { authorized: true, connectionState: 'Ready' };
    if (path === '/api/templates') json = [template];
    if (path === '/api/channels' || path === '/api/workspace/monitoring') json = [];
    if (path === '/api/notifications') json = { items: [], unread: 0, cursor: null };
    if (path === '/api/onboarding') json = { csrfToken: 'synthetic' };
    if (path === '/api/workspace/desktop') json = { enabled: false, hidePreview: true };
    if (path === '/api/source-catalog') json = { items: [nextSource] };
    if (path === '/api/sources') {
      if (route.request().method() === 'POST') { await gate; json = { ...nextSource, key: 'new-key' }; }
      else json = { items: [source] };
    }
    if (path === '/api/workspace/templates/saved') { saved = route.request().postDataJSON(); json = saved; }
    return route.fulfill({ json });
  });
  await page.goto('/?screen=templates');
  await page.getByRole('button', { name: 'Настроить', exact: true }).click();
  await page.getByRole('checkbox', { name: nextSource.title, exact: true }).check();
  await page.getByLabel('Название сохранённого поиска', { exact: true }).fill('Edited during selection');
  await page.getByText('Дополнительные условия · AND / OR / NOT', { exact: true }).click();
  await page.getByLabel('Все слова / фразы (AND)', { exact: true }).fill('Java\nSpring');
  await expect(page.getByRole('button', { name: 'Сохранить настройки', exact: true })).toBeDisabled();
  release();
  await expect(page.getByRole('button', { name: 'Сохранить настройки', exact: true })).toBeEnabled();
  await expect(page.getByLabel('Название сохранённого поиска', { exact: true })).toHaveValue('Edited during selection');
  await page.getByRole('button', { name: 'Сохранить настройки', exact: true }).click();
  await expect.poll(() => saved).toMatchObject({ name: 'Edited during selection', required: ['Java', 'Spring'], sourceKeys: ['qa', 'new-key'] });
});
