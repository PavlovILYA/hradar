import { chromium, expect } from '@playwright/test';
const browser = await chromium.launch({ headless: true });
try {
  let page = await browser.newPage();
  const failures = [];
  page.on('pageerror', e => failures.push(e.message));
  await page.goto(`http://127.0.0.1:${process.env.HRADAR_SMOKE_PORT ?? '8080'}/#launch=${process.env.HRADAR_SMOKE_TOKEN}`);
  await page.getByRole('heading', { name: 'Настройка hradar' }).waitFor();
  await page.getByLabel('API ID', { exact: true }).waitFor();
  await page.getByLabel('API hash', { exact: true }).waitFor();
  if (new URL(page.url()).hash) throw new Error('Launch token was not removed');
  if (await page.locator('script[src*="/assets/"]').count() === 0) throw new Error('Production assets missing');
  // Use a fresh context: register the fake status before any application request.
  // The onboarding context exercised real credential state; CRM REST stays real.
  await page.close();
  page = await browser.newPage();
  page.on('pageerror', e => failures.push(e.message));
  // The live SSE also carries real (unauthorized) Telegram status. Keep that
  // channel out of the fake-auth CRM scenario; all CRM HTTP requests stay real.
  await page.route('**/api/events', route => route.abort());
  await page.route('**/api/telegram/status', route => route.fulfill({ json: { authorized: true, authorizationState: 'Ready', connectionState: 'Ready' } }));
  await page.goto(`http://127.0.0.1:${process.env.HRADAR_SMOKE_PORT ?? '8080'}/#launch=${process.env.HRADAR_SMOKE_TOKEN}`);
  try {
    await page.getByRole('button', { name: 'Проекты', exact: true }).click();
  } catch (error) {
    console.error('Synthetic packaged page:', await page.locator('body').innerText());
    console.error('Page errors:', failures);
    throw error;
  }
  await page.getByText('Installer CRM sentinel', { exact: true }).click();
  await page.getByText('Synthetic Java publication', { exact: true }).waitFor();
  await expect(page.getByLabel('Заметки')).toHaveValue('Synthetic preserved note');
  await expect(page.getByLabel('Стадия', { exact: true })).toHaveValue('CONTACTED');
  if (failures.length) throw new Error(failures.join('\n'));
} finally { await browser.close(); }
