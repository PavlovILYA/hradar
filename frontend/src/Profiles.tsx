import { useEffect, useRef, useState, type ReactNode } from 'react';
import { ownerMutation } from './ownerApi';
import { Onboarding, desktopLaunch } from './Onboarding';
import { freezeWorkspace, initializeWorkspace, workspaceGeneration, workspaceUrl } from './workspace';
import './profiles.css';
export interface TelegramIdentity { userId: number; name: string; username?: string | null; avatarAvailable?: boolean }
export interface TelegramProfile { id: string; identity?: TelegramIdentity | null; legacy: boolean; confirmed: boolean; loggedOut: boolean }
export interface ProfileStatus { generation: string; active: TelegramProfile; profiles: TelegramProfile[]; phase: 'AUTHORIZING' | 'VERIFYING' | 'CONFIRM_OWNER' | 'READY' | 'MISMATCH' | 'SWITCHING' | 'ERROR'; identity: TelegramIdentity | null; credentialsSource: 'environment' | 'saved' | 'missing'; legacyAvailable: boolean; error?: string }
export function Avatar({ name, src }: { name: string; src?: string }) {
  const [failed, setFailed] = useState(false);
  return <span className="account-avatar" aria-hidden="true">{src && !failed ? <img loading="lazy" src={workspaceUrl(src)} alt="" onError={() => setFailed(true)} /> : name.trim().split(/\s+/).slice(0, 2).map(s => s[0]).join('').toLocaleUpperCase() || '?'}</span>;
}
async function status(): Promise<ProfileStatus> {
  const response = await fetch('/api/profiles/status', { cache: 'no-store', signal: AbortSignal.timeout(10_000) });
  if (!response.ok) throw new Error('Не удалось проверить аккаунт. Откройте hradar и повторите подключение.');
  const value = await response.json() as ProfileStatus;
  if (!value.generation || !value.active || !Array.isArray(value.profiles)) throw new Error('Сервер вернул некорректное состояние аккаунтов.');
  return value;
}
const changeChannel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel('hradar-workspace');
export async function changeProfile(path: string, body: unknown = { confirm: true }) {
  freezeWorkspace(); changeChannel?.postMessage('changing');
  try { await ownerMutation(path, 'POST', body); }
  catch (error) {
    sessionStorage.setItem('hradar-profile-error', 'Результат изменения аккаунта не подтверждён. Подключение проверено заново. Проверьте выбранный аккаунт перед повтором.');
    location.replace('/?screen=accounts');
    throw error;
  }
  finally { window.dispatchEvent(new Event('hradar-workspace-reload')); }
}
export function ProfileGate({ children }: { children: (profile: ProfileStatus) => ReactNode }) {
  const [profile, setProfile] = useState<ProfileStatus | null>(null);
  const [changing, setChanging] = useState(false);
  const [transitionError, setTransitionError] = useState(() => { const message = sessionStorage.getItem('hradar-profile-error') || ''; sessionStorage.removeItem('hradar-profile-error'); return message; });
  const [error, setError] = useState('');
  const reloading = useRef(false);
  useEffect(() => {
    let stopped = false, pending = false;
    async function poll() {
      if (pending || reloading.current) return;
      pending = true;
      try {
        const value = await status();
        if (stopped) return;
        if (workspaceGeneration() && value.generation !== workspaceGeneration()) { reloading.current = true; location.replace('/'); return; }
        initializeWorkspace(value.generation); setProfile(value); setError('');
      } catch (e) { if (!stopped) setError((e as Error).message); }
      finally { pending = false; }
    }
    function changingAccount() { setChanging(true); setProfile(null); }
    async function reloadAccount() { try { await desktopLaunch(); } catch { /* Status retry remains explicit on recovery failure. */ } await poll(); }
    function otherTab() { freezeWorkspace(); void reloadAccount(); }
    window.addEventListener('hradar-workspace-changing', changingAccount);
    window.addEventListener('hradar-workspace-reload', reloadAccount);
    changeChannel?.addEventListener('message', otherTab);
    void poll(); const timer = setInterval(poll, 1500);
    return () => { stopped = true; clearInterval(timer); window.removeEventListener('hradar-workspace-changing', changingAccount); window.removeEventListener('hradar-workspace-reload', reloadAccount); changeChannel?.removeEventListener('message', otherTab); };
  }, []);
  if (changing) return <main className="profile-gate"><h1>Переключаем аккаунт</h1><p role="status">Останавливаем прежнее рабочее пространство и проверяем выбранный аккаунт…</p>{(transitionError || error) && <p role="alert">{transitionError || error}</p>}<button onClick={() => location.replace('/')}>Повторить подключение</button></main>;
  if (!profile || error) return <main className="profile-gate"><h1>Проверяем аккаунт</h1><p role={error ? 'alert' : 'status'}>{error || 'Подключаем локальное рабочее пространство…'}</p>{error && <button onClick={() => location.reload()}>Повторить подключение</button>}</main>;
  if (profile.phase === 'READY' && profile.identity && profile.active.confirmed) return <>{transitionError && <div className="error dismissible" role="alert"><span>{transitionError}</span><button aria-label="Закрыть ошибку аккаунта" onClick={() => setTransitionError('')}>×</button></div>}{children(profile)}</>;
  if (profile.phase === 'AUTHORIZING') return <><div className="profile-login-actions"><AccountMenu profile={profile} onAccounts={() => location.assign('/?screen=accounts')} /><AccountActions profile={profile} /></div><Onboarding onReady={() => { /* GetMe and owner confirmation, not TDLib Ready, open the workspace. */ }} /></>;
  return <main className="profile-gate"><h1>{profile.phase === 'CONFIRM_OWNER' ? 'Подтвердите владельца данных' : profile.phase === 'MISMATCH' ? 'Аккаунт не совпадает' : profile.phase === 'ERROR' ? 'Не удалось открыть аккаунт' : 'Проверяем владельца аккаунта'}</h1>
    {profile.identity && <p><Avatar name={profile.identity.name} src={profile.identity.avatarAvailable ? '/api/profiles/avatar' : undefined} /> {profile.identity.name}{profile.identity.username && ` · @${profile.identity.username}`}</p>}
    {profile.identity && <details><summary>Данные аккаунта</summary><p>ID Telegram: {profile.identity.userId}</p></details>}
    <p>{profile.error || (profile.phase === 'CONFIRM_OWNER' ? 'Старые локальные данные закрыты. Подтвердите, что они принадлежат этому Telegram-аккаунту.' : 'Рабочие данные доступны только после проверки владельца Telegram.')}</p>
    {profile.phase === 'CONFIRM_OWNER' && profile.identity && <button onClick={() => void ownerMutation('/api/profiles/confirm-owner', 'POST', { userId: profile.identity!.userId }).catch(e => setError(e.message))}>Подтверждаю: это мои данные</button>}
    {profile.phase === 'ERROR' && <button onClick={() => void ownerMutation('/api/profiles/verify', 'POST', { confirm: true }).catch(e => setError(e.message))}>Повторить проверку аккаунта</button>}
    <AccountActions profile={profile} />
  </main>;
}
export function AccountMenu({ profile, onAccounts }: { profile: ProfileStatus; onAccounts: () => void }) {
  const identity = profile.identity || profile.active.identity;
  return <button className="account-menu" onClick={onAccounts} aria-label="Аккаунты Telegram"><Avatar name={identity?.name || 'Telegram'} src={identity?.avatarAvailable ? '/api/profiles/avatar' : undefined} /><span>{identity?.name || 'Вход в Telegram'}{identity?.username && <small>@{identity.username}</small>}</span><span aria-hidden="true">⌄</span></button>;
}
export function AccountActions({ profile }: { profile: ProfileStatus }) {
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  async function act(path: string, prompt: string) { if (!window.confirm(prompt)) return; setBusy(true); try { await changeProfile(path); } catch(e) { setError((e as Error).message); } finally { setBusy(false); } }
  return <div className="account-actions"><p className="helper">У каждого аккаунта свои чаты, архив, проекты, документы и настройки бота. Выход сохраняет локальные данные. Удаление данных — отдельная операция с резервной копией; в этой версии оно недоступно.</p>
    {profile.profiles.map(item => <article className="account-record" key={item.id}><Avatar name={item.identity?.name || 'Telegram'} /><div><strong>{item.identity?.name || (item.legacy ? 'Прежний аккаунт' : 'Новый аккаунт')}</strong>{item.identity?.username && <p>@{item.identity.username}</p>}<small>{item.id === profile.active.id ? 'Текущий аккаунт' : 'Не активен'}{item.loggedOut ? ' · Требуется вход' : ''}</small><details><summary>Данные аккаунта</summary><p>ID Telegram: {item.identity?.userId ?? 'Ещё не проверен'}</p><small>ID профиля: {item.id}</small></details></div>
      {(item.id !== profile.active.id || item.loggedOut || profile.phase === 'ERROR') && <button disabled={busy} onClick={() => void act(`/api/profiles/${encodeURIComponent(item.id)}/switch`, 'Переключить аккаунт? Текущие операции остановятся, данные сохранятся.')}>{item.loggedOut ? 'Войти в аккаунт' : 'Переключить'}</button>}
      {item.id === profile.active.id && !item.loggedOut && (profile.identity || item.identity) && <button disabled={busy} onClick={() => void act(`/api/profiles/${encodeURIComponent(item.id)}/logout`, 'Выйти из Telegram? Мониторинг и бот остановятся. Локальные данные сохранятся; для продолжения потребуется вход в тот же аккаунт.')}>Выйти из Telegram</button>}</article>)}
    <button className="primary-button" disabled={busy} onClick={() => void act('/api/profiles/add', 'Добавить отдельный аккаунт? Текущие операции остановятся; его данные сохранятся.')}>Добавить аккаунт</button>
    {profile.legacyAvailable && <button disabled={busy} onClick={() => void act('/api/profiles/import-legacy', 'Импортировать прежнюю локальную сессию? Потребуется проверка и подтверждение владельца данных.')}>Импортировать прежнюю сессию</button>}{error && <p role="alert">{error}</p>}
  </div>;
}
export function Accounts({ profile }: { profile: ProfileStatus }) {
  const [apiId, setApiId] = useState(''); const [hash, setHash] = useState(''); const [error, setError] = useState('');
  return <section className="data-screen"><h2>Аккаунты Telegram</h2><AccountActions profile={profile} /><h2>API приложения Telegram</h2><p>Общие настройки для всех аккаунтов. Они не определяют, какой пользователь вошёл в Telegram.</p><p>{profile.credentialsSource === 'environment' ? 'Настройки заданы окружением и имеют приоритет над сохранёнными значениями. API hash скрыт.' : profile.credentialsSource === 'saved' ? 'Используются сохранённые настройки. API hash: ••••••••' : 'API приложения ещё не настроен.'}</p>
    {profile.credentialsSource !== 'environment' && <form onSubmit={e => { e.preventDefault(); if (!window.confirm('Сохранить общие API-настройки и перезапустить подключение?')) return; const nextHash = hash; setHash(''); void changeProfile('/api/profiles/credentials', { apiId: Number(apiId), apiHash: nextHash, confirm: true }).catch(e => setError(e.message)); }}><label>API ID<input required inputMode="numeric" value={apiId} onChange={e => setApiId(e.target.value)} /></label><label>Новый API hash<input required type="password" autoComplete="off" value={hash} onChange={e => setHash(e.target.value)} /></label><button>Сохранить API-настройки</button></form>}{error && <p role="alert">{error}</p>}<p className="helper">Бот подключается отдельно после входа в аккаунт в разделе Telegram Bot.</p></section>;
}
