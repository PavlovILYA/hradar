import { useEffect, useRef, useState } from 'react';
import { ChatPicker } from './ChatPicker';
import { ownerMutation } from './ownerApi';
import type { ResearchMessage, SearchPage, SearchTemplate } from './api';

interface Result extends SearchPage { fetchedAt: number; origin: 'telegram' | 'cache' }
export function ChatSearch({ templates, initialKey, templateId, onSelect, refresh }: { templates: SearchTemplate[]; initialKey: string; templateId: string; onSelect: (m: ResearchMessage) => void; refresh: () => Promise<void> }) {
  const selected = templates.find(t => t.id === templateId);
  const [keys, setKeys] = useState<string[]>(selected?.sourceKeys ?? (initialKey ? [initialKey] : []));
  const [query, setQuery] = useState(selected?.telegramQuery ?? '');
  const [result, setResult] = useState<Result | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [name, setName] = useState('');
  const [monitor, setMonitor] = useState(false);
  const [notice, setNotice] = useState('');
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  async function search(cached = false, more = false) {
    const c = new AbortController(); controller.current = c; setBusy(true); setError('');
    try {
      const page = await ownerMutation<Result>('/api/workspace/search', 'POST', { search: { mode: 'sources', sourceKeys: keys, query, templateId: selected?.id, limit: 30, cursor: more ? result?.cursor ?? '' : '' }, cached }, c.signal, 95_000);
      if (!c.signal.aborted) setResult(old => ({ ...page, messages: more ? [...new Map([...(old?.messages ?? []), ...page.messages].map(m => [`${m.channelId}:${m.id}`, m])).values()] : page.messages }));
    } catch(e) { if (!c.signal.aborted) setError((e as Error).message); }
    finally { if (controller.current === c) setBusy(false); }
  }
  return <section aria-label="Поиск в чатах"><h2>Поиск в Telegram</h2>
    <ChatPicker keys={keys} disabled={busy} onChange={value => { setKeys(value); setResult(null); }} />
    <label>Запрос в чатах<input value={query} maxLength={256} disabled={busy || !!selected} onChange={e => { setQuery(e.target.value); setResult(null); }} /></label>
    {selected && <p className="helper">Правила шаблона: {selected.name}. AND / OR / NOT проверяются после Telegram-поиска.</p>}
    <button className="primary-button" disabled={busy || !keys.length || !query.trim()} onClick={() => void search()}>Искать в выбранных чатах</button>
    <button className="secondary-button" disabled={busy || !keys.length || !query.trim()} onClick={() => void search(true)}>Показать локальный кэш этого запроса</button>
    <button className="text-button" disabled={busy} onClick={() => void ownerMutation('/api/workspace/cache', 'DELETE').then(() => { setResult(null); setNotice('Кэш очищен. Архив и сохранённые публикации сохранены.'); }).catch(e => setError((e as Error).message))}>Очистить кэш поиска</button>
    {busy && <p role="status">Поиск ограничен одной страницей на чат за шаг, до 30 результатов. <button className="text-button" onClick={() => { controller.current?.abort(); setBusy(false); }}>Отменить поиск</button></p>}
    {error && <p role="alert" className="error">{error}</p>}{notice && <p role="status">{notice}</p>}
    {result && <><p role="status">{result.origin === 'cache' ? 'Локальный кэш · не актуальный запрос Telegram' : 'Получено из Telegram'} · {new Date(result.fetchedAt).toLocaleString('ru-RU')} · {result.messages.length} результатов</p>{result.notice && <p role="status">{result.notice}</p>}{result.state !== 'ok' && <p className="helper">Поиск неполный: {result.state}. Ошибки не означают отсутствие сообщений.</p>}<p className="helper">Охват: выбранные {keys.length} чатов/тем. Это ограниченная выборка, не поиск по всему Telegram. Кэш хранится до 24 часов, до 50 страниц.</p>
      {result.messages.map(m => <article className="message-card" key={`${m.channelId}:${m.id}`}><strong>{m.title}{m.scopeTitle && ` / ${m.scopeTitle}`}</strong><p className="message-text">{m.text}</p><button className="text-button" onClick={() => onSelect(m)}>Открыть публикацию</button><button className="text-button" disabled={busy || !m.sourceKey} onClick={() => void ownerMutation('/api/workspace/saved', 'POST', { sourceKey: m.sourceKey, messageId: m.id }).then(() => setNotice('Публикация сохранена в избранное.')).catch(e => setError((e as Error).message))}>Сохранить публикацию</button></article>)}
      {result.cursor && <button className="secondary-button" disabled={busy} onClick={() => void search(false, true)}>Продолжить ограниченный поиск</button>}</>}
    <button className="secondary-button" disabled={busy || !keys.length || !query.trim()} onClick={() => { setSaving(true); setName(query); }}>Сохранить как шаблон</button>
    {saving && <form onSubmit={e => { e.preventDefault(); setBusy(true); setError(''); void ownerMutation('/api/workspace/templates', 'POST', { name, required: selected?.required ?? [query.trim()], any: selected?.any ?? [], excluded: selected?.excluded ?? [], telegramQuery: query, sourceMode: 'chats', sourceKeys: keys, active: true, subscribed: monitor, notificationsEnabled: true }).then(async () => { await refresh(); setSaving(false); setNotice('Шаблон сохранён. Мониторинг выбранных чатов настроен автоматически.'); }).catch(e => setError((e as Error).message)).finally(() => setBusy(false)); }}>
      <label>Название сохранённого поиска<input required maxLength={100} value={name} onChange={e => setName(e.target.value)} /></label>
      <label><input type="checkbox" checked={monitor} onChange={e => setMonitor(e.target.checked)} />Включить мониторинг</label><p className="helper">Только новые сообщения с текущего момента. Область фиксируется; новые диалоги автоматически не подключаются. Подробные AND/OR/NOT доступны в разделе «Шаблоны».</p>
      <button className="primary-button" disabled={busy}>Сохранить поиск</button><button type="button" className="text-button" disabled={busy} onClick={() => setSaving(false)}>Отмена</button>
    </form>}
  </section>;
}
