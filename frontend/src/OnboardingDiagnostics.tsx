import { useEffect, useRef, useState } from 'react';

// Deliberately closed lists. Never copy error messages or serialize a whole API response.
const identifiers = new Set([
  'UNKNOWN', 'PHONE_CODE_INVALID', 'PHONE_CODE_EMPTY', 'EMAIL_CODE_INVALID', 'CODE_INVALID', 'EMAIL_VERIFY_CODE_INVALID',
  'PHONE_CODE_EXPIRED', 'EMAIL_CODE_EXPIRED', 'CODE_EXPIRED', 'EMAIL_VERIFY_EXPIRED', 'PASSWORD_HASH_INVALID',
  'PHONE_NUMBER_INVALID', 'EMAIL_INVALID', 'EMAIL_NOT_ALLOWED', 'SEND_CODE_UNAVAILABLE', 'PHONE_CODE_HASH_EMPTY',
  'SESSION_PASSWORD_NEEDED', 'EMAIL_VERIFICATION_NEEDED', 'AUTH_RESTART', 'FLOOD_WAIT',
  'LOCAL_NETWORK', 'LOCAL_WAIT', 'LOCAL_RATE_LIMIT', 'LOCAL_BUSY', 'LOCAL_STATE_CHANGED',
  'LOCAL_UNSUPPORTED_STATE', 'LOCAL_RESEND_UNAVAILABLE', 'LOCAL_INVALID_PHONE',
]);
const stages = new Set(['Credentials', 'ParametersFailed', 'Initializing', 'WaitTdlibParameters', 'WaitPhoneNumber',
  'WaitPremiumPurchase', 'WaitEmailAddress', 'WaitEmailCode', 'WaitCode', 'WaitOtherDeviceConfirmation',
  'WaitRegistration', 'WaitPassword', 'Ready', 'LoggingOut', 'Closing', 'Closed']);
export type DiagnosticFailure = { telegramCode: number | null; identifier: string };
export function readDiagnostic(value: unknown): DiagnosticFailure {
  const data = value && typeof value === 'object' ? value as Record<string, unknown> : {};
  return {
    telegramCode: typeof data.telegramCode === 'number' && Number.isInteger(data.telegramCode) && data.telegramCode >= 100 && data.telegramCode <= 599 ? data.telegramCode : null,
    identifier: typeof data.identifier === 'string' && identifiers.has(data.identifier) ? data.identifier : 'UNKNOWN',
  };
}

export function OnboardingDiagnostics({ failure, state, waitSeconds }: { failure: DiagnosticFailure | null; state?: string; waitSeconds: number }) {
  const [message, setMessage] = useState('');
  const [copying, setCopying] = useState(false);
  const pending = useRef(false);
  useEffect(() => setMessage(''), [failure, state]);
  const snapshot = {
    telegramCode: failure?.telegramCode ?? null,
    identifier: failure?.identifier ?? 'NONE',
    state: state && stages.has(state) ? state : 'Unknown',
    retryAfterSeconds: Number.isSafeInteger(waitSeconds) && waitSeconds >= 0 && waitSeconds <= 2147483647 ? waitSeconds : 0,
  };
  async function copy() {
    if (pending.current) return;
    pending.current = true; setCopying(true); setMessage('');
    try {
      await navigator.clipboard.writeText(JSON.stringify(snapshot, null, 2));
      setMessage('Диагностика скопирована. Личные данные не включены.');
    } catch { setMessage('Не удалось скопировать. Можно выделить подробности вручную.'); }
    finally { pending.current = false; setCopying(false); }
  }
  return <details className="login-diagnostics" data-testid="login-diagnostics">
    <summary>Технические подробности</summary>
    <dl><dt>Код Telegram</dt><dd>{snapshot.telegramCode ?? 'Нет ответа с кодом'}</dd>
      <dt>Идентификатор</dt><dd>{snapshot.identifier}</dd>
      <dt>Этап входа</dt><dd>{snapshot.state}</dd>
      <dt>Ожидание, сек.</dt><dd>{snapshot.retryAfterSeconds}</dd></dl>
    <button type="button" className="login-text-button" disabled={copying} onClick={() => void copy()}>Скопировать диагностику</button>
    {message && <p role="status">{message}</p>}
  </details>;
}
