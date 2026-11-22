import { useEffect, useRef, useState } from 'react';
export function UpdateStatus({ connected, screen, onApplication }: { connected: boolean; screen: string; onApplication: () => void }) {
  const [open, setOpen] = useState(false);
  const container = useRef<HTMLDivElement>(null), button = useRef<HTMLButtonElement>(null);
  useEffect(() => { setOpen(false); }, [screen]);
  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => { if (!container.current?.contains(event.target as Node)) setOpen(false); };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { event.preventDefault(); setOpen(false); button.current?.focus(); } };
    const focus = (event: FocusEvent) => { if (!container.current?.contains(event.target as Node)) setOpen(false); };
    document.addEventListener('pointerdown', outside); document.addEventListener('keydown', escape); document.addEventListener('focusin', focus);
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape); document.removeEventListener('focusin', focus); };
  }, [open]);
  return <div ref={container} className={`update-status ${connected ? 'connected' : 'reconnecting'}`}>
    <button ref={button} aria-label={connected ? 'Обновления подключены' : 'Обновления переподключаются'} title={connected ? 'Обновления подключены' : 'Обновления переподключаются'} aria-expanded={open} aria-controls="updates-note" onClick={() => setOpen(value => !value)}><span aria-hidden="true">●</span></button>
    {open && <div id="updates-note" role="note">{connected ? 'Новые совпадения обновляются автоматически.' : 'Соединение обновлений восстанавливается. Совпадения сохраняются; можно обновить список вручную.'}<button className="text-button" onClick={() => { setOpen(false); onApplication(); }}>Состояние приложения</button></div>}
  </div>;
}
