import { test, expect, type Page } from '@playwright/test';
import { openSection } from './navigation-helpers';
const channel = { id: -1001, username: 'synthetic_jobs', title: 'Synthetic Jobs' };
const project = { id: 'p1', criteria: { name: 'Synthetic Kotlin', status: 'ACTIVE', sources: [-1001], required: ['Kotlin'], desired: [], excluded: [], templateIds: [] }, createdAt: 1700000000000, updatedAt: 1700000000000 };
const template = { id: 't1', name: 'Synthetic rule', required: ['Kotlin'], any: [], excluded: [], telegramQuery: 'Kotlin', sourceMode: 'selected', sources: [-1001], active: true, subscribed: true };
const message = { id: 42, channelId: -1001, date: 1700000000, title: channel.title, username: channel.username, text: 'Synthetic Kotlin publication', link: 'https://t.me/synthetic_jobs/42' };
const notification = { id: 'n1', templateId: 't1', templateName: template.name, read: false, sequence: 1, message };
async function fixture(page: Page, options: { empty?: boolean; criteria?: boolean; subscribed?: boolean; connection?: string; fail?: string; slow?: boolean; multiple?: boolean } = {}) {
  let channels = options.empty ? [] : options.multiple ? [channel, { ...channel, id: -1002, title: 'Second synthetic source', username: 'synthetic_other' }] : [channel];
  let projects = options.empty ? [] : [{ ...project, criteria: { ...project.criteria, required: options.criteria === false ? [] : ['Kotlin'] } }];
  if (options.multiple) projects = [projects[0], { ...project, id: 'p2', criteria: { ...project.criteria, name: 'Synthetic React' } }, { ...project, id: 'p3', criteria: { ...project.criteria, name: 'Synthetic QA' } }];
  let templates = options.empty ? [] : [{ ...template, subscribed: options.subscribed !== false }];
  let read = false, fail = options.fail;
  const writes: string[] = [], paths: string[] = [];
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname; paths.push(route.request().url());
    const method = route.request().method();
    if (method !== 'GET') writes.push(path);
    if (path === '/api/sources') return route.fulfill({ json: { items: [] } });
    if (path === '/api/events') return route.abort();
    if (fail === 'all' || fail === path) return route.fulfill({ status: 503, json: { error: 'Синтетическая ошибка' } });
    const reply = (json: unknown) => route.fulfill({ json });
    if (options.slow && path === '/api/projects') await new Promise(resolve => setTimeout(resolve, 500));
    if (path === '/api/telegram/status') return reply({ authorized: true, authorizationState: 'Ready', connectionState: options.connection ?? 'Ready' });
    if (path === '/api/channels') return reply(channels);
    if (path === '/api/templates') return reply(templates);
    if (path === '/api/projects') return reply({ items: projects, total: projects.length ? 12 : 0 });
    if (path === '/api/projects/p1') return reply(projects[0]);
    if (path === '/api/projects/p1/statistics') return reply({ total: 0, stages: {} });
    if (path === '/api/projects/p1/cards') return reply({ items: [], total: 0 });
    if (path === '/api/projects/p1/matches' || path === '/api/projects/p1/saved') return reply({ items: [], messages: [], nextCursor: null });
    if (path === '/api/notifications') return reply({ items: options.empty ? [] : [{ ...notification, read }], unread: options.empty || read ? 0 : 7, cursor: null });
    if (path === '/api/notifications/n1/read') { read = true; return reply({ deleted: true }); }
    throw new Error(`Unexpected dashboard API: ${method} ${path}`);
  });
  await page.goto('/');
  return { writes, paths, recover: () => { fail = undefined; }, failAll: () => { fail = 'all'; }, emptySources: () => { channels = []; }, setConfigured: () => { channels = [channel]; projects = [project]; templates = [template]; } };
}
const card = (page: Page, name: string) => page.getByRole('region', { name, exact: true });

test('empty workspace has factual zeroes and dependency-aware setup, without writes', async ({ page }) => {
  const data = await fixture(page, { empty: true });
  await expect(card(page, 'Активные проекты').getByText('0', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Начните с первого источника' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Шаг 2. Создать проект найма' })).toBeDisabled();
  await expect(page.getByText('Непрочитанных совпадений нет')).toBeVisible();
  expect(data.writes).toEqual([]);
  await page.getByRole('button', { name: 'Шаг 1. Добавить Telegram-источник' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Источники');
  await expect(page.getByLabel('Username нового источника')).toBeVisible();
});

test('populated overview uses exact totals, bounded records and existing notification read flow', async ({ page }) => {
  const data = await fixture(page);
  await expect(card(page, 'Активные проекты').getByText('12', { exact: true })).toBeVisible();
  await expect(card(page, 'Новые совпадения').getByText('7', { exact: true })).toBeVisible();
  await expect(card(page, 'Мониторинг').getByText('Включён', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Начните с первого источника' })).toHaveCount(0);
  await expect(card(page, 'Последние совпадения').getByText('Synthetic Jobs')).toBeVisible();
  await expect(card(page, 'Последние совпадения').getByText('Synthetic rule')).toBeVisible();
  await card(page, 'Последние совпадения').getByRole('button', { name: 'Открыть публикацию' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Поиск');
  await expect(page.getByRole('region', { name: 'Сообщения канала' }).getByText(message.text)).toBeVisible();
  await expect(page.getByRole('complementary', { name: 'Детали публикации' }).getByText('42', { exact: true })).toBeVisible();
  await expect.poll(() => data.writes).toEqual(['/api/notifications/n1/read']);
  await openSection(page, 'Обзор');
  await expect(card(page, 'Новые совпадения').getByText('0', { exact: true })).toBeVisible();
  expect(data.paths.some(path => path.includes('/api/projects?limit=3&offset=0&status=ACTIVE'))).toBe(true);
  expect(data.paths.filter(path => path.includes('/history/'))).toEqual([]);
});

test('partial setup guides criteria and monitoring using saved facts, not visits', async ({ page }) => {
  await fixture(page, { criteria: false, subscribed: false });
  await expect(page.getByText('Следующий шаг: критерии последнего проекта')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Шаг 4. Настроить мониторинг' })).toBeDisabled();
  await page.getByRole('button', { name: 'Шаг 3. Указать критерии поиска' }).click();
  await expect(page.getByRole('heading', { name: 'Настройка проекта' })).toBeVisible();
  await expect(page.getByLabel('Обязательные технологии')).toHaveValue('');
  await openSection(page, 'Обзор');
  await expect(page.getByText('Следующий шаг: критерии последнего проекта')).toBeVisible();
});

test('disabled monitoring and Telegram reconnecting are explicit', async ({ page }) => {
  await fixture(page, { subscribed: false, connection: 'Connecting' });
  await expect(card(page, 'Мониторинг').getByText('Нет активных подписок')).toBeVisible();
  await expect(card(page, 'Состояние приложения').getByText('Переподключается')).toBeVisible();
  await page.getByRole('button', { name: 'Шаг 4. Настроить мониторинг' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Шаблоны');
});

test('unknown and failed data are never reported as zero or healthy; retry recovers', async ({ page }) => {
  const data = await fixture(page, { fail: 'all' });
  await expect(card(page, 'Активные проекты').getByText('Нет данных')).toBeVisible();
  await expect(card(page, 'Состояние приложения').getByText('Недоступен')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Начните с первого источника' })).toHaveCount(0);
  data.recover();
  await page.getByRole('button', { name: 'Обновить обзор' }).click();
  await expect(card(page, 'Активные проекты').getByText('12', { exact: true })).toBeVisible();
});

test('partial API failure leaves other cards usable and prevents false setup completion', async ({ page }) => {
  await fixture(page, { fail: '/api/templates' });
  await expect(card(page, 'Активные проекты').getByText('12', { exact: true })).toBeVisible();
  await expect(card(page, 'Мониторинг').locator('.dashboard-state')).toHaveText('Нет данных');
  await expect(page.getByRole('button', { name: 'Шаг 4. Настроить мониторинг' })).toHaveCount(0);
});

test('reentry refreshes setup facts and rapid navigation cannot redirect the screen', async ({ page }) => {
  const data = await fixture(page, { empty: true, slow: true });
  await openSection(page, 'Поиск');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Поиск');
  data.setConfigured();
  await openSection(page, 'Обзор');
  await expect(card(page, 'Активные проекты').getByText('12', { exact: true })).toBeVisible();
  data.emptySources();
  await page.getByRole('button', { name: 'Обновить обзор' }).click();
  await expect(card(page, 'Мониторинг').getByText('Нет доступных источников')).toBeVisible();
});

test('narrow dark/light overview stays usable with keyboard and localized timestamps', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await fixture(page);
  await expect(card(page, 'Активные проекты').getByText('12', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('button', { name: 'Открыть навигацию' }).click();
  await page.getByRole('button', { name: 'Тёмная тема', exact: true }).click();
  await page.keyboard.press('Escape');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.screenshot({ path: '/tmp/hradar-dashboard-mobile.png', fullPage: true });
  await expect(card(page, 'Последние совпадения').locator('time')).toHaveAttribute('datetime', new Date(message.date * 1000).toISOString());
  await page.getByRole('button', { name: 'Начать поиск', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Поиск');
});


test('several projects and sources show bounded previews; stale failures keep values without healthy status', async ({ page }) => {
  await page.clock.install();
  const data = await fixture(page, { multiple: true });
  await expect(card(page, 'Источники').getByText('2', { exact: true })).toBeVisible();
  await expect(card(page, 'Активные проекты').getByRole('listitem')).toHaveCount(3);
  await expect(card(page, 'Мониторинг').getByText('Включён', { exact: true })).toBeVisible();
  await page.screenshot({ path: '/tmp/hradar-dashboard-desktop.png', fullPage: true });
  data.failAll();
  await page.clock.runFor(65_000);
  await expect(card(page, 'Мониторинг').locator('.dashboard-state')).toHaveText('Нет данных');
  await expect(card(page, 'Активные проекты').getByText('12', { exact: true })).toBeVisible();
  await expect(card(page, 'Активные проекты').getByText(/Показаны предыдущие данные/)).toBeVisible();
  await expect(card(page, 'Состояние приложения').getByText('Нет актуальных данных')).toBeVisible();
});


test('quick create opens the real project form and ordinary navigation retains its draft', async ({ page }) => {
  await fixture(page);
  await page.getByRole('button', { name: 'Создать проект', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Новый проект', exact: true })).toBeVisible();
  await page.getByLabel('Название проекта', { exact: true }).fill('Synthetic draft');
  await openSection(page, 'Обзор');
  await openSection(page, 'Проекты');
  await expect(page.getByLabel('Название проекта', { exact: true })).toHaveValue('Synthetic draft');
});
