import { useEffect, useState } from 'react';
import { request } from './api';
interface BotStatus {
  state: 'disabled' | 'blocked' | 'starting' | 'running' | 'error'; notice: string | null;
  allowedUsers: number; registeredUsers: number; delivery: { pending: number; sent: number; failed: number };
  retryAt: number; lastPollAt: number;
}
const labels: Record<BotStatus['state'], string> = { disabled: 'Bot API выключен', blocked: 'Bot API заблокирован конфигурацией', starting: 'Bot API подключается', running: 'Bot API подключён', error: 'Bot API: требуется внимание' };
export function BotIntegration() {
  const [status, setStatus] = useState<BotStatus | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  async function refresh() {
    setBusy(true);
    try { setStatus(await request<BotStatus>('/api/integrations/bot')); setError(''); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  useEffect(() => {
    const controller = new AbortController();
    const load = () => { void request<BotStatus>('/api/integrations/bot', controller.signal).then(setStatus).catch(e => { if (!controller.signal.aborted) setError((e as Error).message); }); };
    load(); const timer = window.setInterval(load, 10_000);
    return () => { controller.abort(); window.clearInterval(timer); };
  }, []);
  return <section className="mcp-integration" aria-label="Интеграции Bot API"><h2>Интеграции → Telegram Bot</h2>
    {error && <p className="error" role="alert">{error}</p>}
    {!status ? <p>Загружаем состояние бота…</p> : <>
      <p><strong>{labels[status.state]}</strong></p>
      {status.notice && <p className="helper">{status.notice}</p>}
      <dl><dt>Разрешённых операторов</dt><dd>{status.allowedUsers}</dd><dt>Получателей после /start</dt><dd>{status.registeredUsers}</dd>
        <dt>В очереди</dt><dd>{status.delivery.pending}</dd><dt>Отправлено ботом</dt><dd>{status.delivery.sent}</dd><dt>Не доставлено</dt><dd>{status.delivery.failed}</dd></dl>
      {status.retryAt > Date.now() && <p>Повторная попытка не раньше {new Date(status.retryAt).toLocaleString('ru-RU')}</p>}
      {status.lastPollAt > 0 && <p className="helper">Последняя проверка: {new Date(status.lastPollAt).toLocaleString('ru-RU')}</p>}
    </>}
    <button className="secondary-button" disabled={busy} onClick={() => void refresh()}>{busy ? 'Проверяем…' : 'Обновить состояние бота'}</button>
    <p className="helper">Настройте TG_BOT_TOKEN и TG_BOT_ALLOWED_USER_IDS в локальном .env, перезапустите backend и отправьте /start боту в личном чате. Токен здесь не отображается.</p>
    <p className="helper">Команды: /status, /subscriptions, /pause, /resume. Кнопки меняют общие подписки hradar. Старые совпадения при подключении не рассылаются.</p>
    <p className="helper">Доставка работает, пока backend запущен и компьютер доступен в сети; браузер можно закрыть. Очередь сохраняется после перезапуска. После сбоя возможна повторная доставка. Счётчик отправленных включает ответы на команды; он не подтверждает прочтение на телефоне.</p>
  </section>;
}
