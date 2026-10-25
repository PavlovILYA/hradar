import { useCallback, useEffect, useRef, useState } from 'react';
import { request, type MonitoredChannel, type SearchTemplate, type NotificationPage, type TelegramStatus } from './api';

export interface DashboardProject { id: string; criteria: { name: string; required: string[]; desired: string[]; excluded: string[]; sources: number[] }; updatedAt: number }
interface ProjectPage { items: DashboardProject[]; total: number }
interface Resource<T> { data?: T; at: number; error: boolean }
interface Data { projects: Resource<ProjectPage>; sources: Resource<MonitoredChannel[]>; templates: Resource<SearchTemplate[]>; notifications: Resource<NotificationPage>; telegram: Resource<TelegramStatus> }
const empty = () => ({ at: 0, error: false });
const paths = { projects: '/api/projects?limit=3&offset=0&status=ACTIVE', sources: '/api/channels', templates: '/api/templates', notifications: '/api/notifications?limit=3', telegram: '/api/telegram/status' } as const;
export function useDashboard(active: boolean, revision: number) {
  const [data, setData] = useState<Data>({ projects: empty(), sources: empty(), templates: empty(), notifications: empty(), telegram: empty() });
  const [loading, setLoading] = useState(false);
  const [now, setNow] = useState(Date.now());
  const controller = useRef<AbortController | null>(null);
  const refresh = useCallback(async () => {
    if (controller.current || !active) return;
    const c = new AbortController(); controller.current = c; setLoading(true);
    await Promise.all(Object.entries(paths).map(async ([key, path]) => {
      try {
        const result = await request(path, c.signal);
        if (!c.signal.aborted) setData(old => ({ ...old, [key]: { data: result, at: Date.now(), error: false } }));
      } catch {
        if (!c.signal.aborted) setData(old => ({ ...old, [key]: { ...old[key as keyof Data], error: true } }));
      }
    }));
    if (controller.current === c) { controller.current = null; setLoading(false); setNow(Date.now()); }
  }, [active]);
  useEffect(() => {
    if (!active) return;
    void refresh();
    const timer = setInterval(() => { setNow(Date.now()); if (!document.hidden) void refresh(); }, 30_000);
    const visible = () => { if (!document.hidden) { setNow(Date.now()); void refresh(); } };
    document.addEventListener('visibilitychange', visible);
    return () => { clearInterval(timer); document.removeEventListener('visibilitychange', visible); controller.current?.abort(); controller.current = null; };
  }, [active, revision, refresh]);
  return { data, loading, refresh, now };
}
