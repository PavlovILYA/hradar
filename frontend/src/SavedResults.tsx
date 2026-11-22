import { useEffect, useState } from 'react';
import { request } from './api';
import { ownerMutation } from './ownerApi';
import type { ResearchMessage } from './api';
interface SavedResult { id: string; status: string; message: ResearchMessage; sequence: number; updatedAt: number }
interface Page { items: SavedResult[]; nextCursor: number | null }
const labels: Record<string, string> = { SAVED: 'Сохранено', REJECTED: 'Не подходит', REMOVED: 'Убрано из избранного', VIEWED: 'Просмотрено' };
export function SavedResults({ onSelect, active = true }: { onSelect: (message: ResearchMessage) => void; active?: boolean }) {
  const [local, setLocal] = useState<Page>({ items: [], nextCursor: null });
  const [status, setStatus] = useState('SAVED');
  const [page, setPage] = useState<Page>({ items: [], nextCursor: null });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  async function load(more = false) {
    setBusy(true); setError('');
    try {
      const next = await request<Page>(`/api/saved?limit=25&status=${status}${more && page.nextCursor ? `&before=${page.nextCursor}` : ''}`);
      setPage(previous => ({ ...next, items: more ? [...previous.items, ...next.items].filter((item, index, all) => all.findIndex(v => v.id === item.id) === index) : next.items }));
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  async function loadLocal(more = false) {
    try { const value = await request<Page>(`/api/workspace/saved${more && local.nextCursor ? `?before=${local.nextCursor}` : ''}`); setLocal(old => ({ ...value, items: more ? [...old.items, ...value.items] : value.items })); }
    catch(e) { setError((e as Error).message); }
  }
  useEffect(() => { if (active) { void load(); void loadLocal(); } }, [status, active]);
  return <section className="mcp-integration" aria-label="Сохранённые публикации"><h2>Сохранённые публикации</h2><p className="helper">Ваши закладки для дальнейшей работы. Они сохраняются при очистке временного кэша и отличаются от сохранённых поисковых запросов.</p>
    <h3>Ваши закладки</h3>{!local.items.length && <p className="helper">Сохраните интересную публикацию из поиска или её деталей.</p>}<button className="text-button" onClick={() => void loadLocal()}>Обновить сохранённые из поиска</button>
    {local.items.map(item => <article className="message-card" key={item.id}><strong>{item.message.title}</strong><p className="message-text">{item.message.text}</p><button className="text-button" onClick={() => onSelect(item.message)}>Открыть публикацию</button>{item.message.link && /^https:\/\/t\.me\//.test(item.message.link) && <a href={item.message.link} target="_blank" rel="noreferrer">Открыть оригинал ↗</a>}<button className="text-button" onClick={() => void ownerMutation(`/api/workspace/saved/${item.id}`, 'DELETE').then(() => loadLocal()).catch(e => setError((e as Error).message))}>Убрать из избранного</button></article>)}
    {local.nextCursor && <button className="text-button" onClick={() => void loadLocal(true)}>Ещё сохранённые из поиска</button>}
    <h3>Решения Telegram-бота</h3><p className="helper">Публикации, сохранённые в Telegram-боте. Решения относятся к оператору, который их принял; список объединяет операторов локального workspace.</p>
    <label htmlFor="saved-status">Статус публикаций</label><select id="saved-status" value={status} disabled={busy} onChange={e => setStatus(e.target.value)}>{Object.entries(labels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
    <button className="secondary-button" disabled={busy} onClick={() => void load()}>Обновить избранное</button>
    {error && <p role="alert" className="error">{error} <button className="text-button" onClick={() => setError('')}>Скрыть</button></p>}
    {!busy && !page.items.length && <p>Публикаций с этим статусом пока нет. В боте используйте /find и «Сохранить».</p>}
    {page.items.map(item => <article key={item.id} className="message-card"><div className="message-meta"><strong>{item.message.title}</strong><span>{labels[item.status]}</span></div><p className="message-text">{item.message.text || 'Публикация без текста'}</p><button className="text-button" onClick={() => onSelect(item.message)}>Открыть публикацию</button>{item.message.link && /^https:\/\/t\.me\//.test(item.message.link) && <a className="original-link" href={item.message.link} target="_blank" rel="noreferrer">Открыть оригинал ↗</a>}</article>)}
    {page.nextCursor && <button className="secondary-button" disabled={busy} onClick={() => void load(true)}>Ещё сохранённые публикации</button>}
  </section>;
}
