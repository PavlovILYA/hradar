export interface TelegramStatus { authorizationState: string; authorized: boolean; connectionState?: string }
export interface TelegramMessage { id: number; date: number; text: string | null; channelId: number }

export async function request<T>(path: string, signal?: AbortSignal, init: RequestInit = {}, timeoutMs = 25_000): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, { ...init, signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs) });
  } catch (error) {
    if (signal?.aborted) throw error;
    throw new Error('Не удалось связаться с сервером. Проверьте, что backend запущен, и повторите попытку.');
  }
  if (!response.ok) {
    const messages: Record<number, string> = {
      400: 'Проверьте username: нужен публичный Telegram-канал, а не группа или профиль.',
      403: 'Канал недоступен для текущего Telegram-аккаунта.',
      404: 'Канал не найден. Проверьте его username.',
      429: 'Telegram ограничил частоту запросов. Попробуйте немного позже.',
      503: 'Telegram пока не авторизован. Завершите вход в терминале backend.',
    };
    const detail = await response.json().catch(() => null) as { error?: string } | null;
    const readable = detail?.error && /[а-яё]/i.test(detail.error) ? detail.error : null;
    throw new Error(readable ?? messages[response.status] ?? 'Не удалось получить данные. Проверьте backend и повторите попытку.');
  }
  try { return await response.json() as T; }
  catch { throw new Error('Сервер вернул неожиданный ответ. Проверьте backend.'); }
}

export function normalizeUsername(value: string): string {
  const username = value.trim().replace(/^@/, '');
  if (!/^[a-zA-Z0-9_]{5,32}$/.test(username)) {
    throw new Error('Введите username канала: 5–32 латинские буквы, цифры или знак _, например @telegram.');
  }
  return username;
}

export interface MonitoredChannel { id: number; username: string; title: string }
export interface SearchTemplate {
  id: string; name: string; required: string[]; any: string[]; excluded: string[];
  telegramQuery: string; sourceMode: 'all' | 'selected' | 'global'; sources: number[];
  active: boolean; subscribed: boolean;
}
export interface MatchExplanation { matches: boolean; foundRequired: string[]; foundAny: string[]; foundExcluded: string[]; missingRequired: string[] }
export function mutate<T>(path: string, method: string, body?: unknown, timeoutMs = 25_000): Promise<T> {
  return request<T>(path, undefined, { method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) }, timeoutMs);
}

export interface ResearchMessage extends TelegramMessage { title?: string; username?: string | null; link?: string | null; explanation?: MatchExplanation | null }
export interface SearchInput { mode: 'channel' | 'tracked' | 'global'; query: string; usernames: string[]; templateId?: string; limit: number; cursor?: string }
export interface SearchPage { messages: ResearchMessage[]; cursor: string; state: string; notice?: string | null }
export interface NotificationRecord { id: string; templateId: string; templateName: string; message: ResearchMessage; read: boolean; sequence: number }
export interface NotificationPage { items: NotificationRecord[]; unread: number; cursor: number | null }
export interface NotificationSummary { unread: number; revision: number; byChannel: Record<string, number>; telegram?: TelegramStatus }
