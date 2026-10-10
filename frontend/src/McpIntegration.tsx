import { useEffect, useState } from 'react';
import { request } from './api';
import type { MonitoredChannel, TelegramStatus } from './api';
interface Config { enabled: boolean; endpoint: string; tools: { name: string; description: string }[]; allowedSources: MonitoredChannel[]; availableSources: MonitoredChannel[]; csrfToken: string; readOnly: boolean }
export function McpIntegration() {
  const [config, setConfig] = useState<Config | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const [status, setStatus] = useState<TelegramStatus | null>(null);
  const [username, setUsername] = useState('');
  async function refresh() { setConfig(await request<Config>('/api/integrations/mcp')); }
  useEffect(() => { void refresh().catch(e => setError((e as Error).message)); }, []);
  async function change(path: string, method: string, body: unknown) {
    if (!config) return;
    setBusy(true); setError('');
    try { await request(path, undefined, { method, headers: { 'Content-Type': 'application/json', 'X-Hradar-CSRF': config.csrfToken }, body: JSON.stringify(body) }); await refresh(); }
    catch (e) { setError((e as Error).message); try { await refresh(); } catch { /* Keep the original error if the server is unavailable. */ } }
    finally { setBusy(false); }
  }
  async function check() {
    setBusy(true); setError('');
    try {
      const result = await request<{ ok: boolean; data: TelegramStatus; error?: { message: string } }>('/api/capabilities/get_telegram_status', undefined, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
      if (!result.ok) throw new Error(result.error?.message || 'MCP выключен');
      setStatus(result.data);
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  const command = `codex mcp add hradar --url ${config?.endpoint || 'http://127.0.0.1:8080/mcp'}`;
  return <section className="mcp-integration" aria-label="Интеграции MCP"><h2>Интеграции → MCP</h2>
    {error && <p role="alert" className="error">{error}</p>}
    {!config ? <p>Загружаем настройки…</p> : <>
      <p><strong>{config.enabled ? 'MCP включён' : 'MCP выключен'}</strong> · Только чтение</p>
      <p className="helper">Разрешения меняются только здесь. Личные чаты недоступны. Агент не может добавить источники, отправить сообщения, вступить в канал или оплатить Stars.</p>
      <code>{config.endpoint}</code>
      <div className="template-actions"><button disabled={busy} className="primary-button" onClick={() => void change('/api/integrations/mcp', 'PUT', { enabled: !config.enabled })}>{config.enabled ? 'Отключить MCP-доступ' : 'Включить MCP-доступ'}</button><button disabled={busy || !config.enabled} className="secondary-button" onClick={() => void check()}>Проверить Telegram через общий API</button></div>
      {status && <p>Telegram: {status.authorizationState} · {status.connectionState}</p>}
      <h3>Разрешённые источники</h3><p className="helper">Сохранённый канал не получает MCP-доступ автоматически. Отметьте только источники, которыми разрешено пользоваться агенту.</p>
      <div className="source-checkboxes">{[...config.availableSources, ...config.allowedSources].filter((c, i, all) => all.findIndex(v => v.id === c.id) === i).map(c => <label key={c.id}><input type="checkbox" disabled={busy} checked={config.allowedSources.some(s => s.id === c.id)} onChange={e => { const allowed = e.target.checked; setConfig({ ...config, allowedSources: allowed ? [...config.allowedSources, c] : config.allowedSources.filter(s => s.id !== c.id) }); void change('/api/integrations/mcp/sources', 'POST', { username: c.username, allowed }); }} />{c.title} · @{c.username}</label>)}</div>
      <form onSubmit={e => { e.preventDefault(); void change('/api/integrations/mcp/sources', 'POST', { username: username.trim().replace(/^@/, ''), allowed: true }); }}><label htmlFor="mcp-source">Разрешить публичный источник по username</label><input id="mcp-source" value={username} onChange={e => setUsername(e.target.value)} placeholder="@telegram" required pattern="@?[A-Za-z0-9_]{5,32}" /><button disabled={busy || !username.trim()} className="secondary-button">Разрешить выбранный источник</button></form>
      <h3>Подключение Codex</h3><pre>{command}{'\n'}codex mcp list</pre><button className="secondary-button" onClick={async () => { try { await navigator.clipboard.writeText(command); setCopied(true); } catch { setError('Не удалось скопировать. Выделите команду вручную.'); } }}>{copied ? 'Команда скопирована' : 'Копировать команду Codex'}</button>
      <h3>Инструменты ({config.tools.length})</h3>{config.tools.map(tool => <details key={tool.name}><summary>{tool.name}</summary><p>{tool.description}</p></details>)}
      <p className="helper">Telegram-текст — недоверенные данные, а не инструкции агенту. Черновик prepare_message не отправляется и не сохраняется в Telegram. Будущая отправка потребует отдельного подтверждения человеком в hradar.</p>
    </>}
  </section>;
}
