import { Onboarding, desktopLaunch, shutdownDesktop, isDesktop } from './Onboarding';
import { Projects, type ProjectIntent } from './Projects';
import { Dashboard } from './Dashboard';
import { createRoot } from 'react-dom/client';
import { useEffect, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { normalizeUsername, request, mutate } from './api';
import { Templates } from './Templates';
import { Notifications } from './Notifications';
import { ChatSearch } from './ChatSearch';
import { NotificationPreferences } from './NotificationPreferences';
import { ownerMutation } from './ownerApi';
import { PublicationDetails } from './PublicationDetails';
import type { TelegramSource } from './SourceCatalog';
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
import './design-system.css';
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
  const [error, setErrorState] = useState('');
  const [notice, setNotice] = useState('');
  const [result, setResult] = useState<{ username: string; messages: ResearchMessage[] } | null>(null);
  const [filter, setFilter] = useState('');
  const [screen, setScreen] = useState<Screen>(() => (['overview', 'application', 'search', 'notifications', 'templates', 'saved', 'projects', 'archive'].includes(new URLSearchParams(location.search).get('screen') ?? '') ? new URLSearchParams(location.search).get('screen') : 'overview') as Screen);
  const currentScreen = useRef(screen);
  const selectionEpoch = useRef(0);
  function setError(message: string, context: Screen = screen) { if (!message || currentScreen.current === context) setErrorState(message); }
  const [visited, setVisited] = useState<Set<Screen>>(() => new Set([screen]));
  const [navigationOpen, setNavigationOpen] = useState(false);
  const [archiveUsername, setArchiveUsername] = useState('telegram');
  const [sourceReading, setSourceReading] = useState(false);
  const [selectedSourceKey, setSelectedSourceKey] = useState('');
  const [sourceIntentRevision, setSourceIntentRevision] = useState(0);
  const [archiveSourceKey, setArchiveSourceKey] = useState<string | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  function navigate(next: Screen) {
    setError(''); setNotice(''); setDetailsOpen(false); selectionEpoch.current++; currentScreen.current = next; setScreen(next); setVisited(old => old.has(next) ? old : new Set([...old, next])); setNavigationOpen(false);
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
  const notificationRequest = useRef(0);
  async function refreshNotifications(more = false) {
    const revision = ++notificationRequest.current;
    const page = await request<NotificationPage>(`/api/notifications${more && notificationPage.cursor ? `?before=${notificationPage.cursor}` : ''}`);
    if (revision === notificationRequest.current) setNotificationPage(old => ({ ...page, items: more ? [...new Map([...old.items, ...page.items].map(item => [item.id, item])).values()] : page.items }));
  }
  useEffect(() => { void refreshNotifications().catch(e => { if (currentScreen.current === 'notifications') setError((e as Error).message, 'notifications'); }); }, []);
  useEffect(() => {
    const events = new EventSource('/api/events');
    let lastRevision: string | null = null;
    let claiming = false;
    let claimPending = false;
    let refreshPending = false;
    let latestRevision = '';
    async function claimDeliveries() {
      if (claiming) return;
      claiming = true;
      try {
        while (claimPending && !stopped) {
          claimPending = false;
          const deliveries = await ownerMutation<{ id: string; title: string; body: string }[]>('/api/workspace/deliveries/claim', 'POST');
          if (stopped) return;
          for (const item of deliveries) {
            try {
              const notice = new Notification(`hradar · ${item.title}`, { body: item.body, tag: item.id });
              notice.onclick = () => { window.focus(); void request<NotificationRecord>(`/api/workspace/inbox/${item.id}`).then(openNotification).catch(() => {}); notice.close(); };
            } catch { /* Durable claim owns at-most-once delivery; the inbox survives permission changes. */ }
          }
        }
      } catch { /* A later summary retries. No delivery is claimed locally. */ }
      finally { claiming = false; }
    }
    async function refreshInbox() {
      if (fetching) return;
      fetching = true;
      try {
        while (refreshPending && !stopped) {
          refreshPending = false; const revision = latestRevision;
          const requestRevision = ++notificationRequest.current;
          const page = await request<NotificationPage>('/api/notifications');
          if (stopped) return;
          if (requestRevision === notificationRequest.current) { setNotificationPage(page); setDashboardRevision(n => n + 1); lastRevision = revision; } else lastRevision = null;
        }
      } catch { lastRevision = null; }
      finally { fetching = false; }
    }
    let stopped = false;
    let fetching = false;
    events.onopen = () => { if (!stopped) setEventConnected(true); };
    events.onmessage = async e => {
      let summary: NotificationSummary;
      try { summary = JSON.parse(e.data) as NotificationSummary; } catch { return; }
      if (stopped) return;
      setEventConnected(true); setEventAt(Date.now()); setByChannel(summary.byChannel);
      if (summary.telegram) { setStatus(summary.telegram); setStatusAt(Date.now()); }
      if ('Notification' in window && Notification.permission === 'granted') {
        claimPending = true; void claimDeliveries();
      }
      latestRevision = `${summary.revision}:${summary.unread}`;
      if (latestRevision !== lastRevision) { refreshPending = true; void refreshInbox(); }
    };
    events.onerror = () => setEventConnected(false);
    return () => { stopped = true; events.close(); };
  }, []);
  async function notificationAction(path: string | string[]) {
    try { for (const target of typeof path === 'string' ? [path] : path) await mutate(target, 'POST'); await refreshNotifications(); setDashboardRevision(n => n + 1); }
    catch (e) { setError((e as Error).message); }
  }
  async function selectMessage(message: ResearchMessage, context: 'search' | 'archive' = 'search') {
    selectionEpoch.current++; setSelected(message); setSelectionContext(context); setDetailsOpen(true);
  }

  function openSelectedSource(source: TelegramSource, archive = false) {
    setSourceIntentRevision(n => n + 1); setSelected(null); setDetailsOpen(false);
    if (archive) { setArchiveSourceKey(source.key); navigate('archive'); }
    else { setSelectedSourceKey(source.key); setSourceReading(true); setChatTemplateId(''); setSearchMode('sources'); navigate('search'); }
  }
  async function openNotification(item: NotificationRecord, related: NotificationRecord[] = [item]) {
    const epoch = ++selectionEpoch.current; const context = currentScreen.current;
    if (related.some(i => !i.read)) {
      try { for (const rule of related.filter(i => !i.read)) await mutate(`/api/notifications/${rule.id}/read`, 'POST'); await refreshNotifications(); setDashboardRevision(n => n + 1); }
      catch(e) { setError((e as Error).message, context); return; }
    }
    if (epoch !== selectionEpoch.current) return;
    void selectMessage(item.message);
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
  const [searchMode, setSearchMode] = useState<SearchInput['mode']>('sources');
  const [query, setQuery] = useState('');
  const [chosenSources, setChosenSources] = useState<string[]>([]);
  const [searchPage, setSearchPage] = useState<SearchPage | null>(null);
  const [lastSearch, setLastSearch] = useState<SearchInput | null>(null);
  async function runSearch(template?: SearchTemplate, next = false) {
    if (template?.sourceMode === 'chats' || (template?.sourceMode !== 'global' && template?.sourceKeys?.length)) { setSourceReading(false); setChatTemplateId(template.id); setSearchMode('sources'); setSourceIntentRevision(n => n + 1); navigate('search'); return; }
    if (loading) return;
    navigate('search');
    const input: SearchInput = next && lastSearch ? { ...lastSearch, cursor: searchPage?.cursor } : {
      mode: template ? (template.sourceMode === 'global' ? 'global' : 'tracked') : searchMode, query: template?.telegramQuery ?? query, usernames: template ? [] : searchMode === 'channel' ? [username] : chosenSources,
      templateId: template?.id, withLinks: false, limit: Number(limit),
    };
    if (template) { setChatTemplateId(''); setQuery(template.telegramQuery); setChosenSources(sources.filter(s => template.sourceMode === 'all' || template.sources.includes(s.id)).map(s => s.username)); setSourceReading(false); setSearchMode(template.sourceMode === 'global' ? 'global' : template.sourceKeys?.length ? 'sources' : 'tracked'); }
    setLoading(true); setError(''); setSelected(null);
    if (!next) { setResult(null); setFilter(''); setApplied(template ?? null); setExplanations({}); setSearchPage(null); }
    try {
      const page = await mutate<SearchPage>('/api/search', 'POST', input, 95_000);
      setSearchPage(page); setLastSearch(input);
      setResult(old => ({ username: input.mode === 'channel' && !template ? username.replace(/^@/, '') : '', messages: next ? [...new Map([...(old?.messages ?? []), ...page.messages].map(m => [`${m.channelId}:${m.id}`, m])).values()] : page.messages }));
      setExplanations(old => ({ ...(next ? old : {}), ...Object.fromEntries(page.messages.filter(m => m.explanation).map(m => [m.id, m.explanation!])) }));
    } catch (e) { setError((e as Error).message, 'search'); }
    finally { setLoading(false); }
  }
  const [selected, setSelected] = useState<ResearchMessage | null>(null);
  const [theme, setTheme] = useState(() => localStorage.getItem('hradar-theme') || 'light');
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [selectionContext, setSelectionContext] = useState<'search' | 'archive'>('search');
  const [chatTemplateId, setChatTemplateId] = useState('');
  const [chosenTemplate, setChosenTemplate] = useState('');
  useEffect(() => { document.documentElement.dataset.theme = theme; localStorage.setItem('hradar-theme', theme); }, [theme]);
  async function checkStatus(signal?: AbortSignal) {
    setChecking(true);
    try { setStatus(await request<TelegramStatus>('/api/telegram/status', signal)); setStatusAt(Date.now()); }
    catch (error) { if (!signal?.aborted) { setStatus(null); setError((error as Error).message); } }
    finally { if (!signal?.aborted) setChecking(false); }
  }
  useEffect(() => { const c = new AbortController(); void checkStatus(c.signal); return () => c.abort(); }, []);
  async function loadMessages(event?: FormEvent, source = username) {
    event?.preventDefault();
    setSearchMode('channel'); setSourceReading(false); navigate('search');
    if (loading) return;
    setError('');
    let channel: string;
    try { channel = normalizeUsername(source); }
    catch (error) { setError((error as Error).message, 'search'); return; }
    const count = Number(limit);
    if (!Number.isInteger(count) || count < 1 || count > 100) { setError('Количество сообщений должно быть целым числом от 1 до 100.'); return; }
    setLoading(true); setResult(null); setSearchPage(null); setLastSearch(null); setApplied(null); setExplanations({}); setSelected(null); setFilter(''); setUsername('@' + channel);
    try {
      const messages = await request<TelegramMessage[]>(`/api/telegram/chats/${encodeURIComponent(channel)}/messages?limit=${count}`);
      setResult({ username: channel, messages });
    } catch (error) { setError((error as Error).message, 'search'); }
    finally { setLoading(false); }
  }
  const visible = result?.messages.filter(m => (m.text ?? '').toLocaleLowerCase('ru').includes(filter.toLocaleLowerCase('ru')) && (!applied || (m.explanation ?? explanations[m.id])?.matches)) ?? [];
  const statusFresh = !!statusAt && statusClock - statusAt < 60_000;
  const statusLabel = checking ? 'Проверяем соединение' : status && !statusFresh ? 'Нет актуальных данных о Telegram' : status?.authorized ? (status.connectionState === 'Ready' ? 'Telegram подключён' : status.connectionState ? 'Telegram переподключается' : 'Состояние Telegram неизвестно') : status ? 'Требуется вход в Telegram' : 'Нет связи с hradar';
  if (status && !status.authorized) return <Onboarding onReady={() => void checkStatus()} />;
  const showDetails = detailsOpen && selected;
  function view(id: Screen, children: ReactNode) {
    return visited.has(id) ? <section key={id} id={`screen-${id}`} className={`workspace-screen ${id === 'search' ? 'search-screen' : ''}`} aria-label={screenTitles[id]} hidden={screen !== id}>{children}</section> : null;
  }
  return <div className="workspace-shell" onKeyDown={e => { if (e.key === 'Escape' && !e.defaultPrevented) setDetailsOpen(false); }}>
    <Navigation screen={screen} navigate={next => next === 'archive' ? openArchive() : navigate(next)} unread={notificationPage.unread} theme={theme} toggleTheme={() => setTheme(theme === 'dark' ? 'light' : 'dark')} open={navigationOpen} close={closeNavigation} />
    <main className="workspace-main" inert={navigationOpen || undefined}>
      <header className="workspace-header"><button id="navigation-toggle" className="navigation-toggle icon-button" aria-label="Открыть навигацию" aria-expanded={navigationOpen} aria-controls="workspace-navigation" onClick={() => setNavigationOpen(true)}>☰</button>
        <div><h1 ref={heading} tabIndex={-1}>{screenTitles[screen]}</h1><span role="status" className={`connection-status ${statusFresh && status?.authorized && status.connectionState === 'Ready' ? 'online' : ''}`}>{statusLabel}</span></div>
        <details className={`update-status ${eventConnected ? 'connected' : 'reconnecting'}`}><summary aria-label={eventConnected ? 'Обновления подключены' : 'Обновления переподключаются'} title={eventConnected ? 'Обновления подключены' : 'Обновления переподключаются'}><span aria-hidden="true">●</span></summary><div role="note">{eventConnected ? 'Новые совпадения обновляются автоматически.' : 'Соединение обновлений восстанавливается. Совпадения сохраняются; можно обновить список вручную.'}<button className="text-button" onClick={() => navigate('application')}>Состояние приложения</button></div></details>
        <button className="icon-button" aria-label="Обновить статус" disabled={checking} onClick={() => void checkStatus()}>↻</button>
        {selected && !detailsOpen && <button className="text-button" onClick={() => setDetailsOpen(true)}>Открыть детали</button>}
      </header>
      {error && <div className="error dismissible" role="alert"><span>{error}</span><button className="icon-button" aria-label="Закрыть ошибку" onClick={() => setError('')}>×</button></div>}
      {notice && <p className="workspace-notice" role="status">{notice}</p>}
      <div className="workspace-layout">
        <div className="workspace-content">
          {view('overview', <Dashboard active={screen === 'overview' && !checking} revision={dashboardRevision} eventConnected={eventConnected} eventAt={eventAt} navigate={navigate} openNotification={openNotification} openProject={(id, edit) => { setProjectIntent({ id, edit }); navigate('projects'); }} />)}
          {view('projects', <Projects active={screen === 'projects'} intent={projectIntent} onSelect={m => void selectMessage(m)} />)}
          {view('sources', <Sources templates={templates} active={screen === 'sources'} sources={sources} unread={byChannel} refresh={refreshResearch} onMessages={source => void loadMessages(undefined, source)} onArchive={openArchive} onSelectedMessages={source => openSelectedSource(source)} onSelectedArchive={source => openSelectedSource(source, true)} />)}
          {view('templates', <div className="data-screen"><Templates templates={templates} channels={sources} refresh={refreshResearch} onRun={t => void runSearch(t)} onError={setError} /></div>)}
          {view('saved', <SavedResults onSelect={m => void selectMessage(m)} active={screen === 'saved'} />)}
          {view('bot', <BotIntegration />)}
          {view('mcp', <McpIntegration active={screen === 'mcp'} />)}
          {view('archive', <><div className="archive-actions"><button className="secondary-button" onClick={() => { setArchiveSourceKey(''); setSelected(null); }}>Выбрать сохранённый источник</button><button className="text-button" onClick={() => { setArchiveSourceKey(null); setSelected(null); }}>Архив публичного канала по username</button></div>{archiveSourceKey !== null ? <SelectedSourceArchive key={`${archiveSourceKey}:${sourceIntentRevision}`} initialKey={archiveSourceKey} authorized={!!status?.authorized} onSelect={m => void selectMessage(m, 'archive')} /> : <HistoryArchive key={archiveUsername} username={archiveUsername} autoStart={false} onSourceChange={name => { setUsername('@' + name); setArchiveUsername(name); if (selectionContext === 'archive') setSelected(null); }} authorized={!!status?.authorized} onSelect={m => void selectMessage(m, 'archive')} />}</>)}
          {view('notifications', <><details className="delivery-settings"><summary>Всплывающие уведомления</summary><NotificationPreferences /></details><Notifications items={notificationPage.items} unread={notificationPage.unread} cursor={notificationPage.cursor} onRead={ids => void notificationAction(ids.map(id => `/api/notifications/${id}/read`))} onRefresh={() => void refreshNotifications().catch(e => setError((e as Error).message))} onReadAll={() => void notificationAction('/api/notifications/read-all')} onOpen={openNotification} onMore={() => void refreshNotifications(true).catch(e => setError((e as Error).message))} /></>)}
          {view('application', <section className="data-screen application-state"><h2>Подключение и работа приложения</h2><dl><dt>Версия hradar</dt><dd>{appVersion}</dd><dt>Telegram</dt><dd>{statusLabel}</dd><dt>Этап авторизации</dt><dd>{status?.authorizationState ?? 'Нет ответа'}</dd><dt>Соединение</dt><dd>{status?.connectionState ?? 'Нет ответа'}</dd><dt>Обновления уведомлений</dt><dd>{eventConnected ? 'Подключены' : 'Переподключение'}</dd></dl><p className="helper">Настройки и данные сохраняются локально. При завершении hradar мониторинг и доставка сообщений ботом остановятся.</p>{isDesktop() ? <button className="secondary-button" onClick={() => void shutdownDesktop().catch(e => setError((e as Error).message))}>Завершить hradar</button> : <p className="helper">Кнопка завершения доступна при запуске через desktop-приложение.</p>}</section>)}
          {view('search', <>
            <section className="online-search" aria-label="Онлайн-поиск">{searchMode !== 'sources' && <><h2>Поиск в Telegram</h2><p className="helper compact">Источник результатов: Telegram API. Локальный архив не используется.</p></>}
              <label htmlFor="search-mode">Область поиска</label><select id="search-mode" value={searchMode} onChange={e => { setSourceReading(false); setSearchMode(e.target.value as SearchInput['mode']); }}><option value="sources">В чатах Telegram</option><option value="channel">В текущем канале</option><option value="tracked">В выбранных источниках</option><option value="global">Публичные публикации · глобально</option></select>
              {searchMode === 'sources' ? sourceReading ? <SelectedSourceSearch key={`${selectedSourceKey}:${sourceIntentRevision}`} initialKey={selectedSourceKey} templates={templates} onSelect={m => void selectMessage(m)} /> : <ChatSearch key={`${selectedSourceKey}:${sourceIntentRevision}:${chatTemplateId}`} initialKey={selectedSourceKey} templateId={chatTemplateId} templates={templates} refresh={refreshResearch} onSavedSearch={() => { navigate('templates'); setNotice('Поиск сохранён. Его настройки и отслеживание доступны ниже.'); }} onSelect={m => void selectMessage(m)} /> : <>
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

      </div>
    </main>
    {showDetails && <PublicationDetails key={`${selected.channelId}:${selected.id}:${selected.sourceKey ?? ''}`} message={selected} onClose={() => setDetailsOpen(false)} onMessages={source => openSelectedSource(source)} onArchive={source => openSelectedSource(source, true)} />}
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
