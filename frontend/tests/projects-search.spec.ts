import { openSection } from './navigation-helpers';
import { test, expect, type Page } from '@playwright/test';

const message = { id: 1, channelId: -1001, date: 1700000000, title: 'Synthetic source', username: 'synthetic_jobs', text: 'Java Spring Boot PostgreSQL', link: 'https://t.me/synthetic_jobs/1', explanation: { matches: true, foundRequired: ['Java', 'Spring Boot', 'PostgreSQL'], foundAny: [], foundExcluded: [], missingRequired: [] } };
const project = { id: 'p1', criteria: { name: 'Senior Java Backend', description: '', required: ['Java', 'Spring Boot', 'PostgreSQL'], desired: ['Kafka'], excluded: ['junior'], sources: [-1001], templateIds: [], status: 'ACTIVE' }, createdAt: 1700000000000, updatedAt: 1700000000000 };
async function open(page: Page, pages: unknown[], sources = true) {
  const searches: any[] = [];
  let additions = 0;
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const reply = (json: unknown) => route.fulfill({ json });
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return reply({ authorized: true, authorizationState: 'Ready', connectionState: 'Ready' });
    if (path === '/api/channels') return reply(sources ? [{ id: -1001, title: 'Synthetic source', username: 'synthetic_jobs' }] : []);
    if (path === '/api/templates' || path === '/api/monitoring') return reply([]);
    if (path === '/api/notifications') return reply({ items: [], unread: 0, cursor: null });
    if (path === '/api/projects') return reply({ items: [project], total: 1 });
    if (path === '/api/projects/p1') return reply(project);
    if (path.endsWith('/statistics')) return reply({ total: additions, stages: { NEW: additions } });
    if (path.endsWith('/cards')) {
      if (route.request().method() === 'POST') { additions++; return reply({ id: 'c1' }); }
      return reply({ items: [], total: 0 });
    }
    if (path.endsWith('/search')) {
      searches.push(route.request().postDataJSON());
      const result = pages.shift();
      if (result === 'failure') return route.fulfill({ status: 502, json: { error: 'Telegram временно недоступен' } });
      if (!result) throw new Error('Unexpected extra search');
      return reply(result);
    }
    throw new Error(`Unmocked offline request: ${path}`);
  });
  await page.goto('/'); await openSection(page, 'Проекты');
  await page.locator('.project-tile').click(); await page.getByText('Найти и добавить публикации', { exact: true }).click();
  return { searches, additions: () => additions };
}

test('empty bounded search continues, explains actual words and prevents duplicate addition', async ({ page }) => {
  const data = await open(page, [
    { messages: [], cursor: 'opaque-next', state: 'budget', scanned: 50, matched: 0, limitReached: true, exhausted: false },
    { messages: [message], cursor: '', state: 'ok', scanned: 3, matched: 1, exhausted: true, alreadyAdded: [] },
    { messages: [message], cursor: '', state: 'ok', scanned: 3, matched: 1, exhausted: true, alreadyAdded: ['-1001:1'] },
  ]);
  await page.getByRole('button', { name: 'Найти публикации', exact: true }).click();
  await expect(page.getByText('За этот шаг проверено публикаций: 50 · Совпадений: 0')).toBeVisible();
  await expect(page.getByText('Достигнут лимит поиска за один шаг.', { exact: false })).toBeVisible();
  await expect(page.getByText('Поиск ещё не завершён', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Продолжить поиск' }).click();
  expect(data.searches[1].cursor).toBe('opaque-next');
  await expect(page.getByText('Обязательные слова в тексте: Java, Spring Boot, PostgreSQL')).toBeVisible();
  await expect(page.getByText('Желательные слова в тексте: не обнаружены')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Оригинал в Telegram' })).toHaveAttribute('href', message.link);
  await page.getByRole('button', { name: 'Добавить на доску' }).click();
  await expect(page.getByRole('button', { name: 'Уже на доске' })).toBeDisabled();
  await expect(page.getByText('Все найденные публикации уже добавлены на доску.')).toBeVisible();
  expect(data.additions()).toBe(1);
  await page.getByRole('button', { name: 'Найти публикации', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Уже на доске' })).toBeDisabled();
  await page.getByLabel('Запрос', { exact: true }).fill('Spring');
  await expect(page.getByRole('button', { name: 'Продолжить поиск' })).toHaveCount(0);
});

for (const [state, text] of [
  ['archive_empty', 'Архив выбранных источников пуст.'],
  ['rate_limited', 'Telegram ограничил частоту запросов.'],
  ['telegram_error', 'Запрос Telegram не выполнен полностью.'],
] as const) test(`project search explains ${state}`, async ({ page }) => {
  await open(page, [{ messages: [], cursor: state === 'archive_empty' ? '' : 'retry', state, scanned: 0, matched: 0 }]);
  await page.getByRole('button', { name: 'Найти публикации', exact: true }).click();
  await expect(page.getByText(text, { exact: false })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Продолжить поиск' })).toHaveCount(state === 'archive_empty' ? 0 : 1);
});

test('missing sources disables search and explains how to save channels', async ({ page }) => {
  const data = await open(page, [], false);
  await expect(page.getByText('Нет сохранённых источников проекта.', { exact: false })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Найти публикации', exact: true })).toBeDisabled();
  expect(data.searches).toHaveLength(0);
  await page.getByRole('button', { name: 'Настроить критерии' }).click();
  await expect(page.getByText('Нет сохранённых источников.', { exact: false })).toBeVisible();
});

test('failed request can be retried and exhausted query differs from restrictive requirements', async ({ page }) => {
  await open(page, ['failure', { messages: [], cursor: '', state: 'ok', scanned: 0, matched: 0, exhausted: true }, { messages: [], cursor: '', state: 'ok', scanned: 17, matched: 0, exhausted: true }]);
  await page.getByRole('button', { name: 'Найти публикации', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Telegram временно недоступен');
  await page.getByRole('button', { name: 'Повторить поиск' }).click();
  await expect(page.getByText('По запросу не найдено совпадений.', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Найти публикации', exact: true }).click();
  await expect(page.getByText('Требования ограничивают выборку:', { exact: false })).toBeVisible();
});
