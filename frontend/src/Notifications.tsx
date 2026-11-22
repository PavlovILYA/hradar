import type { NotificationRecord } from './api';
export function Notifications({ items, unread, cursor, onRead, onReadAll, onOpen, onMore, onRefresh }: {
  items: NotificationRecord[]; unread: number; cursor: number | null;
  onRead: (ids: string[]) => void; onReadAll: () => void; onOpen: (item: NotificationRecord, related?: NotificationRecord[]) => void; onMore: () => void; onRefresh: () => void;
}) {
  const groups = new Map<string, NotificationRecord[]>();
  for (const item of new Map(items.map(item => [item.id, item])).values()) {
    const key = `${item.message.channelId}:${item.message.id}`;
    const group = groups.get(key) ?? []; group.push(item); groups.set(key, group);
  }
  return <section className="notification-center">
    <div className="notification-heading"><h2>Совпадения по сохранённым поискам</h2><div className="action-row"><button className="text-button" onClick={onRefresh}>Обновить совпадения</button><button className="text-button" disabled={!unread} onClick={onReadAll}>Прочитать все</button></div></div>
    {!items.length && <div className="empty-state"><h3>Пока нет совпадений</h3><p>Сохраните поисковый запрос, выберите чаты и включите отслеживание новых публикаций.</p></div>}
    {[...groups.entries()].map(([key, group]) => {
      const item = group[0], unreadItems = group.filter(i => !i.read);
      return <article className={`notification-card ${unreadItems.length ? 'unread' : ''}`} key={key}>
        <div className="message-meta"><strong>{item.message.title || item.message.username || 'Telegram-чат'}</strong><time>{new Date(item.detectedAt || item.message.date * 1000).toLocaleString('ru-RU')}</time></div>
        <p className="notification-source">{item.message.scopeTitle}{item.message.username && ` · @${item.message.username}`}</p>
        <p>{[...new Set(group.map(i => i.templateName))].join(' · ')}{group.length > 1 && ` · Подошло поисков: ${group.length}`}</p>
        <p className="message-text">{item.message.text}</p>
        {item.message.explanation && <p className="match-reason">Совпали: {[...item.message.explanation.foundRequired, ...item.message.explanation.foundAny].join(', ')} · Исключения отсутствуют</p>}
        {group.length > 1 && <details><summary>Поиски, которым подошла публикация</summary><ul>{group.map(rule => <li key={rule.id}>{rule.templateName} · {rule.read ? 'Прочитано' : 'Новое'}<details><summary>Дополнительно</summary>ID поиска: {rule.templateId}</details></li>)}</ul><p className="helper">Показаны совпадения в загруженной выборке. Число в меню учитывает совпадения по каждому поиску.</p></details>}
        <div className="template-actions"><button className="text-button" onClick={() => onOpen(item, group)}>Открыть публикацию</button>{!!unreadItems.length && <button className="text-button" onClick={() => onRead(unreadItems.map(i => i.id))}>Отметить прочитанным</button>}</div>
      </article>;
    })}
    {cursor && <button className="load-more" onClick={onMore}>Более ранние уведомления</button>}
  </section>;
}
