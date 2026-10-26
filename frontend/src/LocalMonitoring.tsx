import { useEffect, useRef, useState } from 'react';
import type { NotificationPage, NotificationRecord, SearchTemplate } from './api';
import { sourceMutation, sourceRequest, type TelegramSource } from './SourceCatalog';
import { sourceLabel } from './SelectedSources';

interface LocalSubscription {
  id: string; sourceKey: string; templateId: string; enabled: boolean; checkpoint: number;
  recoveryCursor: number; recoveryHead: number; sinceDate: number; retryAt: number;
  error?: string | null; status: string;
}
const statusLabels: Record<string, string> = { active: 'Мониторинг включён', template_paused: 'Шаблон на паузе', unavailable: 'Нет доступа к источнику', retrying: 'Повтор после ошибки', enabled: 'Мониторинг включён', paused: 'Мониторинг на паузе', recovering: 'Восстановление пропущенных сообщений', access_denied: 'Нет доступа к источнику', rate_limited: 'Лимит Telegram', error: 'Ошибка мониторинга' };
export function LocalMonitoring({ sources, templates, active }: { sources: TelegramSource[]; templates: SearchTemplate[]; active: boolean }) {
  const [key, setKey] = useState('');
  const [template, setTemplate] = useState('');
  const [subscriptions, setSubscriptions] = useState<LocalSubscription[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const [clock, setClock] = useState(Date.now());
  const request = useRef<AbortController | null>(null);
  const generation = useRef(0);
  useEffect(() => { const timer = setInterval(() => setClock(Date.now()), 15_000); return () => clearInterval(timer); }, []);
  useEffect(() => {
    generation.current++; request.current?.abort(); setBusy(false); setSubscriptions([]); setError('');
    if (!active || !key || !sources.some(s => s.key === key)) return;
    const c = new AbortController(); request.current = c; setBusy(true);
    sourceRequest<LocalSubscription[]>(`/api/sources/${encodeURIComponent(key)}/monitoring`, c.signal)
      .then(items => { if (!c.signal.aborted) setSubscriptions(items); })
      .catch(e => { if (!c.signal.aborted) setError((e as Error).message); })
      .finally(() => { if (request.current === c) { setBusy(false); request.current = null; } });
    return () => c.abort();
  }, [active, key, revision, sources]);
  async function save(templateId: string, enabled: boolean) {
    if (busy) return;
    const current = generation.current; const c = new AbortController(); request.current?.abort(); request.current = c; setBusy(true); setError('');
    try {
      const item = await sourceMutation<LocalSubscription>(`/api/sources/${encodeURIComponent(key)}/monitoring`, 'POST', { templateId, enabled }, c.signal);
      if (current === generation.current && !c.signal.aborted) setSubscriptions(old => [...old.filter(s => s.templateId !== item.templateId), item]);
    } catch(e) { if (current === generation.current && !c.signal.aborted) setError((e as Error).message); }
    finally { if (request.current === c) { setBusy(false); request.current = null; } }
  }
  const selected = subscriptions.find(s => s.templateId === template);
  const hasSource = sources.some(s => s.key === key);
  return <section className="local-monitoring" aria-label="Локальный мониторинг источника"><h3>Источник и текстовые правила</h3>
    <p className="helper">Включение действует только для явно выбранного источника и шаблона. Новые источники и проекты не подключаются автоматически. Совпадения остаются в этом приложении; бот, MCP и внешние AI доступа не получают.</p>
    <label>Источник локального мониторинга<select value={hasSource ? key : ''} onChange={e => { request.current?.abort(); generation.current++; setKey(e.target.value); setTemplate(''); }}><option value="">Выберите источник</option>{sources.map(s => <option key={s.key} value={s.key}>{sourceLabel(s)}</option>)}</select></label>
    <label>Шаблон локального мониторинга<select value={template} disabled={!hasSource || busy} onChange={e => setTemplate(e.target.value)}><option value="">Выберите шаблон</option>{templates.filter(t => t.active || subscriptions.some(s => s.templateId === t.id)).map(t => <option key={t.id} value={t.id}>{t.name}{t.active ? '' : ' · приостановлен'}</option>)}</select></label>
    {!templates.length && <p>Создайте текстовый шаблон в разделе «Шаблоны».</p>}
    {error && <p className="error" role="alert">{error}</p>}
    <div className="source-actions"><button className="secondary-button" disabled={!hasSource || !template || busy || !!selected || !templates.some(t => t.id === template && t.active)} onClick={() => void save(template, true)}>Включить локальный мониторинг</button><button className="text-button" disabled={!hasSource || busy} onClick={() => setRevision(n => n + 1)}>Обновить состояние мониторинга</button></div>
    {busy && <p role="status">Обновляем локальный мониторинг…</p>}
    {subscriptions.map(item => {
      const status = item.status.toLowerCase(); const retry = ['access_denied', 'unavailable', 'rate_limited', 'retrying', 'error'].includes(status);
      return <article className="source-record" key={item.id}><div><strong>{templates.find(t => t.id === item.templateId)?.name ?? 'Шаблон недоступен'}</strong><p role="status">{statusLabels[status] ?? 'Состояние мониторинга неизвестно'}</p>{item.error && <p className="helper">{item.error}</p>}{item.retryAt > clock && <p>Следующая разрешённая попытка: {new Date(item.retryAt).toLocaleString('ru-RU')}</p>}<p className="helper">Пауза сохраняет место обработки. Продолжение проверяет доступ и восстанавливает доступные сообщения.</p></div><div className="source-actions">{item.enabled ? <button className="secondary-button" disabled={busy} onClick={() => void save(item.templateId, false)}>Приостановить мониторинг</button> : <button className="secondary-button" disabled={busy || !templates.some(t => t.id === item.templateId && t.active)} onClick={() => void save(item.templateId, true)}>Продолжить мониторинг</button>}{retry && item.enabled && <button className="secondary-button" disabled={busy || item.retryAt > clock} onClick={() => void save(item.templateId, true)}>Повторить мониторинг</button>}</div></article>;
    })}
    {hasSource && !busy && !error && !subscriptions.length && <p>Локальный мониторинг этого источника не включён.</p>}
  </section>;
}

export function LocalNotifications({ active, onOpen }: { active: boolean; onOpen: (item: NotificationRecord) => void }) {
  const [shown, setShown] = useState(false);
  const [page, setPage] = useState<NotificationPage>({ items: [], unread: 0, cursor: null });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const controller = useRef<AbortController | null>(null);
  async function load(more = false) {
    controller.current?.abort(); const c = new AbortController(); controller.current = c; setBusy(true); setError('');
    try {
      const data = await sourceRequest<NotificationPage>(`/api/local-notifications?limit=25${more && page.cursor ? `&before=${page.cursor}` : ''}`, c.signal);
      if (!c.signal.aborted) setPage(old => ({ ...data, items: more ? [...new Map([...old.items, ...data.items].map(item => [item.id, item])).values()] : data.items }));
    } catch(e) { if (!c.signal.aborted) setError((e as Error).message); }
    finally { if (controller.current === c) { setBusy(false); controller.current = null; } }
  }
  useEffect(() => { if (shown && active) void load(); return () => controller.current?.abort(); }, [shown, active]);
  async function read(item?: NotificationRecord) {
    if (busy) return false;
    if (item?.read) return true;
    const c = new AbortController(); controller.current?.abort(); controller.current = c; setBusy(true); setError('');
    try {
      await sourceMutation(`/api/local-notifications/${item ? encodeURIComponent(item.id) + '/read' : 'read-all'}`, 'POST', undefined, c.signal);
      if (!c.signal.aborted) setPage(old => ({ ...old, unread: item ? Math.max(0, old.unread - 1) : 0, items: old.items.map(row => !item || row.id === item.id ? { ...row, read: true } : row) }));
    } catch(e) { if (!c.signal.aborted) setError((e as Error).message); }
    finally { if (controller.current === c) { setBusy(false); controller.current = null; } }
    return !c.signal.aborted;
  }
  return <section className="notification-center" aria-label="Локальные совпадения"><h2>Локальные совпадения выбранных источников</h2><p className="helper">Отдельный локальный список. Его сообщения не поступают в публичные уведомления, Telegram-бот или MCP.</p>
    {!shown ? <button className="secondary-button" onClick={() => setShown(true)}>Показать локальные совпадения</button> : <>
      <div className="template-actions"><span>Непрочитано локально: {page.unread}</span><button className="text-button" disabled={busy} onClick={() => void load()}>Обновить локальные совпадения</button><button className="text-button" disabled={busy || !page.unread} onClick={() => void read()}>Прочитать все локальные</button></div>
      {error && <p role="alert" className="error">{error}</p>}{busy && <p role="status">Загрузка локальных совпадений…</p>}
      {!busy && !error && !page.items.length && <p>Локальных совпадений пока нет. Настройте источник и шаблон в разделе «Источники».</p>}
      {page.items.map(item => <article key={item.id} className={`notification-card ${item.read ? '' : 'unread'}`}><div className="message-meta"><strong>{item.templateName}</strong><time>{new Date(item.message.date * 1000).toLocaleString('ru-RU')}</time></div><p>{item.message.title}{item.message.scopeTitle ? ` / ${item.message.scopeTitle}` : item.message.forumTopicId ? ` / Тема #${item.message.forumTopicId}` : ''}</p><p className="message-text">{item.message.availability === 'deleted' ? 'Сообщение удалено' : item.message.availability === 'unavailable' ? 'Сообщение недоступно' : item.message.text}</p>{item.message.explanation && <p className="match-reason">Совпали: {[...item.message.explanation.foundRequired, ...item.message.explanation.foundAny].join(', ')} · Исключения отсутствуют</p>}<div className="template-actions"><button className="text-button" disabled={busy} onClick={async () => { if (item.read || await read(item)) onOpen(item); }}>Открыть локальную публикацию</button>{!item.read && <button className="text-button" disabled={busy} onClick={() => void read(item)}>Прочитать локальное совпадение</button>}</div></article>)}
      {page.cursor && <button className="load-more" disabled={busy} onClick={() => void load(true)}>Более ранние локальные совпадения</button>}
    </>}
  </section>;
}
