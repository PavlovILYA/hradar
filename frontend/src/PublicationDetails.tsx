import { useEffect, useRef, useState } from 'react';
import { request, mutate, type ResearchMessage } from './api';
import { ownerMutation } from './ownerApi';
import { DiscussionSource } from './DiscussionSource';
import type { TelegramSource } from './SourceCatalog';

const attachmentKinds: Record<string, string> = { photo: 'Фото', video: 'Видео', animation: 'Анимация', audio: 'Аудио', voice: 'Голосовое сообщение', document: 'Документ' };
interface ProjectChoice { id: string; criteria: { name: string; sources: number[]; sourceKeys?: string[] } }
export function PublicationDetails({ message, onClose, onMessages, onArchive }: {
  message: ResearchMessage; onClose: () => void;
  onMessages: (source: TelegramSource) => void; onArchive: (source: TelegramSource) => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [detail, setDetail] = useState(message);
  const [linkState, setLinkState] = useState('Получаем ссылку на оригинал…');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [projects, setProjects] = useState<ProjectChoice[] | null>(null);
  const [projectId, setProjectId] = useState('');
  const [projectQuery, setProjectQuery] = useState('');
  const [projectOffset, setProjectOffset] = useState(0);
  const [projectTotal, setProjectTotal] = useState(0);
  async function loadProjects(offset = 0) {
    const value = await request<{ items: ProjectChoice[]; total: number }>(`/api/projects?${new URLSearchParams({ limit: '25', offset: String(offset), status: 'ACTIVE', query: projectQuery.trim() })}`);
    setProjects(value.items); setProjectOffset(offset); setProjectTotal(value.total); setProjectId('');
  }
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const modal = dialog.current; modal?.showModal();
    return () => { modal?.close(); if (opener?.isConnected) opener.focus(); };
  }, []);
  useEffect(() => {
    const c = new AbortController();
    if (message.link && !message.sourceKey) { setLinkState(''); return () => c.abort(); }
    setLinkState('Получаем ссылку на оригинал…');
    const fetchDetail = message.sourceKey
      ? request<ResearchMessage>(`/api/sources/${encodeURIComponent(message.sourceKey)}/messages/${message.id}`, c.signal)
      : request<{ link: string | null }>(`/api/messages/${message.channelId}/${message.id}/link`, c.signal);
    void fetchDetail.then(value => {
      if (c.signal.aborted) return;
      setDetail(old => ({ ...old, ...value, date: 'date' in value && value.date > 0 ? value.date : old.date, text: 'text' in value ? value.text ?? old.text : old.text, link: value.link ?? old.link }));
      setLinkState(value.link ? '' : 'Telegram не предоставил ссылку на эту публикацию. Она может быть недоступна для текущего аккаунта.');
    }).catch(() => { if (!c.signal.aborted) setLinkState('Не удалось получить ссылку. Проверьте доступ к чату и повторите попытку.'); });
    return () => c.abort();
  }, [message, retry]);
  async function action(task: () => Promise<unknown>, success: string) {
    setBusy(true); setError(''); setNotice('');
    try { await task(); setNotice(success); }
    catch(e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  const eligible = projects?.filter(p => (detail.sourceKey && p.criteria.sourceKeys?.includes(detail.sourceKey)) || (!detail.forumTopicId && !detail.messageThreadId && p.criteria.sources.includes(detail.channelId))) ?? [];
  const link = detail.link && /^https:\/\/t\.me\//.test(detail.link) ? detail.link : null;
  return <dialog ref={dialog} className="publication-dialog" aria-labelledby="publication-title" onCancel={onClose} onClick={e => { if (e.target === dialog.current) { const rect = dialog.current.getBoundingClientRect(); if (e.clientX < rect.left || e.clientX > rect.right || e.clientY < rect.top || e.clientY > rect.bottom) onClose(); } }}>
    <header className="publication-details-heading"><h2 id="publication-title">Детали публикации</h2><button className="icon-button" aria-label="Закрыть детали" onClick={onClose}>×</button></header>
    <h3>{detail.title || (detail.username ? `@${detail.username}` : 'Telegram-чат')}</h3>
    {(detail.scopeTitle || detail.forumTopicId) && <p>{detail.scopeTitle || `Тема #${detail.forumTopicId}`}</p>}
    <time dateTime={new Date(detail.date * 1000).toISOString()}>{new Date(detail.date * 1000).toLocaleString('ru-RU')}</time>
    {detail.availability && detail.availability !== 'available' && <p role="status">{detail.availability === 'deleted' ? 'Сообщение удалено. Ниже показана сохранённая копия.' : 'Оригинал сейчас недоступен. Ниже показана сохранённая копия.'}</p>}
    <p className="message-text">{detail.text || 'Медиа или служебное сообщение без текста'}</p>
    <section aria-label="Вложения"><h4>Вложения</h4>{detail.attachments ? detail.attachments.length ? <ul>{detail.attachments.map((file, i) => <li key={i}>{file.fileName || (attachmentKinds[file.kind] ?? 'Вложение')}{file.size ? ` · ${new Intl.NumberFormat('ru-RU').format(file.size)} байт` : ''}{file.mimeType ? ` · ${file.mimeType}` : ''}</li>)}</ul> : <p className="helper">Вложений нет.</p> : <p className="helper">Сведения о файлах в этой сохранённой публикации недоступны. Просмотреть доступные вложения можно в Telegram.</p>}</section>
    {link ? <a className="original-link" href={link} target="_blank" rel="noreferrer">Открыть в Telegram ↗</a> : <div><p role="status">{linkState || 'Telegram не предоставил ссылку на эту публикацию.'}</p>{!linkState.startsWith('Получаем') && <button className="text-button" onClick={() => setRetry(n => n + 1)}>Повторить получение ссылки</button>}</div>}
    {error && <div role="alert" className="error dismissible"><span>{error}</span><button className="icon-button" aria-label="Закрыть ошибку публикации" onClick={() => setError('')}>×</button></div>}
    {notice && <p role="status">{notice}</p>}
    <div className="action-row">
      {detail.sourceKey && <button className="secondary-button" disabled={busy} onClick={() => void action(() => ownerMutation('/api/workspace/saved', 'POST', { sourceKey: detail.sourceKey, messageId: detail.id }), 'Публикация сохранена.')}>Сохранить публикацию</button>}
      <button className="secondary-button" disabled={busy} onClick={() => void action(() => navigator.clipboard.writeText(detail.text || ''), 'Текст скопирован.')}>Копировать текст</button>
      <button className="secondary-button" disabled={busy} onClick={() => void action(() => loadProjects(), '')}>Добавить в проект</button>
    </div>
    {projects && !detail.sourceKey && <p className="helper">Добавление использует уже сохранённые в hradar данные. Если публикация впервые найдена в прежнем публичном режиме, сначала найдите её через поиск внутри проекта.</p>}
    {projects && <form onSubmit={e => { e.preventDefault(); void action(() => mutate(`/api/projects/${projectId}/cards`, 'POST', { channelId: detail.channelId, messageId: detail.id, ...(detail.sourceKey ? { sourceKey: detail.sourceKey } : {}) }), 'Публикация добавлена на доску проекта.'); }}>
<label>Найти проект<input value={projectQuery} disabled={busy} onChange={e => setProjectQuery(e.target.value)} /></label><button type="button" className="secondary-button" disabled={busy} onClick={() => void action(() => loadProjects(), '')}>Найти проекты</button>
      <label>Проект найма<select value={projectId} disabled={busy} onChange={e => setProjectId(e.target.value)}><option value="">Выберите проект</option>{eligible.map(p => <option key={p.id} value={p.id}>{p.criteria.name}</option>)}</select></label>
      {!eligible.length && <p className="helper">На этой странице нет подходящих активных проектов. Измените запрос или выберите другую страницу. Для добавления включите этот чат в область поиска проекта.</p>}
<div className="action-row"><button type="button" className="secondary-button" disabled={busy || !projectOffset} onClick={() => void action(() => loadProjects(projectOffset - 25), '')}>Предыдущие проекты</button><button type="button" className="secondary-button" disabled={busy || projectOffset + 25 >= projectTotal} onClick={() => void action(() => loadProjects(projectOffset + 25), '')}>Следующие проекты</button></div>
      <button className="primary-button" disabled={busy || !eligible.some(p => p.id === projectId)}>Добавить публикацию</button>
    </form>}
    <DiscussionSource message={detail} onMessages={onMessages} onArchive={onArchive} />
    <details><summary>Дополнительно</summary><dl><dt>ID сообщения</dt><dd>{detail.id}</dd><dt>ID чата</dt><dd>{detail.channelId}</dd>{detail.sourceKey && <><dt>Ключ области поиска</dt><dd>{detail.sourceKey}</dd></>}</dl></details>
  </dialog>;
}
