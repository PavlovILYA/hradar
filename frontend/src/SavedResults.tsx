import { useEffect, useState } from 'react';
import { request } from './api';
import type { ResearchMessage } from './api';
interface SavedResult { id: string; status: string; message: ResearchMessage; sequence: number; updatedAt: number }
interface Page { items: SavedResult[]; nextCursor: number | null }
const labels: Record<string, string> = { SAVED: 'Сохранено', REJECTED: 'Не подходит', REMOVED: 'Убрано из избранного', VIEWED: 'Просмотрено' };
export function SavedResults() {
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
  useEffect(() => { void load(); }, [status]);
  return <section className="mcp-integration" aria-label="Сохранённые публикации"><h2>Избранное и решения</h2>
    <p className="helper">Публикации, сохранённые в Telegram-боте. Решения относятся к оператору, который их принял; список объединяет операторов локального workspace.</p>
    <label htmlFor="saved-status">Статус публикаций</label><select id="saved-status" value={status} disabled={busy} onChange={e => setStatus(e.target.value)}>{Object.entries(labels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
    <button className="secondary-button" disabled={busy} onClick={() => void load()}>Обновить избранное</button>
    {error && <p role="alert" className="error">{error}</p>}
    {!busy && !page.items.length && <p>Публикаций с этим статусом пока нет. В боте используйте /find и «Сохранить».</p>}
    {page.items.map(item => <article key={item.id} className="message-card"><div className="message-meta"><strong>{item.message.title}</strong><span>{labels[item.status]}</span></div><p className="message-text">{item.message.text || 'Публикация без текста'}</p>{item.message.link && /^https:\/\/t\.me\//.test(item.message.link) && <a className="original-link" href={item.message.link} target="_blank" rel="noreferrer">Открыть оригинал ↗</a>}</article>)}
    {page.nextCursor && <button className="secondary-button" disabled={busy} onClick={() => void load(true)}>Ещё сохранённые публикации</button>}
  </section>;
}
