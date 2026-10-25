import { useEffect, useRef } from 'react';

export type Screen = 'overview' | 'projects' | 'search' | 'saved' | 'notifications' | 'sources' | 'archive' | 'templates' | 'bot' | 'mcp' | 'application';
const groups: { title: string; items: { id: Screen; label: string; symbol: string }[] }[] = [
  { title: 'Рабочее пространство', items: [
    { id: 'overview', label: 'Обзор', symbol: '◫' }, { id: 'projects', label: 'Проекты', symbol: '▤' },
    { id: 'search', label: 'Поиск', symbol: '⌕' }, { id: 'saved', label: 'Избранное', symbol: '☆' }, { id: 'notifications', label: 'Уведомления', symbol: '◉' },
  ] },
  { title: 'Данные', items: [{ id: 'sources', label: 'Источники', symbol: '▥' }, { id: 'archive', label: 'Архив', symbol: '▣' }, { id: 'templates', label: 'Шаблоны', symbol: '≡' }] },
  { title: 'Настройки', items: [{ id: 'bot', label: 'Telegram Bot', symbol: '↗' }, { id: 'mcp', label: 'MCP', symbol: '◇' }, { id: 'application', label: 'Состояние приложения', symbol: '⚙' }] },
];
export const screenTitles = Object.fromEntries(groups.flatMap(group => group.items.map(item => [item.id, item.label]))) as Record<Screen, string>;
export function Navigation({ screen, navigate, unread, theme, toggleTheme, open, close }: {
  screen: Screen; navigate: (screen: Screen) => void; unread: number; theme: string; toggleTheme: () => void; open: boolean; close: () => void;
}) {
  const container = useRef<HTMLElement>(null);
  useEffect(() => { if (open) container.current?.querySelector<HTMLButtonElement>('[aria-current="page"]')?.focus(); }, [open]);
  return <>
    {open && <button className="navigation-backdrop" aria-label="Закрыть навигационное меню" onClick={close} tabIndex={-1} />}
    <aside ref={container} id="workspace-navigation" className={`workspace-nav ${open ? 'is-open' : ''}`} onKeyDown={event => {
      if (!open) return;
      if (event.key === 'Escape') { event.preventDefault(); close(); }
      if (event.key === 'Tab') {
        const buttons = [...(container.current?.querySelectorAll<HTMLButtonElement>('button') ?? [])].filter(button => button.getClientRects().length);
        const first = buttons[0], last = buttons[buttons.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
        if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
      }
    }}>
      <div className="workspace-brand"><span className="brand-mark" aria-hidden="true">h</span><strong>hradar</strong><button className="navigation-close icon-button" aria-label="Закрыть навигацию" onClick={close}>×</button></div>
      <nav aria-label="Основная навигация">{groups.map(group => <section className="navigation-group" key={group.title} aria-label={group.title}>
        <h2>{group.title}</h2>{group.items.map(item => <button key={item.id} aria-label={item.label} aria-current={screen === item.id ? 'page' : undefined} aria-describedby={item.id === 'notifications' ? 'navigation-unread' : undefined} onClick={() => navigate(item.id)}>
          <span className="navigation-symbol" aria-hidden="true">{item.symbol}</span><span>{item.label}</span>{item.id === 'notifications' && <span className="badge" aria-hidden="true">{unread}</span>}
        </button>)}
      </section>)}</nav>
      <span id="navigation-unread" className="sr-only">Непрочитанных уведомлений: {unread}</span>
      <div className="workspace-nav-footer"><span>Локальное рабочее пространство</span><button className="text-button" aria-label={theme === 'dark' ? 'Светлая тема' : 'Тёмная тема'} onClick={toggleTheme}>{theme === 'dark' ? '☀ Светлая тема' : '☾ Тёмная тема'}</button></div>
    </aside>
  </>;
}
