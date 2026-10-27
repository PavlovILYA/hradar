import { chromium, expect } from '@playwright/test';
import { createHmac, randomBytes } from 'node:crypto';
const port = process.env.HRADAR_SMOKE_PORT ?? '1709';
const origin = `http://hradar.localhost:${port}`;
async function launchUrl() {
  const nonce = randomBytes(32).toString('base64url');
  const proof = createHmac('sha256', process.env.HRADAR_SMOKE_TOKEN).update(`request:${nonce}`).digest('base64url');
  const r = await fetch(`http://127.0.0.1:${port}/desktop/reopen`, { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({nonce, proof}) });
  if (!r.ok) throw new Error('Packaged identity request failed');
  const value = await r.json();
  const expected = createHmac('sha256', process.env.HRADAR_SMOKE_TOKEN).update(`response:${nonce}:${value.launch}`).digest('base64url');
  if (value.proof !== expected) throw new Error('Packaged identity mismatch');
  return `${origin}/#launch=${value.launch}`;
}
const browser = await chromium.launch({ headless: true });
try {
  let page = await browser.newPage();
  const failures = [];
  page.on('pageerror', e => failures.push(e.message));
  await page.goto(await launchUrl());
  await page.getByRole('heading', { name: 'Подключите Telegram' }).waitFor();
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
  await page.goto(await launchUrl());
  // Hydrate the real Dashboard before leaving it: cached hidden views may contain
  // the same project title as the project list, just as in the production UI.
  await expect(page.getByRole('region', { name: 'Активные проекты', exact: true }).getByRole('button', { name: 'Installer CRM sentinel', exact: true })).toBeVisible();
  try {
    await page.getByRole('button', { name: 'Проекты', exact: true }).click();
  } catch (error) {
    console.error('Synthetic packaged page:', await page.locator('body').innerText());
    console.error('Page errors:', failures);
    throw error;
  }
  const projects = page.getByRole('region', { name: 'Проекты', exact: true });
  await expect(projects).toBeVisible();
  await projects.getByRole('button').filter({ has: page.getByText('Installer CRM sentinel', { exact: true }) }).click();
  await page.getByText('Synthetic Java publication', { exact: true }).waitFor();
  await expect(page.getByLabel('Заметки')).toHaveValue('Synthetic preserved note');
  await expect(page.getByLabel('Стадия', { exact: true })).toHaveValue('CONTACTED');
  if (failures.length) throw new Error(failures.join('\n'));
} finally { await browser.close(); }
