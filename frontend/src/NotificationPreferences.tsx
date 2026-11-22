import { useEffect, useState } from 'react';
import { request } from './api';
import { ownerMutation } from './ownerApi';
interface Preferences { enabled: boolean; hidePreview: boolean }
export function NotificationPreferences({ compact = false }: { compact?: boolean }) {
  const [prefs, setPrefs] = useState<Preferences | null>(null);
  const [permission, setPermission] = useState(() => 'Notification' in window ? Notification.permission : 'unsupported');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const c = new AbortController();
    request<Preferences>('/api/workspace/desktop', c.signal).then(value => { if (!c.signal.aborted) { setPrefs(value); setError(''); } }).catch(e => { if (!c.signal.aborted) setError((e as Error).message); });
    const refresh = () => { setRevision(n => n + 1); setPermission('Notification' in window ? Notification.permission : 'unsupported'); };
    window.addEventListener('hradar:desktop-preferences', refresh); window.addEventListener('focus', refresh);
    return () => { c.abort(); window.removeEventListener('hradar:desktop-preferences', refresh); window.removeEventListener('focus', refresh); };
  }, [revision]);
  async function save(value: Preferences) {
    const previous = prefs; setPrefs(value); setBusy(true); setError('');
    try { setPrefs(await ownerMutation('/api/workspace/desktop', 'PUT', value)); window.dispatchEvent(new Event('hradar:desktop-preferences')); }
    catch(e) { setPrefs(previous); setError((e as Error).message); } finally { setBusy(false); }
  }
  return <section className="notification-preferences">{compact ? <h4>Доставка в браузере</h4> : <h2>Уведомления о новых совпадениях</h2>}
    <p className="helper">Совпадения сохраняются, пока работает hradar. Всплывающие уведомления требуют открытой вкладки и разрешения браузера. Отключение всплывающих уведомлений не останавливает отслеживание.</p>
    {prefs && <><label><input type="checkbox" disabled={busy} checked={prefs.enabled} onChange={e => void save({ ...prefs, enabled: e.target.checked })} />Включить всплывающие уведомления для hradar</label><label><input type="checkbox" disabled={busy} checked={prefs.hidePreview} onChange={e => void save({ ...prefs, hidePreview: e.target.checked })} />Скрывать текст, название чата и поиска</label></>}
    <p className="helper">Разрешение браузера: {permission === 'granted' ? 'получено' : permission === 'denied' ? 'запрещено — измените разрешение сайта в настройках браузера' : permission === 'unsupported' ? 'не поддерживается' : 'ещё не запрошено'}. Настройки браузера и режим «Фокусирование» macOS могут скрывать уведомления.</p><button type="button" className="secondary-button" disabled={permission !== 'default'} onClick={async () => { if ('Notification' in window) { try { setPermission(await Notification.requestPermission()); window.dispatchEvent(new Event('hradar:desktop-preferences')); } catch { setError('Браузер не смог запросить разрешение. Проверьте настройки уведомлений сайта.'); } } }}>{permission === 'granted' ? 'Разрешение браузера получено' : permission === 'denied' ? 'Уведомления запрещены в настройках браузера' : permission === 'unsupported' ? 'Браузер не поддерживает уведомления' : 'Разрешить уведомления браузера'}</button>
    <details><summary>Уведомления при закрытом браузере</summary><p className="helper">Нативная доставка через Notification Center macOS при закрытом браузере пока не реализована.</p></details>
    {error && <p className="error" role="alert">{error} <button type="button" className="text-button" onClick={() => setError('')}>Скрыть</button><button type="button" className="text-button" onClick={() => setRevision(n => n + 1)}>Повторить загрузку настроек</button></p>}
  </section>;
}
