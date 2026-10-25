import { useEffect, useRef, useState } from 'react';
import './sourceCatalog.css';

export interface SourceScope { kind: 'CHAT' | 'FORUM_TOPIC' | 'MESSAGE_THREAD'; forumTopicId?: number | null; messageThreadId?: number | null }
export interface TelegramSource {
  key: string; chatId: number; type: 'PRIVATE' | 'BASIC_GROUP' | 'SUPERGROUP' | 'CHANNEL'; title: string;
  username?: string | null; scope: SourceScope; scopeTitle?: string | null; isPublic: boolean;
  access: 'AVAILABLE' | 'UNKNOWN' | 'UNAVAILABLE'; isForum: boolean; protectedContent: boolean;
  legacyChannel: boolean; monitoringEnabled: boolean;
}
export interface SourcePage { items: TelegramSource[]; cursor?: string | null; notice?: string | null }
interface TopicCursor { date: number; messageId: number; forumTopicId: number }
interface TopicPage { items: TelegramSource[]; next: TopicCursor | null }
type Filter = 'all' | 'private' | 'groups' | 'channels';
export function sameSource(a: TelegramSource, b: TelegramSource) {
  return a.chatId === b.chatId && a.scope.kind === b.scope.kind &&
    (a.scope.kind === 'FORUM_TOPIC' ? a.scope.forumTopicId === b.scope.forumTopicId :
      a.scope.kind === 'MESSAGE_THREAD' ? a.scope.messageThreadId === b.scope.messageThreadId : true);
}
const sourceId = (source: TelegramSource) => `${source.chatId}:${source.scope.kind}:${source.scope.forumTopicId ?? source.scope.messageThreadId ?? 0}`;
const types = { PRIVATE: 'Личный чат', BASIC_GROUP: 'Группа', SUPERGROUP: 'Супергруппа', CHANNEL: 'Канал' };
const accesses = { AVAILABLE: 'Доступен', UNKNOWN: 'Доступ не подтверждён', UNAVAILABLE: 'Недоступен' };
export async function sourceRequest<T>(path: string, signal?: AbortSignal, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, { ...init, signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(25_000)]) : AbortSignal.timeout(25_000) });
  if (!response.ok) throw new Error(response.status === 429 ? 'Telegram ограничил частоту запросов. Повторите позже.' :
    response.status === 403 ? 'Доступ запрещён. Обновите страницу и проверьте доступ к источнику.' :
      response.status === 503 ? 'Telegram недоступен. Проверьте подключение и авторизацию.' : 'Не удалось выполнить запрос. Повторите попытку.');
  return response.json() as Promise<T>;
}
export async function sourceMutation<T>(path: string, method: 'POST' | 'DELETE', body?: unknown, signal?: AbortSignal) {
  const auth = await sourceRequest<{ csrfToken: string }>('/api/onboarding', signal);
  if (!auth.csrfToken) throw new Error('Не удалось подтвердить запрос. Обновите страницу.');
  return sourceRequest<T>(path, signal, { method, headers: { 'Content-Type': 'application/json', 'X-Hradar-CSRF': auth.csrfToken }, body: body === undefined ? undefined : JSON.stringify(body) });
}

export function SourceBrowser({ saved, onAdded, onClose }: { saved: TelegramSource[]; onAdded: () => Promise<void>; onClose: () => void }) {
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const [appliedQuery, setAppliedQuery] = useState('');
  const [revision, setRevision] = useState(0);
  const [page, setPage] = useState<SourcePage>({ items: [] });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [saving, setSaving] = useState(false);
  const [added, setAdded] = useState<TelegramSource[]>([]);
  const [forum, setForum] = useState<TelegramSource | null>(null);
  const [topics, setTopics] = useState<TopicPage>({ items: [], next: null });
  const [topicLoading, setTopicLoading] = useState(false);
  const [topicError, setTopicError] = useState('');
  const catalogRequest = useRef<AbortController | null>(null);
  const topicRequest = useRef<AbortController | null>(null);
  const mutationRequest = useRef<AbortController | null>(null);
  const catalogGeneration = useRef(0);
  const topicGeneration = useRef(0);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { heading.current?.focus(); return () => { catalogRequest.current?.abort(); topicRequest.current?.abort(); mutationRequest.current?.abort(); }; }, []);

  async function loadCatalog(cursor?: string) {
    catalogRequest.current?.abort();
    const controller = new AbortController(); catalogRequest.current = controller;
    const generation = ++catalogGeneration.current;
    setLoading(true); setError('');
    if (!cursor) { setPage({ items: [] }); setForum(null); topicRequest.current?.abort(); ++topicGeneration.current; }
    const parameters = new URLSearchParams({ query: appliedQuery, filter });
    if (cursor) parameters.set('cursor', cursor);
    try {
      const result = await sourceRequest<SourcePage>(`/api/source-catalog?${parameters}`, controller.signal);
      if (generation !== catalogGeneration.current || controller.signal.aborted) return;
      setPage(previous => ({ ...result, items: cursor ? [...previous.items, ...result.items.filter(item => !previous.items.some(old => sameSource(old, item)))] : result.items }));
    } catch (cause) { if (!controller.signal.aborted && generation === catalogGeneration.current) setError(cause instanceof Error && cause.message.startsWith('Telegram') ? cause.message : 'Не удалось загрузить источники. Проверьте подключение и повторите попытку.'); }
    finally { if (generation === catalogGeneration.current && !controller.signal.aborted) setLoading(false); }
  }
  useEffect(() => { void loadCatalog(); return () => { catalogRequest.current?.abort(); }; }, [filter, appliedQuery, revision]);

  async function loadTopics(source: TelegramSource, cursor?: TopicCursor) {
    topicRequest.current?.abort();
    const controller = new AbortController(); topicRequest.current = controller;
    const generation = ++topicGeneration.current;
    setForum(source); setTopicLoading(true); setTopicError('');
    if (!cursor) setTopics({ items: [], next: null });
    const parameters = new URLSearchParams(Object.entries(cursor ?? { date: 0, messageId: 0, forumTopicId: 0 }).map(([key, value]) => [key, String(value)]));
    try {
      const result = await sourceRequest<TopicPage>(`/api/source-catalog/${source.chatId}/topics?${parameters}`, controller.signal);
      if (generation !== topicGeneration.current || controller.signal.aborted) return;
      setTopics(previous => ({ ...result, items: cursor ? [...previous.items, ...result.items.filter(item => !previous.items.some(old => sameSource(old, item)))] : result.items }));
    } catch { if (!controller.signal.aborted && generation === topicGeneration.current) setTopicError('Не удалось загрузить темы. Повторите попытку.'); }
    finally { if (generation === topicGeneration.current && !controller.signal.aborted) setTopicLoading(false); }
  }
  async function save(source: TelegramSource) {
    if (mutationRequest.current) return;
    if (source.scope.kind === 'MESSAGE_THREAD') { setError('Сохранение веток сообщений пока недоступно.'); return; }
    const controller = new AbortController(); mutationRequest.current = controller;
    setSaving(true); setError(''); setNotice('');
    const scope = source.scope.kind === 'FORUM_TOPIC' ? { kind: 'FORUM_TOPIC', forumTopicId: source.scope.forumTopicId } : { kind: 'CHAT' };
    try {
      await sourceMutation('/api/sources', 'POST', { chatId: source.chatId, scope }, controller.signal);
      if (controller.signal.aborted) return;
      setAdded(previous => [...previous, source]); setNotice(source.access === 'UNKNOWN'
        ? 'Источник сохранён. Доступ к сообщениям не подтверждён. Чтение сообщений и мониторинг не запускались.'
        : 'Источник сохранён. Чтение сообщений и мониторинг не запускались.');
      try { await onAdded(); } catch { if (!controller.signal.aborted) setError('Источник сохранён, но список не обновился. Обновите сохранённые источники.'); }
    } catch { if (!controller.signal.aborted) setError('Не удалось сохранить источник. Проверьте доступ и повторите попытку.'); }
    finally {
      if (mutationRequest.current === controller) mutationRequest.current = null;
      if (!controller.signal.aborted) setSaving(false);
    }
  }
  function row(source: TelegramSource, topic = false) {
    const exists = [...saved, ...added].some(item => sameSource(item, source));
    return <article className="catalog-record" data-testid={topic ? 'catalog-topic' : 'catalog-source'} key={sourceId(source)}>
      <div className="catalog-description"><h4>{topic ? source.scopeTitle ?? source.title : source.title}</h4>
        {source.username && <p>@{source.username}</p>}
        <p className="catalog-metadata">{types[source.type]} · {source.isPublic ? 'Публичный' : 'Непубличный'} · {accesses[source.access]}{source.isForum ? ' · Форум' : ''}{source.protectedContent ? ' · Защищённый контент' : ''}</p>
        {source.access === 'UNKNOWN' && <p>Можно сохранить описание. Доступ к сообщениям не подтверждён.</p>}
        {source.isForum && !topic && <small>Весь форум и отдельная тема сохраняются отдельно.</small>}
      </div>
      <div className="catalog-actions"><button className="secondary-button" disabled={saving || exists || source.access === 'UNAVAILABLE' || source.scope.kind === 'MESSAGE_THREAD'} onClick={() => void save(source)}>{exists ? 'Уже сохранён' : source.isForum && !topic ? 'Сохранить весь форум' : topic ? 'Сохранить тему' : 'Сохранить источник'}</button>
        {source.isForum && !topic && <button className="text-button" aria-expanded={forum?.chatId === source.chatId} disabled={saving || source.access === 'UNAVAILABLE'} onClick={() => {
          if (forum?.chatId === source.chatId) { topicRequest.current?.abort(); ++topicGeneration.current; setForum(null); } else void loadTopics(source);
        }}>Выбрать тему</button>}
      </div>
      {!topic && forum?.chatId === source.chatId && <section className="catalog-topics" aria-label={`Темы: ${source.title}`}>
        <h4>Темы форума</h4>{topicLoading && <p role="status">Загрузка тем…</p>}{topicError && <p role="alert">{topicError} <button className="text-button" onClick={() => void loadTopics(source)}>Повторить загрузку тем</button></p>}
        {topics.items.map(item => row(item, true))}
        {!topicLoading && !topicError && !topics.items.length && <p className="helper">Темы не найдены.</p>}
        {topics.next && <button className="secondary-button" disabled={topicLoading} onClick={() => void loadTopics(source, topics.next!)}>Ещё темы</button>}
      </section>}
    </article>;
  }
  return <section className="source-browser" aria-label="Выбор Telegram-источника" onKeyDown={event => { if (event.key === 'Escape' && !saving) { event.stopPropagation(); onClose(); } }}>
    <header><h3 ref={heading} tabIndex={-1}>Добавить из Telegram</h3><button className="text-button" disabled={saving} onClick={onClose}>Закрыть выбор источника</button></header>
    <p className="helper">Выберите доступный чат или тему. Сохраняется только описание источника. Чтение сообщений, поиск, архив, CRM и мониторинг для новых типов источников пока недоступны. Добавление не вступает в чат и не импортирует историю.</p>
    <form className="catalog-search" onSubmit={event => { event.preventDefault(); setAppliedQuery(query.trim()); setRevision(value => value + 1); }}>
      <div><label htmlFor="catalog-query">Поиск в Telegram</label><input id="catalog-query" type="search" value={query} onChange={event => setQuery(event.target.value)} placeholder="Название чата или username" /></div>
      <div><label htmlFor="catalog-filter">Тип чата</label><select id="catalog-filter" value={filter} onChange={event => setFilter(event.target.value as Filter)}><option value="all">Все чаты</option><option value="private">Личные чаты</option><option value="groups">Группы</option><option value="channels">Каналы</option></select></div>
      <button className="primary-button" type="submit">Найти чаты</button>
    </form>
    {error && <p role="alert" className="error">{error}</p>}{error && !page.items.length && <button className="text-button" disabled={loading} onClick={() => void loadCatalog()}>Повторить загрузку источников</button>}
    {notice && <p role="status" className="helper">{notice}</p>}{page.notice && <p className="helper">{page.notice}</p>}
    {loading && <p role="status">Загрузка источников…</p>}
    {page.items.map(source => row(source))}
    {!loading && !error && !page.items.length && <p className="helper">В этой выборке чаты не найдены. Проверьте следующие страницы или измените запрос.</p>}
    {page.cursor && <button className="secondary-button" disabled={loading} onClick={() => void loadCatalog(page.cursor!)}>Ещё чаты</button>}
  </section>;
}
