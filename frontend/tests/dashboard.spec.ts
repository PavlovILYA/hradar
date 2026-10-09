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

test('real local archive paginates and searches through backend', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('Telegram подключён');
  await page.getByRole('button', { name: 'Локальный архив', exact: true }).click();
  await expect(page.getByText('Доступная история загружена', { exact: true })).toBeVisible();
  await expect(page.locator('.history-archive .message-card')).toHaveCount(50);
  await page.getByRole('button', { name: 'Следующая страница архива' }).click();
  await expect(page.getByText(/Найдено: .*Страница 2/)).toBeVisible();
  await expect(page.locator('.history-archive .message-card')).toHaveCount(50);
  await page.getByLabel('Поиск по всей сохранённой истории').fill('telegram');
  await page.getByRole('button', { name: 'Найти в истории' }).click();
  await expect(page.getByText(/Найдено: .*Страница 1/)).toBeVisible();
  await expect(page.locator('.history-archive .message-card').first()).toBeVisible();
  expect(await page.locator('.history-archive .message-card').count()).toBeLessThanOrEqual(50);
  await page.getByLabel('Поиск по всей сохранённой истории').fill('hradar-no-match-zzxx');
  await page.getByRole('button', { name: 'Найти в истории' }).click();
  await expect(page.getByText('В сохранённой истории нет совпадений')).toBeVisible();
});

test('archive resumes a failed import and displays progress without percentages', async ({ page }) => {
  let started = false;
  const state = () => ({ channelId: -1001, username: 'telegram', title: 'Test archive', status: started ? 'RUNNING' : 'FAILED', cursor: 10, messageCount: started ? 110 : 100, retryCount: 0, nextRetryAt: 0, error: started ? null : 'Сетевая ошибка', updatedAt: 0 });
  await page.route('**/api/history/telegram/sync', route => {
    if (route.request().method() === 'POST') started = true;
    return route.fulfill({ json: state() });
  });
  await page.route('**/api/history/syncs', route => route.fulfill({ json: [state()] }));
  await page.route('**/api/history/telegram/messages?**', route => route.fulfill({ json: { messages: [], total: 0, nextCursor: null, query: '' } }));
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('Telegram подключён');
  await page.getByRole('button', { name: 'Локальный архив', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Сетевая ошибка');
  await page.getByRole('button', { name: 'Продолжить загрузку' }).click();
  await expect(page.getByTestId('history-count')).toHaveText('110');
  await expect(page.getByRole('button', { name: 'Импорт выполняется' })).toBeDisabled();
  await expect(page.locator('.import-status')).not.toContainText('%');
});

test('history stop preserves cursor and explicit limit controls resume', async ({ page }) => {
  let status = 'RUNNING';
  let selectedLimit: number | null = 1000;
  const state = () => ({ channelId: -1001, username: 'telegram', title: 'Synthetic', status, cursor: 10, messageCount: 1000, maxMessages: selectedLimit, pauseReason: status === 'PAUSED' ? 'USER' : null, retryCount: 0, nextRetryAt: 0, error: null, updatedAt: 0 });
  await page.route('**/api/history/telegram/sync', route => {
    if (route.request().method() === 'POST') {
      selectedLimit = route.request().postDataJSON().maxMessages;
      status = 'RUNNING';
    }
    return route.fulfill({ json: state() });
  });
  await page.route('**/api/history/telegram/stop', route => { status = 'PAUSED'; return route.fulfill({ json: state() }); });
  await page.route('**/api/history/syncs', route => route.fulfill({ json: [state()] }));
  await page.route('**/api/history/telegram/messages?**', route => route.fulfill({ json: { messages: [{ id: 10, channelId: -1001, date: 1700000000, text: 'Synthetic saved message' }], total: 1000, nextCursor: null, query: '' } }));
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('Telegram подключён');
  await page.getByRole('button', { name: 'Локальный архив', exact: true }).click();
  await expect(page.getByLabel('Лимит сохранённых сообщений')).toBeDisabled();
  await page.getByRole('button', { name: 'Остановить загрузку' }).click();
  await expect(page.getByText('Загрузка приостановлена', { exact: true })).toBeVisible();
  await expect(page.getByTestId('history-count')).toHaveText('1000');
  await expect(page.getByText('Synthetic saved message')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Продолжить загрузку' })).toBeDisabled();
  await page.getByLabel('Лимит сохранённых сообщений').selectOption('5000');
  await page.getByRole('button', { name: 'Продолжить загрузку' }).click();
  expect(selectedLimit).toBe(5000);
  await page.getByRole('button', { name: 'Остановить загрузку' }).click();
  await page.getByLabel('Лимит сохранённых сообщений').selectOption('all');
  await page.getByRole('button', { name: 'Продолжить загрузку' }).click();
  expect(selectedLimit).toBeNull();
});

test('opening history settings never automatically requests unlimited import', async ({ page }) => {
  let starts = 0;
  await page.route('**/api/history/telegram/sync', route => {
    if (route.request().method() === 'POST') starts++;
    return route.fulfill({ json: { channelId: 0, username: 'telegram', title: '', status: 'IDLE', cursor: 0, messageCount: 0, maxMessages: 1000, pauseReason: null, retryCount: 0, nextRetryAt: 0, error: null, updatedAt: 0 } });
  });
  await page.route('**/api/history/syncs', route => route.fulfill({ json: [] }));
  await page.route('**/api/history/telegram/messages?**', route => route.fulfill({ json: { messages: [], total: 0, nextCursor: null, query: '' } }));
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('Telegram подключён');
  await page.getByRole('button', { name: 'Настроить загрузку истории' }).click();
  await expect(page.getByRole('button', { name: 'Начать загрузку' })).toBeEnabled();
  await expect(page.getByLabel('Лимит сохранённых сообщений')).toHaveValue('1000');
  expect(starts).toBe(0);
});

test('online and archive searches use separate APIs and retain channel switching', async ({ page }) => {
  let onlineRequests = 0;
  let archiveRequests = 0;
  await page.route('**/api/search', route => { onlineRequests++; return route.fulfill({ json: { messages: [], cursor: '', state: 'ok', notice: null } }); });
  await page.route('**/api/history/**/messages?**', route => { archiveRequests++; return route.fulfill({ json: { messages: [], total: 0, nextCursor: null, query: '' } }); });
  await page.goto('/');
  await expect(page.getByRole('status').first()).toHaveText('Telegram подключён');
  await page.getByLabel('Поисковый запрос').fill('Java');
  await page.getByRole('button', { name: 'Искать в Telegram' }).click();
  expect(onlineRequests).toBe(1); expect(archiveRequests).toBe(0);
  await expect(page.getByText('Источник результатов: Telegram API. Локальный архив не используется.')).toBeVisible();
  await page.getByRole('button', { name: 'Локальный архив', exact: true }).click();
  await expect(page.getByText('Источник результатов: локальный архив SQLite. Поиск не обращается к Telegram API.')).toBeVisible();
  await expect(page.getByLabel('Поисковый запрос')).toHaveCount(0);
  await page.getByLabel('Поиск по всей сохранённой истории').fill('Java');
  await page.getByRole('button', { name: 'Найти в истории' }).click();
  await expect(page.getByText('В сохранённой истории нет совпадений')).toBeVisible();
  expect(onlineRequests).toBe(1); expect(archiveRequests).toBeGreaterThanOrEqual(2);
  await page.getByLabel('Канал для истории').fill('@telegramtips');
  await page.getByRole('button', { name: 'Открыть архив', exact: true }).click();
  await expect(page.getByLabel('Канал для истории')).toHaveValue('@telegramtips');
  await page.getByRole('button', { name: 'Поиск в Telegram', exact: true }).click();
  await expect(page.getByLabel('Username канала')).toHaveValue('@telegramtips');
  expect(onlineRequests).toBe(1);
});

test('SSE updates and reconnect deduplicate desktop notices and click opens the publication', async ({ page }) => {
  const item = { id: 'sse-synthetic', templateId: 'test', templateName: 'Java rule', read: false, sequence: 1, message: { id: 42, channelId: -100123, date: 1700000000, title: 'Synthetic source', username: 'synthetic_source', text: 'Java Spring developer: synthetic new publication', link: 'https://t.me/synthetic_source/1' } };
  let hasItem = false;
  await page.route('**/api/notifications', route => route.fulfill({ json: { items: hasItem ? [item] : [], unread: hasItem ? 1 : 0, cursor: null } }));
  await page.route('**/api/notifications/sse-synthetic/read', route => route.fulfill({ json: { deleted: true } }));
  await page.addInitScript(() => {
    const state = window as unknown as { testEvents: FakeEvents; testNotices: FakeNotice[] };
    class FakeEvents {
      onmessage: ((event: MessageEvent) => void) | null = null;
      onerror: (() => void) | null = null;
      onopen: (() => void) | null = null;
      constructor() { state.testEvents = this; }
      close() {}
    }
    class FakeNotice {
      static permission = 'default';
      static async requestPermission() { this.permission = 'granted'; return 'granted'; }
      onclick: (() => void) | null = null;
      constructor(public title: string, public options: NotificationOptions) { state.testNotices.push(this); }
      close() {}
    }
    state.testNotices = [];
    Object.defineProperty(window, 'EventSource', { value: FakeEvents });
    Object.defineProperty(window, 'Notification', { value: FakeNotice });
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Разрешить desktop-уведомления' }).click();
  await expect(page.getByRole('button', { name: 'Desktop-уведомления разрешены' })).toBeDisabled();
  async function emit(revision: number, unread: number) {
    await page.evaluate(async ({ revision, unread }) => {
      const events = (window as unknown as { testEvents: { onmessage: (event: MessageEvent) => void } }).testEvents;
      await events.onmessage(new MessageEvent('message', { data: JSON.stringify({ revision, unread, byChannel: { '-100123': unread } }) }));
    }, { revision, unread });
  }
  await emit(0, 0);
  await expect(page.getByRole('button', { name: 'Уведомления 0', exact: true })).toBeVisible();
  hasItem = true; await emit(1, 1);
  await expect.poll(() => page.evaluate(() => (window as unknown as { testNotices: unknown[] }).testNotices.length)).toBe(1);
  const notice = await page.evaluate(() => {
    const n = (window as unknown as { testNotices: { title: string; options: NotificationOptions }[] }).testNotices[0];
    return { title: n.title, body: n.options.body };
  });
  expect(notice.title).toContain('Synthetic source'); expect(notice.body).toContain('Java Spring developer');
  await page.evaluate(() => (window as unknown as { testEvents: { onerror: () => void } }).testEvents.onerror());
  await expect(page.getByText(/SSE переподключается/)).toBeVisible();
  await page.evaluate(() => (window as unknown as { testEvents: { onopen: () => void } }).testEvents.onopen());
  await expect(page.getByText('Обновления подключены (SSE)')).toBeVisible();
  const refreshed = page.waitForResponse(response => response.url().endsWith('/api/notifications'));
  await emit(2, 1); await refreshed;
  expect(await page.evaluate(() => (window as unknown as { testNotices: unknown[] }).testNotices.length)).toBe(1);
  await page.evaluate(() => (window as unknown as { testNotices: { onclick: () => void }[] }).testNotices[0].onclick());
  await expect(page.getByRole('heading', { name: 'Детали публикации' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Открыть в Telegram' })).toHaveAttribute('href', item.message.link);
});

test('real SSE can disconnect and reconnect with a fresh summary', async ({ page }) => {
  await page.goto('/');
  const summaries = await page.evaluate(async () => {
    const result: { unread: number; revision: number }[] = [];
    for (let i = 0; i < 2; i++) {
      const controller = new AbortController();
      const response = await fetch('/api/events', { signal: controller.signal });
      if (!response.ok || !response.headers.get('Content-Type')?.includes('text/event-stream')) throw new Error('SSE response invalid');
      const reader = response.body!.getReader(); let text = '';
      while (!text.includes('\n\n')) { const part = await reader.read(); if (part.done) throw new Error('SSE ended'); text += new TextDecoder().decode(part.value); }
      result.push(JSON.parse(text.split('\n')[0].replace(/^data: /, '')));
      controller.abort();
    }
    return result;
  });
  expect(summaries).toHaveLength(2);
  for (const summary of summaries) { expect(summary.unread).toBeGreaterThanOrEqual(0); expect(summary.revision).toBeGreaterThanOrEqual(0); }
});
