import type { Screen } from './Navigation';
import type { NotificationRecord } from './api';
import { useDashboard } from './useDashboard';
import './dashboard.css';

const date = (value: number) => new Date(value).toLocaleString('ru-RU');
export function Dashboard({ active, revision, eventConnected, eventAt, navigate, openProject, openNotification }: {
  active: boolean; revision: number; eventConnected: boolean; eventAt: number; navigate: (screen: Screen) => void;
  openProject: (id?: string, edit?: boolean) => void; openNotification: (item: NotificationRecord) => void;
}) {
  const { data, loading, refresh, now } = useDashboard(active, revision);
  const fresh = (resource: { at: number; error: boolean }) => !!resource.at && !resource.error && now - resource.at < 60_000;
  const projects = data.projects.data, sources = data.sources.data, templates = data.templates.data, notifications = data.notifications.data;
  const factsKnown = fresh(data.projects) && fresh(data.sources) && fresh(data.templates);
  const latest = projects?.items[0];
  const projectSourcesExist = !!latest?.criteria.sources.length && latest.criteria.sources.every(id => sources?.some(s => s.id === id));
  const criteriaReady = projectSourcesExist && !!latest && [...latest.criteria.required, ...latest.criteria.desired, ...latest.criteria.excluded].some(term => term.trim());
  const subscriptions = templates?.filter(t => t.active && t.subscribed && t.sourceMode !== 'global') ?? [];
  const usable = subscriptions.filter(t => t.sourceMode === 'all' ? !!sources?.length : t.sources.length > 0 && t.sources.every(id => sources?.some(s => s.id === id)));
  const telegram = fresh(data.telegram) ? data.telegram.data : undefined;
  const telegramLabel = !telegram ? 'Нет актуальных данных' : !telegram.authorized ? 'Требуется авторизация' : telegram.connectionState === 'Ready' ? 'Подключён' : telegram.connectionState ? 'Переподключается' : 'Состояние соединения неизвестно';
  const monitoring = !fresh(data.templates) || !fresh(data.sources) ? 'Нет данных' : !subscriptions.length ? 'Нет активных подписок' : !usable.length ? 'Нет доступных источников' : !telegram || !telegram.authorized || telegram.connectionState !== 'Ready' ? 'Telegram недоступен' : 'Включён';
  const nextStep = !sources?.length ? 0 : !projects?.total ? 1 : !criteriaReady ? 2 : !usable.length ? 3 : -1;
  const steps = [
    { label: 'Шаг 1. Добавить Telegram-источник', done: !!sources?.length, enabled: true, action: () => navigate('sources') },
    { label: 'Шаг 2. Создать проект найма', done: !!projects?.total, enabled: !!sources?.length, action: () => openProject() },
    { label: 'Шаг 3. Указать критерии поиска', done: criteriaReady, enabled: !!sources?.length && !!latest, action: () => latest && openProject(latest.id, true) },
    { label: 'Шаг 4. Настроить мониторинг', done: criteriaReady && !!usable.length, enabled: criteriaReady, action: () => navigate('templates') },
  ];
  const metric = (resource: { at: number; error: boolean }, value?: number) => resource.at ? value : resource.error ? 'Нет данных' : '…';
  const hint = (resource: { at: number; error: boolean }) => resource.error ? <p className="dashboard-warning">Не удалось обновить. {resource.at ? 'Показаны предыдущие данные.' : 'Повторите загрузку.'}</p> : resource.at && !fresh(resource) ? <p className="dashboard-warning">Данные устарели. Обновите обзор.</p> : null;
  const anyFresh = Object.values(data).some(fresh);
  return <div className="dashboard" aria-busy={loading}>
    <div className="dashboard-intro"><div><p className="section-eyebrow">ВАШ РАБОЧИЙ ДЕНЬ</p><h2>Работа под контролем</h2><p className="helper">Проекты, новые совпадения и следующий шаг — в одном месте.</p></div><button className="secondary-button" disabled={loading} onClick={() => void refresh()}>{loading ? 'Обновляем обзор…' : 'Обновить обзор'}</button></div>
    <div className="dashboard-grid">
      <section className="dashboard-card" aria-label="Активные проекты"><h3>Активные проекты</h3><strong className="dashboard-number">{metric(data.projects, projects?.total)}</strong>{hint(data.projects)}<ul className="dashboard-projects">{projects?.items.map(p => <li key={p.id}><button className="text-button" onClick={() => openProject(p.id)}>{p.criteria.name}</button><small>Обновлён {date(p.updatedAt)}</small></li>)}</ul>{projects?.total === 0 && <p className="helper">Создайте проект для вашей вакансии.</p>}<button className="text-button" onClick={() => navigate('projects')}>Все проекты →</button></section>
      <section className="dashboard-card" aria-label="Новые совпадения"><h3>Новые совпадения</h3><strong className="dashboard-number">{metric(data.notifications, notifications?.unread)}</strong><p className="helper">Непрочитанные публичные уведомления по шаблонам. Локальные совпадения открываются отдельно в разделе «Уведомления».</p>{hint(data.notifications)}{notifications?.unread === 0 && <p>Непрочитанных совпадений нет</p>}<button className="text-button" onClick={() => navigate('notifications')}>Открыть уведомления →</button></section>
      <section className="dashboard-card" aria-label="Источники"><h3>Источники</h3><strong className="dashboard-number">{metric(data.sources, sources?.length)}</strong><p className="helper">Публичные каналы для поиска и проектов. Локальные чаты, темы и обсуждения доступны в разделе «Источники».</p>{hint(data.sources)}<button className="text-button" onClick={() => navigate('sources')}>Управлять источниками →</button></section>
      <section className="dashboard-card" aria-label="Мониторинг"><h3>Мониторинг</h3><strong className="dashboard-number">{metric(data.templates, subscriptions.length)}</strong><p className="helper">Активные подписки публичного мониторинга</p><p className='helper'>Локальный мониторинг выбранных чатов настраивается отдельно в разделе «Источники».</p><p className="dashboard-state">{monitoring}</p>{hint(data.templates)}<button className="text-button" onClick={() => navigate('templates')}>Шаблоны и подписки →</button></section>
    </div>
    {factsKnown && nextStep >= 0 && <section className={`dashboard-card dashboard-setup ${projects?.total ? 'compact' : ''}`} aria-label="Настройка рабочего пространства"><h3>{!sources?.length && !projects?.total ? 'Начните с первого источника' : ['Добавьте источник', 'Создайте проект найма', 'Следующий шаг: критерии последнего проекта', 'Следующий шаг: включите мониторинг'][nextStep]}</h3><p className="helper">{latest ? `Критерии проверены для последнего активного проекта «${latest.criteria.name}». ` : ''}Шаги отмечаются по сохранённым настройкам. Ничего не создаётся автоматически.</p><ol>{steps.map((step, i) => <li key={step.label} aria-current={i === nextStep ? 'step' : undefined}><span className="dashboard-step-state">{step.done ? 'Выполнено' : i === nextStep ? 'Следующий шаг' : 'Далее'}</span><button className="text-button" disabled={!step.enabled} onClick={step.action}>{step.label}</button></li>)}</ol>{!projectSourcesExist && latest && <p className="helper">Проверьте источники последнего проекта: один из них мог быть удалён.</p>}<p className="helper">Для мониторинга задайте условия шаблона, выберите сохранённые источники и включите «Уведомлять». Критерии проекта сами по себе подписку не включают.</p></section>}
    <div className="dashboard-bottom">
      <section className="dashboard-card" aria-label="Последние совпадения"><h3>Последние совпадения</h3><p className="helper">Последние уведомления, включая прочитанные</p>{hint(data.notifications)}{!data.notifications.at ? <p>{data.notifications.error ? 'Нет данных' : 'Загружаем совпадения…'}</p> : !notifications?.items.length ? <p>Совпадений пока нет. Настройте подписку в шаблонах.</p> : <ul className="dashboard-matches">{notifications.items.map(item => <li key={item.id}><div><strong>{item.templateName}</strong><span>{item.read ? 'Прочитано' : 'Новое'}</span></div><p>{item.message.title || item.message.username || 'Telegram-источник'}</p><time dateTime={new Date(item.message.date * 1000).toISOString()}>{date(item.message.date * 1000)}</time><p className="dashboard-excerpt">{item.message.text?.slice(0, 240) || 'Публикация без текста'}</p><button className="text-button" onClick={() => openNotification(item)}>Открыть публикацию</button></li>)}</ul>}</section>
      <section className="dashboard-card" aria-label="Состояние приложения"><h3>Состояние приложения</h3><dl><dt>Telegram</dt><dd>{telegramLabel}</dd><dt>Сервер приложения</dt><dd>{fresh(data.telegram) && anyFresh ? 'Доступен' : anyFresh ? 'Частично доступен' : loading ? 'Проверяем…' : 'Недоступен'}</dd><dt>Обновления событий</dt><dd>{eventConnected && eventAt && now - eventAt < 15_000 ? 'Подключены' : 'Нет свежих событий · переподключение'}</dd></dl>{hint(data.telegram)}<p className="helper">Мониторинг работает, пока запущен hradar. Доступность доставки зависит от Telegram.</p><button className="text-button" onClick={() => navigate('application')}>Состояние и управление →</button></section>
    </div>
    <section className="dashboard-card dashboard-quick" aria-label="Быстрые действия"><h3>Быстрые действия</h3><div><button className="secondary-button" onClick={() => navigate('sources')}>Добавить источник</button><button className="secondary-button" disabled={!fresh(data.sources) || !sources?.length} onClick={() => openProject()}>Создать проект</button><button className="primary-button" onClick={() => navigate('search')}>Начать поиск</button><button className="secondary-button" onClick={() => navigate('notifications')}>Уведомления</button></div></section>
  </div>;
}
