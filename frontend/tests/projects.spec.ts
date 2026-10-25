import { openSection } from './navigation-helpers';
import { test, expect } from '@playwright/test';

test('recruiter creates a project, searches, reviews Kanban and restores notes after reload', async ({ page }, testInfo) => {
  let project: any = null;
  const cards: any[] = [];
  const message = { id: 1, channelId: -1001, date: 1700000000, title: 'Synthetic source', username: 'synthetic_jobs', text: 'Ищу работу Senior Java Spring Boot PostgreSQL Kafka Docker', link: 'https://t.me/synthetic_jobs/1', explanation: { matches: true, foundRequired: ['Java', 'Spring Boot', 'PostgreSQL'], foundAny: ['Kafka', 'Docker'], foundExcluded: [], missingRequired: [] } };
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url()); const path = url.pathname; const method = route.request().method();
    const reply = (json: unknown, status = 200) => route.fulfill({ json, status });
    if (process.env.CRM_FIXTURE_URL && path.startsWith('/api/projects')) {
      const response = await route.fetch({ url: `${process.env.CRM_FIXTURE_URL}${path}${url.search}`, headers: { ...route.request().headers(), host: '127.0.0.1:8080', origin: 'http://127.0.0.1:5173' } });
      if (path === '/api/projects' && method === 'POST') project = await response.json();
      return route.fulfill({ response });
    }
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return reply({ authorizationState: 'Ready', authorized: true, connectionState: 'Ready' });
    if (path === '/api/channels') return reply([{ id: -1001, username: 'synthetic_jobs', title: 'Synthetic source' }]);
    if (path === '/api/templates' || path === '/api/monitoring') return reply([]);
    if (path === '/api/notifications') return reply({ items: [], unread: 0, cursor: null });
    if (path === '/api/projects') {
      if (method === 'POST') { project = { id: 'p1', criteria: route.request().postDataJSON(), createdAt: 1700000000000, updatedAt: 1700000000000 }; return reply(project, 201); }
      const matches = project && project.criteria.status === url.searchParams.get('status'); return reply({ items: matches ? [project] : [], total: matches ? 1 : 0 });
    }
    if (path === '/api/projects/p1') { if (method === 'PUT') project.criteria = route.request().postDataJSON(); return reply(project); }
    if (path === '/api/projects/p1/search') return reply({ messages: [message], cursor: '', state: 'ok', scanned: 31, matched: 1, exhausted: true });
    if (path === '/api/projects/p1/statistics') return reply({ total: cards.length, stages: Object.fromEntries(['NEW', 'INTERESTING', 'CONTACTED', 'IN_PROGRESS', 'REJECTED'].map(s => [s, cards.filter(c => c.stage === s).length])) });
    if (path === '/api/projects/p1/cards') {
      if (method === 'POST') { if (!cards.length) cards.push({ id: 'c1', projectId: 'p1', message, stage: 'NEW', notes: '', interest: 0, classification: { kind: 'CANDIDATE', reason: 'Признак поиска работы: «ищу работу»' }, manualKind: null, createdAt: 1700000000000, updatedAt: 1700000000000 }); return reply(cards[0], 201); }
      expect(Number(url.searchParams.get('limit'))).toBeLessThanOrEqual(50);
      const items = cards.filter(c => c.stage === url.searchParams.get('stage') && c.message.text.toLowerCase().includes((url.searchParams.get('query') || '').toLowerCase()) && c.message.text.toLowerCase().includes((url.searchParams.get('technology') || '').toLowerCase())); return reply({ items, total: items.length });
    }
    if (path === '/api/projects/p1/cards/c1') { Object.assign(cards[0], route.request().postDataJSON(), { updatedAt: cards[0].updatedAt + 1000 }); return reply(cards[0]); }
    throw new Error(`Unmocked offline request: ${method} ${path}`);
  });
  await page.goto('/'); await openSection(page, 'Проекты');
  await expect(page.getByRole('heading', { name: 'Проектов пока нет' })).toBeVisible();
  await page.getByRole('button', { name: 'Создать проект', exact: true }).click();
  await page.getByLabel('Название проекта').fill('Senior Java Backend');
  await page.getByLabel('Описание вакансии').fill('Synthetic role description');
  await page.getByLabel('Обязательные технологии').fill('Java, Spring Boot, PostgreSQL');
  await page.getByLabel('Желательные технологии').fill('Kafka, Docker');
  await page.getByLabel('Исключения', { exact: true }).fill('junior');
  await page.getByRole('checkbox', { name: 'Synthetic source (@synthetic_jobs)' }).check();
  await page.getByRole('button', { name: 'Сохранить проект' }).click();
  await expect(page.getByRole('heading', { name: 'Senior Java Backend', exact: true })).toBeVisible();
  expect(project.criteria.required).toEqual(['Java', 'Spring Boot', 'PostgreSQL']);
  await page.getByText('Найти и добавить публикации', { exact: true }).click();
  await page.getByRole('button', { name: 'Найти публикации', exact: true }).click();
  await expect(page.getByText('Обязательные слова в тексте: Java, Spring Boot, PostgreSQL')).toBeVisible();
  await expect(page.getByText('За этот шаг проверено публикаций: 31 · Совпадений: 1')).toBeVisible();
  if (process.env.CRM_FIXTURE_URL) {
    await page.getByLabel('Где искать').selectOption('telegram');
    await page.getByRole('button', { name: 'Найти публикации', exact: true }).click();
    await expect(page.getByText('За этот шаг проверено публикаций: 50 · Совпадений: 0')).toBeVisible();
    await expect(page.getByText('Поиск ещё не завершён', { exact: false })).toBeVisible();
    await page.getByRole('button', { name: 'Продолжить поиск' }).click();
    await expect(page.getByText('За этот шаг проверено публикаций: 21 · Совпадений: 1')).toBeVisible();
  }
  await page.getByRole('button', { name: 'Добавить на доску' }).click();
  const fresh = page.getByRole('region', { name: 'Новые', exact: true });
  await expect(fresh.locator('.recruitment-card')).toHaveCount(1);
  await expect(fresh).toContainText('Кандидат ищет работу');
  await fresh.getByLabel('Заметки').fill('Проверить опыт с PostgreSQL');
  await fresh.getByLabel('Интерес', { exact: true }).selectOption('5');
  await fresh.getByLabel('Стадия', { exact: true }).selectOption('INTERESTING');
  await fresh.getByLabel('Классификация', { exact: true }).selectOption('EMPLOYER');
  await fresh.getByRole('button', { name: 'Сохранить карточку' }).click();
  const interested = page.getByRole('region', { name: 'Интересные', exact: true });
  await expect(interested.locator('.recruitment-card')).toHaveCount(1);
  await expect(interested.getByLabel('Заметки')).toHaveValue('Проверить опыт с PostgreSQL');
  await page.getByRole('region', { name: 'Проекты', exact: true }).evaluate(el => { el.scrollTop = 0; });
  await page.screenshot({ path: testInfo.outputPath('projects-desktop.png'), fullPage: true });
  await page.getByLabel('Поиск внутри проекта').fill('no-match'); await expect(interested.locator('.recruitment-card')).toHaveCount(0);
  await page.getByLabel('Поиск внутри проекта').fill(''); await expect(interested.locator('.recruitment-card')).toHaveCount(1);
  await page.reload(); await openSection(page, 'Проекты'); await page.locator('.project-tile').click();
  await expect(interested.getByLabel('Заметки')).toHaveValue('Проверить опыт с PostgreSQL');
  await expect(interested.getByLabel('Интерес', { exact: true })).toHaveValue('5');
  await expect(interested.getByRole('link', { name: 'Оригинал в Telegram' })).toHaveAttribute('href', message.link);
  await page.setViewportSize({ width: 390, height: 844 });
  await interested.getByLabel('Стадия', { exact: true }).selectOption('CONTACTED'); await interested.getByRole('button', { name: 'Сохранить карточку' }).click();
  await expect(page.getByRole('region', { name: 'Связались', exact: true }).locator('.recruitment-card')).toHaveCount(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole('region', { name: 'Проекты', exact: true }).evaluate(el => { el.scrollTop = 0; });
  await page.screenshot({ path: testInfo.outputPath('projects-mobile.png'), fullPage: true });
  await page.getByRole('button', { name: 'Настроить критерии' }).click(); await page.getByLabel('Статус проекта').selectOption('ARCHIVED'); await page.getByRole('button', { name: 'Сохранить проект' }).click();
  await expect(page.getByText('Проект архивирован.', { exact: false })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Связались', exact: true }).getByLabel('Стадия')).toBeDisabled();
});
