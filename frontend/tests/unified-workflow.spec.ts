import { test, expect } from '@playwright/test';

test('search chooses an unsaved account chat and saves a monitored template', async ({ page }) => {
  const chat = { key: '', chatId: 123, type: 'BASIC_GROUP', title: 'Synthetic QA', scope: { kind: 'CHAT' }, access: 'AVAILABLE', isForum: false };
  let template: Record<string, unknown> | null = null;
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown = {};
    if (path === '/api/telegram/status') body = { authorized: true, connectionState: 'Ready' };
    else if (path === '/api/events') return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"unread":0,"revision":0,"byChannel":{}}\n\n' });
    else if (path === '/api/channels' || path === '/api/templates') body = [];
    else if (path === '/api/source-catalog') body = { items: [chat], cursor: null, notice: 'Synthetic bounded catalog' };
    else if (path === '/api/sources') body = route.request().method() === 'POST' ? { ...chat, key: 'synthetic-key' } : { items: [] };
    else if (path === '/api/notifications') body = { items: [], unread: 0, cursor: null };
    else if (path === '/api/onboarding') body = { csrfToken: 'synthetic' };
    else if (path === '/api/workspace/search') body = { messages: [{ id: 11, channelId: 123, date: 1, text: 'Synthetic java', title: chat.title, sourceKey: 'synthetic-key' }], cursor: '', state: 'ok', fetchedAt: Date.now(), origin: 'telegram' };
    else if (path === '/api/workspace/templates') { template = route.request().postDataJSON(); body = { ...template, id: 'test-template' }; }
    return route.fulfill({ json: body });
  });
  await page.goto('/?screen=search');
  await page.getByRole('checkbox', { name: 'Synthetic QA', exact: true }).check();
  await page.getByLabel('Запрос в чатах').fill('java');
  await page.getByRole('button', { name: 'Искать в выбранных чатах', exact: true }).click();
  await expect(page.getByText('Synthetic java', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Сохранить как шаблон', exact: true }).click();
  await page.getByLabel('Название сохранённого поиска').fill('Java');
  await page.getByLabel('Включить мониторинг').check();
  await page.getByRole('button', { name: 'Сохранить поиск', exact: true }).click();
  await expect.poll(() => template).toMatchObject({ sourceMode: 'chats', sourceKeys: ['synthetic-key'], subscribed: true, required: ['java'] });
});

test('select all fills the ten chat bound after a partial selection', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const chats = Array.from({ length: 12 }, (_, i) => ({ key: `synthetic-${i}`, chatId: 100 + i, type: 'BASIC_GROUP', title: `QA ${i + 1}`, scope: { kind: 'CHAT' }, access: 'AVAILABLE' }));
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return route.fulfill({ json: { authorized: true, connectionState: 'Ready' } });
    if (path === '/api/source-catalog' || path === '/api/sources') return route.fulfill({ json: { items: chats, cursor: null } });
    if (path === '/api/templates' || path === '/api/channels') return route.fulfill({ json: [] });
    if (path === '/api/notifications') return route.fulfill({ json: { items: [], unread: 0, cursor: null } });
    return route.fulfill({ json: {} });
  });
  await page.goto('/?screen=search');
  for (let i = 1; i <= 5; i++) await page.getByRole('checkbox', { name: `QA ${i}`, exact: true }).check();
  await page.getByRole('button', { name: 'Выбрать все в текущей выборке (до 10)', exact: true }).click();
  await expect(page.getByRole('group', { name: 'Чаты и темы · выбрано 10 из 10' })).toBeVisible();
  await expect(page.getByRole('checkbox', { checked: true })).toHaveCount(10);
  await expect(page.getByRole('checkbox', { name: 'QA 11', exact: true })).toBeDisabled();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
