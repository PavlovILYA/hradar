import { useState } from 'react';
import { mutate, normalizeUsername } from './api';
import type { MonitoredChannel } from './api';

export function Sources({ sources, unread, refresh, onMessages, onArchive }: {
  sources: MonitoredChannel[]; unread: Record<string, number>; refresh: () => Promise<void>;
  onMessages: (username: string) => void; onArchive: (username: string) => void;
}) {
  const [filter, setFilter] = useState('');
  const [username, setUsername] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  async function act(task: () => Promise<unknown>) {
    if (busy) return;
    setBusy(true); setError('');
    try { await task(); await refresh(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  const visible = sources.filter(source => `${source.title} @${source.username}`.toLowerCase().includes(filter.toLowerCase()));
  return <section className="data-screen" aria-label="Управление источниками">
    <h2>Сохранённые Telegram-источники</h2><p className="helper">Сохраняйте публичные каналы для поиска, проектов и мониторинга. Добавление источника не вступает в канал и не запускает импорт истории.</p>
    <form className="source-add-form" onSubmit={event => { event.preventDefault(); void act(async () => { await mutate('/api/channels', 'POST', { username: normalizeUsername(username) }); setUsername(''); }); }}>
      <label htmlFor="new-source">Username нового источника</label><div><input id="new-source" value={username} onChange={e => setUsername(e.target.value)} placeholder="@telegram" required disabled={busy} /><button className="primary-button" disabled={busy || !username.trim()}>Добавить источник</button></div>
    </form>
    {error && <p className="error" role="alert">{error}</p>}
    <label htmlFor="source-filter">Найти источник</label><input id="source-filter" type="search" value={filter} onChange={e => setFilter(e.target.value)} />
    <button className="text-button" disabled={busy} onClick={() => void refresh().catch(e => setError((e as Error).message))}>Обновить источники</button>
    {!visible.length && <p className="helper">{sources.length ? 'Источники не найдены. Измените фильтр.' : 'Сохранённых источников пока нет. Добавьте публичный канал по username.'}</p>}
    {visible.map(source => <article className="source-record" key={source.id}><div><h3>{source.title}</h3><p>@{source.username}</p>{unread[source.id] > 0 && <small>Новых совпадений: {unread[source.id]}</small>}</div>
      <div className="source-actions"><button className="secondary-button" onClick={() => onMessages(source.username)}>Последние сообщения</button><button className="secondary-button" onClick={() => onArchive(source.username)}>Открыть архив</button><button className="text-button danger" disabled={busy} onClick={() => { if (window.confirm('Удалить сохранённый источник?')) void act(() => mutate(`/api/channels/${source.id}`, 'DELETE')); }}>Удалить источник</button></div>
    </article>)}
  </section>;
}
