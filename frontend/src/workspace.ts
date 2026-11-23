// One immutable generation per page. A profile change discards the entire React tree.
let generation = '';
let epoch = 0;
let frozen = false;
export const workspaceGeneration = () => generation;
export function initializeWorkspace(value: string) { if (generation && generation !== value) throw new Error('Workspace changed'); generation = value; }
export function workspaceUrl(path: string) {
  const url = new URL(path, location.origin);
  if (generation) url.searchParams.set('workspace', generation);
  return url.pathname + url.search;
}
export function freezeWorkspace() { frozen = true; epoch++; window.dispatchEvent(new Event('hradar-workspace-changing')); }
export async function workspaceFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const ownEpoch = epoch;
  const control = path.startsWith('/api/profiles/') || path === '/api/onboarding';
  if (frozen && !control) throw new Error('Аккаунт меняется. Дождитесь перезагрузки.');
  const headers = new Headers(init.headers);
  if (generation) headers.set('X-Hradar-Workspace', generation);
  const response = await fetch(path, { ...init, headers });
  function guard() { if (ownEpoch !== epoch || (frozen && !control)) throw new Error('Ответ предыдущего аккаунта отклонён.'); }
  guard();
  const currentGeneration = response.headers.get('X-Hradar-Workspace');
  if (response.status === 401 || (currentGeneration && currentGeneration !== generation)) {
    freezeWorkspace(); window.dispatchEvent(new Event('hradar-workspace-reload'));
    throw new Error('Аккаунт изменился. Обновляем подключение.');
  }
  const json = response.json.bind(response);
  response.json = async () => { const value = await json(); guard(); return value; };
  return response;
}
