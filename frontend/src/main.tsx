import { Onboarding, desktopLaunch, shutdownDesktop, isDesktop } from './Onboarding';
import { Projects, type ProjectIntent } from './Projects';
import { Dashboard } from './Dashboard';
import { createRoot } from 'react-dom/client';
import { useEffect, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { normalizeUsername, request, mutate } from './api';
import { Templates } from './Templates';
import { Notifications } from './Notifications';
import { HistoryArchive } from './HistoryArchive';
import { McpIntegration } from './McpIntegration';
import { BotIntegration } from './BotIntegration';
import { SavedResults } from './SavedResults';
import type { MonitoredChannel, SearchTemplate, MatchExplanation, ResearchMessage, SearchPage, SearchInput, NotificationRecord, NotificationPage, NotificationSummary } from './api';
import type { TelegramMessage, TelegramStatus } from './api';
import { Navigation, screenTitles } from './Navigation';
import type { Screen } from './Navigation';
import { Sources } from './Sources';
import { SelectedSourceSearch, SelectedSourceArchive } from './SelectedSources';
import './styles.css';
import './navigation.css';
import { version as appVersion } from '../package.json';

const dateFormat = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
function Highlight({ text, terms }: { text: string; terms: string[] }) {
  const words = terms.filter(Boolean).sort((a, b) => b.length - a.length);
  if (!words.length) return text;
  const regex = new RegExp(`(${words.map(word => word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})`, 'giu');
  return text.split(regex).map((part, i) => i % 2 ? <mark key={i}>{part}</mark> : part);
}
function App() {
  const [status, setStatus] = useState<TelegramStatus | null>(null);
  const [statusAt, setStatusAt] = useState(0);
  const [statusClock, setStatusClock] = useState(Date.now());
  useEffect(() => { const timer = setInterval(() => setStatusClock(Date.now()), 15_000); return () => clearInterval(timer); }, []);
  const [checking, setChecking] = useState(true);
  const [username, setUsername] = useState('@telegram');
  const [limit, setLimit] = useState('10');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<{ username: string; messages: ResearchMessage[] } | null>(null);
  const [filter, setFilter] = useState('');
  const [screen, setScreen] = useState<Screen>('overview');
  const [visited, setVisited] = useState<Set<Screen>>(() => new Set(['overview']));
  const [navigationOpen, setNavigationOpen] = useState(false);
  const [archiveUsername, setArchiveUsername] = useState('telegram');
  const [selectedSourceKey, setSelectedSourceKey] = useState('');
  const [sourceIntentRevision, setSourceIntentRevision] = useState(0);
  const [archiveSourceKey, setArchiveSourceKey] = useState<string | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  function navigate(next: Screen) {
    setScreen(next); setVisited(old => old.has(next) ? old : new Set([...old, next])); setNavigationOpen(false);
    if (next === 'notifications') void refreshNotifications().catch(e => setError((e as Error).message));
    if (next === 'templates') void refreshResearch().catch(e => setError((e as Error).message));
    requestAnimationFrame(() => heading.current?.focus());
  }
  function openArchive(source?: string) {
    try {
      if (source) setArchiveSourceKey(null);
      if (source || !visited.has('archive')) setArchiveUsername(normalizeUsername(source ?? username));
      navigate('archive');
    } catch (e) { setError((e as Error).message); }
  }
  function closeNavigation() { setNavigationOpen(false); requestAnimationFrame(() => document.getElementById('navigation-toggle')?.focus()); }
  const [notificationPage, setNotificationPage] = useState<NotificationPage>({ items: [], unread: 0, cursor: null });
  const [byChannel, setByChannel] = useState<Record<string, number>>({});
  const [eventConnected, setEventConnected] = useState(false);
  const [eventAt, setEventAt] = useState(0);
  const [dashboardRevision, setDashboardRevision] = useState(0);
  const [projectIntent, setProjectIntent] = useState<ProjectIntent | null>(null);
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
    let fetching = false;
    events.onopen = () => { if (!stopped) setEventConnected(true); };
    events.onmessage = async e => {
      let summary: NotificationSummary;
      try { summary = JSON.parse(e.data) as NotificationSummary; } catch { return; }
      if (stopped) return;
      setEventConnected(true); setEventAt(Date.now()); setByChannel(summary.byChannel);
      if (summary.telegram) { setStatus(summary.telegram); setStatusAt(Date.now()); }
      const revision = `${summary.revision}:${summary.unread}`;
      if (revision === lastRevision || fetching) return;
      lastRevision = revision; fetching = true;
      try {
        const page = await request<NotificationPage>('/api/notifications');
        if (stopped) return;
        if (initialized && 'Notification' in window && Notification.permission === 'granted') {
          for (const item of page.items.filter(n => !n.read && !known.has(n.id)).slice(0, 5)) {
            try {
              const notice = new Notification(`hradar · ${item.message.title || item.message.username || item.templateName}`, {
                body: `${item.templateName}: ${(item.message.text || 'Публикация без текста').replace(/\s+/g, ' ').slice(0, 160)}`, tag: item.id,
              });
              notice.onclick = () => { window.focus(); openNotification(item); notice.close(); };
            } catch { /* Browser/OS may block delivery; the in-app match remains available. */ }
          }
        }
        initialized = true; page.items.forEach(n => known.add(n.id));
        setNotificationPage(page); setDashboardRevision(n => n + 1);
      } catch (e) { if (!stopped) setError((e as Error).message); lastRevision = null; }
      finally { fetching = false; }
    };
    events.onerror = () => setEventConnected(false);
    return () => { stopped = true; events.close(); };
  }, []);
  async function notificationAction(path: string) {
    try { await mutate(path, 'POST'); await refreshNotifications(); setDashboardRevision(n => n + 1); }
    catch (e) { setError((e as Error).message); }
  }
  async function selectMessage(message: ResearchMessage, context: 'search' | 'archive' = 'search') {
    setSelected(message); setSelectionContext(context); setDetailsOpen(true);
    if (message.sourceKey) {
      try {
        const data = await request<ResearchMessage>(`/api/sources/${encodeURIComponent(message.sourceKey)}/messages/${message.id}`);
        setSelected(old => old?.id === message.id && old.channelId === message.channelId ? data : old);
      } catch { setSelected(old => old?.id === message.id && old.channelId === message.channelId ? { ...old, availability: 'unavailable' } : old); }
    } else if (!message.link) {
      try {
        const data = await request<{ link: string }>(`/api/messages/${message.channelId}/${message.id}/link`);
        setSelected(old => old?.id === message.id && old.channelId === message.channelId ? { ...old, link: data.link } : old);
      } catch { /* Original link is optional; reading and details remain available. */ }
    }
  }
  function openNotification(item: NotificationRecord) {
    navigate('search'); setResult({ username: item.message.username ?? '', messages: [item.message] });
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
    navigate('search');
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
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [selectionContext, setSelectionContext] = useState<'search' | 'archive'>('search');
  const [chosenTemplate, setChosenTemplate] = useState('');
  useEffect(() => { document.documentElement.dataset.theme = theme; localStorage.setItem('hradar-theme', theme); }, [theme]);
  async function checkStatus(signal?: AbortSignal) {
    setChecking(true);
    try { setStatus(await request<TelegramStatus>('/api/telegram/status', signal)); setStatusAt(Date.now()); setError(''); }
    catch (error) { if (!signal?.aborted) { setStatus(null); setError((error as Error).message); } }
    finally { if (!signal?.aborted) setChecking(false); }
  }
  useEffect(() => { const c = new AbortController(); void checkStatus(c.signal); return () => c.abort(); }, []);
  async function loadMessages(event?: FormEvent, source = username) {
    event?.preventDefault();
    navigate('search');
    if (loading) return;
    setError('');
    let channel: string;
    try { channel = normalizeUsername(source); }
    catch (error) { setError((error as Error).message); return; }
    const count = Number(limit);
    if (!Number.isInteger(count) || count < 1 || count > 100) { setError('Количество сообщений должно быть целым числом от 1 до 100.'); return; }
    setLoading(true); setResult(null); setSearchPage(null); setLastSearch(null); setApplied(null); setExplanations({}); setSelected(null); setFilter(''); setUsername('@' + channel);
    try {
      const messages = await request<TelegramMessage[]>(`/api/telegram/chats/${encodeURIComponent(channel)}/messages?limit=${count}`);
      setResult({ username: channel, messages });
    } catch (error) { setError((error as Error).message); }
    finally { setLoading(false); }
  }
  const visible = result?.messages.filter(m => (m.text ?? '').toLocaleLowerCase('ru').includes(filter.toLocaleLowerCase('ru')) && (!applied || (m.explanation ?? explanations[m.id])?.matches)) ?? [];
  const statusFresh = !!statusAt && statusClock - statusAt < 60_000;
  const statusLabel = checking ? 'Проверяем соединение' : status && !statusFresh ? 'Нет актуальных данных о Telegram' : status?.authorized ? (status.connectionState === 'Ready' ? 'Telegram подключён' : status.connectionState ? 'Telegram переподключается' : 'Состояние Telegram неизвестно') : status ? 'Требуется вход в Telegram' : 'Backend недоступен';
  if (status && !status.authorized) return <Onboarding onReady={() => void checkStatus()} />;
  const showDetails = detailsOpen && selected && screen === selectionContext;
  function view(id: Screen, children: ReactNode) {
    return visited.has(id) ? <section key={id} id={`screen-${id}`} className={`workspace-screen ${id === 'search' ? 'search-screen' : ''}`} aria-label={screenTitles[id]} hidden={screen !== id}>{children}</section> : null;
  }
  return <div className="workspace-shell" onKeyDown={e => { if (e.key === 'Escape' && !e.defaultPrevented) setDetailsOpen(false); }}>
    <Navigation screen={screen} navigate={next => next === 'archive' ? openArchive() : navigate(next)} unread={notificationPage.unread} theme={theme} toggleTheme={() => setTheme(theme === 'dark' ? 'light' : 'dark')} open={navigationOpen} close={closeNavigation} />
    <main className="workspace-main" inert={navigationOpen || undefined}>
      <header className="workspace-header"><button id="navigation-toggle" className="navigation-toggle icon-button" aria-label="Открыть навигацию" aria-expanded={navigationOpen} aria-controls="workspace-navigation" onClick={() => setNavigationOpen(true)}>☰</button>
        <div><h1 ref={heading} tabIndex={-1}>{screenTitles[screen]}</h1><span role="status" className={`connection-status ${statusFresh && status?.authorized && status.connectionState === 'Ready' ? 'online' : ''}`}>{statusLabel}</span></div>
        <button className="icon-button" aria-label="Обновить статус" disabled={checking} onClick={() => void checkStatus()}>↻</button>
        {selected && screen === selectionContext && !detailsOpen && <button className="text-button" onClick={() => setDetailsOpen(true)}>Открыть детали</button>}
      </header>
      {error && <p className="error" role="alert">{error}</p>}
      <div className={`workspace-layout ${showDetails ? 'with-details' : ''}`}>
        <div className="workspace-content">
          {view('overview', <Dashboard active={screen === 'overview' && !checking} revision={dashboardRevision} eventConnected={eventConnected} eventAt={eventAt} navigate={navigate} openNotification={openNotification} openProject={(id, edit) => { setProjectIntent({ id, edit }); navigate('projects'); }} />)}
          {view('projects', <Projects active={screen === 'projects'} intent={projectIntent} />)}
          {view('sources', <Sources active={screen === 'sources'} sources={sources} unread={byChannel} refresh={refreshResearch} onMessages={source => void loadMessages(undefined, source)} onArchive={openArchive} onSelectedMessages={source => { setSourceIntentRevision(n => n + 1); setSelectedSourceKey(source.key); setSearchMode('sources'); setSelected(null); navigate('search'); }} onSelectedArchive={source => { setSourceIntentRevision(n => n + 1); setArchiveSourceKey(source.key); setSelected(null); navigate('archive'); }} />)}
          {view('templates', <div className="data-screen"><Templates templates={templates} channels={sources} refresh={refreshResearch} onRun={t => void runSearch(t)} onError={setError} /></div>)}
          {view('saved', <SavedResults />)}
          {view('bot', <BotIntegration />)}
          {view('mcp', <McpIntegration active={screen === 'mcp'} />)}
          {view('archive', <><div className="archive-actions"><button className="secondary-button" onClick={() => { setArchiveSourceKey(''); setSelected(null); }}>Выбрать сохранённый источник</button><button className="text-button" onClick={() => { setArchiveSourceKey(null); setSelected(null); }}>Архив публичного канала по username</button></div>{archiveSourceKey !== null ? <SelectedSourceArchive key={`${archiveSourceKey}:${sourceIntentRevision}`} initialKey={archiveSourceKey} authorized={!!status?.authorized} onSelect={m => void selectMessage(m, 'archive')} /> : <HistoryArchive key={archiveUsername} username={archiveUsername} autoStart={false} onSourceChange={name => { setUsername('@' + name); setArchiveUsername(name); if (selectionContext === 'archive') setSelected(null); }} authorized={!!status?.authorized} onSelect={m => void selectMessage(m, 'archive')} />}</>)}
          {view('notifications', <><section className="notification-preferences"><h2>Уведомления о новых совпадениях</h2><p className="helper">Мониторинг работает, пока запущен hradar. Для уведомлений браузера оставьте приложение открытым.</p><button className="secondary-button" disabled={desktopPermission !== 'default'} onClick={async () => { if ('Notification' in window) setDesktopPermission(await Notification.requestPermission()); }}>{desktopPermission === 'granted' ? 'Desktop-уведомления разрешены' : desktopPermission === 'denied' ? 'Уведомления запрещены в браузере' : desktopPermission === 'unsupported' ? 'Браузер не поддерживает уведомления' : 'Разрешить desktop-уведомления'}</button><p className="helper compact">{eventConnected ? 'Обновления подключены (SSE)' : 'SSE переподключается; обновите статус при длительном сбое.'}</p></section><Notifications items={notificationPage.items} unread={notificationPage.unread} cursor={notificationPage.cursor} onRead={id => void notificationAction(`/api/notifications/${id}/read`)} onReadAll={() => void notificationAction('/api/notifications/read-all')} onOpen={openNotification} onMore={() => void refreshNotifications(true).catch(e => setError((e as Error).message))} /></>)}
          {view('application', <section className="data-screen application-state"><h2>Подключение и работа приложения</h2><dl><dt>Версия hradar</dt><dd>{appVersion}</dd><dt>Telegram</dt><dd>{statusLabel}</dd><dt>Этап авторизации</dt><dd>{status?.authorizationState ?? 'Нет ответа'}</dd><dt>Соединение</dt><dd>{status?.connectionState ?? 'Нет ответа'}</dd><dt>Обновления уведомлений</dt><dd>{eventConnected ? 'Подключены' : 'Переподключение'}</dd></dl><p className="helper">Настройки и данные сохраняются локально. При завершении hradar мониторинг и доставка сообщений ботом остановятся.</p>{isDesktop() ? <button className="secondary-button" onClick={() => void shutdownDesktop().catch(e => setError((e as Error).message))}>Завершить hradar</button> : <p className="helper">Кнопка завершения доступна при запуске через desktop-приложение.</p>}</section>)}
          {view('search', <>
            <section className="online-search" aria-label="Онлайн-поиск"><h2>Поиск в Telegram</h2><p className="helper compact">Источник результатов: Telegram API. Локальный архив не используется.</p>
              <label htmlFor="search-mode">Область поиска</label><select id="search-mode" value={searchMode} onChange={e => setSearchMode(e.target.value as SearchInput['mode'])}><option value="sources">В сохранённых чатах и темах</option><option value="channel">В текущем канале</option><option value="tracked">В выбранных источниках</option><option value="global">Публичные публикации · глобально</option></select>
              {searchMode === 'sources' ? <SelectedSourceSearch key={`${selectedSourceKey}:${sourceIntentRevision}`} initialKey={selectedSourceKey} templates={templates} onSelect={m => void selectMessage(m)} /> : <>
              {searchMode === 'channel' && <><label htmlFor="search-source">Сохранённый источник</label><select id="search-source" value={sources.some(s => '@' + s.username === username) ? username.replace(/^@/, '') : ''} onChange={e => setUsername('@' + e.target.value)}><option value="" disabled>Или введите username ниже</option>{sources.map(s => <option value={s.username} key={s.id}>{s.title} · @{s.username}</option>)}</select><label htmlFor="username">Username канала</label><input id="username" value={username} onChange={e => setUsername(e.target.value)} placeholder="@telegram" /></>}
              <label htmlFor="search-query">Поисковый запрос</label><input id="search-query" value={query} onChange={e => setQuery(e.target.value)} placeholder="Java developer" maxLength={256} />
              <label htmlFor="limit">Сообщений</label><input id="limit" type="number" min="1" max="100" value={limit} onChange={e => setLimit(e.target.value)} disabled={loading} />
              {searchMode === 'tracked' && <div className="source-checkboxes">{sources.map(c => <label key={c.id}><input type="checkbox" checked={chosenSources.includes(c.username)} onChange={e => setChosenSources(old => e.target.checked ? [...old, c.username] : old.filter(n => n !== c.username))} />@{c.username}</label>)}</div>}
              {searchMode === 'global' && <p className="helper compact">Только бесплатный поиск. Stars не расходуются. Глобальные уведомления недоступны.</p>}
              <button className="primary-button" onClick={() => void runSearch()} disabled={loading || !status?.authorized || !query.trim()}>Искать в Telegram</button>
              {searchMode === 'channel' && <form className="recent-messages" onSubmit={e => void loadMessages(e)}><button className="secondary-button" disabled={loading || !status?.authorized}>{loading ? 'Загружаем…' : 'Получить сообщения'}</button><button type="button" className="text-button" onClick={() => openArchive(username)}>Настроить загрузку истории</button></form>}
              {!!templates.length && <div className="search-template"><label htmlFor="search-template">Шаблон поиска</label><select id="search-template" value={chosenTemplate} onChange={e => setChosenTemplate(e.target.value)}><option value="">Выберите шаблон</option>{templates.map(t => <option value={t.id} key={t.id}>{t.name}</option>)}</select><button className="secondary-button" disabled={loading || !status?.authorized || !templates.some(t => t.id === chosenTemplate && t.active)} onClick={() => { const t = templates.find(t => t.id === chosenTemplate); if (t) void runSearch(t); }}>Запустить шаблон</button></div>}
            </>}
            </section>
            {searchMode !== 'sources' && <>
            <div className="conversation-toolbar"><label className="filter"><span className="sr-only">Поиск по сообщениям</span><input type="search" placeholder="Поиск в загруженных сообщениях…" value={filter} onChange={e => setFilter(e.target.value)} /></label>{result && <span className="count">{visible.length} / {result.messages.length}</span>}<button className="text-button" onClick={() => { setFilter(''); setApplied(null); }} disabled={!filter && !applied}>Сбросить фильтр</button>{result?.username && !sources.some(s => s.username === result.username) && <button className="text-button" onClick={() => void saveSource()}>Сохранить источник</button>}</div>
            {searchPage?.notice && <p className="search-notice" role="status">{searchPage.notice}</p>}
            <section className="message-area" aria-label="Сообщения канала" aria-busy={loading}>{loading ? <Empty title="Получаем сообщения" text="Большая история может загружаться до 20 секунд." /> : !result ? <Empty title="Здесь появятся сообщения канала" text="Выберите сохранённый источник или введите username и загрузите публикации." /> : !result.messages.length && searchPage && ['unsupported', 'free_limit'].includes(searchPage.state) ? <Empty title="Глобальный поиск недоступен" text={searchPage.notice || 'Запрос недоступен для текущей сессии.'} /> : !result.messages.length ? <Empty title="В канале пока нет доступных сообщений" text="Попробуйте другой публичный канал." /> : !visible.length ? <Empty title="Совпадений не найдено" text="Измените запрос или очистите поиск."><button className="text-button" onClick={() => setFilter('')}>Очистить поиск</button></Empty> : <div className="message-list"><div className="date-divider">Последние публикации</div>{visible.map(message => <article className={`message-card ${selected?.id === message.id && selected.channelId === message.channelId ? 'selected' : ''}`} key={`${message.channelId}:${message.id}`}><div className="message-meta"><strong>{message.title || `@${message.username || result.username}`}</strong><time dateTime={new Date(message.date * 1000).toISOString()}>{dateFormat.format(message.date * 1000)}</time></div><p className={`message-text ${!message.text ? 'no-text' : ''}`}><Highlight text={message.text || 'Медиа или служебное сообщение без текста'} terms={[filter, ...(lastSearch?.query.split(/\s+/) ?? []), ...(applied?.required ?? []), ...(applied?.any ?? [])]} /></p>{message.explanation?.matches && <p className="match-reason">Совпали: {[...message.explanation.foundRequired, ...message.explanation.foundAny].join(', ')} · Исключения отсутствуют</p>}<div className="message-footer"><span>ID канала: {message.channelId}</span><button className="text-button" onClick={() => void selectMessage(message)}>Детали ↗</button></div></article>)}</div>}</section>
            {searchPage?.cursor && <button className="load-more" onClick={() => void runSearch(undefined, true)} disabled={loading}>Следующая страница</button>}
            <footer className="conversation-footer">{applied ? `Локальные условия: ${applied.name}` : 'Источник результатов: Telegram API · Фильтр только текущей выборки'}</footer></>}
          </>)}
        </div>
        {showDetails && <aside className="publication-details" aria-label="Детали публикации"><div className="publication-details-heading"><h2>Детали публикации</h2><button className="icon-button" aria-label="Закрыть детали" onClick={() => setDetailsOpen(false)}>×</button></div><dl><dt>Источник</dt><dd>{selected.title || `@${selected.username || result?.username}`}</dd><dt>Дата</dt><dd>{dateFormat.format(selected.date * 1000)}</dd><dt>ID сообщения</dt><dd>{selected.id}</dd><dt>ID канала</dt><dd>{selected.channelId}</dd>{(selected.scopeTitle || selected.forumTopicId) && <><dt>Тема</dt><dd>{selected.scopeTitle || `#${selected.forumTopicId}`}</dd></>}{selected.availability && selected.availability !== 'available' && <><dt>Доступность</dt><dd>{selected.availability === 'deleted' ? 'Сообщение удалено' : 'Сообщение недоступно'}</dd></>}</dl><p className="message-text">{selected.availability === 'deleted' ? 'Сообщение удалено' : selected.availability === 'unavailable' ? 'Сообщение недоступно' : selected.text || 'Медиа или служебное сообщение без текста'}</p>{selected.link && /^https:\/\/t\.me\//.test(selected.link) && <a className="original-link" href={selected.link} target="_blank" rel="noreferrer">Открыть в Telegram ↗</a>}<button className="secondary-button" onClick={async () => { try { await navigator.clipboard.writeText(selected.text || ''); } catch { setError('Не удалось скопировать текст. Выделите его вручную.'); } }}>Копировать текст</button></aside>}
      </div>
    </main>
  </div>;
}
function Empty({ title, text, children }: { title: string; text: string; children?: ReactNode }) { return <div className="empty-state"><span className="empty-symbol" aria-hidden="true">⌕</span><h3>{title}</h3><p>{text}</p>{children}</div>; }
void desktopLaunch().then(() => createRoot(document.getElementById('root')!).render(<App />)).catch(e => {
  const root = document.getElementById('root')!;
  root.textContent = (e as Error).message;
  const retry = document.createElement('button');
  retry.textContent = 'Повторить подключение';
  retry.onclick = () => location.reload();
  root.append(document.createElement('br'), retry);
});
