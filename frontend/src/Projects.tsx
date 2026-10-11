import { useEffect, useState, useRef, type FormEvent } from 'react';
import { request, mutate, type MonitoredChannel, type SearchTemplate, type ResearchMessage, type SearchPage } from './api';
import './projects.css';

type Status = 'ACTIVE' | 'ARCHIVED';
type Stage = 'NEW' | 'INTERESTING' | 'CONTACTED' | 'IN_PROGRESS' | 'REJECTED';
type Kind = 'CANDIDATE' | 'EMPLOYER' | 'OTHER' | 'INSUFFICIENT';
interface Criteria { name: string; description: string; required: string[]; desired: string[]; excluded: string[]; sources: number[]; templateIds: string[]; status: Status }
interface Project { id: string; criteria: Criteria; createdAt: number; updatedAt: number }
interface Card { id: string; projectId: string; message: ResearchMessage; stage: Stage; notes: string; interest: number; classification: { kind: Kind; reason: string }; manualKind: Kind | null; createdAt: number; updatedAt: number }
interface ProjectSearchPage extends SearchPage { scanned?: number; matched?: number; pagesScanned?: number; limitReached?: boolean; exhausted?: boolean; alreadyAdded?: string[] }
const publicationKey = (m: ResearchMessage) => `${m.channelId}:${m.id}`;
interface Page<T> { items: T[]; total: number }
const stages: Record<Stage, string> = { NEW: 'Новые', INTERESTING: 'Интересные', CONTACTED: 'Связались', IN_PROGRESS: 'В процессе', REJECTED: 'Не подходит' };
const kinds: Record<Kind, string> = { CANDIDATE: 'Кандидат ищет работу', EMPLOYER: 'Работодатель предлагает работу', OTHER: 'Другая публикация', INSUFFICIENT: 'Недостаточно данных' };
const blank: Criteria = { name: '', description: '', required: [], desired: [], excluded: [], sources: [], templateIds: [], status: 'ACTIVE' };
const terms = (value: string) => value.split(',').map(x => x.trim()).filter(Boolean);
const date = (value: number) => new Date(value).toLocaleString('ru');

export function Projects() {
  const [projects, setProjects] = useState<Page<Project>>({ items: [], total: 0 });
  const [selected, setSelected] = useState<Project | null>(null);
  const [form, setForm] = useState<Criteria | null>(null);
  const [sources, setSources] = useState<MonitoredChannel[]>([]);
  const [templates, setTemplates] = useState<SearchTemplate[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('ACTIVE');
  const [query, setQuery] = useState('');
  const [offset, setOffset] = useState(0);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const c = new AbortController(); setBusy(true); setError('');
    Promise.all([request<Page<Project>>(`/api/projects?limit=25&offset=${offset}&status=${status}&query=${encodeURIComponent(query)}`, c.signal), request<MonitoredChannel[]>('/api/channels', c.signal), request<SearchTemplate[]>('/api/templates', c.signal)])
      .then(([p, s, t]) => { setProjects(p); setSources(s); setTemplates(t); }).catch(e => { if (!c.signal.aborted) setError(e.message); }).finally(() => { if (!c.signal.aborted) setBusy(false); });
    return () => c.abort();
  }, [status, query, offset, revision]);
  async function save(e: FormEvent) {
    e.preventDefault(); if (!form) return; setBusy(true); setError('');
    try { const p = await mutate<Project>(selected ? `/api/projects/${selected.id}` : '/api/projects', selected ? 'PUT' : 'POST', { ...form, required: terms(form.required.join(',')), desired: terms(form.desired.join(',')), excluded: terms(form.excluded.join(',')) }); setSelected(p); setForm(null); setRevision(x => x + 1); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <div className="projects-workspace">
    <div className="project-heading"><div><h2>{form ? selected ? 'Настройка проекта' : 'Новый проект' : selected ? selected.criteria.name : 'Проекты найма'}</h2><p className="helper">Публикации, критерии и ваша работа с ними · сохраняется локально</p></div><button onClick={() => { setSelected(null); setForm(null); setRevision(x => x + 1); }}>Все проекты</button></div>
    {error && <p role="alert" className="error">{error} <button onClick={() => setRevision(x => x + 1)}>Повторить</button></p>}
    {form ? <form className="project-form" onSubmit={save}>
      <label>Название проекта<input required maxLength={100} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} placeholder="Senior Java Backend" /></label>
      <label>Описание вакансии<textarea maxLength={20000} value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} /></label>
      <fieldset><legend>Telegram-источники проекта</legend>{sources.map(s => <label key={s.id}><input type="checkbox" checked={form.sources.includes(s.id)} onChange={e => setForm({ ...form, sources: e.target.checked ? [...form.sources, s.id] : form.sources.filter(x => x !== s.id) })} />{s.title} (@{s.username})</label>)}{form.sources.filter(id => !sources.some(s => s.id === id)).map(id => <label key={id}><input type="checkbox" checked onChange={() => setForm({ ...form, sources: form.sources.filter(x => x !== id) })} />Удалённый источник {id} · уберите из критериев</label>)}{!sources.length && <p>Нет сохранённых источников. В разделе «Поиск» сохраните публичные Telegram-каналы и вернитесь сюда.</p>}</fieldset>
      {(['required', 'desired', 'excluded'] as const).map((key, i) => <label key={key}>{['Обязательные технологии', 'Желательные технологии', 'Исключения'][i]}<input value={form[key].join(', ')} onChange={e => setForm({ ...form, [key]: e.target.value.split(',') })} placeholder={['Java, Spring Boot, PostgreSQL', 'Kafka, Docker', 'стажировка, junior'][i]} /></label>)}
      <p className="helper">Разделяйте требования запятыми. Обязательные должны встретиться все; желательные не исключают публикацию.</p>
      <fieldset><legend>Связанные шаблоны</legend>{templates.map(t => <label key={t.id}><input type="checkbox" checked={form.templateIds.includes(t.id)} onChange={e => setForm({ ...form, templateIds: e.target.checked ? [...form.templateIds, t.id] : form.templateIds.filter(x => x !== t.id) })} />{t.name}</label>)}<p className="helper">Связь сохраняет ссылку на шаблон. Мониторинг включается отдельной кнопкой на доске.</p></fieldset>
      <label>Статус проекта<select value={form.status} onChange={e => setForm({ ...form, status: e.target.value as Status })}><option value="ACTIVE">Активный</option><option value="ARCHIVED">Архивный</option></select></label>
      <div className="project-actions"><button className="primary-button" disabled={busy || !form.sources.length}>Сохранить проект</button><button type="button" onClick={() => setForm(null)}>Отмена</button></div>
    </form> : selected ? <><div className="project-actions"><button onClick={() => setForm(selected.criteria)}>Настроить критерии</button><button onClick={async () => { if (!window.confirm('Удалить проект и его карточки? Исходные публикации сохранятся.')) return; try { await mutate(`/api/projects/${selected.id}`, 'DELETE'); setSelected(null); setRevision(x => x + 1); } catch(e) { setError((e as Error).message); } }}>Удалить проект</button></div><ProjectBoard key={`${selected.id}:${selected.updatedAt}`} project={selected} sources={sources} onError={setError} /></> : <>
      <div className="project-actions"><button className="primary-button" onClick={() => { setSelected(null); setForm({ ...blank }); }}>Создать проект</button><label>Статус<select value={status} onChange={e => { setStatus(e.target.value); setOffset(0); }}><option value="ACTIVE">Активные</option><option value="ARCHIVED">Архивные</option></select></label><label>Найти проект<input type="search" value={query} onChange={e => { setQuery(e.target.value); setOffset(0); }} /></label></div>
      {busy ? <p role="status">Загружаем проекты…</p> : !projects.items.length ? <div className="empty-state"><h3>Проектов пока нет</h3><p>Создайте проект, выберите Telegram-источники и задайте требования. Затем найдите публикации и добавьте интересные на доску.</p></div> : <div className="project-grid">{projects.items.map(p => <button className="project-tile" key={p.id} onClick={async () => { try { setSelected(await request<Project>(`/api/projects/${p.id}`)); } catch(e) { setError((e as Error).message); } }}><strong>{p.criteria.name}</strong><p>{p.criteria.description.slice(0, 180) || 'Описание не добавлено'}</p><span>{p.criteria.required.join(' · ') || 'Без обязательных технологий'}</span><small>Обновлён {date(p.updatedAt)}</small></button>)}</div>}
      <div className="project-actions"><span>{projects.total} проектов</span><button disabled={!offset || busy} onClick={() => setOffset(x => x - 25)}>Назад</button><button disabled={offset + 25 >= projects.total || busy} onClick={() => setOffset(x => x + 25)}>Далее</button></div>
    </>}
  </div>;
}
function ProjectBoard({ project, sources, onError }: { project: Project; sources: MonitoredChannel[]; onError: (s: string) => void }) {
  const [query, setQuery] = useState(''); const [source, setSource] = useState(''); const [technology, setTechnology] = useState(''); const [sort, setSort] = useState('updated');
  const [revision, setRevision] = useState(0); const [stats, setStats] = useState<{ total: number; stages: Record<Stage, number> } | null>(null);
  const [searchQuery, setSearchQuery] = useState(project.criteria.required[0] || ''); const [mode, setMode] = useState('archive'); const [results, setResults] = useState<ProjectSearchPage | null>(null); const [searchBusy, setSearchBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [searchError, setSearchError] = useState('');
  const [added, setAdded] = useState<Set<string>>(new Set()); const [adding, setAdding] = useState<Set<string>>(new Set());
  const searchController = useRef<AbortController | null>(null);
  const retryMore = useRef(false);
  useEffect(() => () => searchController.current?.abort(), []);
  const selectedSources = sources.filter(s => project.criteria.sources.includes(s.id));
  function resetSearch() { searchController.current?.abort(); searchController.current = null; setSearchBusy(false); setResults(null); setSearchError(''); }

  const [matches, setMatches] = useState<{ messages: ResearchMessage[]; nextCursor: number | null } | null>(null);
  const [saved, setSaved] = useState<{ items: { id: string; message: ResearchMessage }[]; nextCursor: number | null } | null>(null);
  const archived = project.criteria.status === 'ARCHIVED';
  useEffect(() => { const c = new AbortController(); request<typeof stats>(`/api/projects/${project.id}/statistics`, c.signal).then(setStats).catch(e => { if (!c.signal.aborted) onError(e.message); }); return () => c.abort(); }, [project.id, revision]);
  async function add(message: ResearchMessage) {
    const key = publicationKey(message); setAdding(old => new Set(old).add(key));
    try { await mutate(`/api/projects/${project.id}/cards`, 'POST', { channelId: message.channelId, messageId: message.id }); setAdded(old => new Set(old).add(key)); setRevision(x => x + 1); onError(''); }
    catch(e) { onError((e as Error).message); } finally { setAdding(old => { const next = new Set(old); next.delete(key); return next; }); }
  }
  async function original(message: ResearchMessage) {
    try {
      const { link } = await request<{ link: string }>(`/api/messages/${message.channelId}/${message.id}/link`);
      if (!/^https:\/\/t\.me\//.test(link)) throw new Error('Ссылка на оригинал недоступна');
      setResults(old => old && ({ ...old, messages: old.messages.map(m => publicationKey(m) === publicationKey(message) ? { ...m, link } : m) }));
    } catch(e) { setSearchError((e as Error).message); }
  }
  async function search(more = false) {
    searchController.current?.abort(); const c = new AbortController(); searchController.current = c;
    retryMore.current = more; if (!more) setResults(null);
    setSearchBusy(true); setSearchError(''); onError('');
    try {
      const page = await request<ProjectSearchPage>(`/api/projects/${project.id}/search`, c.signal,
        { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: searchQuery, mode, cursor: more ? results?.cursor || '' : '' }) }, 30_000);
      if (c.signal.aborted) return;
      setResults(page); setAdded(new Set(page.alreadyAdded || []));
    } catch(e) { if (!c.signal.aborted) setSearchError((e as Error).message); }
    finally { if (searchController.current === c) { setSearchBusy(false); searchController.current = null; } }
  }
  return <>
    <p>{project.criteria.description}</p><p className="helper">Обязательные: {project.criteria.required.join(', ') || 'не заданы'} · Желательные: {project.criteria.desired.join(', ') || 'не заданы'} · Исключения: {project.criteria.excluded.join(', ') || 'не заданы'}</p>
    {notice && <p role="status">{notice}</p>}
    {archived && <p role="status">Проект архивирован. Для редактирования карточек верните его в активные.</p>}
    <div className="project-statistics" aria-label="Статистика проекта"><strong>Всего: {stats?.total ?? '…'}</strong>{Object.entries(stages).map(([s, label]) => <span key={s}>{label}: {stats?.stages[s as Stage] ?? 0}</span>)}</div>
    {!archived && <details className="project-discovery"><summary>Найти и добавить публикации</summary>
    <p className="helper">Источники → требования → поиск → просмотр текста → добавление на доску. Совпадение слов в публикации не подтверждает навыки человека.</p>
    <p>Источники: {selectedSources.map(s => s.title).join(', ') || 'не выбраны'}. Изменить их можно кнопкой «Настроить критерии».</p>
    {!selectedSources.length && <p role="status">Нет сохранённых источников проекта. Сохраните каналы в разделе «Поиск» и выберите их в настройках проекта.</p>}
    <p className="helper">Архив содержит ранее загруженные публикации. Поиск в Telegram обращается к выбранным каналам; подписка и загрузка истории включаются отдельно.</p><div className="project-actions"><label>Где искать<select value={mode} onChange={e => { resetSearch(); setMode(e.target.value); }}><option value="archive">Локальный архив</option><option value="telegram">Telegram · выбранные источники</option></select></label><label>Запрос<input value={searchQuery} onChange={e => { resetSearch(); setSearchQuery(e.target.value); }} /></label><button className="primary-button" disabled={searchBusy || !searchQuery.trim() || !selectedSources.length} onClick={() => void search()}>Найти публикации</button><button onClick={async () => { try { await mutate(`/api/projects/${project.id}/subscription`, 'POST'); onError(''); setNotice('Мониторинг включён. Шаблон связан с проектом; новые совпадения доступны в уведомлениях.'); } catch(e) { onError((e as Error).message); } }}>Включить мониторинг по критериям</button></div>
    {searchBusy && <p role="status">Просматриваем публикации и проверяем требования… <button onClick={resetSearch}>Остановить поиск</button></p>}
    {searchError && <p role="alert">Поиск не выполнен: {searchError} <button disabled={searchBusy} onClick={() => void search(retryMore.current)}>Повторить поиск</button></p>}
    {results && <>
      {results.scanned !== undefined && <p role="status">За этот шаг проверено публикаций: {results.scanned} · Совпадений: {results.matched ?? results.messages.length}</p>}
      {results.state === 'archive_empty' ? <p>Архив выбранных источников пуст. Найдите публикации в Telegram или загрузите историю в разделе «Архив».</p> : results.state === 'no_sources' ? <p>Выберите сохранённые Telegram-источники в настройках проекта.</p> : <>
        {results.limitReached && <p role="status">Достигнут лимит поиска за один шаг. {results.cursor ? 'Можно продолжить с места остановки.' : 'Уточните запрос и начните поиск заново.'}</p>}
        {['telegram_error', 'partial', 'rate_limited', 'search_timeout', 'cursor_stalled'].includes(results.state) && <p role="alert">{results.state === 'rate_limited' ? 'Telegram ограничил частоту запросов. Подождите перед продолжением.' : results.state === 'search_timeout' ? 'Поиск занял слишком много времени.' : 'Запрос Telegram не выполнен полностью.'} Уже найденные публикации доступны ниже. {results.cursor && 'Продолжение сохраняет место остановки.'}</p>}
        {!results.messages.length && !['telegram_error', 'partial', 'rate_limited', 'search_timeout', 'cursor_stalled'].includes(results.state) && <p>{results.scanned ? 'Требования ограничивают выборку: среди проверенных публикаций нет текста со всеми обязательными словами без исключений. Попробуйте уточнить требования.' : 'По запросу не найдено совпадений.'} {results.cursor ? 'Поиск ещё не завершён — продолжите просмотр.' : 'Попробуйте другой запрос или источник.'}</p>}
        {!!results.messages.length && results.messages.every(m => added.has(publicationKey(m))) && <p role="status">Все найденные публикации уже добавлены на доску.</p>}
        {results.messages.map(m => <article className="discovery-result" key={publicationKey(m)}><strong>{m.title}</strong><p>{m.text}</p>
          <p className="helper">Обязательные слова в тексте: {m.explanation?.foundRequired.join(', ') || 'не заданы'}</p>
          <p className="helper">Желательные слова в тексте: {m.explanation?.foundAny.join(', ') || 'не обнаружены'}</p>
          <div className="project-actions">{m.link && /^https:\/\/t\.me\//.test(m.link) ? <a href={m.link} target="_blank" rel="noreferrer">Оригинал в Telegram ↗</a> : <button onClick={() => void original(m)}>Получить ссылку на оригинал</button>}
          <button disabled={added.has(publicationKey(m)) || adding.has(publicationKey(m)) || searchBusy} onClick={() => void add(m)}>{added.has(publicationKey(m)) ? 'Уже на доске' : adding.has(publicationKey(m)) ? 'Добавляем…' : 'Добавить на доску'}</button></div></article>)}
        {results.cursor && <button disabled={searchBusy} onClick={() => void search(true)}>Продолжить поиск</button>}
      </>}
      {results.notice && <details><summary>Подробности поиска</summary><p className="helper">{results.notice}</p></details>}
    </>}
    <button onClick={async () => { try { setMatches(await request(`/api/projects/${project.id}/matches?limit=20`)); } catch(e) { onError((e as Error).message); } }}>Совпадения связанных шаблонов</button>{matches?.messages.map(m => <article className="discovery-result" key={`${m.channelId}:${m.id}`}><strong>{m.title}</strong><p>{m.text}</p><button onClick={() => void add(m)}>Добавить совпадение на доску</button></article>)}{matches && !matches.messages.length && <p>Сохранённых совпадений пока нет. Свяжите шаблон или включите мониторинг; новая подходящая публикация появится здесь.</p>}{matches?.nextCursor && <button onClick={async () => { try { setMatches(await request(`/api/projects/${project.id}/matches?limit=20&before=${matches.nextCursor}`)); } catch(e) { onError((e as Error).message); } }}>Следующая страница совпадений</button>}
    <button onClick={async () => { try { setSaved(await request('/api/saved?status=SAVED&limit=25')); } catch(e) { onError((e as Error).message); } }}>Добавить из избранного</button>{saved?.items.filter(s => project.criteria.sources.includes(s.message.channelId)).map(s => <article className="discovery-result" key={s.id}><strong>{s.message.title}</strong><p>{s.message.text}</p><button onClick={() => void add(s.message)}>Добавить сохранённую публикацию</button></article>)}{saved && !saved.items.some(s => project.criteria.sources.includes(s.message.channelId)) && <p>На этой странице нет сохранённых публикаций из разрешённых источников.</p>}{saved?.nextCursor && <button onClick={async () => { try { setSaved(await request(`/api/saved?status=SAVED&limit=25&before=${saved.nextCursor}`)); } catch(e) { onError((e as Error).message); } }}>Следующая страница избранного</button>}</details>}
    <div className="board-filters"><label>Поиск внутри проекта<input type="search" value={query} onChange={e => setQuery(e.target.value)} /></label><label>Источник<select value={source} onChange={e => setSource(e.target.value)}><option value="">Все источники</option>{sources.filter(s => project.criteria.sources.includes(s.id)).map(s => <option key={s.id} value={s.id}>{s.title}</option>)}</select></label><label>Технология<input value={technology} onChange={e => setTechnology(e.target.value)} list="project-technologies" /></label><datalist id="project-technologies">{[...project.criteria.required, ...project.criteria.desired].map(t => <option key={t} value={t} />)}</datalist><label>Сортировка<select value={sort} onChange={e => setSort(e.target.value)}><option value="updated">Последние изменения</option><option value="interest">По интересу</option><option value="oldest">Сначала старые</option></select></label></div>
    <div className="kanban">{Object.entries(stages).map(([stage, label]) => <Column key={stage} project={project} stage={stage as Stage} label={label} filters={{ query, source, technology, sort }} revision={revision} changed={() => setRevision(x => x + 1)} onError={onError} />)}</div>
  </>;
}
function Column({ project, stage, label, filters, revision, changed, onError }: { project: Project; stage: Stage; label: string; filters: Record<string, string>; revision: number; changed: () => void; onError: (s: string) => void }) {
  const [page, setPage] = useState<Page<Card>>({ items: [], total: 0 }); const [offset, setOffset] = useState(0); const [busy, setBusy] = useState(false); const [failed, setFailed] = useState(false);
  const filterKey = JSON.stringify(filters);
  useEffect(() => { setOffset(0); }, [filterKey, revision]);
  useEffect(() => { const c = new AbortController(); setBusy(true); setFailed(false); const q = new URLSearchParams({ ...filters, stage, limit: '20', offset: String(offset) }); if (!filters.source) q.delete('source');
    request<Page<Card>>(`/api/projects/${project.id}/cards?${q}`, c.signal).then(setPage).catch(e => { if (!c.signal.aborted) { setFailed(true); onError(e.message); } }).finally(() => { if (!c.signal.aborted) setBusy(false); }); return () => c.abort();
  }, [project.id, filterKey, stage, offset, revision]);
  return <section className="kanban-column" aria-label={label}><h3>{label} <span>{page.total}</span></h3>{busy ? <p role="status">Загружаем…</p> : failed ? <p>Не удалось загрузить карточки.</p> : !page.items.length ? <p className="column-empty">Публикаций пока нет</p> : page.items.map(card => <CardEditor key={`${card.id}:${card.updatedAt}`} card={card} archived={project.criteria.status === 'ARCHIVED'} changed={changed} onError={onError} />)}{page.total > 20 && <div className="project-actions"><button disabled={!offset || busy} onClick={() => setOffset(x => Math.max(0, x - 20))}>Назад</button><button disabled={offset + 20 >= page.total || busy} onClick={() => setOffset(x => x + 20)}>Далее</button></div>}</section>;
}
function CardEditor({ card, archived, changed, onError }: { card: Card; archived: boolean; changed: () => void; onError: (s: string) => void }) {
  const [notes, setNotes] = useState(card.notes); const [interest, setInterest] = useState(card.interest); const [stage, setStage] = useState(card.stage); const [kind, setKind] = useState<Kind | ''>(card.manualKind || ''); const [busy, setBusy] = useState(false); const [link, setLink] = useState(card.message.link);
  async function save(e: FormEvent) { e.preventDefault(); setBusy(true); try { await mutate(`/api/projects/${card.projectId}/cards/${card.id}`, 'PUT', { notes, interest, stage, manualKind: kind || null }); changed(); onError(''); } catch(e) { onError((e as Error).message); } finally { setBusy(false); } }
  return <article className="recruitment-card"><strong>{card.message.title || card.message.username}</strong><small>{date(card.message.date * 1000)}</small><p className="publication-text">{card.message.text || 'Публикация без текста'}</p>{link && /^https:\/\/t\.me\//.test(link) ? <a href={link} target="_blank" rel="noreferrer">Оригинал в Telegram ↗</a> : <button onClick={async () => { try { const result = await request<{ link: string }>(`/api/messages/${card.message.channelId}/${card.message.id}/link`); if (!/^https:\/\/t\.me\//.test(result.link)) throw new Error('Ссылка на оригинал недоступна'); setLink(result.link); } catch(e) { onError((e as Error).message); } }}>Получить ссылку на оригинал</button>}<p className="classification">{kinds[card.manualKind || card.classification.kind]}</p><p className="helper">{card.manualKind ? 'Классификация исправлена вручную. ' : ''}{card.classification.reason}</p>
    <form onSubmit={save}><fieldset disabled={busy || archived}><label>Стадия<select aria-label="Стадия" value={stage} onChange={e => setStage(e.target.value as Stage)}>{Object.entries(stages).map(([s, title]) => <option key={s} value={s}>{title}</option>)}</select></label><label>Интерес<select aria-label="Интерес" value={interest} onChange={e => setInterest(Number(e.target.value))}>{[0, 1, 2, 3, 4, 5].map(x => <option key={x} value={x}>{x ? `${x} из 5` : 'Не оценён'}</option>)}</select></label><label>Классификация<select aria-label="Классификация" value={kind} onChange={e => setKind(e.target.value as Kind | '')}><option value="">Автоматически</option>{Object.entries(kinds).map(([k, label]) => <option key={k} value={k}>{label}</option>)}</select></label><label>Заметки<textarea maxLength={10000} value={notes} onChange={e => setNotes(e.target.value)} /></label><button className="primary-button">{busy ? 'Сохраняем…' : 'Сохранить карточку'}</button><button type="button" onClick={async () => { if (!window.confirm('Убрать публикацию из этого проекта?')) return; try { await mutate(`/api/projects/${card.projectId}/cards/${card.id}`, 'DELETE'); changed(); } catch(e) { onError((e as Error).message); } }}>Убрать из проекта</button></fieldset></form><small>Изменено {date(card.updatedAt)}</small></article>;
}
