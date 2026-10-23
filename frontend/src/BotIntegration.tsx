import { useEffect, useState } from 'react';
import { request } from './api';

interface BotStatus {
  state: 'disabled' | 'blocked' | 'starting' | 'running' | 'error'; notice: string | null;
  allowedUsers: number; registeredUsers: number; delivery: { pending: number; sent: number; failed: number };
  retryAt: number; lastPollAt: number;
}
interface BotIdentity { id: number; username: string; name: string }
interface User { id: number; name: string; username: string | null; role: string; connected: boolean }
interface Invitation { id: string; expiresAt: number }
interface Settings { status: BotStatus; configured: boolean; enabled: boolean; identity: BotIdentity | null; owner: User | null; users: User[]; invitations: Invitation[]; legacy: boolean }
interface InvitationLink extends Invitation { url: string }

const labels: Record<BotStatus['state'], string> = {
  disabled: 'Бот выключен', blocked: 'Бот требует настройки', starting: 'Подключаемся к Telegram',
  running: 'Бот работает', error: 'Боту требуется внимание',
};
export function BotIntegration() {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [csrf, setCsrf] = useState('');
  const [token, setToken] = useState('');
  const [checked, setChecked] = useState<BotIdentity | null>(null);
  const [username, setUsername] = useState('');
  const [found, setFound] = useState<User | null>(null);
  const [invite, setInvite] = useState<InvitationLink | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function load() {
    setSettings(await request<Settings>('/api/integrations/bot/settings'));
  }
  useEffect(() => {
    void load().catch(e => setError((e as Error).message));
    void request<{ csrfToken: string }>('/api/onboarding').then(v => setCsrf(v.csrfToken)).catch(() => {});
    const timer = window.setInterval(() => { void load().catch(() => {}); }, 10_000);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => {
    if (invite && settings && !settings.invitations.some(item => item.id === invite.id)) setInvite(null);
  }, [settings, invite]);
  async function act<T>(path: string, method: string, body?: unknown): Promise<T> {
    setBusy(true); setError('');
    try {
      const result = await request<T>(path, undefined, { method, headers: { 'Content-Type': 'application/json', 'X-Hradar-CSRF': csrf }, body: body === undefined ? undefined : JSON.stringify(body) });
      await load(); return result;
    } catch (e) { setError((e as Error).message); throw e; }
    finally { setBusy(false); }
  }
  const run = (task: () => Promise<unknown>) => { void task().catch(() => {}); };
  const status = settings?.status;
  const botUrl = settings?.identity?.username ? `https://t.me/${settings.identity.username}` : null;
  return <section className="mcp-integration" aria-label="Интеграции Bot API">
    <h2>Telegram Bot</h2>
    {error && <p className="error" role="alert">{error}</p>}
    <h3>Подключение бота</h3>
    <p>Создайте бота через <a href="https://t.me/BotFather" target="_blank" rel="noreferrer">@BotFather</a>, скопируйте выданный токен и вставьте его ниже.</p>
    <label htmlFor="bot-token">Токен бота</label>
    <input id="bot-token" type="password" value={token} onChange={e => { setToken(e.target.value); setChecked(null); }} autoComplete="off" placeholder={settings?.configured ? 'Новый токен для замены' : 'Вставьте токен'} />
    <div className="template-actions">
      <button className="secondary-button" disabled={busy || !csrf || !token.trim()} onClick={() => run(async () => setChecked(await act<BotIdentity>('/api/integrations/bot/check', 'POST', { token: token.trim() })))}>Проверить токен</button>
      <button className="primary-button" disabled={busy || !csrf || !checked} onClick={() => run(async () => { await act<Settings>('/api/integrations/bot/connection', 'PUT', { token: token.trim() }); setToken(''); setChecked(null); })}>{settings?.configured ? 'Заменить подключение' : 'Сохранить подключение'}</button>
    </div>
    {checked && <p>Найден бот: <strong>{checked.name || checked.username}</strong> · @{checked.username}</p>}
    {settings?.identity && <p>Подключён: <strong>{settings.identity.name || settings.identity.username}</strong> · @{settings.identity.username}. Токен сохранён локально и здесь не показывается.</p>}
    {settings?.legacy && <p className="helper">Бот запущен через прежние локальные настройки. Вставьте его токен здесь, чтобы перейти на управление через интерфейс.</p>}
    {settings?.configured && <button className="secondary-button" disabled={busy || !csrf} onClick={() => run(() => act<Settings>('/api/integrations/bot/enabled', 'PUT', { enabled: !settings.enabled }))}>{settings.enabled ? 'Выключить бота' : 'Включить бота'}</button>}
    {status && <p role="status"><strong>{labels[status.state]}</strong>{status.notice ? ` · ${status.notice}` : ''}</p>}
    {botUrl && settings?.enabled && <p><a href={botUrl} target="_blank" rel="noreferrer">Открыть бота</a> и отправьте ему /start в личном чате. Telegram не позволяет боту написать вам первым.</p>}

    <h3>Пользователи</h3>
    {!settings?.owner && <p className="helper">Чтобы основной аккаунт получил доступ автоматически, завершите вход в Telegram в hradar и включите бота.</p>}
    {settings?.owner && <p>Основной аккаунт: <strong>{settings.owner.name || `ID ${settings.owner.id}`}</strong> · ID {settings.owner.id}{settings.owner.username ? ` · @${settings.owner.username}` : ''} · {settings.owner.connected ? 'Открыл бота' : 'Ожидает /start'}</p>}
    {settings?.users.map(user => <div className="template-actions" key={user.id}><span>{user.name || 'Пользователь'} · ID {user.id}{user.username ? ` · @${user.username}` : ''} · {user.connected ? 'Открыл бота' : 'Ожидает /start'}</span><button className="secondary-button" disabled={busy || !csrf} onClick={() => run(() => act<Settings>(`/api/integrations/bot/users/${user.id}`, 'DELETE'))}>Отозвать доступ</button></div>)}
    {settings?.enabled && <>
      <p>Приглашение работает и для аккаунта без публичного имени. Оно действует 10 минут и подходит одному человеку.</p>
      <button className="secondary-button" disabled={busy || !csrf} onClick={() => run(async () => setInvite(await act<InvitationLink>('/api/integrations/bot/invitations', 'POST')))}>Пригласить пользователя</button>
      {invite && <p><a href={invite.url} target="_blank" rel="noreferrer">Открыть приглашение</a> · <button className="text-button" onClick={() => run(() => navigator.clipboard.writeText(invite.url))}>Скопировать ссылку</button> · до {new Date(invite.expiresAt).toLocaleTimeString('ru-RU')}</p>}
      {settings.invitations.map(item => <div className="template-actions" key={item.id}><span>Приглашение до {new Date(item.expiresAt).toLocaleString('ru-RU')}</span><button className="secondary-button" disabled={busy || !csrf} onClick={() => run(async () => { await act<Settings>(`/api/integrations/bot/invitations/${item.id}`, 'DELETE'); if (invite?.id === item.id) setInvite(null); })}>Отозвать приглашение</button></div>)}
      <form onSubmit={e => { e.preventDefault(); run(async () => setFound(await act<User>('/api/integrations/bot/users/lookup', 'POST', { username }))); }}><label htmlFor="bot-user">Добавить по @username</label><input id="bot-user" value={username} onChange={e => { setUsername(e.target.value); setFound(null); }} placeholder="@username" required /><button className="secondary-button" disabled={busy || !csrf}>Найти пользователя</button></form>
      {found && <p>Найден: {found.name || 'Пользователь'} · @{found.username || username.replace(/^@/, '')} · ID {found.id}. <button className="secondary-button" disabled={busy || !csrf} onClick={() => run(async () => { await act<Settings>('/api/integrations/bot/users', 'POST', { username, id: found.id }); setFound(null); setUsername(''); })}>Подтвердить доступ</button></p>}
      <p className="helper">Если поиск не нашёл аккаунт, отправьте приглашение. Для получения сообщений человек должен открыть бота и отправить /start.</p>
    </>}

    <h3>Работа бота</h3>
    {status && <><dl><dt>Подключились к боту</dt><dd>{status.registeredUsers}</dd><dt>Ожидают отправки</dt><dd>{status.delivery.pending}</dd><dt>Отправлено</dt><dd>{status.delivery.sent}</dd><dt>Не доставлено</dt><dd>{status.delivery.failed}</dd></dl>
      {status.retryAt > Date.now() && <p>Повторим после {new Date(status.retryAt).toLocaleString('ru-RU')}</p>}
      {status.lastPollAt > 0 && <p className="helper">Последняя проверка Telegram: {new Date(status.lastPollAt).toLocaleString('ru-RU')}</p>}</>}
    <button className="secondary-button" disabled={busy} onClick={() => run(load)}>Обновить состояние бота</button>
    <p className="helper">Бот работает, пока hradar запущен. Команды: /help, /find, /watch, /saved, /matches, /status, /subscriptions, /pause, /resume.</p>
  </section>;
}
