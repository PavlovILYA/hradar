import { test, expect } from '@playwright/test';
import { openSection } from './navigation-helpers';

test('updates popover closes outside, on Escape and navigation', async ({ page }) => {
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/events') return route.abort();
    return route.fulfill({ json: path === '/api/telegram/status' ? { authorized: true, authorizationState: 'Ready', connectionState: 'Ready' } : path === '/api/notifications' ? { items: [], unread: 0, cursor: null } : path === '/api/sources' || path === '/api/source-catalog' ? { items: [] } : path === '/api/onboarding' ? { csrfToken: 'synthetic' } : path === '/api/workspace/desktop' ? { enabled: false } : path === '/api/documents' ? { items: [], total: 0, bytes: 0 } : [] });
  });
  await page.goto('/?screen=search');
  const toggle = page.getByLabel('Обновления переподключаются', { exact: true });
  await toggle.click();
  const note = page.getByRole('note');
  await expect(note).toBeVisible();
  await page.getByRole('heading', { level: 1 }).click();
  await expect(note).toBeHidden();
  await toggle.click();
  await page.keyboard.press('Escape');
  await expect(note).toBeHidden();
  await expect(toggle).toBeFocused();
  await toggle.click();
  await openSection(page, 'Документы');
  await expect(note).toBeHidden();
});

const source = { key: 'synthetic-files', chatId: -1001, title: 'Synthetic files', scope: { kind: 'CHAT' }, type: 'SUPERGROUP', access: 'AVAILABLE' };
const message = { id: 42, channelId: -1001, date: 1700000000, title: source.title, sourceKey: source.key, text: 'Synthetic document publication', link: 'https://t.me/synthetic_group/42', attachments: [{ kind: 'document', fileName: 'synthetic.pdf', mimeType: 'application/pdf', size: 4096 }] };
const initial = { id: 'doc-1', message, fileName: 'synthetic.pdf', mimeType: 'application/pdf', size: 4096, state: 'NOT_DOWNLOADED', downloadedBytes: 0, hasFile: false, hasText: false };
async function documentsFixture(page: import('@playwright/test').Page) {
  let item = { ...initial };
  const writes: string[] = [], searches: string[] = [];
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url()), path = url.pathname, method = route.request().method();
    const reply = (json: unknown) => route.fulfill({ json });
    if (method !== 'GET') writes.push(path);
    if (path === '/api/events') return route.abort();
    if (path === '/api/telegram/status') return reply({ authorized: true, authorizationState: 'Ready', connectionState: 'Ready' });
    if (path === '/api/onboarding') return reply({ csrfToken: 'synthetic' });
    if (path === '/api/sources' || path === '/api/source-catalog') return reply({ items: [source] });
    if (path === '/api/workspace/desktop') return reply({ enabled: false });
    if (path === '/api/notifications') return reply({ items: [{ id: 'n1', templateId: 't1', templateName: 'Synthetic', message, read: true, sequence: 1 }], unread: 0, cursor: null });
    if (path === '/api/sources/synthetic-files/messages/42') return reply(message);
    if (path === '/api/documents') return reply({ items: [item], total: 1, bytes: item.hasFile ? 4096 : 0 });
    if (path === '/api/documents/search') { searches.push(url.searchParams.get('q')!); return reply({ items: [{ document: item, snippet: 'Synthetic Kotlin document text' }], total: 1 }); }
    if (path === '/api/documents/discover') { expect(route.request().postDataJSON()).toMatchObject({ sourceKey: source.key, limit: 50 }); return reply({ items: [item], nextCursor: 20, notice: 'Проверена одна синтетическая страница.' }); }
    if (path === '/api/documents/register') return reply([item]);
    if (path === '/api/documents/doc-1/download') { item = { ...item, state: 'READY', downloadedBytes: 4096, hasFile: true, hasText: true }; return reply(item); }
    if (path === '/api/documents/doc-1/text') return reply({ text: 'Synthetic Kotlin document text\n'.repeat(300), truncated: true });
    if (path === '/api/projects') return reply({ items: [], total: 0 });
    return reply([]);
  });
  return { writes, searches };
}

test('document discovery registers metadata and requires a separate download; local search retains source', async ({ page }) => {
  const data = await documentsFixture(page);
  await page.goto('/?screen=documents');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Документы');
  await page.getByText('Найти документы в выбранном чате', { exact: true }).click();
  await page.getByRole('checkbox', { name: source.title, exact: true }).check();
  await page.getByRole('button', { name: 'Проверить страницу сообщений', exact: true }).click();
  await expect(page.getByText('Проверена одна синтетическая страница.')).toBeVisible();
  expect(data.writes).toEqual(['/api/documents/discover']);
  await expect(page.getByRole('button', { name: 'Проверить следующую страницу' })).toBeVisible();
  await page.locator('.document-discovery').getByRole('button', { name: 'Скачать документ', exact: true }).click();
  await expect(page.locator('.document-discovery').getByRole('link', { name: 'Открыть файл' })).toHaveAttribute('href', '/api/documents/doc-1/file');
  await page.getByLabel('Поиск в локальных документах').fill('Kotlin');
  await page.getByRole('button', { name: 'Искать в документах', exact: true }).click();
  expect(data.searches).toEqual(['Kotlin']);
  await expect(page.locator('.document-snippet')).toContainText('Kotlin');
  await page.locator('.document-card').last().getByRole('button', { name: 'Читать текст', exact: true }).click();
  const text = page.getByRole('dialog', { name: 'Текст документа: synthetic.pdf' });
  await expect(text).toContainText(source.title);
  await expect(text).toContainText('Показана ограниченная часть');
  await expect(text.locator('pre')).toContainText('Kotlin');
  await page.keyboard.press('Escape');
  await expect(text).toBeHidden();
  await page.locator('.document-card').last().getByRole('button', { name: 'Открыть публикацию', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Детали публикации' })).toContainText(message.text);
  expect(data.writes).not.toContain('/api/search');
});

test('publication attachment prepares metadata before explicit download and text opens separately', async ({ page }) => {
  const data = await documentsFixture(page);
  await page.goto('/?screen=notifications');
  await page.getByRole('button', { name: 'Открыть публикацию', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Детали публикации' });
  await dialog.getByRole('button', { name: 'Подготовить документы к скачиванию' }).click();
  await expect(dialog.getByRole('button', { name: 'Скачать документ', exact: true })).toBeVisible();
  expect(data.writes).toEqual(['/api/documents/register']);
  await dialog.getByRole('button', { name: 'Скачать документ', exact: true }).click();
  await dialog.getByRole('button', { name: 'Читать текст', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Текст документа: synthetic.pdf' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Читать текст', exact: true })).toBeFocused();
});

test('document progress, cancellation, rate deadline and unavailable states stay explicit', async ({ page }) => {
  const data = await documentsFixture(page);
  const records = [
    { ...initial, id: 'progress', state: 'DOWNLOADING', downloadedBytes: 2048 },
    { ...initial, id: 'limited', state: 'ERROR', error: 'Telegram ограничил загрузку', retryAt: Math.floor(Date.now() / 1000) + 3600 },
    { ...initial, id: 'missing', state: 'UNAVAILABLE', error: 'Файл недоступен' },
    { ...initial, id: 'scanned', state: 'NEEDS_OCR', hasFile: true },
  ];
  await page.route('**/api/documents?**', route => route.fulfill({ json: { items: records, total: records.length, bytes: 4096 } }));
  await page.route('**/api/documents/progress', route => route.fulfill({ json: records[0] }));
  await page.route('**/api/documents/progress/cancel', route => route.fulfill({ json: { ...records[0], state: 'ERROR', error: 'Загрузка отменена' } }));
  await page.goto('/?screen=documents');
  await expect(page.getByRole('progressbar')).toHaveAttribute('value', '2048');
  const cards = page.locator('.document-card');
  await expect(cards.nth(1).getByRole('button', { name: 'Повторить загрузку' })).toBeDisabled();
  await expect(cards.nth(2)).toContainText('Файл недоступен');
  await expect(cards.nth(2).getByRole('button', { name: 'Скачать документ' })).toHaveCount(0);
  await expect(cards.nth(3)).toContainText('Нужен OCR');
  await expect(cards.nth(3).getByRole('button', { name: 'Читать текст' })).toHaveCount(0);
  await cards.first().getByRole('button', { name: 'Отменить загрузку' }).click();
  await expect(cards.first()).toContainText('Загрузка отменена');
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: '/tmp/hradar-documents-390-light.png', fullPage: true });
  await page.getByRole('button', { name: 'Открыть навигацию', exact: true }).click();
  await page.getByRole('button', { name: 'Тёмная тема', exact: true }).click();
  await page.keyboard.press('Escape');
  await page.screenshot({ path: '/tmp/hradar-documents-390-dark.png', fullPage: true });
  expect(data.writes.some(path => path === '/api/search')).toBe(false);
});
