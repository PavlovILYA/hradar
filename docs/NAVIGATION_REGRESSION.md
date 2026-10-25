# Navigation regression checklist (recorded before implementation)

Base: `fa65a5f`, origin/master including PR #8. Existing open PR #3 is excluded.
Only frontend shell/navigation and its tests/docs may change. No live Telegram tests.

| Existing scenario | Preserve and verify |
| --- | --- |
| Login/desktop cookie and onboarding | All existing fake login/diagnostics tests; unchanged auth files |
| Recent channel messages | Username validation, 1–100 limit, saved-source selection, errors, empty/media text |
| Online search | Current channel, chosen sources, free global search, cursor paging, explicit rate/free-limit errors |
| Local result filtering | Text highlight, AND/OR/NOT template explanation, reset, details, original link and copy |
| Sources | List/filter, add/save and remove, recent messages, open archive; never auto-join/import |
| Templates/subscriptions | Create/edit/save/delete, pause/resume, subscribe, global manual-only, refresh bot-created subscription, run template |
| Archive | Choose channel, bounded import, explicit unlimited option, pause/resume/retry, counters, paging, whole-archive query, details |
| Saved results | Status filter, refresh, bounded pagination/deduplication, original links |
| Notifications | SSE reconnect/deduplication, unread count, read/read-all/more, open publication, OS notification permission |
| Projects/CRM | CRUD/criteria, source/template selection, searches/paging, board, notes/stages, source links, monitoring, saved results |
| Bot settings | Token check/save/clear, owner, invitations, lookup/revocation, delivery state and errors; same guarded APIs |
| MCP settings | Explicit enable, source grants, username, capability status, command copy; no implicit grants |
| Application state | Telegram status/retry, SSE state, desktop-only graceful shutdown, theme persistence |
| Navigation | Every requested section, working overview actions, one selected item, mobile open/close/Escape/focus, keyboard access |
| State retention | Search query/source/results, archive draft/limit, template/project drafts and notes, integration form drafts survive navigation |
| Context | Details only for selected search/archive publication; no right tools on settings/CRM; filters local to search |

Implementation approach: one typed current-screen value and one grouped sidebar.
Lazy-mount visited screens and hide inactive screens to preserve their local React
state; do not persist form inputs to localStorage. Keep existing domain components.
Move source CRUD into Sources; recent messages and source selection live in Search.
Move template editing into Templates; keep search filters inline. The right panel
contains only a selected publication and is tied to its originating screen.
Overview is an explicit placeholder with real navigation actions, not a dashboard.

## Verification results

- Before changes: all 35 offline browser tests passed.
- After changes: all 41 offline browser tests passed, including six new navigation
  journeys. Existing search/CRM/archive/SSE/Bot/MCP assertions were retained;
  selectors now use the sidebar and active screen rather than removed containers.
- Production TypeScript/Vite build passed. Synthetic visual checks cover desktop
  overview/search/details and narrow dark-mode navigation.
- Onboarding source/tests/styles from PR #8 are unchanged. Source/session/database
  runtime behavior and REST contracts are unchanged. Backend edits in this PR are
  application-version metadata only, per the added versioning requirement.

## Limits of this stage

- Overview is a placeholder with working actions; it contains no invented metrics.
- Navigation/form drafts live in memory. A reload, closing the app or reauthorizing
  starts fresh; sensitive drafts are not persisted to local storage.
- This stage uses local screen state, without URL routes/browser-back history.
- Visited views remain mounted. Existing archive/bot polling can continue while
  hidden; manual refresh actions remain available. Projects and MCP reload their
  read metadata on entry while preserving drafts.
- Actual Telegram traffic, OS notification delivery and physical Mac upgrade were
  not exercised. Automated browser requests are entirely synthetic.

Version decision: new navigation screens are a MINOR change; the owner requested
the new baseline `0.2.0`. See VERSIONING.md for the canonical source and metadata gates.
