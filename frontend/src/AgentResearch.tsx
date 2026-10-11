import { useEffect, useRef, useState } from 'react';
import { request } from './api';
import type { MonitoredChannel } from './api';

interface Limits { model: string; maxOutputTokens: number; tokenBudget: number; budgetUsd: number; timeoutSeconds: number; maxToolCalls: number; retentionDays: number; inputUsdPerMillion: number; outputUsdPerMillion: number }
interface Settings { available: boolean; limits: Limits; sources: MonitoredChannel[]; consent: { enabled: boolean; sources: number[]; policy: string } | null; csrfToken: string; operator: string }
interface Evidence { reference: string; title: string; date: number; link: string | null; reason: string }
interface Job { id: string; question: string; state: string; createdAt: number; error: string | null; limits?: Limits; result: { summary: string; evidence: Evidence[]; notice: string } | null }
const states: Record<string, string> = { QUEUED: 'В очереди', RUNNING: 'Исследование выполняется', COMPLETED: 'Завершено', CANCELLED: 'Отменено', FAILED: 'Ошибка', INTERRUPTED: 'Прервано при остановке' };
const active = (job: Job) => ['QUEUED', 'RUNNING'].includes(job.state);
function validLink(value: string | null): value is string {
  if (!value) return false;
  try { const url = new URL(value); return url.protocol === 'https:' && url.hostname === 't.me' && !url.username && !url.password && !url.port && !url.hash; } catch { return false; }
}
export function AgentResearch() {
  const [operator, setOperator] = useState('');
  const [code, setCode] = useState('');
  const [settings, setSettings] = useState<Settings | null>(null);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [sources, setSources] = useState<number[]>([]);
  const [agree, setAgree] = useState(false);
  const [question, setQuestion] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [more, setMore] = useState(false);
  const loadedOlder = useRef(false);
  function headers(csrf = false) { return { 'Content-Type': 'application/json', ...(operator ? { 'X-Hradar-Agent-Operator': operator } : {}), ...(csrf && settings ? { 'X-Hradar-CSRF': settings.csrfToken } : {}) }; }
  async function refresh(signal?: AbortSignal, reset = false) {
    const [next, list] = await Promise.all([
      request<Settings>('/api/agent/settings', signal, { headers: headers() }),
      request<Job[]>('/api/agent/jobs', signal, { headers: headers() }),
    ]);
    if (signal?.aborted) return;
    setSettings(next);
    if (reset) loadedOlder.current = false;
    setJobs(old => !loadedOlder.current || list.length < 50 ? list : [...list, ...old.filter(job => job.createdAt < list[list.length - 1].createdAt)]);
    if (!loadedOlder.current) setMore(list.length === 50);
    if (reset) { setSources(next.consent?.sources ?? []); setAgree(false); }
  }
  useEffect(() => {
    const controller = new AbortController();
    setSettings(null); setJobs([]); setError(''); setSources([]); setAgree(false);
    void refresh(controller.signal, true).catch(e => { if (!controller.signal.aborted) setError((e as Error).message); });
    const interval = window.setInterval(() => { void refresh(controller.signal).catch(e => { if (!controller.signal.aborted) setError((e as Error).message); }); }, 5000);
    return () => { controller.abort(); window.clearInterval(interval); };
  }, [operator]);
  async function action(path: string, method: string, body?: unknown) {
    setBusy(true); setError('');
    try { await request(path, undefined, { method, headers: headers(true), body: body === undefined ? undefined : JSON.stringify(body) }); await refresh(undefined, true); return true; }
    catch (e) { setError((e as Error).message); return false; }
    finally { setBusy(false); }
  }
  return <div className="agent-research">
    <h2>AI-исследования</h2>
    <p className="helper">Фоновый анализ разрешённых публичных публикаций. Для истории своего бота получите /ask_access и вставьте код. Без кода используется отдельный локальный оператор.</p>
    <form className="agent-operator" onSubmit={e => { e.preventDefault(); setOperator(code.trim()); }}>
      <label htmlFor="agent-code">Код оператора</label><input id="agent-code" type="password" value={code} autoComplete="off" maxLength={100} onChange={e => setCode(e.target.value)} />
      <button className="secondary-button" disabled={busy}>Открыть историю оператора</button>
      {operator && <button className="text-button" type="button" onClick={() => { setCode(''); setOperator(''); }}>Локальный оператор</button>}
    </form>
    {error && <p role="alert" className="error-banner">{error}</p>}
    {!settings ? <><p role="status">Загрузка настроек AI…</p>{error && <button className="secondary-button" onClick={() => void refresh(undefined, true).catch(e => setError((e as Error).message))}>Повторить загрузку AI</button>}</> : <>
      <section className="agent-settings" aria-label="Настройки внешнего AI">
        <h3>{settings.operator} · OpenAI</h3>
        <dl className="agent-limits"><dt>Модель</dt><dd>{settings.limits.model || 'Не настроена'}</dd><dt>Бюджет на задачу</dt><dd>${settings.limits.budgetUsd} · {settings.limits.tokenBudget} токенов суммарно</dd><dt>Ответ за один вызов</dt><dd>{settings.limits.maxOutputTokens} токенов</dd><dt>Ограничения</dt><dd>{settings.limits.timeoutSeconds} с · {settings.limits.maxToolCalls} вызовов инструментов</dd></dl>
        {!settings.available && <p role="status">Production AI не настроен. Нужны API-ключ, модель и актуальные тарифы в конфигурации backend.</p>}
        <fieldset disabled={busy}><legend>Разрешённые MCP-источники для внешнего AI</legend>
          {settings.sources.length === 0 && <p>Включите MCP и разрешите публичные источники в разделе «Интеграции».</p>}
          {settings.sources.map(source => <label key={source.id} className="agent-checkbox"><input type="checkbox" checked={sources.includes(source.id)} onChange={e => { setAgree(false); setSources(old => e.target.checked ? [...old, source.id] : old.filter(id => id !== source.id)); }} />{source.title} (@{source.username})</label>)}
        </fieldset>
        <p className="helper">OpenAI получит вопрос и фрагменты выбранных публичных публикаций (до 2000 символов за публикацию). Данные нужны для анализа релевантности. Ключи, сессии и приватные чаты не передаются. Вопросы и результаты хранятся локально {settings.limits.retentionDays} дней. store=false отключает сохранение Responses, но служебное хранение провайдером зависит от вашего договора. Проверьте право на передачу данных и правила источника. Тарифы и модель меняются в локальной конфигурации; новое значение требует нового согласия.</p>
        <p><a href="https://developers.openai.com/api/docs/guides/your-data" target="_blank" rel="noreferrer">Условия хранения данных OpenAI ↗</a></p>
        <label className="agent-checkbox"><input type="checkbox" checked={agree} onChange={e => setAgree(e.target.checked)} />Я разрешаю внешнюю AI-обработку вопроса и публикаций выбранных источников в пределах указанного бюджета.</label>
        <div className="agent-actions"><button className="primary-button" disabled={busy || !agree || sources.length === 0} onClick={() => void action('/api/agent/consent', 'PUT', { enabled: true, sources })}>Сохранить согласие и источники</button>
          <button className="secondary-button" disabled={busy || !settings.consent?.enabled} onClick={() => void action('/api/agent/consent', 'PUT', { enabled: false, sources: [] })}>Отозвать согласие</button></div>
        <p role="status">{settings.consent?.enabled ? 'Согласие сохранено. Изменение источников требует повторного сохранения.' : 'Внешняя AI-обработка запрещена до согласия.'}</p>
      </section>
      <form className="agent-question" onSubmit={e => { e.preventDefault(); void action('/api/agent/jobs', 'POST', { question }).then(ok => { if (ok) setQuestion(''); }); }}>
        <label htmlFor="agent-question">Вопрос исследователю</label><textarea id="agent-question" value={question} onChange={e => setQuestion(e.target.value)} maxLength={1000} rows={4} placeholder="Проанализируй Java-разработчиков, которые ищут удалённую работу, за последние 7 дней…" />
        <button className="primary-button" disabled={busy || !settings.available || !settings.consent?.enabled || !question.trim() || jobs.some(active)}>Запустить исследование</button>
      </form>
      <div className="agent-actions"><h3>История запросов</h3><button className="text-button" disabled={busy} onClick={() => void refresh().catch(e => setError((e as Error).message))}>Обновить исследования</button></div>
      {jobs.length === 0 && <p>Исследований пока нет.</p>}
      {jobs.map(job => <article key={job.id} className="agent-job">
        <div className="agent-job-meta"><strong role="status">{states[job.state] ?? job.state}</strong><time>{new Date(job.createdAt).toLocaleString('ru-RU')}</time><code>{job.id.slice(0, 8)}</code></div>
        <h3>{job.question}</h3>{job.limits && <p className="helper">Модель: {job.limits.model} · бюджет ${job.limits.budgetUsd}</p>}
        {active(job) && <button className="secondary-button" disabled={busy} onClick={() => void action(`/api/agent/jobs/${job.id}/cancel`, 'POST')}>Отменить исследование</button>}
        {job.error && <p role="status">{job.error === 'RESTART_INTERRUPTED' ? 'Прервано при перезапуске. Платный вызов не повторяется автоматически.' : `Исследование остановлено: ${job.error}. Новый запрос можно отправить вручную.`}</p>}
        {job.result && <><p className="agent-result">{job.result.summary}</p><ol>{job.result.evidence.map(ref => <li key={ref.reference}><strong>{ref.title}</strong> · {new Date(ref.date * 1000).toLocaleDateString('ru-RU')}<p>{ref.reason}</p>{validLink(ref.link) ? <a href={ref.link} target="_blank" rel="noreferrer">Исходная публикация ↗</a> : <span>Ссылка недоступна</span>}</li>)}</ol><p className="helper">{job.result.notice}</p></>}
      </article>)}
      {more && <button className="secondary-button" onClick={async () => { try { const list = await request<Job[]>(`/api/agent/jobs?before=${jobs.at(-1)?.createdAt}`, undefined, { headers: headers() }); loadedOlder.current = true; setJobs(old => [...old, ...list]); setMore(list.length === 50); } catch (e) { setError((e as Error).message); } }}>Более ранние исследования</button>}
    </>}
  </div>;
}
