import { useEffect, useRef, useState } from 'react';
import { sameSource, sourceMutation, sourceRequest } from './SourceCatalog';
import type { SourcePage, TelegramSource } from './SourceCatalog';

export function ChatPicker({ keys, onChange, disabled = false, maxScopes = 10, onBusyChange }: { keys: string[]; onChange: (keys: string[]) => void; disabled?: boolean; maxScopes?: number; onBusyChange?: (busy: boolean) => void }) {
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('all');
  const [page, setPage] = useState<SourcePage>({ items: [] });
  const [saved, setSaved] = useState<TelegramSource[]>([]);
  const [pending, setPending] = useState<TelegramSource[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => { onBusyChange?.(busy); return () => onBusyChange?.(false); }, [busy, onBusyChange]);
  const [topics, setTopics] = useState<TelegramSource[]>([]);
  const [topicPage, setTopicPage] = useState<{ chatId: number; next: { date: number; messageId: number; forumTopicId: number } | null } | null>(null);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => { const c = new AbortController(); sourceRequest<SourcePage>('/api/sources', c.signal).then(p => setSaved(p.items)).catch(() => {}); return () => { c.abort(); controller.current?.abort(); }; }, []);
  async function load(cursor?: string) {
    controller.current?.abort(); const c = new AbortController(); controller.current = c;
    setBusy(true); setPending([]); setError('');
    try {
      const p = await sourceRequest<SourcePage>(`/api/source-catalog?${new URLSearchParams({ query, filter, ...(cursor ? { cursor } : {}) })}`, c.signal);
      if (!c.signal.aborted) { setPage(old => ({ ...p, items: cursor ? [...old.items, ...p.items.filter(s => !old.items.some(o => sameSource(o, s)))] : p.items })); if (!cursor) { setTopics([]); setTopicPage(null); } }
    } catch (e) { if (!c.signal.aborted) setError((e as Error).message); }
    finally { if (controller.current === c) setBusy(false); }
  }
  useEffect(() => { void load(); }, [filter]);
  async function select(items: TelegramSource[]) {
    if (busy || disabled) return;
    controller.current?.abort(); const c = new AbortController(); controller.current = c;
    setBusy(true); setPending(items); setError('');
    try {
      const resolved: TelegramSource[] = [];
      for (const item of items) resolved.push(saved.find(s => sameSource(s, item)) ?? await sourceMutation<TelegramSource>('/api/sources', 'POST', { chatId: item.chatId, scope: item.scope }, c.signal));
      if (c.signal.aborted) return;
      setSaved(old => [...old, ...resolved.filter(s => !old.some(o => sameSource(o, s)))]);
      onChange([...new Set([...keys, ...resolved.map(s => s.key)])].slice(0, maxScopes));
    } catch (e) { if (!c.signal.aborted) setError((e as Error).message); }
    finally { if (controller.current === c) { setPending([]); setBusy(false); controller.current = null; } }
  }
  async function showTopics(chatId: number, more = false) {
    setBusy(true); setError('');
    try {
      const next = more ? topicPage?.next : null;
      const p = await sourceRequest<{ items: TelegramSource[]; next: { date: number; messageId: number; forumTopicId: number } | null }>(`/api/source-catalog/${chatId}/topics?${new URLSearchParams(next ? Object.fromEntries(Object.entries(next).map(([k, v]) => [k, String(v)])) : {})}`);
      setTopics(old => more ? [...old, ...p.items] : p.items); setTopicPage({ chatId, next: p.next });
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  function row(source: TelegramSource) {
    const existing = saved.find(s => sameSource(s, source)); const checked = (!!existing && keys.includes(existing.key)) || pending.some(s => sameSource(s, source));
    const name = source.scopeTitle ? `${source.title} / ${source.scopeTitle}` : source.title;
    return <div className="source-record" key={`${source.chatId}:${source.scope.kind}:${source.scope.forumTopicId ?? source.scope.messageThreadId ?? 0}`}><label><input type="checkbox" checked={checked} disabled={disabled || busy || source.access === 'UNAVAILABLE' || (!checked && keys.length >= maxScopes)} onChange={e => e.target.checked ? void select([source]) : onChange(keys.filter(k => k !== existing?.key))} />{name}</label>{source.isForum && source.scope.kind === 'CHAT' && <button type="button" className="text-button" disabled={busy || disabled} onClick={() => void showTopics(source.chatId)}>Выбрать тему</button>}</div>;
  }
  return <fieldset disabled={disabled} className="chat-picker"><legend>Чаты и темы · выбрано {keys.length} из {maxScopes}</legend>
    <label>Найти чат<input type="search" disabled={busy} value={query} onChange={e => setQuery(e.target.value)} /></label><button type="button" disabled={busy} className="secondary-button" onClick={() => void load()}>Найти чаты</button>
    <label>Тип чатов<select disabled={busy} value={filter} onChange={e => setFilter(e.target.value)}><option value="all">Все типы</option><option value="groups">Группы</option><option value="channels">Каналы</option><option value="private">Личные чаты</option></select></label>
    <p className="helper">Доступные чаты текущего аккаунта. Выбор не вступает в чаты и не импортирует историю. Поиск и мониторинг ограничены 10 областями.</p>
    <button type="button" className="text-button" disabled={busy || !page.items.length || keys.length >= maxScopes} onClick={() => void select(page.items.filter(s => s.access !== 'UNAVAILABLE' && !saved.some(old => keys.includes(old.key) && sameSource(old, s))).slice(0, Math.max(0, maxScopes - keys.length)))}>Выбрать все в текущей выборке (до {maxScopes})</button><button type="button" className="text-button" disabled={busy} onClick={() => onChange([])}>Очистить выбор</button>
    {error && <p role="alert" className="error">{error} <button type="button" className="text-button" onClick={() => setError('')}>Скрыть</button></p>}{busy && <p role="status">Загружаем чаты…</p>}
    {!!keys.length && <ul className="scope-labels" aria-label="Выбранные чаты">{keys.map(key => { const source = saved.find(s => s.key === key); return <li key={key}>{source ? `${source.title}${source.scopeTitle ? ` / ${source.scopeTitle}` : ''}` : 'Выбранная область'}</li>; })}</ul>}
    <div className="chat-picker-list">{saved.filter(s => keys.includes(s.key) && !page.items.some(o => sameSource(o, s)) && !topics.some(o => sameSource(o, s))).map(row)}{page.items.map(row)}{topics.map(row)}</div>
    {keys.filter(key => !saved.some(s => s.key === key)).map(key => <p className="helper" key={key}>Выбранная область недоступна в каталоге <button type="button" className="text-button" disabled={busy} onClick={() => onChange(keys.filter(k => k !== key))}>Убрать область</button></p>)}
    {page.notice && <p className="helper">{page.notice}</p>}{page.cursor && <button type="button" disabled={busy} className="text-button" onClick={() => void load(page.cursor!)}>Ещё чаты</button>}
    {topicPage?.next && <button type="button" disabled={busy} className="text-button" onClick={() => void showTopics(topicPage.chatId, true)}>Ещё темы</button>}
  </fieldset>;
}
