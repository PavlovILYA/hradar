import { useEffect, useState } from 'react';
import { ChatPicker } from './ChatPicker';
import { NotificationPreferences } from './NotificationPreferences';
import { ownerMutation } from './ownerApi';
import { request, mutate } from './api';
import type { MonitoredChannel, SearchTemplate } from './api';
import type { SourcePage, TelegramSource } from './SourceCatalog';
import './searchWorkflow.css';

const empty: SearchTemplate = { id: '', name: '', required: [], any: [], excluded: [], telegramQuery: '', sourceMode: 'chats', sourceKeys: [], notificationsEnabled: false, sources: [], active: true, subscribed: false };
const split = (value: string) => value.split('\n').map(s => s.trim()).filter(Boolean);
interface MonitoringState { templateId: string; sourceKey: string; enabled: boolean; status: string; error: string | null; retryAt: number }
export function Templates({ templates, channels, refresh, onRun, onError }: {
  templates: SearchTemplate[]; channels: MonitoredChannel[]; refresh: () => Promise<void>;
  onRun: (template: SearchTemplate) => void; onError: (message: string) => void;
}) {
  const [draft, setDraft] = useState<SearchTemplate | null>(null);
  const [busy, setBusy] = useState(false);
  const [picking, setPicking] = useState(false);
  const [deleting, setDeleting] = useState<SearchTemplate | null>(null);
  const [states, setStates] = useState<MonitoringState[]>([]);
  const [sources, setSources] = useState<TelegramSource[]>([]);
  const [stateError, setStateError] = useState('');
  async function loadStates() { setStates(await request('/api/workspace/monitoring')); }
  useEffect(() => { const c = new AbortController(); void request<SourcePage>('/api/sources', c.signal).then(p => setSources(p.items)).catch(e => { if (!c.signal.aborted) setStateError((e as Error).message); }); return () => c.abort(); }, []);
  useEffect(() => { void loadStates().catch(e => setStateError((e as Error).message)); }, [templates]);
  async function reload() { await refresh(); await loadStates(); setStateError(''); }
  const hasLocal = (id: string) => states.some(s => s.templateId === id);
  const live = (t: SearchTemplate) => t.active && (t.sourceMode === 'chats' ? t.subscribed : t.subscribed || states.some(s => s.templateId === t.id && s.enabled));
  const statuses: Record<string, string> = { active: 'Отслеживание включено', paused: 'На паузе', template_paused: 'Поиск на паузе', recovering: 'Восстановление пропущенных сообщений', rate_limited: 'Лимит Telegram', unavailable: 'Нет доступа к чату', access_denied: 'Нет доступа к чату', retrying: 'Повторная попытка', error: 'Ошибка отслеживания' };
  function save(t: SearchTemplate) { return ownerMutation(t.id ? `/api/workspace/templates/${t.id}` : '/api/workspace/templates', t.id ? 'PUT' : 'POST', t); }
  async function action(task: () => Promise<unknown>) {
    setBusy(true);
    try { await task(); await reload(); }
    catch (e) { onError((e as Error).message); }
    finally { setBusy(false); }
  }
  function scopeLabels(t: SearchTemplate) {
    if (t.sourceMode === 'global') return ['Глобальный поиск Telegram · запуск вручную'];
    if (t.sourceMode === 'chats' || t.sourceKeys?.length) return (t.sourceKeys ?? []).map(key => { const s = sources.find(s => s.key === key); return s ? `${s.title}${s.scopeTitle ? ` / ${s.scopeTitle}` : ''}` : 'Выбранный чат или тема · название недоступно'; });
    if (t.sourceMode === 'all') return ['Существующие публичные каналы · прежняя область'];
    return t.sources.map(id => { const c = channels.find(c => c.id === id); return c?.title || (c?.username ? `@${c.username}` : 'Публичный канал · название недоступно'); });
  }
  return <div className="templates saved-searches">
    <h2>Сохранённые поиски</h2><p className="helper">Повторите поиск или включите отслеживание новых публикаций. Отслеживание сохраняет совпадения, пока работает hradar; всплывающие уведомления настраиваются отдельно.</p>
    <button className="secondary-button" disabled={busy} onClick={() => void action(reload)}>Обновить список</button>
    {stateError && <p role="alert" className="error">Не удалось уточнить область или состояние отслеживания. {stateError} <button className="text-button" onClick={() => setStateError('')}>Скрыть</button><button className="text-button" disabled={busy} onClick={() => void reload().catch(e => setStateError((e as Error).message))}>Повторить</button></p>}
    {!draft ? <>
      <button className="secondary-button" onClick={() => setDraft({ ...empty })}>+ Создать сохранённый поиск</button>
      {!templates.length && <p className="helper">Сохраните запрос и выбранные чаты на странице поиска или создайте поиск здесь.</p>}
      {templates.map(t => <article aria-label={t.name} className="template-card" key={t.id}><h3>{t.name}</h3>
        <p className="saved-query">Запрос: <strong>{t.telegramQuery}</strong></p><ul className="scope-labels" aria-label="Чаты сохранённого поиска">{scopeLabels(t).map((label, i) => <li key={i}>{label}</li>)}</ul>
        <p className="helper">{live(t) ? 'Отслеживание включено' : 'Отслеживание выключено'} · {t.notificationsEnabled !== false ? 'Всплывающие уведомления разрешены для этого поиска' : 'Без всплывающих уведомлений'}</p>
        {(!!t.required.length || !!t.any.length || !!t.excluded.length) && <p className="helper">{[t.required.length ? `AND: ${t.required.join(', ')}` : '', t.any.length ? `OR: ${t.any.join(', ')}` : '', t.excluded.length ? `NOT: ${t.excluded.join(', ')}` : ''].filter(Boolean).join(' · ')}</p>}
        {t.sourceMode !== 'chats' && hasLocal(t.id) && <p className="helper">Этот прежний поиск также связан с локальными чатами. Список и позиции этих связей сохраняются; единый переключатель управляет отслеживанием поиска.</p>}
        {states.filter(s => s.templateId === t.id).map((s, i) => <p key={i} role="status">{sources.find(source => source.key === s.sourceKey)?.title ?? 'Выбранная область'} · {statuses[s.status] ?? 'Состояние отслеживания уточняется'}{s.error && ` · ${s.error}`}{s.retryAt > Date.now() && ` · Следующая попытка: ${new Date(s.retryAt).toLocaleString('ru-RU')}`}</p>)}
        <div className="template-actions"><button className="text-button" disabled={busy} onClick={() => onRun(t)}>Искать сейчас</button><button className="text-button" disabled={busy} onClick={() => setDraft({ ...t })}>Настроить</button><button className="text-button" disabled={busy || t.sourceMode === 'global'} title={t.sourceMode === 'global' ? 'Глобальный поиск доступен только вручную' : undefined} onClick={() => void action(() => save({ ...t, ...(t.sourceMode !== 'chats' && hasLocal(t.id) ? { active: !live(t) } : { active: true, subscribed: !live(t) }) }))}>{live(t) ? 'Выключить отслеживание' : 'Включить отслеживание'}</button><button className="text-button danger" disabled={busy} onClick={() => setDeleting(t)}>Удалить</button></div>
      </article>)}
    </> : <form onSubmit={e => { e.preventDefault(); if (busy || picking) return; void action(async () => { await save({ ...draft, required: split(draft.required.join('\n')).length || split(draft.any.join('\n')).length ? split(draft.required.join('\n')) : [draft.telegramQuery.trim()], any: split(draft.any.join('\n')), excluded: split(draft.excluded.join('\n')), active: draft.active }); setDraft(null); }); }}>
      <h3>{draft.id ? 'Настройки поиска' : 'Новый сохранённый поиск'}</h3><label htmlFor="template-name">Название сохранённого поиска</label><input id="template-name" required maxLength={100} disabled={busy} value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} />
      <label htmlFor="telegram-query">Запрос Telegram</label><input id="telegram-query" required maxLength={256} disabled={busy} placeholder="Java" value={draft.telegramQuery} onChange={e => setDraft({ ...draft, telegramQuery: e.target.value })} /><p className="helper compact">Запрос Telegram ограничивает выборку. Дополнительные условия проверяются в тексте полученных публикаций.</p>
      <p className="helper">При отслеживании проверяются текстовые условия. Если обязательные и альтернативные фразы не заданы, используется вся фраза запроса «{draft.telegramQuery || '…'}».</p><details><summary>Дополнительные условия · AND / OR / NOT</summary>{([['required', 'Все слова / фразы (AND)'], ['any', 'Хотя бы одно (OR)'], ['excluded', 'Исключения (NOT)']] as const).map(([key, label]) => <div key={key}><label htmlFor={`terms-${key}`}>{label}</label><textarea id={`terms-${key}`} rows={3} disabled={busy} placeholder="Каждое слово или фраза с новой строки" value={draft[key].join('\n')} onChange={e => setDraft({ ...draft, [key]: e.target.value.split('\n') })} /></div>)}</details>
      {draft.sourceMode === 'chats' ? <ChatPicker onBusyChange={setPicking} keys={draft.sourceKeys ?? []} disabled={busy} onChange={keys => setDraft(old => old ? { ...old, sourceKeys: keys } : old)} /> : <><p className="helper">Прежняя область: {scopeLabels(draft).join(', ')}. Она сохраняется при изменении настроек.</p>{draft.sourceMode === 'selected' && <div className="source-checkboxes">{channels.map(c => <label key={c.id}><input type="checkbox" disabled={busy} checked={draft.sources.includes(c.id)} onChange={e => setDraft({ ...draft, sources: e.target.checked ? [...draft.sources, c.id] : draft.sources.filter(id => id !== c.id) })} />{c.title || `@${c.username}`}</label>)}</div>}</>}
      {draft.sourceMode !== 'global' && <label className="checkbox-label"><input type="checkbox" disabled={busy} checked={draft.sourceMode !== 'chats' && hasLocal(draft.id) ? live(draft) : draft.subscribed} onChange={e => setDraft({ ...draft, ...(draft.sourceMode !== 'chats' && hasLocal(draft.id) ? { active: e.target.checked } : { subscribed: e.target.checked, active: true }) })} />Отслеживать новые публикации</label>}
      <p className="helper">Новые публикации с текущего момента; при паузе позиция сохраняется. Новые чаты автоматически не подключаются.</p>
      <label className="checkbox-label"><input type="checkbox" disabled={busy} checked={draft.notificationsEnabled !== false} onChange={e => setDraft({ ...draft, notificationsEnabled: e.target.checked })} />Всплывающие уведомления для этого поиска</label><p className="helper">Разрешение браузера и общее включение уведомлений нужны отдельно. Отказ браузера не отключает отслеживание.</p>
      <button className="primary-button" disabled={busy || picking || (draft.sourceMode === 'chats' && !draft.sourceKeys?.length)}>{busy ? 'Сохраняем…' : 'Сохранить настройки'}</button><button className="secondary-button" type="button" disabled={busy} onClick={() => setDraft(null)}>Отмена</button>
    </form>}
    {deleting && <div className="delete-confirmation" role="group" aria-label="Подтверждение удаления поиска"><p>Удалить сохранённый поиск «{deleting.name}» и остановить его отслеживание? Совпадения этого поиска тоже будут удалены. Сохранённые публикации и карточки проектов останутся.</p><button className="primary-button danger" disabled={busy} onClick={() => void action(async () => { await mutate(`/api/templates/${deleting.id}`, 'DELETE'); setDeleting(null); })}>Удалить сохранённый поиск</button><button className="secondary-button" disabled={busy} onClick={() => setDeleting(null)}>Отмена</button></div>}
    <details className="saved-delivery"><summary>Как включить всплывающие уведомления</summary><NotificationPreferences compact /></details>
  </div>;
}
