import { test, expect } from '@playwright/test';

test.beforeAll(async ({ request }) => {
  await expect.poll(async () => {
    try {
      const response = await request.get('/api/telegram/status', { timeout: 2000 });
      const status = await response.json();
      return response.ok() && status.authorized && (!status.connectionState || status.connectionState === 'Ready');
    } catch { return false; }
  }, { timeout: 30_000, intervals: [1000, 2000] }).toBe(true);
});

test('loads real Telegram messages through the frontend proxy', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('Telegram подключён');
  await expect(page.getByRole('heading', { name: 'Здесь появятся сообщения канала' })).toBeVisible();
  await page.screenshot({ path: '/tmp/hradar-desktop.png', fullPage: true });
  await page.getByRole('button', { name: 'Получить сообщения' }).click();
  await expect(page.locator('.message-card')).toHaveCount(10, { timeout: 30_000 });
  await expect(page.locator('.message-footer').first()).toContainText('ID канала: -100');
  await page.getByRole('searchbox', { name: 'Поиск по сообщениям' }).fill('hradar-no-match-zzxx');
  await expect(page.getByRole('heading', { name: 'Совпадений не найдено' })).toBeVisible();
  await page.getByRole('button', { name: 'Очистить поиск' }).click();
  await expect(page.locator('.message-card')).toHaveCount(10);
});

test('shows real missing-channel error without stale messages', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('Telegram подключён');
  await page.getByLabel('Username канала').fill('@hradar_missing_test_9x7q2');
  await page.getByRole('button', { name: 'Получить сообщения' }).click();
  await expect(page.getByRole('alert')).toContainText('Канал не найден', { timeout: 30_000 });
  await expect(page.locator('.message-card')).toHaveCount(0);
});

test('validates username before making a history request', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('Telegram подключён');
  await page.getByLabel('Username канала').fill('bad!');
  await page.getByRole('button', { name: 'Получить сообщения' }).click();
  await expect(page.getByRole('alert')).toContainText('Введите username канала');
});

test('displays a backend error and retries status', async ({ page }) => {
  await page.route('**/api/events', route => route.abort());
  await page.route('**/api/telegram/status', route => route.fulfill({ status: 502, body: '' }));
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('Backend недоступен');
  await expect(page.getByRole('button', { name: 'Получить сообщения' })).toBeDisabled();
  await expect(page.getByRole('alert')).toBeVisible();
  await page.unroute('**/api/telegram/status');
  await page.getByRole('button', { name: 'Обновить статус' }).click();
  await expect(page.getByRole('status')).toHaveText('Telegram подключён');
  await expect(page.getByRole('button', { name: 'Получить сообщения' })).toBeEnabled();
});

test('renders empty history and media without text safely', async ({ page }) => {
  await page.route('**/api/telegram/chats/**', route => route.fulfill({ json: [] }));
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('Telegram подключён');
  await page.getByRole('button', { name: 'Получить сообщения' }).click();
  await expect(page.getByRole('heading', { name: 'В канале пока нет доступных сообщений' })).toBeVisible();
  await page.unroute('**/api/telegram/chats/**');
  await page.route('**/api/telegram/chats/**', route => route.fulfill({ json: [
    { id: 1, date: 1700000000, text: null, channelId: -100123 },
    { id: 2, date: 1700000001, text: '<script>window.hradarInjected=true</script>', channelId: -100123 },
  ] }));
  await page.getByRole('button', { name: 'Получить сообщения' }).click();
  await expect(page.locator('.message-card')).toHaveCount(2);
  await expect(page.getByText('Медиа или служебное сообщение без текста')).toBeVisible();
  await expect(page.locator('.message-text').nth(1)).toContainText('<script>');
  expect(await page.evaluate(() => 'hradarInjected' in window)).toBe(false);
});

test('mobile layout fits the viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('Telegram подключён');
  await page.getByRole('button', { name: 'Открыть источники' }).click();
  await page.getByRole('button', { name: 'Получить сообщения' }).click();
  await expect(page.locator('.message-card')).toHaveCount(10, { timeout: 30_000 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: '/tmp/hradar-mobile.png', fullPage: true });
});

test('theme persists and publication details show actual message IDs', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('Telegram подключён');
  await page.getByRole('button', { name: 'Тёмная тема' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.getByRole('button', { name: 'Получить сообщения' }).click();
  await expect(page.locator('.message-card')).toHaveCount(10);
  await page.getByRole('button', { name: 'Детали ↗' }).first().click();
  await expect(page.getByRole('heading', { name: 'Детали публикации' })).toBeVisible();
  await expect(page.locator('dd').last()).toContainText('-100');
  await page.screenshot({ path: '/tmp/hradar-research-dark.png', fullPage: true });
});

test('creates edits pauses and deletes a template through the real API', async ({ page }) => {
  const name = `E2E research ${Date.now()}`;
  const edited = name + ' edited';
  await page.goto('/');
  await page.getByRole('button', { name: 'Шаблоны', exact: true }).click();
  await page.getByRole('button', { name: 'Создать шаблон' }).click();
  await page.getByLabel('Название шаблона').fill(name);
  await page.getByLabel('Запрос Telegram', { exact: true }).fill('Java');
  await page.getByLabel('Все слова / фразы (AND)').fill('Java\nSpring');
  await page.getByLabel('Хотя бы одно (OR)').fill('Kafka\nPostgreSQL');
  await page.getByLabel('Исключения (NOT)').fill('Junior');
  await page.getByRole('button', { name: 'Сохранить шаблон' }).click();
  const card = page.locator('.template-card').filter({ hasText: name });
  await expect(card).toBeVisible();
  await card.getByRole('button', { name: 'Изменить' }).click();
  await page.getByLabel('Название шаблона').fill(edited);
  await page.getByRole('button', { name: 'Сохранить шаблон' }).click();
  await expect(card).toContainText(edited);
  await card.getByRole('button', { name: 'Пауза', exact: true }).click();
  await expect(card).toContainText('Приостановлен');
  await page.reload();
  await page.getByRole('button', { name: 'Шаблоны', exact: true }).click();
  await expect(card).toContainText('Приостановлен');
  await card.getByRole('button', { name: 'Удалить', exact: true }).click();
  await expect(card).toHaveCount(0);
});

test('searches Telegram history with paging and original links', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('Telegram подключён');
  await page.getByLabel('Сообщений', { exact: true }).fill('5');
  await page.getByLabel('Поисковый запрос').fill('features');
  await page.getByRole('button', { name: 'Искать в Telegram' }).click();
  await expect(page.locator('.message-card')).toHaveCount(5, { timeout: 90_000 });
  await page.getByRole('button', { name: 'Детали ↗' }).first().click();
  await expect(page.getByRole('link', { name: 'Открыть в Telegram' })).toHaveAttribute('href', /^https:\/\/t\.me\//);
  await page.getByRole('button', { name: 'Следующая страница' }).click();
  await expect(page.locator('.message-card')).toHaveCount(10, { timeout: 90_000 });
});

test('notification center shows explanation and marks an item read', async ({ page }) => {
  await page.route('**/api/events', route => route.abort());
  const item = { id: 'synthetic-test', templateId: 'test', templateName: 'Synthetic Java', read: false, sequence: 1, message: { id: 42, channelId: -100123, date: 1700000000, title: 'Synthetic source', username: 'synthetic_source', text: 'Java Spring Kafka', link: 'https://t.me/synthetic_source/1', explanation: { matches: true, foundRequired: ['Java', 'Spring'], foundAny: ['Kafka'], foundExcluded: [], missingRequired: [] } } };
  await page.route('**/api/notifications', route => route.fulfill({ json: { items: [item], unread: item.read ? 0 : 1, cursor: null } }));
  await page.route('**/api/notifications/synthetic-test/read', route => { item.read = true; return route.fulfill({ json: { deleted: true } }); });
  await page.goto('/');
  await page.getByRole('button', { name: /^Уведомления \d+$/ }).click();
  await expect(page.locator('.notification-card')).toHaveCount(1);
  await expect(page.locator('.match-reason')).toContainText('Java, Spring, Kafka');
  await page.getByRole('button', { name: 'Отметить прочитанным' }).click();
  await expect(page.locator('.notification-card.unread')).toHaveCount(0);
  await page.getByRole('button', { name: 'Открыть публикацию' }).click();
  await expect(page.getByRole('heading', { name: 'Детали публикации' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Открыть в Telegram' })).toHaveAttribute('href', item.message.link);
});

test('global free limit is explicit and never substitutes channel results', async ({ page }) => {
  await page.route('**/api/search', route => route.fulfill({ json: { messages: [], cursor: '', state: 'free_limit', notice: 'Бесплатный глобальный поиск недоступен. Stars не расходуются.' } }));
  await page.goto('/');
  await page.getByLabel('Область поиска').selectOption('global');
  await page.getByLabel('Поисковый запрос').fill('synthetic query');
  await page.getByRole('button', { name: 'Искать в Telegram' }).click();
  await expect(page.getByRole('heading', { name: 'Глобальный поиск недоступен' })).toBeVisible();
  await expect(page.locator('.message-card')).toHaveCount(0);
});
