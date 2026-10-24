import { useEffect, useState, useRef } from 'react';
import './Onboarding.css';

type Delivery = { method: string; destination?: string; length?: number };
type Status = { state: string; csrfToken: string; link?: string; notice?: string; revision?: number;
  delivery?: Delivery; nextDelivery?: Delivery; resendSupported?: boolean; resendAfterSeconds?: number;
  retryAfterSeconds?: number; canChangePhone?: boolean; busy?: boolean };
const waiting: Record<string, string> = {
  Initializing: 'Подключаем Telegram…', WaitTdlibParameters: 'Готовим защищённое подключение…',
  Closing: 'Telegram завершает работу…', Closed: 'Сессия закрыта. Завершите hradar и откройте приложение снова.',
  LoggingOut: 'Завершаем выход из Telegram…', WaitOtherDeviceConfirmation: 'Подтвердите вход на другом устройстве в приложении Telegram.',
};
const labels: Record<string, string> = { WaitPhoneNumber: 'Номер телефона', WaitCode: 'Код Telegram', WaitPassword: 'Пароль 2FA', WaitEmailAddress: 'Email для входа', WaitEmailCode: 'Код из email' };
const titles: Record<string, string> = { Credentials: 'Подключите Telegram', ParametersFailed: 'Проверьте настройки', WaitPhoneNumber: 'Ваш аккаунт Telegram', WaitCode: 'Введите код входа', WaitPassword: 'Введите пароль Telegram', WaitEmailAddress: 'Подтвердите email', WaitEmailCode: 'Проверьте вашу почту', WaitOtherDeviceConfirmation: 'Подтвердите вход', WaitRegistration: 'Используйте существующий аккаунт', WaitPremiumPurchase: 'Требуется действие в Telegram', Closed: 'Сессия закрыта', Ready: 'Telegram подключён' };
const methods: Record<string, [string, string]> = {
  telegram: ['В приложении Telegram', 'Откройте Telegram на устройстве, где вы уже вошли, и найдите служебное сообщение с кодом.'],
  sms: ['По SMS', 'Telegram сообщил об отправке SMS. Проверьте сообщения на телефоне с этим номером.'],
  sms_word: ['Слово в SMS', 'Найдите SMS от Telegram и введите слово из сообщения целиком.'],
  sms_phrase: ['Фраза в SMS', 'Найдите SMS от Telegram и введите фразу из сообщения целиком.'],
  call: ['Голосовым звонком', 'Ответьте на звонок и введите продиктованный код.'],
  flash_call: ['Коротким звонком', 'Telegram использует номер входящего короткого звонка как код. Если получить его не удалось, дождитесь доступного следующего способа.'],
  missed_call: ['Пропущенным звонком', 'Введите последние цифры номера входящего звонка.'],
  fragment: ['Через Fragment', 'Откройте fragment.com самостоятельно и войдите с кошельком, которому принадлежит номер.'],
  official_android: ['Через официальное приложение Android', 'Этот способ требует подтверждения в официальном приложении Telegram на Android. hradar не может выполнить его вместо вас.'],
  official_ios: ['Через официальное приложение iOS', 'Этот способ требует подтверждения в официальном приложении Telegram на iPhone. hradar не может выполнить его вместо вас.'],
  email: ['По электронной почте', 'Проверьте входящие письма и папку «Спам». Используйте последний код от Telegram.'],
  unknown: ['Способ доставки не указан', 'Telegram не сообщил, где искать код. Проверьте официальное приложение Telegram и доступный следующий способ.'],
};
const deliveryText = (delivery?: Delivery) => methods[delivery?.method ?? 'unknown'] ?? methods.unknown;

export function Onboarding({ onReady }: { onReady: () => void }) {
  const ready = useRef(onReady); ready.current = onReady;
  const [status, setStatus] = useState<Status | null>(null);
  const statusRef = useRef<Status | null>(null);
  const [value, setValue] = useState('');
  const [phone, setPhone] = useState('');
  const [editingPhone, setEditingPhone] = useState(false);
  const [apiId, setApiId] = useState('');
  const [apiHash, setApiHash] = useState('');
  const [error, setError] = useState('');
  const [connectionError, setConnectionError] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [tick, setTick] = useState(Date.now());
  const [sampledAt, setSampledAt] = useState(Date.now());
  const [resendUntil, setResendUntil] = useState(0);
  const [retryUntil, setRetryUntil] = useState(0);
  const pending = useRef(false);
  const generation = useRef(0);
  const mounted = useRef(true);
  const input = useRef<HTMLInputElement>(null);
  const refresh = useRef<() => Promise<void>>(async () => {});
  useEffect(() => {
    mounted.current = true;
    let polling = false;
    async function poll() {
      if (polling) return;
      polling = true;
      const version = generation.current;
      try {
        const r = await fetch('/api/onboarding', { cache: 'no-store' });
        if (!r.ok) { if (mounted.current) setConnectionError('Откройте hradar через установленное приложение.'); return; }
        const s = await r.json() as Status;
        if (!mounted.current || version !== generation.current) return;
        const old = statusRef.current;
        if (old && (old.state !== s.state || old.revision !== s.revision)) {
          setValue(''); setPhone(''); setEditingPhone(false); setApiHash(''); setError(''); setMessage('');
        }
        statusRef.current = s; setStatus(s); setSampledAt(Date.now()); setTick(Date.now()); setConnectionError('');
        if (s.state === 'Ready') ready.current();
      } catch { if (mounted.current) setConnectionError('Не удалось обновить этап входа. Проверьте соединение — повторим автоматически.'); }
      finally { polling = false; }
    }
    refresh.current = poll;
    void poll();
    const timer = setInterval(() => { if (!pending.current) void poll(); }, 1500);
    const clock = setInterval(() => setTick(Date.now()), 250);
    return () => { mounted.current = false; clearInterval(timer); clearInterval(clock); };
  }, []);
  useEffect(() => { input.current?.focus(); }, [status?.state, editingPhone]);
  const elapsed = Math.max(0, Math.floor((tick - sampledAt) / 1000));
  const resendSeconds = Math.max(0, (status?.resendAfterSeconds ?? 0) - elapsed, Math.ceil((resendUntil - tick) / 1000));
  const retrySeconds = Math.max(0, (status?.retryAfterSeconds ?? 0) - elapsed, Math.ceil((retryUntil - tick) / 1000));
  const locked = busy || !!status?.busy;
  async function submit(path: string, body?: unknown) {
    if (pending.current || locked || (retrySeconds > 0 && path !== '/api/desktop/shutdown')) return;
    pending.current = true; generation.current++; setBusy(true); setError(''); setMessage('');
    try {
      const r = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Hradar-CSRF': statusRef.current?.csrfToken ?? '' }, body: JSON.stringify(body ?? {}) });
      if (!mounted.current) return;
      if (!r.ok) {
        const data = await r.json() as { error?: string; kind?: string; retryAfterSeconds?: number };
        if (data.retryAfterSeconds && data.retryAfterSeconds > 0) {
          const deadline = Date.now() + data.retryAfterSeconds * 1000;
          if (data.kind === 'wait') setResendUntil(deadline); else setRetryUntil(deadline);
          setTick(Date.now());
        }
        setError(data.error ?? 'Не удалось выполнить действие. Повторите текущий этап.');
      } else {
        if (path.endsWith('/credentials')) setApiHash('');
        if (path.endsWith('/resend')) { setResendUntil(Date.now() + 30_000); setTick(Date.now()); setMessage('Запрос повторной отправки принят Telegram. Проверьте актуальный способ доставки.'); }
        else setMessage('Запрос принят. Ожидаем следующий этап от Telegram…');
        if (path === '/api/desktop/shutdown') { document.getElementById('root')!.textContent = 'hradar завершён. Теперь можно закрыть вкладку.'; return; }
      }
    } catch { if (mounted.current) setError('Не удалось связаться с приложением. Проверьте соединение и повторите текущий этап.'); }
    finally {
      pending.current = false;
      if (mounted.current) { setBusy(false); await refresh.current(); }
    }
  }
  const credentials = !!status && ['Credentials', 'ParametersFailed'].includes(status.state);
  const code = !!status && ['WaitCode', 'WaitEmailCode'].includes(status.state);
  const step = !status || credentials ? 0 : status.state === 'WaitPhoneNumber' || editingPhone ? 1 : 2;
  const title = editingPhone ? 'Изменить номер телефона' : titles[status?.state ?? ''] ?? (status && !waiting[status.state] ? 'Дополнительный этап входа' : 'Подключаем Telegram');
  const description = editingPhone ? 'Введите другой номер. Telegram начнёт вход для него, сохранив локальные данные hradar.'
    : credentials ? 'Один раз настройте доступ для своего аккаунта. Это данные приложения, полученные у Telegram.'
    : status?.state === 'WaitPhoneNumber' ? 'Укажите номер существующего аккаунта в международном формате. Способ подтверждения выберет Telegram.'
    : status?.state === 'WaitPassword' ? 'Введите пароль двухэтапной проверки, который вы задали в Telegram. Это не код из сообщения.'
    : status?.state === 'WaitEmailAddress' ? 'Telegram запросил адрес электронной почты для подтверждения входа.'
    : code ? 'Используйте последний код. Способ получения указан ниже по данным Telegram.'
    : waiting[status?.state ?? ''] ?? 'Ожидаем актуальное состояние от Telegram.';
  const loginBody = (v: string) => ({ state: status?.state, revision: status?.revision, value: v });
  return <main className="login-screen">
    <header className="login-brand"><span className="brand-mark" aria-hidden="true">h</span><strong>hradar</strong><span>Ваш рабочий радар</span></header>
    <section className="login-card" aria-labelledby="login-title">
      <aside className="login-intro"><span className="login-eyebrow">НАЧНЁМ С ПОДКЛЮЧЕНИЯ</span><h2>Ваш Telegram.<br />Ваше пространство.</h2><p>Подключите аккаунт, чтобы работать с источниками и находить нужные публикации в одном месте.</p><div className="login-privacy"><span aria-hidden="true">⌘</span><div><strong>На вашем Mac</strong><p>Сессия и настройки хранятся локально. Коды входа и пароль не сохраняются.</p></div></div></aside>
      <div className="login-content">
        <ol className="login-progress" aria-label="Этапы подключения">{['Настройка', 'Аккаунт', 'Подтверждение'].map((label, index) => <li key={label} aria-current={step === index ? 'step' : undefined} className={index <= step ? 'reached' : ''}><span aria-hidden="true">{index < step ? '✓' : index + 1}</span>{label}</li>)}</ol>
        <div className="login-heading"><p className="login-eyebrow">ШАГ {step + 1} ИЗ 3</p><h1 id="login-title">{title}</h1><p>{description}</p></div>
        {code && !editingPhone && <div className="login-delivery"><strong>{deliveryText(status?.delivery)[0]}</strong>{status?.delivery?.destination && <span>{status.delivery.destination}</span>}<p>{deliveryText(status?.delivery)[1]}</p>{!!status?.delivery?.length && <small>Ожидается символов: {status.delivery.length}</small>}</div>}
        {status?.notice && <p className="login-notice">{status.notice}</p>}
        {editingPhone ? <form aria-busy={locked} onSubmit={e => { e.preventDefault(); void submit('/api/onboarding/phone', loginBody(phone)); }}>
          <label htmlFor="auth-phone">Новый номер телефона</label><input ref={input} id="auth-phone" required type="tel" autoComplete="tel" value={phone} onChange={e => setPhone(e.target.value)} disabled={locked} aria-describedby="login-feedback" placeholder="+…" maxLength={21} />
          <p className="login-field-hint">Текущий этап Telegram: {labels[status?.state ?? ''] ?? 'подтверждение'}. Изменение произойдёт после отправки нового номера.</p>
          <button className="login-primary" disabled={locked || retrySeconds > 0}>{locked ? 'Отправляем…' : 'Получить код'}</button>
          <button type="button" className="login-back" aria-label={code ? 'Назад к коду' : 'Назад к подтверждению'} disabled={locked} onClick={() => setEditingPhone(false)}>← {code ? 'Назад к коду' : 'Назад к подтверждению'}</button>
        </form> : credentials ? <form aria-busy={locked} onSubmit={e => { e.preventDefault(); void submit('/api/onboarding/credentials', { apiId: Number(apiId), apiHash }); }}>
          <p className="login-field-hint">Получите данные на <a href="https://my.telegram.org/apps" target="_blank" rel="noreferrer">my.telegram.org/apps ↗</a>. Они сохраняются в приватном профиле macOS. Не передавайте их другим людям.</p>
          <label htmlFor="auth-api-id">API ID</label><input ref={input} id="auth-api-id" required inputMode="numeric" value={apiId} onChange={e => setApiId(e.target.value)} disabled={locked} aria-describedby="login-feedback" />
          <label htmlFor="auth-api-hash">API hash</label><input id="auth-api-hash" required type="password" autoComplete="off" value={apiHash} onChange={e => setApiHash(e.target.value)} disabled={locked} aria-describedby="login-feedback" />
          <button className="login-primary" disabled={locked}>{locked ? 'Сохраняем…' : 'Сохранить и продолжить'}</button>
        </form> : status && labels[status.state] ? <form aria-busy={locked} onSubmit={e => { e.preventDefault(); void submit('/api/onboarding/login', loginBody(value)); }}>
          <label htmlFor="auth-value">{labels[status.state]}</label><input ref={input} id="auth-value" required autoComplete={code ? 'one-time-code' : status.state === 'WaitPhoneNumber' ? 'tel' : 'off'} type={status.state === 'WaitPassword' ? 'password' : status.state === 'WaitEmailAddress' ? 'email' : status.state === 'WaitPhoneNumber' ? 'tel' : 'text'} value={value} onChange={e => setValue(e.target.value)} disabled={locked} aria-describedby="login-feedback" placeholder={status.state === 'WaitPhoneNumber' ? '+…' : ''} maxLength={512} className={code ? 'login-code' : ''} />
          <button className="login-primary" disabled={locked || retrySeconds > 0}>{locked ? 'Проверяем…' : 'Продолжить'}</button>
        </form> : <p className="login-wait" role="status">{status?.notice ?? description}</p>}
        {code && !editingPhone && <div className="login-resend">{status?.nextDelivery && <p>Следующий способ: <strong>{deliveryText(status.nextDelivery)[0]}</strong></p>}
          {status?.resendSupported ? <><button className="login-text-button" disabled={locked || resendSeconds > 0 || retrySeconds > 0} onClick={() => void submit('/api/onboarding/resend', loginBody(''))}>Отправить повторно</button><span data-testid="resend-countdown" role="timer" aria-live="off">{resendSeconds > 0 ? `Повторно через ${resendSeconds} сек.` : 'Доступно по запросу'}</span></> : <p>Telegram пока не разрешил повторную отправку кода.</p>}
        </div>}
        {status?.canChangePhone && !editingPhone && <button className="login-back" aria-label="Изменить номер" disabled={locked} onClick={() => { setEditingPhone(true); setError(''); setMessage(''); }}>← Изменить номер</button>}
        <div id="login-feedback" className="login-feedback">{retrySeconds > 0 && <p role="timer">Подождите {retrySeconds} сек. перед следующим запросом.</p>}{(error || connectionError) && <p className="login-error" role="alert">{error || connectionError}</p>}{message && !error && <p role="status">{message}</p>}</div>
        {status?.link?.startsWith('tg://') && <a className="login-confirm" href={status.link}>Открыть подтверждение в Telegram ↗</a>}
        <p className="login-footnote">Доставкой кода управляет Telegram. Для сторонних приложений SMS и другие способы могут быть ограничены. Исправная SIM-карта не гарантирует отправку кода.</p>
      </div>
    </section>
    <footer className="login-footer"><span>Приватное подключение через официальный TDLib</span>{isDesktop() && <button className="login-text-button" disabled={locked || !status} onClick={() => void submit('/api/desktop/shutdown')}>Завершить hradar</button>}</footer>
  </main>;
}
export async function desktopLaunch() {
  const token = new URLSearchParams(location.hash.slice(1)).get('launch');
  if (!token) return;
  history.replaceState(null, '', location.pathname);
  const response = await fetch('/desktop/launch', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token }) });
  if (!response.ok) throw new Error('Запуск отклонён. Откройте приложение hradar повторно.');
  sessionStorage.setItem('hradar-desktop', 'true');
}
export function isDesktop() { return sessionStorage.getItem('hradar-desktop') === 'true'; }
export async function shutdownDesktop() {
  const response = await fetch('/api/onboarding', { cache: 'no-store' });
  const status = await response.json() as Status;
  const stopped = await fetch('/api/desktop/shutdown', { method: 'POST', headers: { 'X-Hradar-CSRF': status.csrfToken } });
  if (!stopped.ok) throw new Error('Не удалось завершить hradar.');
  document.getElementById('root')!.textContent = 'hradar завершён. Теперь можно закрыть вкладку.';
}
