import { useEffect, useRef, useState } from 'react';
import { SourceBrowser, sourceMutation, sourceRequest, type TelegramSource, type SourcePage } from './SourceCatalog';
import { mutate, normalizeUsername } from './api';
import type { MonitoredChannel } from './api';

export function Sources({ sources, unread, refresh, onMessages, onArchive, onSelectedMessages, onSelectedArchive, active = true }: {
  active?: boolean;
  sources: MonitoredChannel[]; unread: Record<string, number>; refresh: () => Promise<void>;
  onSelectedMessages: (source: TelegramSource) => void; onSelectedArchive: (source: TelegramSource) => void;
  onMessages: (username: string) => void; onArchive: (username: string) => void;
}) {
  const [catalog, setCatalog] = useState<TelegramSource[]>([]);
  const [browser, setBrowser] = useState(false);
  const [catalogError, setCatalogError] = useState('');
  const catalogGeneration = useRef(0);
  const catalogRequest = useRef<AbortController | null>(null);
  async function refreshCatalog(signal?: AbortSignal) {
    catalogRequest.current?.abort();
    const c = new AbortController(); catalogRequest.current = c;
    const generation = ++catalogGeneration.current;
    try {
      const data = await sourceRequest<SourcePage>('/api/sources', signal ? AbortSignal.any([signal, c.signal]) : c.signal);
      if (!signal?.aborted && !c.signal.aborted && generation === catalogGeneration.current) { setCatalog(data.items); setCatalogError(''); }
    } catch { if (!signal?.aborted && !c.signal.aborted && generation === catalogGeneration.current) setCatalogError('Каталог недоступен. Существующие публичные источники остаются доступны.'); }
    finally { if (catalogRequest.current === c) catalogRequest.current = null; }
  }
  useEffect(() => { if (!active) return; const c = new AbortController(); void refreshCatalog(c.signal); return () => { c.abort(); catalogRequest.current?.abort(); catalogGeneration.current++; }; }, [active]);
  const added = async () => { await refreshCatalog(); await refresh(); };
  const [filter, setFilter] = useState('');
  const [username, setUsername] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  async function act(task?: () => Promise<unknown>) {
    if (busy) return;
    setBusy(true); setError('');
    try { if (task) await task(); await refresh(); await refreshCatalog(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  const visible = sources.filter(source => `${source.title} @${source.username}`.toLowerCase().includes(filter.toLowerCase()));
  return <section className="data-screen" aria-label="Управление источниками">
    <h2>Сохранённые Telegram-источники</h2><p className="helper">Выберите чаты своего Telegram-аккаунта или добавьте публичный канал по username. Ничего не добавляется автоматически; вступление в чаты и импорт истории не запускаются.</p>
    <button id="open-source-browser" className="secondary-button" onClick={() => setBrowser(true)}>Добавить из Telegram</button>
    {catalogError && <p role="alert" className="error">{catalogError}</p>}
    {browser && active && <SourceBrowser saved={catalog} onAdded={added} onClose={() => { setBrowser(false); requestAnimationFrame(() => document.getElementById('open-source-browser')?.focus()); }} />}
    <form className="source-add-form" onSubmit={event => { event.preventDefault(); void act(async () => { await mutate('/api/channels', 'POST', { username: normalizeUsername(username) }); setUsername(''); }); }}>
      <label htmlFor="new-source">Username нового источника</label><div><input id="new-source" value={username} onChange={e => setUsername(e.target.value)} placeholder="@telegram" required disabled={busy} /><button className="primary-button" disabled={busy || !username.trim()}>Добавить источник</button></div>
    </form>
    {error && <p className="error" role="alert">{error}</p>}
    <label htmlFor="source-filter">Найти источник</label><input id="source-filter" type="search" value={filter} onChange={e => setFilter(e.target.value)} />
    <button className="text-button" disabled={busy} onClick={() => void act()}>Обновить источники</button>
    {!visible.length && <p className="helper">{sources.length ? 'Источники не найдены. Измените фильтр.' : 'Публичных источников пока нет. Выберите чат из Telegram или добавьте канал по username.'}</p>}
    {visible.map(source => <article className="source-record" key={source.id}><div><h3>{source.title}</h3><p>@{source.username}</p><small>Telegram ID: {source.id} · Публичный канал</small>{unread[source.id] > 0 && <small>Новых совпадений: {unread[source.id]}</small>}</div>
      <div className="source-actions"><button className="secondary-button" onClick={() => onMessages(source.username)}>Последние сообщения</button><button className="secondary-button" onClick={() => onArchive(source.username)}>Открыть архив</button><button className="text-button danger" disabled={busy} onClick={() => { if (window.confirm('Удалить сохранённый источник?')) void act(() => mutate(`/api/channels/${source.id}`, 'DELETE')); }}>Удалить источник</button></div>
    </article>)}
    {catalog.filter(source => !source.legacyChannel && `${source.title} ${source.username ?? ''} ${source.scopeTitle ?? ''}`.toLowerCase().includes(filter.toLowerCase())).map(source => <article className="source-record" data-testid="saved-catalog-source" key={source.key}><div><h3>{source.title}</h3><p>{source.scopeTitle || 'Весь чат'} · {source.type === 'PRIVATE' ? 'Личная переписка' : source.type === 'CHANNEL' ? 'Канал' : 'Группа'} · {source.isPublic ? 'Публичный' : 'Приватный'}</p>{source.username && <p>@{source.username}</p>}<small>Telegram ID: {source.chatId}</small><p className="helper">{source.access === 'UNAVAILABLE' ? 'Доступ потерян' : source.access === 'UNKNOWN' ? 'Доступ к сообщениям не подтверждён' : 'Доступен по данным Telegram'}</p><p className="helper">Доступны чтение сообщений, поиск, ручной архив и проекты. Мониторинг выключен; бот и внешние помощники доступа не получают.</p></div><div className="source-actions"><button className="secondary-button" onClick={() => onSelectedMessages(source)}>Последние сообщения</button><button className="secondary-button" onClick={() => onSelectedArchive(source)}>Открыть архив</button><button className="secondary-button" disabled={busy} onClick={() => void act(() => sourceMutation(`/api/sources/${source.key}/refresh`, 'POST'))}>Проверить доступ</button><button className="text-button danger" disabled={busy} onClick={() => { if (window.confirm('Удалить источник из локального каталога?')) void act(() => sourceMutation(`/api/sources/${source.key}`, 'DELETE')); }}>Удалить из каталога</button></div></article>)}
  </section>;
}
