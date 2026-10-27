// Account-free packaged launcher; one browser context/tab survives backend restart.
import { chromium, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { createHmac, randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';

const [launcher, home, port = '1709'] = process.argv.slice(2);
const origin = `http://hradar.localhost:${port}`;
const direct = `http://127.0.0.1:${port}`;
const browser = await chromium.launch({ headless: true, ...(process.env.HRADAR_BROWSER_CHANNEL ? { channel: process.env.HRADAR_BROWSER_CHANNEL } : {}) });
let child;
const context = await browser.newContext();
const page = await context.newPage();
const errors = [];
page.on('pageerror', e => errors.push(e.message));
const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('TG_')));
env.JAVA_TOOL_OPTIONS = `-Duser.home=${home} -Dhradar.smoke=true -Dhradar.smokePort=${port}`;
function proof(secret, value) { return createHmac('sha256', secret).update(value).digest('base64url'); }
async function start(previous) {
  child = spawn(launcher, [], { env, stdio: 'ignore' });
  for (let attempt = 0; attempt < 200; attempt++) {
    if (child.exitCode !== null) throw new Error('Packaged restart exited early');
    try {
      const marker = JSON.parse(await readFile(`${home}/.hradar/desktop-instance.json`, 'utf8'));
      if (marker.token !== previous && (await fetch(`${direct}/health`)).ok) return marker.token;
    } catch { /* Only synthetic home; marker is published after bind. */ }
    await delay(100);
  }
  throw new Error('Packaged restart timed out');
}
async function stop(csrf) {
  const stopped = new Promise(resolve => child.once('exit', resolve));
  expect(await page.evaluate(async csrf => (await fetch('/api/desktop/shutdown', {method:'POST',headers:{'X-Hradar-CSRF':csrf}})).status, csrf)).toBe(200);
  await Promise.race([stopped, delay(20000).then(() => { throw new Error('Packaged shutdown timed out'); })]);
  expect(child.exitCode).toBe(0);
}
try {
  const first = await start();
  const nonce = randomBytes(32).toString('base64url');
  const response = await fetch(`${direct}/desktop/reopen`, {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({nonce,proof:proof(first,`request:${nonce}`)})});
  const code = await response.json();
  expect(code.proof).toBe(proof(first,`response:${nonce}:${code.launch}`));
  await page.goto(`${origin}/#launch=${code.launch}`);
  await expect(page.getByRole('heading',{name:'Подключите Telegram'})).toBeVisible();
  expect(page.url()).toBe(`${origin}/`);
  const old = await page.evaluate(async () => (await fetch('/api/onboarding')).json());
  expect((await context.cookies()).find(c => c.name === 'hradar-desktop')).toMatchObject({httpOnly:true,sameSite:'Strict',domain:'hradar.localhost'});
  // A bare context cannot acquire a capability from the root URL or recovery route.
  const stranger = await browser.newContext();
  const bare = await stranger.newPage();
  await bare.goto(`${origin}/`);
  await expect(bare.getByRole('button',{name:'Повторить подключение'})).toBeVisible();
  expect(await bare.evaluate(async () => (await fetch('/api/onboarding')).status)).toBe(403);
  await stranger.close();
  await stop(old.csrfToken);
  // During downtime a foreign owner can bind the exact HTTP origin. It sees old
  // cookies, but must gain no authority in any future hradar process.
  let stolenCookie = '';
  const foreign = createServer((request, response) => {
    stolenCookie = request.headers.cookie ?? '';
    response.writeHead(200, {'Content-Type':'text/plain'}); response.end('Foreign listener fixture');
  });
  await new Promise(resolve => foreign.listen(Number(port), '127.0.0.1', resolve));
  try {
    await page.reload();
    expect(stolenCookie).toContain(`hradar-desktop=${first}`);
  } finally { await new Promise(resolve => foreign.close(resolve)); }
  const second = await start(first);
  expect((await fetch(`${direct}/desktop/recover`, {method:'POST',headers:{Origin:direct,'X-Hradar-Recovery':'1',Cookie:stolenCookie}})).status).toBe(403);
  expect((await fetch(`${direct}/api/onboarding`, {headers:{Cookie:stolenCookie}})).status).toBe(403);
  expect(second).not.toBe(first);
  // Old cookie is refused. The owner's native launch opens a fresh handshake tab;
  // its process cookie is shared with the unchanged saved tab (no URL copying).
  await page.reload();
  await expect(page.getByRole('button',{name:'Повторить подключение'})).toBeVisible();
  expect(await page.evaluate(async () => (await fetch('/api/onboarding')).status)).toBe(403);
  const secondNonce = randomBytes(32).toString('base64url');
  const secondResponse = await fetch(`${direct}/desktop/reopen`, {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({nonce:secondNonce,proof:proof(second,`request:${secondNonce}`)})});
  const secondCode = await secondResponse.json();
  expect(secondCode.proof).toBe(proof(second,`response:${secondNonce}:${secondCode.launch}`));
  const nativeTab = await context.newPage();
  await nativeTab.goto(`${origin}/#launch=${secondCode.launch}`);
  await expect(nativeTab.getByRole('heading',{name:'Подключите Telegram'})).toBeVisible();
  await nativeTab.close();
  await page.getByRole('button',{name:'Повторить подключение'}).click();
  await expect(page.getByRole('heading',{name:'Подключите Telegram'})).toBeVisible();
  expect(page.url()).toBe(`${origin}/`);
  const current = await page.evaluate(async () => (await fetch('/api/onboarding')).json());
  expect(current.csrfToken).not.toBe(old.csrfToken);
  expect(await page.evaluate(async csrf => (await fetch('/api/desktop/shutdown',{method:'POST',headers:{'X-Hradar-CSRF':csrf}})).status,old.csrfToken)).toBe(403);
  expect(await page.evaluate(async () => (await fetch('/mcp',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).status)).toBe(403);
  expect(errors).toEqual([]);
  await stop(current.csrfToken);
  console.log(`Packaged saved-tab restart/recovery and CSRF isolation passed at ${origin}; browser ${browser.version()}`);
} finally {
  if (child && child.exitCode === null) child.kill('SIGTERM');
  await browser.close();
}
