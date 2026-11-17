import { useEffect, useState } from 'react';
import { ChatPicker } from './ChatPicker';
import { ownerMutation } from './ownerApi';
import { request, mutate } from './api';
import type { MonitoredChannel, SearchTemplate } from './api';

const empty: SearchTemplate = { id: '', name: '', required: [], any: [], excluded: [], telegramQuery: '', sourceMode: 'chats', sourceKeys: [], notificationsEnabled: true, sources: [], active: true, subscribed: false };
const split = (value: string) => value.split('\n').map(s => s.trim()).filter(Boolean);

export function Templates({ templates, channels, refresh, onRun, onError }: {
  templates: SearchTemplate[]; channels: MonitoredChannel[]; refresh: () => Promise<void>;
  onRun: (template: SearchTemplate) => void; onError: (message: string) => void;
}) {
  const [draft, setDraft] = useState<SearchTemplate | null>(null);
  const [busy, setBusy] = useState(false);
  const [states, setStates] = useState<{ templateId: string; enabled: boolean; status: string; error: string | null; retryAt: number }[]>([]);
  async function loadStates() { setStates(await request('/api/workspace/monitoring')); }
  useEffect(() => { void loadStates().catch(() => {}); }, [templates]);
  async function reload() { await refresh(); await loadStates(); }
  const hasLocal = (id: string) => states.some(s => s.templateId === id);
  const live = (t: SearchTemplate) => t.active && (t.subscribed || states.some(s => s.templateId === t.id && s.enabled));
  const statuses: Record<string, string> = { active: 'Мониторинг включён', paused: 'На паузе', template_paused: 'Шаблон на паузе', recovering: 'Восстановление пропущенных сообщений', rate_limited: 'Лимит Telegram', unavailable: 'Нет доступа к чату', access_denied: 'Нет доступа к чату', retrying: 'Повторная попытка', error: 'Ошибка мониторинга' };
  function save(t: SearchTemplate) {
    return ownerMutation(t.id ? `/api/workspace/templates/${t.id}` : '/api/workspace/templates', t.id ? 'PUT' : 'POST', t);
  }
  async function action(task: () => Promise<unknown>) {
    setBusy(true);
    try { await task(); await reload(); }
    catch (e) { onError((e as Error).message); }
    finally { setBusy(false); }
  }
  return <div className="templates">
    <h2>Шаблоны и мониторинг</h2><p className="helper">Выберите чаты и условия. Включение мониторинга автоматически подключает выбранные чаты; второй настройки в «Источниках» не требуется. При паузе сохраняется позиция обработки. Область фиксируется, до 10 чатов/тем. Уведомления настраиваются отдельно от мониторинга.</p>
    <button className="secondary-button" disabled={busy} onClick={() => void reload().catch(e => onError((e as Error).message))}>Обновить подписки</button>
    <p className="helper">Telegram-запрос отбирает кандидатов. Локальные AND / OR / NOT проверяют текст отдельно.</p>
    {!draft ? <>
      <button className="secondary-button" onClick={() => setDraft({ ...empty })}>+ Создать шаблон</button>
      {!templates.length && <p className="helper">Сохраните слова и фразы, чтобы повторять исследование.</p>}
      {templates.map(t => <div className="template-card" key={t.id}><strong>{t.name}</strong><small>{t.active ? 'Активен' : 'Приостановлен'} · {live(t) ? 'Мониторинг включён' : 'Мониторинг выключен'} · {t.sourceMode === 'global' ? 'Глобальный · вручную' : t.sourceMode === 'chats' ? `${t.sourceKeys?.length ?? 0} чатов/тем` : t.sourceMode === 'all' ? 'Существующие публичные каналы' : `${t.sources.length} публичных каналов`}</small><p>{t.required.join(' + ') || t.any.join(' / ')}</p>{states.filter(s => s.templateId === t.id).map((s, i) => <p key={i} role="status">{statuses[s.status] ?? 'Состояние мониторинга уточняется'}{s.error && ` · ${s.error}`}{s.retryAt > Date.now() && ` · Следующая попытка: ${new Date(s.retryAt).toLocaleString('ru-RU')}`}</p>)}<div className="template-actions"><button className="text-button" disabled={busy || !t.active} onClick={() => onRun(t)}>Запустить</button><button className="text-button" disabled={busy} onClick={() => setDraft(t)}>Изменить</button><button className="text-button" disabled={busy} onClick={() => void action(() => save({ ...t, active: !t.active }))}>{t.active ? 'Пауза' : 'Активировать'}</button>{t.sourceMode !== 'global' && !(t.sourceMode !== 'chats' && hasLocal(t.id)) && <button className="text-button" disabled={busy} onClick={() => void action(() => save({ ...t, subscribed: !t.subscribed }))}>{t.subscribed ? 'Выключить мониторинг' : 'Включить мониторинг'}</button>}<button className="text-button danger" disabled={busy} onClick={() => void action(() => mutate(`/api/templates/${t.id}`, 'DELETE'))}>Удалить</button></div></div>)}
    </> : <form onSubmit={e => { e.preventDefault(); void action(async () => { await save({ ...draft, required: split(draft.required.join('\n')), any: split(draft.any.join('\n')), excluded: split(draft.excluded.join('\n')) }); setDraft(null); }); }}>
      <label htmlFor="template-name">Название шаблона</label><input id="template-name" required maxLength={100} value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} />
      <label htmlFor="telegram-query">Запрос Telegram</label><input id="telegram-query" required maxLength={256} placeholder="Java" value={draft.telegramQuery} onChange={e => setDraft({ ...draft, telegramQuery: e.target.value })} /><p className="helper compact">Один поисковый запрос, без локальных AND/OR/NOT. Ограничивает проверяемую выборку.</p>
      {([['required', 'Все слова / фразы (AND)'], ['any', 'Хотя бы одно (OR)'], ['excluded', 'Исключения (NOT)']] as const).map(([key, label]) => <div key={key}><label htmlFor={`terms-${key}`}>{label}</label><textarea id={`terms-${key}`} rows={3} placeholder="Каждое слово или фраза с новой строки" value={draft[key].join('\n')} onChange={e => setDraft({ ...draft, [key]: e.target.value.split('\n') })} onBlur={() => setDraft({ ...draft, [key]: split(draft[key].join('\n')) })} /></div>)}
      <label htmlFor="template-source-mode">Источники шаблона</label><select id="template-source-mode" value={draft.sourceMode} onChange={e => setDraft({ ...draft, sourceMode: e.target.value as SearchTemplate['sourceMode'], subscribed: false })}><option value="chats">Выбранные чаты и темы Telegram</option>{draft.sourceMode !== 'chats' && <option value="all">Существующие публичные каналы · совместимость</option>}{draft.id && draft.sourceMode !== 'chats' && <><option value="selected">Выбранные публичные каналы · совместимость</option><option value="global">Глобальный поиск · вручную</option></>}</select>
      {draft.sourceMode === 'chats' && <ChatPicker keys={draft.sourceKeys ?? []} disabled={busy} onChange={keys => setDraft({ ...draft, sourceKeys: keys })} />}
      {draft.sourceMode === 'selected' && <div className="source-checkboxes">{channels.map(c => <label key={c.id}><input type="checkbox" checked={draft.sources.includes(c.id)} onChange={e => setDraft({ ...draft, sources: e.target.checked ? [...draft.sources, c.id] : draft.sources.filter(id => id !== c.id) })} />@{c.username}</label>)}{!channels.length && <p className="helper">Сначала сохраните источник.</p>}</div>}
      {draft.sourceMode !== 'global' && !(draft.sourceMode !== 'chats' && hasLocal(draft.id)) && <label className="checkbox-label"><input type="checkbox" checked={draft.subscribed} onChange={e => setDraft({ ...draft, subscribed: e.target.checked })} />Включить мониторинг новых сообщений</label>}<label className="checkbox-label"><input type="checkbox" checked={draft.notificationsEnabled !== false} onChange={e => setDraft({ ...draft, notificationsEnabled: e.target.checked })} />Уведомлять о новых совпадениях этого шаблона</label><button className="primary-button" disabled={busy}>{busy ? 'Сохраняем…' : 'Сохранить шаблон'}</button><button className="secondary-button" type="button" disabled={busy} onClick={() => setDraft(null)}>Отмена</button>
    </form>}
  </div>;
}
