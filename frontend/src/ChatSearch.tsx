import { useEffect, useRef, useState } from 'react';
import { ChatPicker } from './ChatPicker';
import { NotificationPreferences } from './NotificationPreferences';
import { ownerMutation } from './ownerApi';
import type { ResearchMessage, SearchPage, SearchTemplate } from './api';
import './searchWorkflow.css';

interface Result extends SearchPage { fetchedAt: number; origin: 'telegram' | 'cache' }
const terms = (value: string) => value.split('\n').map(s => s.trim()).filter(Boolean);
export function ChatSearch({ templates, initialKey, templateId, onSelect, refresh, onSavedSearch }: { templates: SearchTemplate[]; initialKey: string; templateId: string; onSelect: (m: ResearchMessage) => void; refresh: () => Promise<void>; onSavedSearch?: () => void }) {
  const selected = templates.find(t => t.id === templateId);
  const [step, setStep] = useState(selected ? 2 : 1);
  const [keys, setKeys] = useState<string[]>(selected?.sourceKeys ?? (initialKey ? [initialKey] : []));
  const [query, setQuery] = useState(selected?.telegramQuery ?? '');
  const [conditions, setConditions] = useState({ required: selected?.required.join('\n') ?? '', any: selected?.any.join('\n') ?? '', excluded: selected?.excluded.join('\n') ?? '' });
  const [result, setResult] = useState<Result | null>(null);
  const [busy, setBusy] = useState(false);
  const [picking, setPicking] = useState(false);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [saveBusy, setSaveBusy] = useState(false);
  const [name, setName] = useState('');
  const [monitor, setMonitor] = useState(false);
  const [popups, setPopups] = useState(false);
  const [notice, setNotice] = useState('');
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  const rules = { required: terms(conditions.required), any: terms(conditions.any), excluded: terms(conditions.excluded) };
  const hasRules = Object.values(rules).some(list => list.length);
  const savedRules = { ...rules, required: rules.required.length || rules.any.length ? rules.required : [query.trim()] };
  const ready = !!keys.length && !!query.trim() && !picking;
  async function search(cached = false, more = false) {
    controller.current?.abort();
    const c = new AbortController(); controller.current = c; setBusy(true); setError(''); setNotice(''); setStep(3);
    try {
      const page = await ownerMutation<Result>('/api/workspace/search', 'POST', { search: { mode: 'sources', sourceKeys: keys, query, templateId: selected?.id, ...(hasRules && !selected ? { conditions: rules } : {}), limit: 30, cursor: more ? result?.cursor ?? '' : '' }, cached }, c.signal, 95_000);
      if (!c.signal.aborted) setResult(old => ({ ...page, messages: more ? [...new Map([...(old?.messages ?? []), ...page.messages].map(m => [`${m.channelId}:${m.id}`, m])).values()] : page.messages }));
    } catch(e) { if (!c.signal.aborted) setError((e as Error).message); }
    finally { if (controller.current === c) { setBusy(false); controller.current = null; } }
  }
  useEffect(() => { if (selected) void search(); }, [selected?.id]);
  async function save() {
    setSaveBusy(true); setError('');
    try {
      await ownerMutation('/api/workspace/templates', 'POST', { name, ...savedRules, telegramQuery: query, sourceMode: 'chats', sourceKeys: keys, active: true, subscribed: monitor, notificationsEnabled: popups });
      setSaving(false);
      try { await refresh(); } catch { setNotice('Поиск сохранён. Список поисков не обновился: откройте «Сохранённые поиски», чтобы повторить загрузку.'); return; }
       setNotice(monitor ? 'Поиск сохранён. Новые публикации будут отслеживаться с текущего момента.' : 'Поиск сохранён. Отслеживание выключено.'); onSavedSearch?.();
    } catch(e) { setError((e as Error).message); }
    finally { setSaveBusy(false); }
  }
  return <section aria-label="Поиск в чатах" className="search-workflow"><h2>Поиск в Telegram</h2>
    {error && <p role="alert" className="error">{error} <button className="text-button" onClick={() => setError('')}>Скрыть</button></p>}{notice && <p role="status">{notice}</p>}
    <nav className="search-stepper" aria-label="Этапы поиска">{['Где искать', 'Что искать', 'Результаты'].map((label, i) => <button key={label} aria-current={step === i + 1 ? 'step' : undefined} disabled={busy || saveBusy || picking || (i > 0 && !keys.length)} onClick={() => setStep(i + 1)}>{i + 1}. {label}</button>)}</nav>
    <div className="search-steps"><section className="search-step" hidden={step !== 1}><h3>1. Чаты и темы</h3>
      <ChatPicker onBusyChange={setPicking} keys={keys} disabled={busy || saveBusy || !!selected} onChange={value => { setKeys(value); setResult(null); }} />
      {selected && <p className="helper">Область сохранённого поиска фиксирована. Измените её в настройках сохранённого поиска.</p>}
    <button className="primary-button" disabled={!keys.length || busy || saveBusy || picking} onClick={() => setStep(2)}>Далее: что искать</button>
    </section><section className="search-step" hidden={step !== 2}><h3>2. Поисковый запрос</h3>
      <p className="helper">Выбрано чатов и тем: {keys.length}. <button className="text-button" onClick={() => setStep(1)}>Изменить выбор</button></p><label>Запрос в чатах<input value={query} maxLength={256} disabled={busy || saveBusy || !!selected} onChange={e => { setQuery(e.target.value); setResult(null); }} /></label>
      <p className="helper">Telegram отбирает публикации по запросу. Дополнительные условия проверяют текст полученной выборки.</p>
      <details><summary>Дополнительные условия · AND / OR / NOT</summary>
        {([['required', 'Все слова / фразы (AND)'], ['any', 'Хотя бы одно (OR)'], ['excluded', 'Исключения (NOT)']] as const).map(([key, label]) => <label key={key}>{label}<textarea rows={2} disabled={busy || saveBusy || !!selected} value={conditions[key]} onChange={e => { setConditions({ ...conditions, [key]: e.target.value }); setResult(null); }} /></label>)}
        <p className="helper">Каждая фраза с новой строки, до 20 фраз по 100 символов в каждом поле.</p>
      </details>
      {selected && <p className="helper">Сохранённый поиск: {selected.name}. Условия изменяются через его настройки.</p>}
      <div className="search-actions"><button className="primary-button" disabled={busy || saveBusy || !ready} onClick={() => void search()}>Искать в выбранных чатах</button><button className="secondary-button" disabled={busy || saveBusy || !ready} onClick={() => { setSaving(true); setName(selected?.name ?? query); }}>Сохранить поиск…</button></div>
      <details><summary>Локальный кэш поиска</summary><p className="helper">Сохранённые страницы этого запроса: до 24 часов и 50 страниц. Кэш отделён от архива и избранного.</p>
        <button className="secondary-button" disabled={busy || !ready} onClick={() => void search(true)}>Показать локальный кэш этого запроса</button><button className="text-button" disabled={busy} onClick={() => void ownerMutation('/api/workspace/cache', 'DELETE').then(() => { setResult(null); setNotice('Кэш очищен. Архив и сохранённые публикации сохранены.'); }).catch(e => setError((e as Error).message))}>Очистить кэш поиска</button>
      </details>
    </section></div>
    {saving && <form className="search-save-form" onSubmit={e => { e.preventDefault(); void save(); }}><h3>Сохранить поиск</h3>
      <label>Название сохранённого поиска<input required maxLength={100} disabled={saveBusy} value={name} onChange={e => setName(e.target.value)} /></label>
      <p className="helper">Условия сохранённого поиска: {[savedRules.required.length ? `Все: ${savedRules.required.join(', ')}` : '', savedRules.any.length ? `Любое: ${savedRules.any.join(', ')}` : '', savedRules.excluded.length ? `Без: ${savedRules.excluded.join(', ')}` : ''].filter(Boolean).join(' · ')}. Если обязательные и альтернативные фразы не заданы, используется вся фраза запроса. Уточнить условия можно в шаге «Что искать».</p>
      <label className="checkbox-label"><input type="checkbox" disabled={saveBusy} checked={monitor} onChange={e => setMonitor(e.target.checked)} />Отслеживать новые публикации</label>
      <p className="helper">Отслеживание проверяет текстовые условия выше; Telegram-запрос используется при разовом поиске. Только новые публикации с текущего момента в выбранных чатах. Область фиксируется, новые чаты не подключаются автоматически. Совпадения сохраняются, пока работает hradar.</p>
      <label className="checkbox-label"><input type="checkbox" disabled={saveBusy} checked={popups} onChange={e => setPopups(e.target.checked)} />Всплывающие уведомления для этого поиска</label>
      <p className="helper">Всплывающие уведомления требуют общего включения, разрешения браузера и открытой вкладки. Отказ браузера не отключает отслеживание и сохранение совпадений.</p>
      {popups && <NotificationPreferences compact />}
      <button className="primary-button" disabled={saveBusy || !ready}>{saveBusy ? 'Сохраняем…' : 'Сохранить поиск'}</button><button type="button" className="text-button" disabled={saveBusy} onClick={() => setSaving(false)}>Отмена</button>
    </form>}
    <section className="search-step search-results" hidden={step !== 3}><h3>3. Результаты</h3><div className="action-row"><button className="secondary-button" disabled={busy} onClick={() => setStep(2)}>Изменить запрос</button><button className="secondary-button" disabled={busy || !ready} onClick={() => { setSaving(true); setName(selected?.name ?? query); }}>Сохранить поиск…</button></div>
      {busy && <p role="status">Ищем в выбранных чатах… <button className="text-button" onClick={() => { controller.current?.abort(); setBusy(false); setNotice('Поиск отменён. Уже полученные результаты сохранены на экране.'); }}>Отменить поиск</button></p>}
      {!result && !busy && <p className="helper">Выберите чаты и введите запрос. Результаты появятся здесь.</p>}
      {result && <><p role="status">{result.origin === 'cache' ? 'Локальный кэш · не актуальный запрос Telegram' : 'Получено из Telegram'} · {new Date(result.fetchedAt).toLocaleString('ru-RU')} · {result.messages.length} результатов</p>{result.notice && <p role="status">{result.notice}</p>}{result.state !== 'ok' && <p className="helper">Поиск неполный: {result.state === 'rate_limited' && !result.notice ? 'Telegram ограничил частоту запросов. Повторите позже.' : 'не все выбранные чаты удалось проверить. Полученные результаты сохранены.'}</p>}<p className="helper">Охват: выбранные {keys.length} чатов/тем. Это ограниченная выборка, не поиск по всему Telegram.</p>
        {!result.messages.length && <p>В этой выборке публикаций нет.</p>}
        {result.messages.map(m => <article className="message-card" key={`${m.channelId}:${m.id}`}><div className="message-meta"><strong>{m.title}{m.scopeTitle && ` / ${m.scopeTitle}`}</strong><time>{new Date(m.date * 1000).toLocaleString('ru-RU')}</time></div><p className="message-text">{m.text}</p><button className="text-button" onClick={() => onSelect(m)}>Открыть публикацию</button><button className="text-button" disabled={busy || !m.sourceKey} onClick={() => void ownerMutation('/api/workspace/saved', 'POST', { sourceKey: m.sourceKey, messageId: m.id }).then(() => setNotice('Публикация сохранена. Откройте «Сохранённые публикации».')).catch(e => setError((e as Error).message))}>Сохранить публикацию</button></article>)}
        {result.cursor && <button className="secondary-button" disabled={busy} onClick={() => void search(false, true)}>Продолжить поиск</button>}</>}
    </section>
  </section>;
}
