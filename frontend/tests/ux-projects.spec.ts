import { test, expect, type Page } from './fixtures';
import { openSection } from './navigation-helpers';

async function fixture(page: Page, legacyCount = 0) {
  const sources = Array.from({ length: legacyCount }, (_, i) => ({ id: -1000 - i, title: `Legacy ${i}`, username: `legacy_${i}` }));
  const local = { key: 'local-scope', chatId: -2000, title: 'Direct chat', scope: { kind: 'CHAT' }, access: 'AVAILABLE', type: 'SUPERGROUP', legacyChannel: false };
  let selected = false;
  let project: any = null;
  let card: any = null;
  const writes: any[] = [];
  const message = { id: 1, channelId: -2000, sourceKey: local.key, date: 1700000000, title: 'Direct chat', text: 'Synthetic Java publication', link: null };
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname, method = route.request().method();
    const reply = (json: unknown) => route.fulfill({ json });
    if (path === '/api/onboarding') return reply({ csrfToken: 'synthetic' });
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return reply({ authorized: true, authorizationState: 'Ready', connectionState: 'Ready' });
    if (path === '/api/sources') { if (method === 'POST') { selected = true; return reply(local); } return reply({ items: selected ? [local] : [] }); }
    if (path === '/api/source-catalog') return reply({ items: [local] });
    if (path === '/api/channels') return reply(sources);
    if (path === '/api/templates' || path === '/api/monitoring' || path === '/api/workspace/monitoring') return reply([]);
    if (path === '/api/projects') {
      if (method === 'POST') { writes.push(route.request().postDataJSON()); project = { id: 'p1', criteria: writes.at(-1), createdAt: 1700000000000, updatedAt: 1700000000000 }; return reply(project); }
      return reply({ items: project ? [project] : [], total: project ? 1 : 0 });
    }
    if (path === '/api/projects/p1') return reply(project);
    if (path.endsWith('/statistics')) return reply({ total: card ? 1 : 0, stages: card ? { [card.stage]: 1 } : {} });
    if (path.endsWith('/search')) return reply({ messages: [message], cursor: '', state: 'ok', scanned: 1, matched: 1 });
    if (path.endsWith('/cards')) {
      if (method === 'POST') { card = { id: 'c1', projectId: 'p1', message, stage: 'NEW', notes: '', interest: 0, classification: { kind: 'INSUFFICIENT', reason: 'Synthetic classification' }, manualKind: null, createdAt: 1700000000000, updatedAt: 1700000000000 }; return reply(card); }
      const items = card && card.stage === url.searchParams.get('stage') ? [card] : []; return reply({ items, total: items.length });
    }
    if (path.endsWith('/cards/c1')) { Object.assign(card, route.request().postDataJSON(), { updatedAt: card.updatedAt + 1 }); return reply(card); }
    if (path.endsWith('/subscription')) throw new Error('Local projects must not call public subscriptions');
    return reply(path.includes('preferences') ? { enabled: false, hidePreviews: true } : { items: [], messages: [], unread: 0 });
  });
  await page.goto('/'); await openSection(page, 'Проекты');
  await page.getByRole('button', { name: 'Создать проект', exact: true }).click();
  await page.getByLabel('Название проекта').fill('Direct selection project');
  return { writes };
}

test('project selects chat directly, explains tracking and preserves card workflow', async ({ page }) => {
  const { writes } = await fixture(page);
  await page.getByRole('checkbox', { name: 'Direct chat', exact: true }).check();
  await page.getByLabel('Обязательные технологии').fill('Java');
  await page.getByRole('button', { name: 'Сохранить проект' }).click();
  expect(writes[0].sourceKeys).toEqual(['local-scope']);
  await page.getByText('Найти и добавить публикации', { exact: true }).click();
  await expect(page.getByText('Мониторинг чатов проекта настраивается через сохранённый поиск.', { exact: false })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Включить мониторинг по критериям' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Найти публикации', exact: true }).click();
  await page.getByRole('button', { name: 'Добавить на доску', exact: true }).click();
  const fresh = page.getByRole('region', { name: 'Новые', exact: true });
  await fresh.getByLabel('Заметки').fill('Synthetic note');
  await fresh.getByLabel('Интерес', { exact: true }).selectOption('4');
  await fresh.getByLabel('Стадия', { exact: true }).selectOption('INTERESTING');
  await fresh.getByRole('button', { name: 'Сохранить карточку' }).click();
  const card = page.getByRole('region', { name: 'Интересные', exact: true });
  await expect(card.getByLabel('Заметки')).toHaveValue('Synthetic note');
  await expect(card.getByLabel('Интерес', { exact: true })).toHaveValue('4');
  for (const width of [1280, 768, 390]) {
    await page.setViewportSize({ width, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  }
});

test('project scope bound includes retained legacy sources', async ({ page }) => {
  const { writes } = await fixture(page, 10);
  await page.locator('.project-legacy-sources > summary').click();
  for (let i = 0; i < 10; i++) await page.getByRole('checkbox', { name: `Legacy ${i} (@legacy_${i})`, exact: true }).check();
  await expect(page.getByRole('checkbox', { name: 'Direct chat', exact: true })).toBeDisabled();
  await page.getByRole('checkbox', { name: 'Legacy 9 (@legacy_9)', exact: true }).uncheck();
  await page.getByRole('checkbox', { name: 'Direct chat', exact: true }).check();
  await expect(page.getByRole('checkbox', { name: 'Legacy 9 (@legacy_9)', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Сохранить проект' }).click();
  expect(writes[0].sources.length + writes[0].sourceKeys.length).toBe(10);
});

test('late chat selection preserves project edits and blocks saving pending scope', async ({ page }) => {
  const { writes } = await fixture(page);
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/sources', async route => {
    if (route.request().method() !== 'POST') return route.fallback();
    await gate;
    return route.fulfill({ json: { key: 'local-scope', chatId: -2000, title: 'Direct chat', scope: { kind: 'CHAT' }, access: 'AVAILABLE', type: 'SUPERGROUP', legacyChannel: false } });
  });
  await page.getByRole('checkbox', { name: 'Direct chat', exact: true }).check();
  await page.getByLabel('Название проекта').fill('Edited during selection');
  await page.getByLabel('Обязательные технологии').fill('Java');
  await expect(page.getByRole('button', { name: 'Сохранить проект' })).toBeDisabled();
  release();
  await expect(page.getByRole('button', { name: 'Сохранить проект' })).toBeEnabled();
  await expect(page.getByLabel('Название проекта')).toHaveValue('Edited during selection');
  await expect(page.getByLabel('Обязательные технологии')).toHaveValue('Java');
  await page.getByRole('button', { name: 'Сохранить проект' }).click();
  expect(writes[0]).toMatchObject({ name: 'Edited during selection', sourceKeys: ['local-scope'], required: ['Java'] });
});
