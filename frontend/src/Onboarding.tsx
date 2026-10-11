import { useEffect, useState } from 'react';

type Status = { state: string; csrfToken: string; link?: string; notice?: string };
const labels: Record<string, string> = { WaitPhoneNumber: 'Номер телефона', WaitCode: 'Код Telegram', WaitPassword: 'Пароль 2FA', WaitEmailAddress: 'Email для входа', WaitEmailCode: 'Код из email' };
export function Onboarding({ onReady }: { onReady: () => void }) {
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
        if (s.state === 'Ready') onReady();
      } catch (e) { if (active) setError((e as Error).message); }
    }
    void poll(); const timer = setInterval(() => void poll(), 1500);
    return () => { active = false; clearInterval(timer); };
  }, [onReady]);
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
    {status?.state === 'Credentials' ? <form onSubmit={e => { e.preventDefault(); void submit('/api/onboarding/credentials', { apiId: Number(apiId), apiHash }); }}>
      <p>Создайте API credentials на <a href="https://my.telegram.org/apps" target="_blank" rel="noreferrer">my.telegram.org/apps</a>. Они хранятся в приватном файле вашего профиля macOS; не передавайте их другим людям.</p>
      <label>API ID<input required inputMode="numeric" value={apiId} onChange={e => setApiId(e.target.value)} /></label>
      <label>API hash<input required type="password" autoComplete="off" value={apiHash} onChange={e => setApiHash(e.target.value)} /></label>
      <button disabled={busy}>Сохранить и продолжить</button></form> : status && labels[status.state] ? <form onSubmit={e => { e.preventDefault(); void submit('/api/onboarding/login', { state: status.state, value }); }}>
      <label>{labels[status.state]}<input required autoComplete="off" type={status.state === 'WaitPassword' ? 'password' : status.state === 'WaitEmailAddress' ? 'email' : 'text'} value={value} onChange={e => setValue(e.target.value)} placeholder={status.state === 'WaitPhoneNumber' ? '+79991234567' : ''} maxLength={512} /></label>
      <button disabled={busy}>Продолжить</button></form> : <p role="status">{status?.notice ?? (status?.state === 'WaitOtherDeviceConfirmation' ? 'Подтвердите вход на другом устройстве Telegram.' : 'Ожидание Telegram…')}</p>}
    {status?.link?.startsWith('tg://') && <a href={status.link}>Открыть подтверждение в Telegram</a>}
    <button disabled={busy || !status} onClick={() => void submit('/api/desktop/shutdown')}>Завершить hradar</button>
  </main>;
}
export async function desktopLaunch() {
  const token = new URLSearchParams(location.hash.slice(1)).get('launch');
  if (!token) return;
  history.replaceState(null, '', location.pathname);
  const response = await fetch('/desktop/launch', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token }) });
  if (!response.ok) throw new Error('Запуск отклонён. Откройте приложение hradar повторно.');
}
export async function shutdownDesktop() {
  const response = await fetch('/api/onboarding', { cache: 'no-store' });
  const status = await response.json() as Status;
  await fetch('/api/desktop/shutdown', { method: 'POST', headers: { 'X-Hradar-CSRF': status.csrfToken } });
  document.getElementById('root')!.textContent = 'hradar завершён. Теперь можно закрыть вкладку.';
}
