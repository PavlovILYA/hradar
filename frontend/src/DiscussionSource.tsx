import { useEffect, useRef, useState } from 'react';
import type { ResearchMessage } from './api';
import { sourceMutation, type TelegramSource } from './SourceCatalog';

export function DiscussionSource({ message, onMessages, onArchive }: {
  message: ResearchMessage; onMessages?: (source: TelegramSource) => void; onArchive?: (source: TelegramSource) => void;
}) {
  const [saved, setSaved] = useState<TelegramSource | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => { setSaved(null); setError(''); setBusy(false); return () => controller.current?.abort(); }, [message.sourceKey, message.channelId, message.id]);
  if (!message.sourceKey || !message.canGetMessageThread || message.availability !== 'available') return null;
  return <section aria-label="Доступное обсуждение публикации"><p className="helper">Telegram разрешает открыть обсуждение этой публикации. Будет сохранён только доступный источник обсуждения; импорт и мониторинг не запускаются.</p>
    {!saved && <button className="secondary-button" disabled={busy} onClick={async () => {
      const c = new AbortController(); controller.current = c; setBusy(true); setError('');
      try { const source = await sourceMutation<TelegramSource>(`/api/sources/${encodeURIComponent(message.sourceKey!)}/messages/${message.id}/discussion`, 'POST', undefined, c.signal); if (!c.signal.aborted) setSaved(source); }
      catch { if (!c.signal.aborted) setError('Обсуждение недоступно. Проверьте доступ к публикации и повторите попытку.'); }
      finally { if (controller.current === c) setBusy(false); }
    }}>Сохранить доступное обсуждение</button>}
    {error && <p role="alert" className="error">{error}</p>}
    {saved && <><p role="status">Обсуждение сохранено: {saved.title}{saved.scopeTitle ? ` / ${saved.scopeTitle}` : ''}</p><p className="helper">Исходная публикация: {message.title}. Сообщения обсуждения могут находиться в другой связанной группе.</p><div className="source-actions">{onMessages && <button className="secondary-button" onClick={() => onMessages(saved)}>Открыть сообщения обсуждения</button>}{onArchive && <button className="secondary-button" onClick={() => onArchive(saved)}>Открыть архив обсуждения</button>}</div>{!onMessages && <p className="helper">Откройте сохранённое обсуждение в разделе «Источники» для чтения и поиска.</p>}</>}
  </section>;
}
