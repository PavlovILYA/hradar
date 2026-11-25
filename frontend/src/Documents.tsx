import { workspaceUrl } from './workspace';
import { useEffect, useRef, useState } from 'react';
import { request, type ResearchMessage } from './api';
import { ownerMutation } from './ownerApi';
import { ChatPicker } from './ChatPicker';
import './documents.css';

export interface DocumentRecord {
  id: string; message: ResearchMessage; fileName: string; mimeType?: string | null; size: number;
  state: string; downloadedBytes: number; error?: string | null; retryAt?: number | null;
  hasFile: boolean; hasText: boolean; format?: string | null; truncated?: boolean;
}
const states: Record<string, string> = { NOT_DOWNLOADED: 'Не скачан', QUEUED: 'В очереди', DOWNLOADING: 'Скачивается', DOWNLOADED: 'Скачан', PROCESSING: 'Обработка текста', READY: 'Готов', ERROR: 'Ошибка', UNAVAILABLE: 'Недоступен', NEEDS_OCR: 'Нужен OCR — текст не распознан', EMPTY: 'Текст не найден' };
const activeStates = ['QUEUED', 'DOWNLOADING', 'PROCESSING'];
const bytes = (value: number) => new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 1 }).format(value < 1024 * 1024 ? value / 1024 : value / 1024 / 1024) + (value < 1024 * 1024 ? ' КБ' : ' МБ');
function highlight(text: string, query: string) {
  const terms = query.split(/\s+/).filter(Boolean);
  if (!terms.length) return text;
  const escaped = terms.map(term => term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  return text.split(new RegExp(`(${escaped.join('|')})`, 'giu')).map((part, index) => index % 2 ? <mark key={index}>{part}</mark> : part);
}
export function DocumentText({ document: item, onClose }: { document: DocumentRecord; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [value, setValue] = useState<{ text: string; truncated: boolean } | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    const opener = window.document.activeElement as HTMLElement | null;
    dialog.current?.showModal();
    const c = new AbortController();
    void request<{ text: string; truncated: boolean }>(`/api/documents/${encodeURIComponent(item.id)}/text`, c.signal).then(setValue).catch(e => { if (!c.signal.aborted) setError((e as Error).message); });
    return () => { c.abort(); dialog.current?.close(); if (opener?.isConnected) opener.focus(); };
  }, [item.id]);
  return <dialog ref={dialog} className="document-text-dialog" aria-labelledby="document-text-title" onCancel={onClose} onKeyDown={event => { if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); onClose(); } }}>
    <header className="publication-details-heading"><h2 id="document-text-title">Текст документа: {item.fileName}</h2><button className="icon-button" aria-label="Закрыть текст документа" onClick={onClose}>×</button></header>
    <p className="helper">{item.message.title || 'Telegram-чат'}{item.message.scopeTitle ? ` · ${item.message.scopeTitle}` : ''} · Сообщение {item.message.id}</p>
    {error && <p role="alert" className="error">{error}</p>}{!value && !error && <p role="status">Загружаем текст…</p>}
    {value?.truncated && <p role="status">Показана ограниченная часть извлечённого текста.</p>}
    {value && <pre className="document-text-body" tabIndex={0}>{value.text}</pre>}
  </dialog>;
}
export function DocumentControls({ item, onChange }: { item: DocumentRecord; onChange: (item: DocumentRecord) => void }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [textOpen, setTextOpen] = useState(false);
  async function action(name: string) {
    setBusy(true); setError('');
    try { onChange(await ownerMutation<DocumentRecord>(`/api/documents/${encodeURIComponent(item.id)}/${name}`, 'POST')); }
    catch(e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  const running = activeStates.includes(item.state);
  const retryAt = item.retryAt ? new Date(item.retryAt < 1e12 ? item.retryAt * 1000 : item.retryAt) : null;
  const waiting = !!retryAt && retryAt.getTime() > Date.now();
  return <div className="document-controls">
    <p role="status">{states[item.state] ?? item.state}{item.size ? ` · ${bytes(item.size)}` : ''}</p>
    {item.state === 'DOWNLOADING' && <progress aria-label={`Загрузка ${item.fileName}`} max={item.size > 0 ? item.size : undefined} value={item.size > 0 ? item.downloadedBytes : undefined} />}
    {item.error && <p className="helper">{item.error}</p>}{retryAt && <p className="helper">Повтор возможен после {retryAt.toLocaleString('ru-RU')}</p>}
    <div className="action-row">
      {running ? <button className="secondary-button" disabled={busy} onClick={() => void action('cancel')}>Отменить загрузку</button> : !item.hasFile && item.state !== 'UNAVAILABLE' && <button className="secondary-button" disabled={busy || waiting} onClick={() => void action('download')}>{item.state === 'ERROR' ? 'Повторить загрузку' : 'Скачать документ'}</button>}
      {item.hasFile && <a className="original-link" href={workspaceUrl(`/api/documents/${encodeURIComponent(item.id)}/file`)} target="_blank" rel="noreferrer">Открыть файл ↗</a>}
      {item.hasText && <button className="secondary-button" onClick={() => setTextOpen(true)}>Читать текст</button>}
      {item.hasFile && !running && <button className="text-button" disabled={busy} onClick={() => void action('reindex')}>Извлечь текст заново</button>}
    </div>
    {error && <p role="alert" className="error">{error}</p>}{textOpen && <DocumentText document={item} onClose={() => setTextOpen(false)} />}
  </div>;
}
export function PublicationDocuments({ message }: { message: ResearchMessage }) {
  const [items, setItems] = useState<DocumentRecord[] | null>(null), [error, setError] = useState(''), [busy, setBusy] = useState(false);
  async function register() {
    setBusy(true); setError('');
    try { setItems(await ownerMutation<DocumentRecord[]>('/api/documents/register', 'POST', { sourceKey: message.sourceKey, messageId: message.id })); }
    catch(e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  useEffect(() => {
    if (!items?.some(item => activeStates.includes(item.state))) return;
    const c = new AbortController();
    const timer = setInterval(() => { for (const item of items.filter(value => activeStates.includes(value.state))) void request<DocumentRecord>(`/api/documents/${encodeURIComponent(item.id)}`, c.signal).then(next => setItems(old => old?.map(value => value.id === next.id ? next : value) ?? null)).catch(() => {}); }, 1500);
    return () => { clearInterval(timer); c.abort(); };
  }, [items, message.sourceKey, message.id]);
  if (!message.sourceKey || !message.attachments?.some(item => item.kind === 'document')) return null;
  return <section aria-label="Локальные документы публикации">
    {!items && <button className="secondary-button" disabled={busy} onClick={() => void register()}>Подготовить документы к скачиванию</button>}
    {items?.map(item => <div key={item.id}><strong>{item.fileName}</strong><DocumentControls item={item} onChange={next => setItems(old => old!.map(value => value.id === next.id ? next : value))} /></div>)}
    {items?.length === 0 && <p className="helper">Поддерживаемых документов PDF, DOCX или TXT нет.</p>}
    {error && <p role="alert" className="error">{error}</p>}
  </section>;
}
export function Documents({ active, onSelect }: { active: boolean; onSelect: (message: ResearchMessage) => void }) {
  const [items, setItems] = useState<DocumentRecord[]>([]), [total, setTotal] = useState(0), [disk, setDisk] = useState(0);
  const [query, setQuery] = useState(''), [applied, setApplied] = useState(''), [offset, setOffset] = useState(0);
  const [snippets, setSnippets] = useState<Record<string, string>>({});
  const [keys, setKeys] = useState<string[]>([]), [discovered, setDiscovered] = useState<DocumentRecord[]>([]), [cursor, setCursor] = useState<number | null>(null);
  const [discoverySource, setDiscoverySource] = useState('');
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  async function load(q = applied, pageOffset = offset, signal?: AbortSignal) {
    const params = new URLSearchParams({ offset: String(pageOffset), limit: '25', ...(q ? { q } : {}) });
    if (q) {
      const page = await request<{ items: { document: DocumentRecord; snippet: string }[]; total: number }>(`/api/documents/search?${params}`, signal);
      setItems(page.items.map(item => item.document)); setSnippets(Object.fromEntries(page.items.map(item => [item.document.id, item.snippet]))); setTotal(page.total);
    } else {
      const page = await request<{ items: DocumentRecord[]; total: number; bytes: number }>(`/api/documents?${params}`, signal);
      setItems(page.items); setSnippets({}); setTotal(page.total); setDisk(page.bytes);
    }
    setOffset(pageOffset); setApplied(q);
  }
  useEffect(() => { if (!active) return; const c = new AbortController(); void load(applied, offset, c.signal).catch(e => { if (!c.signal.aborted) setError((e as Error).message); }); return () => c.abort(); }, [active]);
  useEffect(() => {
    if (!active || ![...items, ...discovered].some(item => activeStates.includes(item.state))) return;
    const c = new AbortController(); const timer = setInterval(() => {
      const pending = new Map([...items, ...discovered].filter(item => activeStates.includes(item.state)).map(item => [item.id, item]));
      for (const item of pending.values()) void request<DocumentRecord>(`/api/documents/${encodeURIComponent(item.id)}`, c.signal).then(update).catch(() => {});
    }, 1500);
    return () => { clearInterval(timer); c.abort(); };
  }, [active, items, discovered, applied, offset]);
  async function task(run: () => Promise<void>) { setBusy(true); setError(''); try { await run(); } catch(e) { setError((e as Error).message); } finally { setBusy(false); } }
  function update(item: DocumentRecord) { setItems(old => old.map(value => value.id === item.id ? item : value)); setDiscovered(old => old.map(value => value.id === item.id ? item : value)); }
  async function discover(more = false) {
    const sourceKey = keys[0];
    const page = await ownerMutation<{ items: DocumentRecord[]; nextCursor: number | null; notice?: string }>('/api/documents/discover', 'POST', { sourceKey, limit: 50, ...(more ? { before: cursor } : {}) });
    setDiscovered(page.items); setCursor(page.nextCursor); setDiscoverySource(sourceKey); setNotice(page.notice || 'Проверена одна страница сообщений. Файлы не скачаны; выберите нужные документы.'); await load();
  }
  function cards(records: DocumentRecord[], discovery = false) { return records.map(item => <article className="document-card" key={item.id}>
    <h3>{discovery ? item.fileName : highlight(item.fileName, applied)}</h3><p className="helper">{item.message.title || 'Telegram-чат'}{item.message.scopeTitle ? ` · ${item.message.scopeTitle}` : ''} · {new Date(item.message.date * 1000).toLocaleDateString('ru-RU')} · Сообщение {item.message.id}</p>
    {!discovery && snippets[item.id] && <p className="document-snippet">{highlight(snippets[item.id], applied)}</p>}
    <DocumentControls item={item} onChange={update} /><button className="text-button" onClick={() => onSelect(item.message)}>Открыть публикацию</button>
  </article>); }
  return <div className="data-screen documents-screen"><h2>Документы</h2><p className="helper">Локальные файлы PDF, DOCX и TXT. Поиск охватывает только скачанные и обработанные документы, не весь Telegram. OCR и распознавание аудио недоступны.</p>
    <form onSubmit={e => { e.preventDefault(); void task(() => load(query.trim(), 0)); }}><label>Поиск в локальных документах<input type="search" value={query} maxLength={256} onChange={e => setQuery(e.target.value)} /></label><div className="action-row"><button className="primary-button" disabled={busy}>Искать в документах</button><button type="button" className="secondary-button" disabled={busy} onClick={() => { setQuery(''); void task(() => load('', 0)); }}>Все документы</button></div></form>
    <details className="document-discovery"><summary>Найти документы в выбранном чате</summary><p className="helper">Выберите один чат или тему. Каждое действие проверяет до 50 сообщений; скачивание каждого файла подтверждается отдельно. До 25 МБ на файл; общий лимит 500 МБ учитывает оригиналы hradar и документы в кэше Telegram.</p><ChatPicker keys={keys} onChange={value => { setKeys(value); setDiscovered([]); setCursor(null); }} maxScopes={1} disabled={busy} /><button className="secondary-button" disabled={busy || keys.length !== 1} onClick={() => void task(() => discover())}>Проверить страницу сообщений</button>{cursor !== null && discoverySource === keys[0] && <button className="secondary-button" disabled={busy} onClick={() => void task(() => discover(true))}>Проверить следующую страницу</button>}{cards(discovered, true)}</details>
    {error && <p role="alert" className="error">{error}</p>}{notice && <p role="status">{notice}</p>}
    <p className="helper">Найдено: {total}{!applied ? ` · Оригиналы hradar: ${bytes(disk)}` : ''}</p>
    {!items.length && <p>Документов пока нет. Подготовьте вложения из публикации или проверьте выбранный чат.</p>}{cards(items)}
    <div className="action-row"><button className="secondary-button" disabled={busy || !offset} onClick={() => void task(() => load(applied, offset - 25))}>Предыдущие документы</button><button className="secondary-button" disabled={busy || offset + 25 >= total} onClick={() => void task(() => load(applied, offset + 25))}>Следующие документы</button></div>
  </div>;
}
