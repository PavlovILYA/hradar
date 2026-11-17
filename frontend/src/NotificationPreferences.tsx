import { useEffect, useState } from 'react';
import { request } from './api';
import { ownerMutation } from './ownerApi';
interface Preferences { enabled: boolean; hidePreview: boolean }
export function NotificationPreferences() {
  const [prefs, setPrefs] = useState<Preferences | null>(null);
  const [permission, setPermission] = useState(() => 'Notification' in window ? Notification.permission : 'unsupported');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => { const c = new AbortController(); request<Preferences>('/api/workspace/desktop', c.signal).then(setPrefs).catch(e => { if (!c.signal.aborted) setError((e as Error).message); }); return () => c.abort(); }, []);
  async function save(value: Preferences) {
    const previous = prefs; setPrefs(value); setBusy(true); setError('');
    try { setPrefs(await ownerMutation('/api/workspace/desktop', 'PUT', value)); }
    catch(e) { setPrefs(previous); setError((e as Error).message); } finally { setBusy(false); }
  }
  return <section className="notification-preferences"><h2>Уведомления о новых совпадениях</h2>
    <p className="helper">Совпадения сохраняются, пока запущен backend hradar. Всплывающие уведомления сейчас требуют открытой вкладки и разрешения браузера. Доставка через Notification Center macOS при закрытом браузере пока не реализована.</p>
    {prefs && <><label><input type="checkbox" disabled={busy} checked={prefs.enabled} onChange={e => void save({ ...prefs, enabled: e.target.checked })} />Включить всплывающие уведомления</label><label><input type="checkbox" disabled={busy} checked={prefs.hidePreview} onChange={e => void save({ ...prefs, hidePreview: e.target.checked })} />Скрывать текст, название чата и шаблона</label></>}
    <button className="secondary-button" disabled={permission !== 'default'} onClick={async () => { if ('Notification' in window) setPermission(await Notification.requestPermission()); }}>{permission === 'granted' ? 'Разрешение браузера получено' : permission === 'denied' ? 'Уведомления запрещены в настройках браузера' : permission === 'unsupported' ? 'Браузер не поддерживает уведомления' : 'Разрешить уведомления браузера'}</button>
    {error && <p className="error" role="alert">{error}</p>}
  </section>;
}
