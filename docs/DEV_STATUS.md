# hradar — Development status / session handoff

Verified 2026-10-10. Controlled import retained; online/archive navigation and monitoring verification completed. Previous control commit: `0b33994`. Working tree was clean at task start; no checkpoint needed.

## VERIFIED
- Existing history controls retained: IDLE/RUNNING/PAUSED/COMPLETED/FAILED, idempotent coroutine stop, durable cursor/count, manual continuation of paused work, automatic continuation of RUNNING after restart, no parallel workers per channel. Limits 1000/5000/10000 or explicitly chosen unlimited; no invented percentage. SQLite v3 unchanged.
- Central “Поиск в Telegram” and “Локальный архив” modes label result sources. API query/scope/channel/action are together; archive search uses only saved SQLite messages. Channel input is not duplicated; archive/source switching retains selection. Existing latest-N, search, templates, themes and pagination preserved.
- Existing UpdateNewMessage listener/queue, deterministic matching, SQLite subscriptions/matches, deduplication and SSE retained. Subscriptions use selected/all saved sources; active/subscribed toggles pause/resume without deleting history. Global templates remain manual only.
- Non-member public sources use bounded checks every minute (up to four history pages/source), now with 700 ms between recovery requests. A username lookup never joins/subscribes the account. Per-source errors are isolated; account FLOOD_WAIT persists and pauses polling. Initial saved sources can enqueue live updates immediately on startup.
- Browser notices after explicit permission show source and a short publication fragment; click opens the actual publication/details and marks the match read. SSE reconnect refreshes durable matches; duplicate notices are suppressed across reconnects. Browser/OS failure leaves in-app records available. UI states that closed-tab notifications are not guaranteed.
- Backend build succeeded; all 30 Kotlin tests passed. Four added cases exercise actual UpdateNewMessage callback dispatch into the queue, subscription pause/reactivation, bounded recovery and durable cursor/subscription restart, replay deduplication/exclusions, source-error isolation and persisted FLOOD_WAIT. Existing import cancellation/caps/cursors and matching/storage tests remain green.
- Final frontend build succeeded; all 18 Playwright tests passed. Added separate-API/channel-selection checks, synthetic SSE/permission/desktop payload/click/deduplication checks and real SSE disconnect/reconnect checks. Regression suite covers import stop/resume/limits, real history/search/archive pagination and template CRUD. Desktop/mobile layouts inspected; mobile width test passed.
- Real scoped @telegram recovery fixture used only three public historical messages and created one explained match. Restart retained subscription, notification/read flag; replay produced no duplicate. Only the verification template/results were removed; original source state restored.
- After restart: /health and GetMe returned 200, authorization/connection Ready, latest-10 API returned 10. Existing completed archives remain @telegram=430 and @telegramtips=447. One backend on localhost 8080; native TDLib/JNI reused. No sends, joins or paid operations.

## Limits / NOT VERIFIED
- No new external publication arrived during this session. Live callback processing was verified with synthetic UpdateNewMessage; real Telegram verification covered bounded historical recovery. An observed fresh publication on a user-controlled test source is still needed for a fully live external end-to-end check.
- Desktop API behavior was tested with a browser test double after explicit permission; real OS delivery was not manually observed. Closed tabs cannot guarantee notifications; backend monitoring and persisted matches remain independent.
- Real FLOOD_WAIT was not induced; requested waits/cancellation/restart are tested synthetically. Completed archives remain snapshots without incremental ingestion or edit/deletion reconciliation. Historical archive records are not yet automatically matched.
- Local substring search scans the chosen archive; FTS can be added for substantially larger datasets. This account’s global search previously returned explicit free_limit; starCount=0 remains enforced.

## Run / checks
Backend: `./scripts/run-local.sh`; frontend: `cd frontend && npm ci && npm run dev` → localhost 5173. Stop backend before Kotlin compilation; never run two instances against one TDLib session.
Checks: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :backend:build`; `npm run build --prefix frontend`; `npm test --prefix frontend` with authorized backend and completed @telegram archive.
Monitoring: save a source, create a template with selected sources and enable “Уведомлять”; inspect “Уведомления”. Use “Пауза” / “Активировать” or “Отписаться” / “Уведомлять” to control subscriptions.
Import: POST `/api/history/<username>/sync` with JSON maxMessages; POST `/api/history/<username>/stop`; GET same sync path for status. SSE: GET `/api/events`; saved matches: GET `/api/notifications`.
SQLite: `~/.hradar/research.sqlite`; session: `~/.recruitment-radar/tdlib`. Secrets/databases/native/build artifacts remain outside Git; verification scripts/logs under `/tmp`.

## Next action
Observe a fresh publication on an explicitly chosen user-controlled source and confirm real TDLib/polling → matching → SSE → OS desktop delivery without sending or joining automatically.
