import { useEffect, useRef, useState } from 'react';
import { mutate, normalizeUsername, request } from './api';
import type { HistoryPage, HistorySyncRecord, ResearchMessage } from './api';

const labels = { IDLE: 'Импорт не запускался', RUNNING: 'Фоновый импорт выполняется', COMPLETED: 'Доступная история загружена', FAILED: 'Импорт остановлен с ошибкой', PAUSED: 'Загрузка приостановлена' };
const emptyPage: HistoryPage = { messages: [], nextCursor: null, total: 0, query: '' };

export function HistoryArchive({ username, autoStart, authorized, onSelect, onSourceChange }: {
  username: string; autoStart: boolean; authorized: boolean; onSelect: (message: ResearchMessage) => void; onSourceChange?: (username: string) => void;
}) {
  const [source, setSource] = useState(() => username.replace(/^@/, '').toLowerCase());
  const [draft, setDraft] = useState('@' + username.replace(/^@/, ''));
  const [sync, setSync] = useState<HistorySyncRecord | null>(null);
  const [archives, setArchives] = useState<HistorySyncRecord[]>([]);
  const [query, setQuery] = useState('');
  const [appliedQuery, setAppliedQuery] = useState('');
  const [page, setPage] = useState<HistoryPage>(emptyPage);
  const [cursors, setCursors] = useState<(number | null)[]>([null]);
  const [error, setError] = useState('');
  const [maxMessages, setMaxMessages] = useState('1000');
  const [busy, setBusy] = useState(false);
  const generation = useRef(0);
  const busyRef = useRef(false);
  const initialStart = useRef(autoStart);
  const limitEdited = useRef(false);

  async function loadPage(before: number | null, term = appliedQuery, signal?: AbortSignal) {
    const params = new URLSearchParams({ limit: '50', query: term });
    if (before != null) params.set('before', String(before));
    return request<HistoryPage>(`/api/history/${encodeURIComponent(source)}/messages?${params}`, signal);
  }
  async function refresh(signal?: AbortSignal) {
    const [state, list] = await Promise.all([
      request<HistorySyncRecord>(`/api/history/${encodeURIComponent(source)}/sync`, signal),
      request<HistorySyncRecord[]>('/api/history/syncs', signal),
    ]);
    if (signal?.aborted) return;
    setSync(state); setArchives(list);
    return state;
  }
  async function start() {
    if (busyRef.current) return;
    const currentGeneration = generation.current;
    busyRef.current = true; setBusy(true); setError('');
    try {
      const state = await mutate<HistorySyncRecord>(`/api/history/${encodeURIComponent(normalizeUsername(source))}/sync`, 'POST', { maxMessages: maxMessages === 'all' ? null : Number(maxMessages) });
      if (currentGeneration !== generation.current) return;
      setSync(state);
      setPage(await loadPage(cursors[cursors.length - 1]));
    } catch (e) { if (currentGeneration === generation.current) setError((e as Error).message); }
    finally { busyRef.current = false; if (currentGeneration === generation.current) setBusy(false); }
  }
  async function stop() {
    if (busyRef.current) return;
    busyRef.current = true; setBusy(true); setError('');
    const currentGeneration = generation.current;
    try {
      const state = await mutate<HistorySyncRecord>(`/api/history/${encodeURIComponent(source)}/stop`, 'POST');
      if (currentGeneration === generation.current) { setSync(state); setPage(await loadPage(cursors[cursors.length - 1])); }
    } catch (e) { if (currentGeneration === generation.current) setError((e as Error).message); }
    finally { busyRef.current = false; if (currentGeneration === generation.current) setBusy(false); }
  }
  useEffect(() => {
    const controller = new AbortController();
    generation.current++;
    limitEdited.current = false;
    setSync(null); setPage(emptyPage); setCursors([null]); setAppliedQuery(''); setQuery(''); setError('');
    void Promise.all([refresh(controller.signal), loadPage(null, '', controller.signal)]).then(([state, data]) => {
      if (!controller.signal.aborted) {
        setPage(data);
        if (!limitEdited.current) setMaxMessages(state?.maxMessages === null ? 'all' : String(state?.maxMessages ?? 1000));
        if (initialStart.current) { initialStart.current = false; void start(); }
      }
    }).catch(e => { if (!controller.signal.aborted) setError((e as Error).message); });
    return () => { generation.current++; controller.abort(); };
  }, [source]);
  useEffect(() => {
    if (sync?.status !== 'RUNNING') return;
    const controller = new AbortController();
    let pending = false;
    const interval = setInterval(async () => {
      if (pending) return;
      pending = true;
      try {
        const state = await refresh(controller.signal);
        if (state && !controller.signal.aborted) {
          const data = await loadPage(cursors[cursors.length - 1], appliedQuery, controller.signal);
          if (!controller.signal.aborted) setPage(data);
        }
      } catch (e) { if (!controller.signal.aborted) setError((e as Error).message); }
      finally { pending = false; }
    }, 2000);
    return () => { clearInterval(interval); controller.abort(); };
  }, [source, sync?.status, appliedQuery, cursors]);
  async function show(before: number | null, term: string, nextCursors: (number | null)[]) {
    const currentGeneration = generation.current;
    setBusy(true); setError('');
    try { const data = await loadPage(before, term); if (currentGeneration === generation.current) { setPage(data); setAppliedQuery(term); setCursors(nextCursors); } }
    catch (e) { if (currentGeneration === generation.current) setError((e as Error).message); }
    finally { if (currentGeneration === generation.current) setBusy(false); }
  }
  function selectSource(value: string) {
    try { const name = normalizeUsername(value).toLowerCase(); setSource(name); setDraft('@' + name); onSourceChange?.(name); }
    catch (e) { setError((e as Error).message); }
  }
  return <section className="history-archive" aria-label="Локальная история канала">
    <div className="archive-heading"><h2>Загрузка истории канала</h2><p className="helper">Источник результатов: локальный архив SQLite. Поиск не обращается к Telegram API.</p><p className="helper">Импорт работает в backend. Можно закрыть эту страницу; активный импорт продолжится после перезапуска, остановленный — по кнопке «Продолжить загрузку».</p></div>
    <form className="archive-source-form" onSubmit={e => { e.preventDefault(); selectSource(draft); }}><label htmlFor="archive-source">Канал для истории</label><div><input id="archive-source" value={draft} onChange={e => setDraft(e.target.value)} placeholder="@telegram" disabled={busy} required /><button className="secondary-button" disabled={busy}>Открыть архив</button></div></form>
    {archives.length > 0 && <label>Сохранённые архивы<select aria-label="Сохранённые архивы" value={archives.some(a => a.username === source) ? source : ''} onChange={e => selectSource(e.target.value)}><option value="" disabled>Выберите архив</option>{archives.map(a => <option value={a.username} key={a.channelId}>{a.title || '@' + a.username} · {a.messageCount}</option>)}</select></label>}
    <div className="import-status" aria-live="polite"><strong>{sync ? labels[sync.status] : 'Проверяем состояние…'}</strong><span>Загружено сообщений: <b data-testid="history-count">{sync?.messageCount ?? 0}</b></span>{sync?.status === 'RUNNING' && <small>Общее количество заранее неизвестно. Порции — до 100 сообщений.</small>}{sync?.nextRetryAt && sync.nextRetryAt > Date.now() ? <small>Следующая попытка: {new Date(sync.nextRetryAt).toLocaleTimeString('ru-RU')}</small> : null}</div>
    {sync?.error && <p className="error" role="alert">{sync.error}</p>}{error && <p className="error" role="alert">{error}</p>}
    <label>Лимит сохранённых сообщений<select aria-label="Лимит сохранённых сообщений" value={maxMessages} disabled={busy || sync?.status === 'RUNNING'} onChange={e => { limitEdited.current = true; setMaxMessages(e.target.value); }}><option value="1000">1000 сообщений</option><option value="5000">5000 сообщений</option><option value="10000">10000 сообщений</option><option value="all">Вся доступная история · без ограничения</option></select></label>
    <p className="helper">Лимит — общее число сообщений в архиве канала. Безлимитная загрузка запускается только после явного выбора «Вся доступная история».</p>
    {sync?.pauseReason === 'LIMIT' && <p className="helper">Достигнут выбранный лимит. Увеличьте его, чтобы продолжить загрузку с сохранённого курсора.</p>}
    <div className="archive-actions"><button className="primary-button" disabled={busy || !authorized || !sync || sync.status === 'RUNNING' || sync.status === 'COMPLETED' || (maxMessages !== 'all' && Number(maxMessages) <= (sync?.messageCount ?? 0))} onClick={() => void start()}>{sync?.status === 'FAILED' || sync?.status === 'PAUSED' ? 'Продолжить загрузку' : sync?.status === 'RUNNING' ? 'Импорт выполняется' : sync?.status === 'COMPLETED' ? 'История загружена' : 'Начать загрузку'}</button>{sync?.status === 'RUNNING' && <button className="secondary-button" disabled={busy} onClick={() => void stop()}>Остановить загрузку</button>}<button className="secondary-button" disabled={busy} onClick={async () => { try { await refresh(); await show(cursors[cursors.length - 1], appliedQuery, cursors); } catch (e) { setError((e as Error).message); } }}>Обновить архив</button></div>
    <form className="archive-search" onSubmit={e => { e.preventDefault(); void show(null, query.trim(), [null]); }}><label htmlFor="archive-query">Поиск по всей сохранённой истории</label><div><input id="archive-query" type="search" value={query} onChange={e => setQuery(e.target.value)} maxLength={256} placeholder="Слово или фраза во всех загруженных сообщениях" /><button className="secondary-button" disabled={busy}>Найти в истории</button></div></form>
    <div className="archive-page-info"><span>Найдено: {page.total} · Страница {cursors.length}</span><span>До 50 сообщений на странице</span></div>
    {page.messages.length ? page.messages.map(message => <article className="message-card" key={message.id}><div className="message-meta"><strong>{sync?.title || '@' + source}</strong><time>{new Date(message.date * 1000).toLocaleString('ru-RU')}</time></div><p className="message-text">{message.text || 'Медиа или служебное сообщение без текста'}</p><div className="message-footer"><span>ID сообщения: {message.id}</span><button className="text-button" onClick={() => onSelect({ ...message, title: sync?.title, username: source })}>Детали ↗</button></div></article>) : <div className="empty-state"><h3>{appliedQuery ? 'В сохранённой истории нет совпадений' : 'В архиве пока нет сообщений'}</h3><p>{appliedQuery ? 'Измените запрос. Проверяется вся уже импортированная история.' : 'Запустите импорт выбранного канала.'}</p></div>}
    <div className="archive-pagination"><button className="secondary-button" disabled={busy || cursors.length < 2} onClick={() => { const previous = cursors.slice(0, -1); void show(previous[previous.length - 1], appliedQuery, previous); }}>Предыдущая страница</button><button className="secondary-button" disabled={busy || page.nextCursor == null} onClick={() => void show(page.nextCursor, appliedQuery, [...cursors, page.nextCursor])}>Следующая страница архива</button></div>
  </section>;
}
