import { useState } from 'react';
import { mutate } from './api';
import type { MonitoredChannel, SearchTemplate } from './api';

const empty: SearchTemplate = { id: '', name: '', required: [], any: [], excluded: [], telegramQuery: '', sourceMode: 'all', sources: [], active: true, subscribed: false };
const split = (value: string) => value.split('\n').map(s => s.trim()).filter(Boolean);

export function Templates({ templates, channels, refresh, onRun, onError }: {
  templates: SearchTemplate[]; channels: MonitoredChannel[]; refresh: () => Promise<void>;
  onRun: (template: SearchTemplate) => void; onError: (message: string) => void;
}) {
  const [draft, setDraft] = useState<SearchTemplate | null>(null);
  const [busy, setBusy] = useState(false);
  async function action(task: () => Promise<unknown>) {
    setBusy(true);
    try { await task(); await refresh(); }
    catch (e) { onError((e as Error).message); }
    finally { setBusy(false); }
  }
  return <div className="templates">
    <h2>Шаблоны поиска</h2>
    <p className="helper">Telegram-запрос отбирает кандидатов. Локальные AND / OR / NOT проверяют текст отдельно.</p>
    {!draft ? <>
      <button className="secondary-button" onClick={() => setDraft({ ...empty })}>+ Создать шаблон</button>
      {!templates.length && <p className="helper">Сохраните слова и фразы, чтобы повторять исследование.</p>}
      {templates.map(t => <div className="template-card" key={t.id}><strong>{t.name}</strong><small>{t.active ? 'Активен' : 'Приостановлен'} · {t.sourceMode === 'global' ? 'Глобальный · вручную' : t.sourceMode === 'all' ? 'Все источники' : `${t.sources.length} источников`}</small><p>{t.required.join(' + ') || t.any.join(' / ')}</p><div className="template-actions"><button className="text-button" disabled={busy || !t.active} onClick={() => onRun(t)}>Запустить</button><button className="text-button" disabled={busy} onClick={() => setDraft(t)}>Изменить</button><button className="text-button" disabled={busy} onClick={() => void action(() => mutate(`/api/templates/${t.id}`, 'PUT', { ...t, active: !t.active }))}>{t.active ? 'Пауза' : 'Активировать'}</button>{t.sourceMode !== 'global' && <button className="text-button" disabled={busy} onClick={() => void action(() => mutate(`/api/templates/${t.id}`, 'PUT', { ...t, subscribed: !t.subscribed }))}>{t.subscribed ? 'Отписаться' : 'Уведомлять'}</button>}<button className="text-button danger" disabled={busy} onClick={() => void action(() => mutate(`/api/templates/${t.id}`, 'DELETE'))}>Удалить</button></div></div>)}
    </> : <form onSubmit={e => { e.preventDefault(); void action(async () => { await mutate(draft.id ? `/api/templates/${draft.id}` : '/api/templates', draft.id ? 'PUT' : 'POST', { ...draft, required: split(draft.required.join('\n')), any: split(draft.any.join('\n')), excluded: split(draft.excluded.join('\n')) }); setDraft(null); }); }}>
      <label htmlFor="template-name">Название шаблона</label><input id="template-name" required maxLength={100} value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} />
      <label htmlFor="telegram-query">Запрос Telegram</label><input id="telegram-query" required maxLength={256} placeholder="Java" value={draft.telegramQuery} onChange={e => setDraft({ ...draft, telegramQuery: e.target.value })} /><p className="helper compact">Один поисковый запрос, без локальных AND/OR/NOT. Ограничивает проверяемую выборку.</p>
      {([['required', 'Все слова / фразы (AND)'], ['any', 'Хотя бы одно (OR)'], ['excluded', 'Исключения (NOT)']] as const).map(([key, label]) => <div key={key}><label htmlFor={`terms-${key}`}>{label}</label><textarea id={`terms-${key}`} rows={3} placeholder="Каждое слово или фраза с новой строки" value={draft[key].join('\n')} onChange={e => setDraft({ ...draft, [key]: e.target.value.split('\n') })} onBlur={() => setDraft({ ...draft, [key]: split(draft[key].join('\n')) })} /></div>)}
      <label htmlFor="template-source-mode">Источники шаблона</label><select id="template-source-mode" value={draft.sourceMode} onChange={e => setDraft({ ...draft, sourceMode: e.target.value as SearchTemplate['sourceMode'], subscribed: false })}><option value="all">Все сохранённые каналы</option><option value="selected">Выбранные каналы</option><option value="global">Глобальный поиск · вручную</option></select>
      {draft.sourceMode === 'selected' && <div className="source-checkboxes">{channels.map(c => <label key={c.id}><input type="checkbox" checked={draft.sources.includes(c.id)} onChange={e => setDraft({ ...draft, sources: e.target.checked ? [...draft.sources, c.id] : draft.sources.filter(id => id !== c.id) })} />@{c.username}</label>)}{!channels.length && <p className="helper">Сначала сохраните источник.</p>}</div>}
      {draft.sourceMode !== 'global' && <label className="checkbox-label"><input type="checkbox" checked={draft.subscribed} onChange={e => setDraft({ ...draft, subscribed: e.target.checked })} />Уведомлять о новых совпадениях</label>}<button className="primary-button" disabled={busy}>{busy ? 'Сохраняем…' : 'Сохранить шаблон'}</button><button className="secondary-button" type="button" disabled={busy} onClick={() => setDraft(null)}>Отмена</button>
    </form>}
  </div>;
}
