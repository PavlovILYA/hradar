import { useEffect, useRef, useState } from 'react';
import { sourceMutation, sourceRequest, type SourcePage, type TelegramSource } from './SourceCatalog';
import { DiscussionSource } from './DiscussionSource';
import type { HistorySyncRecord, ResearchMessage, SearchPage, SearchTemplate } from './api';

export const sourceLabel = (s: TelegramSource) => `${s.title}${s.scopeTitle ? ` / ${s.scopeTitle}` : ''} · ${{ PRIVATE: 'Личный чат', BASIC_GROUP: 'Группа', SUPERGROUP: 'Супергруппа', CHANNEL: 'Канал' }[s.type]}${s.scope.kind === 'FORUM_TOPIC' ? ' · Тема' : s.scope.kind === 'MESSAGE_THREAD' ? ' · Обсуждение' : ''}`;
export function useSelectedSources(active = true) {
  const [sources, setSources] = useState<TelegramSource[]>([]);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState('');
  useEffect(() => {
    if (!active) return;
    const c = new AbortController();
    sourceRequest<SourcePage>('/api/sources', c.signal).then(p => { if (!c.signal.aborted) { setSources(p.items); setError(''); } })
      .catch(() => { if (!c.signal.aborted) setError('Не удалось загрузить сохранённые источники.'); });
    return () => c.abort();
  }, [active, revision]);
  return { sources, error, reload: () => setRevision(n => n + 1) };
}
interface MessagePage { messages: ResearchMessage[]; nextCursor: number | null; state?: string; notice?: string | null; total?: number }
const emptyPage: MessagePage = { messages: [], nextCursor: null };
function MessageList({ messages, onSelect }: { messages: ResearchMessage[]; onSelect: (m: ResearchMessage) => void }) {
  return <>{messages.map(m => <article className="message-card" key={`${m.channelId}:${m.id}`}><div className="message-meta"><strong>{m.title}{m.scopeTitle ? ` / ${m.scopeTitle}` : m.forumTopicId ? ` / Тема #${m.forumTopicId}` : ''}</strong><time>{new Date(m.date * 1000).toLocaleString('ru-RU')}</time></div><p className="message-text">{m.availability === 'deleted' ? 'Сообщение удалено' : m.availability === 'unavailable' ? 'Сообщение недоступно' : m.text || 'Медиа или служебное сообщение без текста'}</p><div className="message-footer"><span>ID сообщения: {m.id}</span><button className="text-button" onClick={() => onSelect(m)}>Детали ↗</button></div></article>)}</>;
}

export function SelectedSourceSearch({ initialKey = '', templates = [], onSelect }: { initialKey?: string; templates?: SearchTemplate[]; onSelect: (m: ResearchMessage) => void }) {
  const { sources, error: catalogError, reload } = useSelectedSources();
  const [keys, setKeys] = useState<string[]>(initialKey ? [initialKey] : []);
  const [query, setQuery] = useState('');
  const [page, setPage] = useState<MessagePage>(emptyPage);
  const [search, setSearch] = useState<SearchPage | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [operation, setOperation] = useState<'recent' | 'search'>('recent');
  const [templateId, setTemplateId] = useState('');
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  function reset(next: string[]) { controller.current?.abort(); setBusy(false); setKeys(next); setPage(emptyPage); setSearch(null); setError(''); }
  async function load(kind: 'recent' | 'search', more = false) {
    controller.current?.abort(); const c = new AbortController(); controller.current = c;
    setBusy(true); setError(''); setOperation(kind);
    try {
      if (kind === 'search') {
        const data = await sourceMutation<SearchPage>('/api/search', 'POST', { mode: 'sources', sourceKeys: keys, usernames: [], query, ...(templateId ? { templateId } : {}), limit: 50, cursor: more ? search?.cursor ?? '' : '' }, c.signal, 95_000);
        if (c.signal.aborted) return;
        setSearch(data); setPage(old => ({ messages: more ? [...new Map([...old.messages, ...data.messages].map(m => [`${m.channelId}:${m.id}`, m])).values()] : data.messages, nextCursor: null, state: data.state, notice: data.notice }));
      } else {
        const p = new URLSearchParams({ limit: '50' }); if (more && page.nextCursor) p.set('before', String(page.nextCursor));
        const data = await sourceRequest<MessagePage>(`/api/sources/${encodeURIComponent(keys[0])}/messages?${p}`, c.signal);
        if (c.signal.aborted) return;
        setSearch(null); setPage(old => ({ ...data, messages: more ? [...new Map([...old.messages, ...data.messages].map(m => [`${m.channelId}:${m.id}`, m])).values()] : data.messages }));
      }
    } catch(e) { if (!c.signal.aborted) setError((e as Error).message); }
    finally { if (controller.current === c) { setBusy(false); controller.current = null; } }
  }
  return <section className="data-screen selected-source-workspace" aria-label="Чтение сохранённых источников">
    <h2>Сохранённые источники</h2><p className="helper">Чтение и поиск по выбранным чатам и темам. Доступ проверяется при обращении к Telegram. Мониторинг и передача боту не включаются.</p>
    {(catalogError || error) && <p role="alert" className="error">{catalogError || error}</p>}{catalogError && <button onClick={reload}>Повторить загрузку сохранённых источников</button>}
    <fieldset disabled={busy}><legend>Источники для поиска · до 10</legend>{sources.map(s => <label key={s.key}><input type="checkbox" checked={keys.includes(s.key)} disabled={!keys.includes(s.key) && keys.length >= 10} onChange={e => reset(e.target.checked ? [...keys, s.key] : keys.filter(k => k !== s.key))} />{sourceLabel(s)}{s.access === 'UNAVAILABLE' ? ' · доступ потерян' : ''}</label>)}{!sources.length && !catalogError && <p>Выберите и сохраните чат в разделе «Источники».</p>}</fieldset>
    <div className="project-actions"><button className="secondary-button" disabled={busy || keys.length !== 1} onClick={() => void load('recent')}>Получить сообщения источника</button></div>
    <form onSubmit={e => { e.preventDefault(); void load('search'); }}>{!!templates.length && <label>Текстовые правила источников<select value={templateId} onChange={e => { controller.current?.abort(); setBusy(false); setTemplateId(e.target.value); setPage(emptyPage); setSearch(null); }}><option value="">Без шаблона</option>{templates.filter(t => t.active).map(t => <option value={t.id} key={t.id}>{t.name}</option>)}</select></label>}<label>Поисковый запрос источников<input value={query} maxLength={256} onChange={e => { controller.current?.abort(); setBusy(false); setQuery(e.target.value); setPage(emptyPage); setSearch(null); }} /></label><button className="primary-button" disabled={busy || !keys.length || (!templateId && !query.trim())}>Искать в выбранных источниках</button></form>
    {busy && <p role="status">Получаем сообщения… <button onClick={() => { controller.current?.abort(); setBusy(false); }}>Остановить запрос</button></p>}
    {page.notice && <p role="status">{page.notice}</p>}
    {page.state && !['ok', 'exhausted'].includes(page.state) && <p role="status">{page.state === 'rate_limited' ? 'Telegram ограничил частоту запросов. Повторите позже.' : 'Telegram вернул неполный результат или источник недоступен.'}</p>}
    <MessageList messages={page.messages} onSelect={onSelect} />
    {!busy && page.state && !page.messages.length && <p>Доступных сообщений не найдено.</p>}
    {(operation === 'search' ? !!search?.cursor : page.nextCursor != null) && <button className="secondary-button" disabled={busy} onClick={() => void load(operation, true)}>Ещё сообщения источника</button>}
  </section>;
}

export function SelectedSourceArchive({ initialKey = '', authorized, onSelect }: { initialKey?: string; authorized: boolean; onSelect: (m: ResearchMessage) => void }) {
  const { sources, error: catalogError, reload } = useSelectedSources();
  const [key, setKey] = useState(initialKey);
  const [sync, setSync] = useState<HistorySyncRecord | null>(null);
  const [page, setPage] = useState<MessagePage>(emptyPage);
  const [query, setQuery] = useState('');
  const [appliedQuery, setAppliedQuery] = useState('');
  const [maxMessages, setMaxMessages] = useState('1000');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const controller = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const limitEdited = useRef(false);
  async function load(before: number | null = null, term = appliedQuery, signal?: AbortSignal) {
    const p = new URLSearchParams({ limit: '50', query: term }); if (before) p.set('before', String(before));
    return sourceRequest<MessagePage>(`/api/sources/${encodeURIComponent(key)}/archive?${p}`, signal);
  }
  useEffect(() => {
    generation.current++; setSync(null); setPage(emptyPage); setError(''); setQuery(''); setAppliedQuery(''); limitEdited.current = false;
    if (!key) return;
    const c = new AbortController(); controller.current = c;
    Promise.all([sourceRequest<HistorySyncRecord | null>(`/api/sources/${encodeURIComponent(key)}/sync`, c.signal), load(null, '', c.signal)])
      .then(([s, p]) => { if (!c.signal.aborted) { setSync(s ?? { status: 'IDLE', messageCount: 0 } as HistorySyncRecord); setPage(p); if (!limitEdited.current) setMaxMessages(s?.maxMessages === null ? 'all' : String(s?.maxMessages ?? 1000)); } })
      .catch(e => { if (!c.signal.aborted) setError(e.message); });
    return () => { c.abort(); generation.current++; };
  }, [key]);
  useEffect(() => {
    if (!key || sync?.status !== 'RUNNING') return;
    const c = new AbortController(); let pending = false;
    const timer = setInterval(async () => { if (pending) return; pending = true; try { const s = await sourceRequest<HistorySyncRecord>(`/api/sources/${encodeURIComponent(key)}/sync`, c.signal); const p = await load(null, appliedQuery, c.signal); if (!c.signal.aborted) { setSync(s); setPage(p); } } catch(e) { if (!c.signal.aborted) setError((e as Error).message); } finally { pending = false; } }, 2000);
    return () => { clearInterval(timer); c.abort(); };
  }, [key, sync?.status, appliedQuery]);
  async function action(stop = false) {
    const current = generation.current; setBusy(true); setError('');
    try {
      const state = await sourceMutation<HistorySyncRecord>(`/api/sources/${encodeURIComponent(key)}/${stop ? 'stop' : 'sync'}`, 'POST', stop ? undefined : { maxMessages: maxMessages === 'all' ? null : Number(maxMessages) });
      if (current !== generation.current) return;
      setSync(state); const p = await load(); if (current === generation.current) setPage(p);
    } catch(e) { if (current === generation.current) setError((e as Error).message); }
    finally { if (current === generation.current) setBusy(false); }
  }
  async function show(before: number | null, term: string, append = false) {
    const current = generation.current; setBusy(true); setError('');
    try { const p = await load(before, term); if (current === generation.current) { setAppliedQuery(term); setPage(old => ({ ...p, messages: append ? [...new Map([...old.messages, ...p.messages].map(m => [`${m.channelId}:${m.id}`, m])).values()] : p.messages })); } }
    catch(e) { if (current === generation.current) setError((e as Error).message); }
    finally { if (current === generation.current) setBusy(false); }
  }
  return <section className="history-archive" aria-label="Архив сохранённого источника"><h2>Архив выбранного чата или темы</h2>
    <label>Сохранённый источник для архива<select value={key} disabled={busy} onChange={e => setKey(e.target.value)}><option value="">Выберите источник</option>{sources.map(s => <option value={s.key} key={s.key}>{sourceLabel(s)}</option>)}</select></label>
    {(error || catalogError) && <p role="alert" className="error">{error || catalogError}</p>}{catalogError && <button onClick={reload}>Повторить загрузку сохранённых источников</button>}
    {key && <><p className="helper">Импорт запускается только вручную. Архив, курсор и пауза сохраняются после перезапуска. Локальный мониторинг настраивается отдельно; внешние адаптеры доступа не получают.</p>
      <div className="import-status" aria-live="polite"><strong>{sync?.status === 'RUNNING' ? 'Фоновый импорт выполняется' : sync?.status === 'COMPLETED' ? 'Доступная история загружена' : sync?.status === 'FAILED' ? 'Импорт остановлен с ошибкой' : sync?.status === 'PAUSED' ? 'Загрузка приостановлена' : 'Импорт не запускался'}</strong><span>Загружено сообщений: <b>{sync?.messageCount ?? 0}</b></span></div>
      {sync?.error && <p role="alert" className="error">{sync.error}</p>}
      <label>Лимит сообщений источника<select value={maxMessages} disabled={busy || sync?.status === 'RUNNING'} onChange={e => { limitEdited.current = true; setMaxMessages(e.target.value); }}><option value="1000">1000 сообщений</option><option value="5000">5000 сообщений</option><option value="10000">10000 сообщений</option><option value="all">Вся доступная история · без ограничения</option></select></label>
      <div className="archive-actions"><button className="primary-button" disabled={!authorized || busy || !sync || sync.status === 'RUNNING' || sync.status === 'COMPLETED' || (maxMessages !== 'all' && Number(maxMessages) <= sync.messageCount)} onClick={() => void action()}>{sync?.status === 'PAUSED' || sync?.status === 'FAILED' ? 'Продолжить импорт источника' : 'Начать импорт источника'}</button>{sync?.status === 'RUNNING' && <button className="secondary-button" disabled={busy} onClick={() => void action(true)}>Приостановить импорт источника</button>}<button className="secondary-button" disabled={busy} onClick={() => void show(null, appliedQuery)}>Обновить архив источника</button></div>
      <form onSubmit={e => { e.preventDefault(); void show(null, query.trim()); }}><label>Поиск в архиве источника<input maxLength={256} value={query} onChange={e => setQuery(e.target.value)} /></label><button disabled={busy}>Найти в архиве источника</button></form><p>Найдено: {page.total ?? 0}</p><MessageList messages={page.messages} onSelect={onSelect} />{page.nextCursor != null && <button disabled={busy} onClick={() => void show(page.nextCursor, appliedQuery, true)}>Ещё из архива источника</button>}
    </>}
  </section>;
}

export function LocalMessageDetails({ message }: { message: ResearchMessage }) {
  const [detail, setDetail] = useState<ResearchMessage | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  return <><button disabled={busy} onClick={async () => {
    const c = new AbortController(); controller.current?.abort(); controller.current = c; setBusy(true); setError('');
    try { const m = await sourceRequest<ResearchMessage>(`/api/sources/${encodeURIComponent(message.sourceKey!)}/messages/${message.id}`, c.signal); if (!c.signal.aborted) setDetail(m); }
    catch { if (!c.signal.aborted) setError('Сообщение недоступно. Сохранённая карточка остаётся в проекте.'); }
    finally { if (controller.current === c) setBusy(false); }
  }}>Открыть детали сообщения</button>{error && <p role="status">{error}</p>}{detail && <details open><summary>Детали сообщения</summary><p>{detail.title}{detail.scopeTitle ? ` / ${detail.scopeTitle}` : detail.forumTopicId ? ` / Тема #${detail.forumTopicId}` : ''}</p><p>ID чата: {detail.channelId} · ID сообщения: {detail.id}</p><p>{detail.availability === 'deleted' ? 'Сообщение удалено' : detail.availability === 'unavailable' ? 'Сообщение недоступно' : detail.text || 'Медиа или служебное сообщение без текста'}</p>{detail.link && /^https:\/\/t\.me\//.test(detail.link) && <a href={detail.link} target="_blank" rel="noreferrer">Оригинал в Telegram ↗</a>}<DiscussionSource message={detail} /></details>}</>;
}
