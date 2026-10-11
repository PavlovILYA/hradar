import { useEffect, useState, useRef } from 'react';

type Status = { state: string; csrfToken: string; link?: string; notice?: string };
const waiting: Record<string, string> = { Initializing: 'Подключение TDLib…', WaitTdlibParameters: 'Настройка TDLib…', Closing: 'Telegram завершает работу…', Closed: 'Сессия закрыта. Завершите hradar и откройте приложение снова.', LoggingOut: 'Выход из Telegram…', WaitOtherDeviceConfirmation: 'Подтвердите вход на другом устройстве Telegram.' };
const labels: Record<string, string> = { WaitPhoneNumber: 'Номер телефона', WaitCode: 'Код Telegram', WaitPassword: 'Пароль 2FA', WaitEmailAddress: 'Email для входа', WaitEmailCode: 'Код из email' };
export function Onboarding({ onReady }: { onReady: () => void }) {
  const ready = useRef(onReady); ready.current = onReady;
  const [status, setStatus] = useState<Status | null>(null);
  const [value, setValue] = useState('');
  const [apiId, setApiId] = useState('');
  const [apiHash, setApiHash] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let active = true;
    async function poll() {
      try {
        const r = await fetch('/api/onboarding', { cache: 'no-store' });
        if (!r.ok) throw new Error('Откройте hradar через установленное приложение.');
        const s = await r.json() as Status;
        if (!active) return;
        setStatus(old => { if (old?.state !== s.state) setValue(''); return s; });
        if (s.state === 'Ready') ready.current();
      } catch (e) { if (active) setError((e as Error).message); }
    }
    void poll(); const timer = setInterval(() => void poll(), 1500);
    return () => { active = false; clearInterval(timer); };
  }, []);
  async function submit(path: string, body?: unknown) {
    setBusy(true); setError('');
    try {
      const r = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Hradar-CSRF': status?.csrfToken ?? '' }, body: JSON.stringify(body ?? {}) });
      setValue(''); setApiHash('');
      if (!r.ok) { const data = await r.json(); throw new Error(data.error ?? 'Не удалось выполнить действие.'); }
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  return <main className="onboarding"><h1>Настройка hradar</h1><p>Вход выполняется локально через официальный TDLib. Коды и пароль не сохраняются.</p>
    {error && <p role="alert">{error}</p>}
    {status?.notice && <p>{status.notice}</p>}
    {status && ['Credentials', 'ParametersFailed'].includes(status.state) ? <form onSubmit={e => { e.preventDefault(); void submit('/api/onboarding/credentials', { apiId: Number(apiId), apiHash }); }}>
      <p>Создайте API credentials на <a href="https://my.telegram.org/apps" target="_blank" rel="noreferrer">my.telegram.org/apps</a>. Они хранятся в приватном файле вашего профиля macOS; не передавайте их другим людям.</p>
      <label>API ID<input required inputMode="numeric" value={apiId} onChange={e => setApiId(e.target.value)} /></label>
      <label>API hash<input required type="password" autoComplete="off" value={apiHash} onChange={e => setApiHash(e.target.value)} /></label>
      <button disabled={busy}>Сохранить и продолжить</button></form> : status && labels[status.state] ? <form onSubmit={e => { e.preventDefault(); void submit('/api/onboarding/login', { state: status.state, value }); }}>
      <label>{labels[status.state]}<input required autoComplete="off" type={status.state === 'WaitPassword' ? 'password' : status.state === 'WaitEmailAddress' ? 'email' : 'text'} value={value} onChange={e => setValue(e.target.value)} placeholder={status.state === 'WaitPhoneNumber' ? '+79991234567' : ''} maxLength={512} /></label>
      <button disabled={busy}>Продолжить</button></form> : <p role="status">{status?.notice ?? (waiting[status?.state ?? ''] ?? 'Ожидание Telegram…')}</p>}
    {status?.link?.startsWith('tg://') && <a href={status.link}>Открыть подтверждение в Telegram</a>}
    {isDesktop() && <button disabled={busy || !status} onClick={() => void submit('/api/desktop/shutdown')}>Завершить hradar</button>}
  </main>;
}
export async function desktopLaunch() {
  const token = new URLSearchParams(location.hash.slice(1)).get('launch');
  if (!token) return;
  history.replaceState(null, '', location.pathname);
  const response = await fetch('/desktop/launch', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token }) });
  if (!response.ok) throw new Error('Запуск отклонён. Откройте приложение hradar повторно.');
  sessionStorage.setItem('hradar-desktop', 'true');
}
export function isDesktop() { return sessionStorage.getItem('hradar-desktop') === 'true'; }
export async function shutdownDesktop() {
  const response = await fetch('/api/onboarding', { cache: 'no-store' });
  const status = await response.json() as Status;
  const stopped = await fetch('/api/desktop/shutdown', { method: 'POST', headers: { 'X-Hradar-CSRF': status.csrfToken } });
  if (!stopped.ok) throw new Error('Не удалось завершить hradar.');
  document.getElementById('root')!.textContent = 'hradar завершён. Теперь можно закрыть вкладку.';
}
