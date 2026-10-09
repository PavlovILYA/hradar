import { createRoot } from 'react-dom/client';
import { useEffect, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { normalizeUsername, request, mutate } from './api';
import { Templates } from './Templates';
import { Notifications } from './Notifications';
import { HistoryArchive } from './HistoryArchive';
import type { MonitoredChannel, SearchTemplate, MatchExplanation, ResearchMessage, SearchPage, SearchInput, NotificationRecord, NotificationPage, NotificationSummary } from './api';
import type { TelegramMessage, TelegramStatus } from './api';
import './styles.css';

const dateFormat = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
function Highlight({ text, terms }: { text: string; terms: string[] }) {
  const words = terms.filter(Boolean).sort((a, b) => b.length - a.length);
  if (!words.length) return text;
  const regex = new RegExp(`(${words.map(word => word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})`, 'giu');
  return text.split(regex).map((part, i) => i % 2 ? <mark key={i}>{part}</mark> : part);
}
function App() {
  const [status, setStatus] = useState<TelegramStatus | null>(null);
  const [checking, setChecking] = useState(false);
  const [username, setUsername] = useState('@telegram');
  const [limit, setLimit] = useState('10');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<{ username: string; messages: ResearchMessage[] } | null>(null);
  const [filter, setFilter] = useState('');
  const [sourceFilter, setSourceFilter] = useState('');
  const [archiveView, setArchiveView] = useState(false);
  const [archiveUsername, setArchiveUsername] = useState('telegram');
  const [archiveAutoStart, setArchiveAutoStart] = useState(false);
  function openArchive(start = false) {
    try { setArchiveUsername(normalizeUsername(username)); setArchiveAutoStart(start); setArchiveView(true); setNotificationView(false); setMobileSources(false); }
    catch (e) { setError((e as Error).message); }
  }
  const [notificationView, setNotificationView] = useState(false);
  const [notificationPage, setNotificationPage] = useState<NotificationPage>({ items: [], unread: 0, cursor: null });
  const [byChannel, setByChannel] = useState<Record<string, number>>({});
  const [eventConnected, setEventConnected] = useState(true);
  const [desktopPermission, setDesktopPermission] = useState(() => 'Notification' in window ? Notification.permission : 'unsupported');
  async function refreshNotifications(more = false) {
    const page = await request<NotificationPage>(`/api/notifications${more && notificationPage.cursor ? `?before=${notificationPage.cursor}` : ''}`);
    setNotificationPage(old => ({ ...page, items: more ? [...old.items, ...page.items] : page.items }));
  }
  useEffect(() => {
    const events = new EventSource('/api/events');
    let lastRevision: string | null = null;
    let known = new Set<string>();
    let initialized = false;
    let stopped = false;
    events.onmessage = async e => {
      const summary = JSON.parse(e.data) as NotificationSummary;
      setEventConnected(true); setByChannel(summary.byChannel);
      if (summary.telegram) setStatus(summary.telegram);
      const revision = `${summary.revision}:${summary.unread}`;
      if (revision === lastRevision) return;
      lastRevision = revision;
      try {
        const page = await request<NotificationPage>('/api/notifications');
        if (stopped) return;
        if (initialized && 'Notification' in window && Notification.permission === 'granted') {
          for (const item of page.items.filter(n => !n.read && !known.has(n.id)).slice(0, 5)) {
            const notice = new Notification(`hradar · ${item.templateName}`, { body: `Совпадение в ${item.message.title}`, tag: item.id });
            notice.onclick = () => { window.focus(); setNotificationView(true); notice.close(); };
          }
        }
        initialized = true; known = new Set(page.items.map(n => n.id));
        setNotificationPage(page);
      } catch (e) { if (!stopped) setError((e as Error).message); lastRevision = null; }
    };
    events.onerror = () => setEventConnected(false);
    return () => { stopped = true; events.close(); };
  }, []);
  async function notificationAction(path: string) {
    try { await mutate(path, 'POST'); await refreshNotifications(); }
    catch (e) { setError((e as Error).message); }
  }
  async function selectMessage(message: ResearchMessage) {
    setSelected(message); setPanel('details'); setMobileContext(true);
    if (!message.link) {
      try {
        const data = await request<{ link: string }>(`/api/messages/${message.channelId}/${message.id}/link`);
        setSelected(old => old?.id === message.id && old.channelId === message.channelId ? { ...old, link: data.link } : old);
      } catch { /* Original link is optional; reading and details remain available. */ }
    }
  }
  function openNotification(item: NotificationRecord) {
    setArchiveView(false); setNotificationView(false); setResult({ username: item.message.username ?? '', messages: [item.message] });
    setApplied(null); setSearchPage(null); setLastSearch(null); setFilter('');
    void selectMessage(item.message); void notificationAction(`/api/notifications/${item.id}/read`);
  }
  const [sources, setSources] = useState<MonitoredChannel[]>([]);
  const [templates, setTemplates] = useState<SearchTemplate[]>([]);
  const [applied, setApplied] = useState<SearchTemplate | null>(null);
  const [explanations, setExplanations] = useState<Record<number, MatchExplanation>>({});
  async function refreshResearch() {
    const [channels, saved] = await Promise.all([request<MonitoredChannel[]>('/api/channels'), request<SearchTemplate[]>('/api/templates')]);
    setSources(channels); setTemplates(saved);
  }
  useEffect(() => { void refreshResearch().catch(e => setError((e as Error).message)); }, []);
  async function saveSource() {
    if (!result) return;
    try { await mutate('/api/channels', 'POST', { username: result.username }); await refreshResearch(); }
    catch (e) { setError((e as Error).message); }
  }
  const [searchMode, setSearchMode] = useState<SearchInput['mode']>('channel');
  const [query, setQuery] = useState('');
  const [chosenSources, setChosenSources] = useState<string[]>([]);
  const [searchPage, setSearchPage] = useState<SearchPage | null>(null);
  const [lastSearch, setLastSearch] = useState<SearchInput | null>(null);
  async function runSearch(template?: SearchTemplate, next = false) {
    if (loading) return;
    setArchiveView(false); setNotificationView(false);
    const input: SearchInput = next && lastSearch ? { ...lastSearch, cursor: searchPage?.cursor } : {
      mode: searchMode, query, usernames: searchMode === 'channel' ? [username] : chosenSources,
      templateId: template?.id, limit: Number(limit),
    };
    setLoading(true); setError(''); setSelected(null);
    if (!next) { setResult(null); setFilter(''); setApplied(template ?? null); setExplanations({}); setSearchPage(null); }
    try {
      const page = await mutate<SearchPage>('/api/search', 'POST', input, 95_000);
      setSearchPage(page); setLastSearch(input);
      setResult(old => ({ username: input.mode === 'channel' && !template ? username.replace(/^@/, '') : '', messages: next ? [...new Map([...(old?.messages ?? []), ...page.messages].map(m => [`${m.channelId}:${m.id}`, m])).values()] : page.messages }));
      setExplanations(old => ({ ...(next ? old : {}), ...Object.fromEntries(page.messages.filter(m => m.explanation).map(m => [m.id, m.explanation!])) }));
    } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }
  const [selected, setSelected] = useState<ResearchMessage | null>(null);
  const [theme, setTheme] = useState(() => localStorage.getItem('hradar-theme') || 'light');
  const [panel, setPanel] = useState<'filters' | 'templates' | 'details'>('filters');
  const [mobileSources, setMobileSources] = useState(false);
  const [mobileContext, setMobileContext] = useState(() => window.innerWidth > 1150);
  useEffect(() => { document.documentElement.dataset.theme = theme; localStorage.setItem('hradar-theme', theme); }, [theme]);
  async function checkStatus(signal?: AbortSignal) {
    setChecking(true);
    try { setStatus(await request<TelegramStatus>('/api/telegram/status', signal)); setError(''); }
    catch (error) { if (!signal?.aborted) { setStatus(null); setError((error as Error).message); } }
    finally { if (!signal?.aborted) setChecking(false); }
  }
  useEffect(() => { const c = new AbortController(); void checkStatus(c.signal); return () => c.abort(); }, []);
  async function loadMessages(event?: FormEvent, source = username) {
    event?.preventDefault();
    setArchiveView(false); setNotificationView(false);
    if (loading) return;
    setError('');
    let channel: string;
    try { channel = normalizeUsername(source); }
    catch (error) { setError((error as Error).message); return; }
    const count = Number(limit);
    if (!Number.isInteger(count) || count < 1 || count > 100) { setError('Количество сообщений должно быть целым числом от 1 до 100.'); return; }
    setLoading(true); setResult(null); setSearchPage(null); setLastSearch(null); setApplied(null); setExplanations({}); setSelected(null); setFilter(''); setUsername('@' + channel); setMobileSources(false);
    try {
      const messages = await request<TelegramMessage[]>(`/api/telegram/chats/${encodeURIComponent(channel)}/messages?limit=${count}`);
      setResult({ username: channel, messages });
    } catch (error) { setError((error as Error).message); }
    finally { setLoading(false); }
  }
  const visible = result?.messages.filter(m => (m.text ?? '').toLocaleLowerCase('ru').includes(filter.toLocaleLowerCase('ru')) && (!applied || (m.explanation ?? explanations[m.id])?.matches)) ?? [];
  const statusLabel = checking ? 'Проверяем соединение' : status?.authorized ? (status.connectionState && status.connectionState !== 'Ready' ? 'Telegram переподключается' : 'Telegram подключён') : status ? 'Требуется вход в Telegram' : 'Backend недоступен';
  return <div className={`research-app ${!mobileContext ? 'context-collapsed' : ''}`}>
    <aside className={`sources-pane ${mobileSources ? 'mobile-open' : ''}`}>
      <div className="brand"><span className="brand-mark">h</span><strong>hradar</strong><span className="brand-caption">research workspace</span><button className="mobile-close icon-button" aria-label="Закрыть источники" onClick={() => setMobileSources(false)}>×</button></div>
      <label className="source-search"><span className="sr-only">Поиск источников</span><input type="search" placeholder="Найти источник…" value={sourceFilter} onChange={e => setSourceFilter(e.target.value)} /></label>
      <button className={`notification-nav ${notificationView ? 'active' : ''}`} onClick={() => { setArchiveView(false); setNotificationView(true); setMobileSources(false); void refreshNotifications().catch(e => setError((e as Error).message)); }}>Уведомления <span className="badge">{notificationPage.unread}</span></button><button className={`notification-nav ${archiveView ? 'active' : ''}`} onClick={() => openArchive()}>Сохранённая история</button><div className="pane-label">ИСТОЧНИКИ <span>{sources.length}</span></div>
      <div className="source-list">{sources.filter(s => s.username.includes(sourceFilter.replace('@', '').toLowerCase()) || s.title.toLowerCase().includes(sourceFilter.toLowerCase())).map(source => <button key={source.id} className={`source-item ${result?.username === source.username ? 'active' : ''}`} onClick={() => void loadMessages(undefined, source.username)} disabled={loading || !status?.authorized}><span className="avatar">{source.title[0].toUpperCase()}</span><span><strong>{source.title}</strong><small>@{source.username}</small></span>{byChannel[source.id] > 0 && <span className="badge">{byChannel[source.id]}</span>}</button>)}{!sources.length && <p className="helper">Откройте канал и сохраните его как источник.</p>}</div>
      <form className="add-source" onSubmit={e => void loadMessages(e)}><label htmlFor="username">Username канала</label><input id="username" value={username} onChange={e => setUsername(e.target.value)} placeholder="@telegram" autoComplete="off" disabled={loading} required /><button className="primary-button" disabled={loading || !status?.authorized}>{loading ? 'Загружаем…' : 'Получить сообщения'}</button></form>
      <div className="sidebar-bottom"><span className="local-label">● Локально · только чтение</span><button className="text-button" onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>{theme === 'dark' ? '☀ Светлая тема' : '☾ Тёмная тема'}</button></div>
    </aside>
    <section className="conversation">
      <header className="conversation-header"><button className="icon-button mobile-toggle" aria-label="Открыть источники" onClick={() => setMobileSources(true)}>☰</button><span className="avatar">{result?.username[0]?.toUpperCase() || '#'}</span><div className="source-heading"><h1>{archiveView ? 'Локальная история' : notificationView ? 'Центр уведомлений' : lastSearch && searchPage ? (applied?.name || `Поиск: ${lastSearch.query}`) : result ? `@${result.username}` : 'Обзор каналов'}</h1><span role="status" className={`connection-status ${status?.authorized ? 'online' : ''}`}>{statusLabel}</span></div><button className="icon-button" aria-label="Обновить статус" title="Обновить статус" disabled={checking} onClick={() => void checkStatus()}>↻</button><button className="icon-button" aria-label="Открыть фильтры" title="Фильтры и детали" onClick={() => setMobileContext(!mobileContext)}>☷</button></header>
      {!archiveView && <div className="conversation-toolbar"><label className="filter"><span className="sr-only">Поиск по сообщениям</span><input type="search" placeholder="Поиск в загруженных сообщениях…" value={filter} onChange={e => setFilter(e.target.value)} /></label>{result && <span className="count">{visible.length} / {result.messages.length}</span>}{!notificationView && result?.username && !sources.some(s => s.username === result.username) && <button className="text-button" onClick={() => void saveSource()}>Сохранить источник</button>}</div>}
      {error && <p className="error" role="alert">{error}</p>}
      {searchPage?.notice && <p className="search-notice" role="status">{searchPage.notice}</p>}<section className="message-area" aria-label="Сообщения канала" aria-busy={loading}>
        {archiveView ? <HistoryArchive key={`${archiveUsername}:${archiveAutoStart}`} username={archiveUsername} autoStart={archiveAutoStart} authorized={!!status?.authorized} onSelect={m => void selectMessage(m)} /> : notificationView ? <Notifications items={notificationPage.items} unread={notificationPage.unread} cursor={notificationPage.cursor} onRead={id => void notificationAction(`/api/notifications/${id}/read`)} onReadAll={() => void notificationAction('/api/notifications/read-all')} onOpen={openNotification} onMore={() => void refreshNotifications(true).catch(e => setError((e as Error).message))} /> : loading ? <Empty title="Получаем сообщения" text="Большая история может загружаться до 20 секунд." /> : !result ? <Empty title="Здесь появятся сообщения канала" text="Введите username публичного канала слева и загрузите публикации." /> : !result.messages.length && searchPage && ['unsupported', 'free_limit'].includes(searchPage.state) ? <Empty title="Глобальный поиск недоступен" text={searchPage.notice || 'Запрос недоступен для текущей сессии.'} /> : !result.messages.length ? <Empty title="В канале пока нет доступных сообщений" text="Попробуйте другой публичный канал." /> : !visible.length ? <Empty title="Совпадений не найдено" text="Измените запрос или очистите поиск."><button className="text-button" onClick={() => setFilter('')}>Очистить поиск</button></Empty> : <div className="message-list"><div className="date-divider">Последние публикации</div>{visible.map(message => <article className={`message-card ${selected?.id === message.id && selected.channelId === message.channelId ? 'selected' : ''}`} key={`${message.channelId}:${message.id}`}><div className="message-meta"><strong>{message.title || `@${message.username || result.username}`}</strong><time dateTime={new Date(message.date * 1000).toISOString()}>{dateFormat.format(message.date * 1000)}</time></div><p className={`message-text ${!message.text ? 'no-text' : ''}`}><Highlight text={message.text || 'Медиа или служебное сообщение без текста'} terms={[filter, ...(lastSearch?.query.split(/\s+/) ?? []), ...(applied?.required ?? []), ...(applied?.any ?? [])]} /></p>{message.explanation?.matches && <p className="match-reason">Совпали: {[...message.explanation.foundRequired, ...message.explanation.foundAny].join(', ')} · Исключения отсутствуют</p>}<div className="message-footer"><span>ID канала: {message.channelId}</span><button className="text-button" onClick={() => void selectMessage(message)}>Детали ↗</button></div></article>)}</div>}
      </section>{!archiveView && !notificationView && searchPage?.cursor && <button className="load-more" onClick={() => void runSearch(undefined, true)} disabled={loading}>Следующая страница</button>}<footer className="conversation-footer">{archiveView ? 'Архив SQLite · Поиск и страницы через backend' : applied ? `Локальные условия: ${applied.name}` : 'Фильтр по загруженным сообщениям · Шаблоны сохраняются локально'}</footer>
    </section>
    <aside className={`context-pane ${mobileContext ? 'context-open' : ''}`}><div className="context-heading"><strong>Инструменты исследования</strong><button className="icon-button" aria-label="Закрыть инструменты" onClick={() => setMobileContext(false)}>×</button></div><div className="tabs"><button className={panel === 'filters' ? 'active' : ''} onClick={() => setPanel('filters')}>Фильтры</button><button className={panel === 'templates' ? 'active' : ''} onClick={() => setPanel('templates')}>Шаблоны</button><button className={panel === 'details' ? 'active' : ''} onClick={() => setPanel('details')}>Публикация</button></div>
      {panel === 'filters' ? <div className="context-content"><h2>Уведомления</h2><p className="helper compact">Мониторинг сохранённых каналов работает, пока запущен backend. Браузерные уведомления требуют открытого приложения.</p><button className="secondary-button" disabled={desktopPermission !== 'default'} onClick={async () => { if ('Notification' in window) setDesktopPermission(await Notification.requestPermission()); }}>{desktopPermission === 'granted' ? 'Desktop-уведомления разрешены' : desktopPermission === 'denied' ? 'Уведомления запрещены в браузере' : desktopPermission === 'unsupported' ? 'Браузер не поддерживает уведомления' : 'Разрешить desktop-уведомления'}</button><p className="helper compact">{eventConnected ? 'Обновления подключены (SSE)' : 'SSE переподключается; обновите статус при длительном сбое.'}</p><h2>Поиск Telegram</h2><label htmlFor="search-mode">Область поиска</label><select id="search-mode" value={searchMode} onChange={e => setSearchMode(e.target.value as SearchInput['mode'])}><option value="channel">В текущем канале</option><option value="tracked">В выбранных источниках</option><option value="global">Публичные публикации · глобально</option></select><label htmlFor="search-query">Поисковый запрос</label><input id="search-query" value={query} onChange={e => setQuery(e.target.value)} placeholder="Java developer" maxLength={256} />{searchMode === 'tracked' && <div className="source-checkboxes">{sources.map(c => <label key={c.id}><input type="checkbox" checked={chosenSources.includes(c.username)} onChange={e => setChosenSources(old => e.target.checked ? [...old, c.username] : old.filter(n => n !== c.username))} />@{c.username}</label>)}</div>}{searchMode === 'global' && <p className="helper compact">Только бесплатный поиск. Stars не расходуются. Глобальные уведомления недоступны.</p>}<button className="primary-button" onClick={() => void runSearch()} disabled={loading || !status?.authorized || !query.trim()}>Искать в Telegram</button><h2>Полная история</h2><p className="helper compact">Импортировать весь выбранный канал в локальную SQLite. Данные сохранятся после перезапуска.</p><button className="secondary-button" disabled={!status?.authorized} onClick={() => openArchive(true)}>Загрузить всю историю</button><h2>История канала</h2><p className="helper">Получите последние сообщения из Telegram без вступления в канал.</p><label htmlFor="limit">Сообщений</label><input id="limit" type="number" min="1" max="100" value={limit} onChange={e => setLimit(e.target.value)} disabled={loading} /><p className="helper">От 1 до 100 за один запрос.</p><h2>Текстовый фильтр</h2><p className="helper">Поле над сообщениями фильтрует текущую выборку. Совпадения подсвечиваются.</p><button className="secondary-button" onClick={() => { setFilter(''); setApplied(null); }} disabled={!filter && !applied}>Сбросить фильтр</button>{result && sources.some(c => c.username === result.username) && <button className="text-button danger" onClick={async () => { try { await mutate(`/api/channels/${sources.find(c => c.username === result.username)!.id}`, 'DELETE'); await refreshResearch(); } catch (e) { setError((e as Error).message); } }}>Удалить сохранённый источник</button>}</div> : panel === 'templates' ? <div className="context-content"><Templates templates={templates} channels={sources} refresh={refreshResearch} onRun={t => void runSearch(t)} onError={setError} /></div> : <div className="context-content">{selected ? <><h2>Детали публикации</h2><dl><dt>Источник</dt><dd>{selected.title || `@${selected.username || result?.username}`}</dd><dt>Дата</dt><dd>{dateFormat.format(selected.date * 1000)}</dd><dt>ID сообщения</dt><dd>{selected.id}</dd><dt>ID канала</dt><dd>{selected.channelId}</dd></dl>{selected.link && /^https:\/\/t\.me\//.test(selected.link) && <a className="original-link" href={selected.link} target="_blank" rel="noreferrer">Открыть в Telegram ↗</a>}<button className="secondary-button" onClick={async () => { try { await navigator.clipboard.writeText(selected.text || ''); } catch { setError('Не удалось скопировать текст. Выделите его вручную.'); } }}>Копировать текст</button></> : <p className="helper">Нажмите «Детали» у сообщения, чтобы изучить публикацию.</p>}</div>}
    </aside>
  </div>;
}
function Empty({ title, text, children }: { title: string; text: string; children?: ReactNode }) { return <div className="empty-state"><span className="empty-symbol" aria-hidden="true">⌕</span><h3>{title}</h3><p>{text}</p>{children}</div>; }
createRoot(document.getElementById('root')!).render(<App />);
