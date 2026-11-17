import { request } from './api';
export async function ownerMutation<T>(path: string, method: string, body?: unknown, signal?: AbortSignal, timeoutMs = 95_000): Promise<T> {
  const auth = await request<{ csrfToken: string }>('/api/onboarding', signal);
  if (!auth.csrfToken) throw new Error('Обновите страницу для подтверждения запроса.');
  return request<T>(path, signal, { method, headers: { 'Content-Type': 'application/json', 'X-Hradar-CSRF': auth.csrfToken }, body: body === undefined ? undefined : JSON.stringify(body) }, timeoutMs);
}
