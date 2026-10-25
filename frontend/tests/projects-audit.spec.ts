import { openSection } from './navigation-helpers';
import { test, expect, type Page } from '@playwright/test';

async function fixture(page: Page, options: { delayedProject?: boolean; failedColumn?: boolean; delayedWrite?: boolean; failedOriginal?: boolean } = {}) {
  let project = { id: 'p1', criteria: { name: 'Synthetic project', description: '', required: ['Java'], desired: [], excluded: [], sources: [-1001], templateIds: [] as string[], status: 'ACTIVE' }, createdAt: 1700000000000, updatedAt: 1700000000000 };
  const template = { id: 'owned', name: 'Synthetic monitoring', required: ['Java'], any: [], excluded: [], telegramQuery: 'java', sourceMode: 'selected', sources: [-1001], active: true, subscribed: true };
  const message = { id: 1, channelId: -1001, date: 1700000000, title: 'Synthetic source', username: 'synthetic_jobs', text: 'Synthetic saved Java publication', link: 'https://t.me/synthetic_jobs/1' };
  const cards = [{ id: 'c1', projectId: 'p1', message, stage: 'NEW', notes: 'Saved note', interest: 0, classification: { kind: 'INSUFFICIENT', reason: 'Synthetic reason' }, manualKind: null, createdAt: 1700000000000, updatedAt: 1700000000000 }];
  const writes: { method: string; body: any }[] = []; let newColumnCalls = 0;
  let releaseWrite!: () => void;
  const writeGate = new Promise<void>(resolve => { releaseWrite = resolve; });
  let releaseProject!: () => void;
  const gate = new Promise<void>(resolve => { releaseProject = resolve; });
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url()); const path = url.pathname; const method = route.request().method();
    const reply = (json: unknown, status = 200) => route.fulfill({ json, status });
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return reply({ authorized: true, authorizationState: 'Ready', connectionState: 'Ready' });
    if (path === '/api/sources') return reply({ items: [] });
    if (path === '/api/channels') return reply([{ id: -1001, title: 'Synthetic source', username: 'synthetic_jobs' }]);
    if (path === '/api/templates') return reply(project.criteria.templateIds.length ? [template] : []);
    if (path === '/api/monitoring') return reply([]);
    if (path === '/api/notifications') return reply({ items: [], unread: 0, cursor: null });
    if (path === '/api/projects') {
      if (method === 'POST') { writes.push({ method, body: route.request().postDataJSON() }); if (options.delayedWrite) await writeGate; project = { ...project, id: 'new', criteria: route.request().postDataJSON() }; return reply(project); }
      return reply({ items: [project], total: 1 });
    }
    if (path === '/api/projects/p1') {
      if (method === 'PUT') { writes.push({ method, body: route.request().postDataJSON() }); project = { ...project, criteria: route.request().postDataJSON(), updatedAt: project.updatedAt + 1000 }; }
      else if (options.delayedProject) { await gate; if (route.request().failure()) return; }
      return reply(project);
    }
    if (path.endsWith('/statistics')) return reply({ total: cards.length, stages: { NEW: cards.length } });
    if (path.endsWith('/subscription')) { project = { ...project, criteria: { ...project.criteria, templateIds: [template.id] }, updatedAt: project.updatedAt + 1000 }; return reply(template); }
    if (path.endsWith('/link')) return reply({ error: 'Ссылка на оригинал временно недоступна' }, 502);
    if (path.endsWith('/search')) return reply({ messages: [{ ...message, id: 2, text: 'Synthetic new Java publication', link: options.failedOriginal ? null : message.link }], cursor: '', state: 'ok', scanned: 1, matched: 1 });
    if (path.endsWith('/cards')) {
      if (method === 'POST') { cards.push({ ...cards[0], id: 'c2', message: { ...message, id: 2, text: 'Synthetic new Java publication' }, notes: '' }); return reply(cards[1]); }
      if (url.searchParams.get('stage') === 'NEW') {
        newColumnCalls++;
        if (options.failedColumn && newColumnCalls === 1) return reply({ error: 'Временная ошибка карточек' }, 502);
      }
      const items = cards.filter(c => c.stage === url.searchParams.get('stage'));
      return reply({ items, total: items.length });
    }
    throw new Error(`Unexpected offline request: ${method} ${path}`);
  });
  await page.goto('/'); await openSection(page, 'Проекты');
  await expect(page.locator('.project-tile')).toBeVisible();
  return { writes, releaseProject, releaseWrite, project: () => project, calls: () => newColumnCalls };
}

for (const width of [1280, 390]) test(`unsaved card note survives adding another result at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 844 }); await fixture(page); await page.locator('.project-tile').click();
  const card = page.getByRole('region', { name: 'Новые', exact: true }).locator('.recruitment-card').filter({ hasText: 'Synthetic saved Java publication' });
  await card.getByLabel('Заметки').fill('Unsaved draft that must survive refresh');
  await card.getByLabel('Интерес', { exact: true }).selectOption('5');
  await page.getByText('Найти и добавить публикации', { exact: true }).click();
  await page.getByRole('button', { name: 'Найти публикации', exact: true }).click();
  await page.getByRole('button', { name: 'Добавить на доску', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Новые', exact: true }).locator('.recruitment-card')).toHaveCount(2);
  await expect(card.getByLabel('Заметки')).toHaveValue('Unsaved draft that must survive refresh', { timeout: 2000 });
  await expect(card.getByLabel('Интерес', { exact: true })).toHaveValue('5');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test('monitoring link survives immediately opening and saving project settings', async ({ page }) => {
  const data = await fixture(page); await page.locator('.project-tile').click();
  await page.getByText('Найти и добавить публикации', { exact: true }).click();
  await page.getByRole('button', { name: 'Включить мониторинг по критериям' }).click();
  await expect(page.getByText('Мониторинг включён.', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Настроить критерии' }).click();
  await page.getByLabel('Описание вакансии').fill('Changed description');
  await page.getByRole('button', { name: 'Сохранить проект' }).click();
  await expect(page.getByRole('heading', { name: 'Synthetic project', exact: true })).toBeVisible();
  expect(data.writes[0].body.templateIds).toEqual(['owned']);
  expect(data.project().criteria.templateIds).toEqual(['owned']);
});

test('requirements preserve typed spacing and caret edits', async ({ page }) => {
  await fixture(page); await page.getByRole('button', { name: 'Создать проект', exact: true }).click();
  const input = page.getByLabel('Обязательные технологии');
  await input.pressSequentially('Java, Spring Boot, PostgreSQL');
  await expect(input).toHaveValue('Java, Spring Boot, PostgreSQL', { timeout: 1000 });
  await input.evaluate((el: HTMLInputElement) => el.setSelectionRange(0, 0));
  await input.pressSequentially('Senior ');
  await expect(input).toHaveValue('Senior Java, Spring Boot, PostgreSQL');
});

test('delayed opening cannot turn a new project form into an edit of an existing project', async ({ page }) => {
  const data = await fixture(page, { delayedProject: true });
  const requested = page.waitForRequest('**/api/projects/p1');
  await page.locator('.project-tile').click(); await requested;
  await page.getByRole('button', { name: 'Создать проект', exact: true }).click();
  data.releaseProject();
  // Allow the deliberately delayed response to reach the browser before submitting.
  await page.waitForTimeout(150);
  await page.getByLabel('Название проекта').fill('New independent project');
  await page.getByRole('checkbox', { name: 'Synthetic source (@synthetic_jobs)' }).check();
  await page.getByRole('button', { name: 'Сохранить проект' }).click();
  await expect(page.getByRole('heading', { name: 'New independent project', exact: true })).toBeVisible();
  expect(data.writes.map(w => w.method)).toEqual(['POST']);
});

test('retry reloads a failed Kanban column without a full browser reload', async ({ page }) => {
  const data = await fixture(page, { failedColumn: true }); await page.locator('.project-tile').click();
  await expect(page.getByRole('alert')).toContainText('Временная ошибка карточек');
  await page.getByRole('button', { name: 'Повторить', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Новые', exact: true }).locator('.recruitment-card')).toHaveCount(1, { timeout: 2000 });
  expect(data.calls()).toBe(2);
});


test('saving a project locks its form and navigation until the write finishes', async ({ page }) => {
  const data = await fixture(page, { delayedWrite: true });
  await page.getByRole('button', { name: 'Создать проект', exact: true }).click();
  await page.getByLabel('Название проекта').fill('Pending save');
  await page.getByRole('checkbox', { name: 'Synthetic source (@synthetic_jobs)' }).check();
  await page.getByRole('button', { name: 'Сохранить проект' }).click();
  try {
    await expect(page.getByRole('button', { name: 'Все проекты', exact: true })).toBeDisabled({ timeout: 1000 });
    await expect(page.getByRole('button', { name: 'Отмена', exact: true })).toBeDisabled();
    await expect(page.getByLabel('Название проекта')).toBeDisabled();
    await page.locator('.project-form').dispatchEvent('submit');
    expect(data.writes).toHaveLength(1);
  } finally { data.releaseWrite(); }
  await expect(page.getByRole('heading', { name: 'Pending save', exact: true })).toBeVisible();
  expect(data.writes).toHaveLength(1);
});

test('an original-link failure does not claim the successful search failed', async ({ page }) => {
  await fixture(page, { failedOriginal: true }); await page.locator('.project-tile').click();
  await page.getByText('Найти и добавить публикации', { exact: true }).click();
  await page.getByRole('button', { name: 'Найти публикации', exact: true }).click();
  await page.locator('.discovery-result').getByRole('button', { name: 'Получить ссылку на оригинал' }).click();
  await expect(page.getByRole('alert')).toContainText('Ссылка на оригинал временно недоступна');
  await expect(page.getByRole('button', { name: 'Повторить поиск' })).toHaveCount(0);
  await expect(page.locator('.discovery-result')).toContainText('Synthetic new Java publication');
});
