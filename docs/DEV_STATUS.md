# hradar — Development status / session handoff

Verified 2026-10-10. Current research workspace ready for checkpoint; next task: full channel-history import.

## VERIFIED
- Telegram-inspired React/TypeScript workspace: three panels, sources, light/dark themes, responsive drawers, keyword highlighting, publication details and original links. Existing history endpoint preserved.
- SQLite repositories/migration v1 for channels, templates, sources, matches, notifications and monitoring cursors. Deterministic AND/OR/NOT matching and explanations; template CRUD, activation and subscription controls.
- Real `SearchChatMessages`: channel results, original links and next page without duplicates; merged search over two saved public sources returned results from both.
- Global method exists; a real query was blocked by this account's free-search conditions. Explicit `free_limit`; `starCount=0` enforced and tested. Unsupported-method behavior tested. No paid operations, joins or sends.
- Monitoring uses a bounded update queue outside the TDLib thread, durable recovery checkpoints, subscription start times, rate-limit waits, SQLite deduplication, SSE and notification read state. Browser desktop permission is requested only by a user click.
- 13 Kotlin tests passed (history, matching, template persistence, notification uniqueness/read state, synthetic new-message handling, global-search safety).
- Frontend build and all 11 browser tests passed; startup readiness is checked before the suite. Real Telegram history/search/template CRUD plus mocked empty/error/notification/global-limit cases verified.
- Real controlled recovery: an isolated verification template and a rewound checkpoint for three public @telegram messages created one matching notification. Restart preserved the template, notification and read flag; replay produced no duplicate. Only verification records were removed afterward.
- Authorization and connection returned Ready after restarts. GetMe returned 200; profile values were not printed. Native TDLib/JNI reused, debug logging disabled.

## Limits / NOT VERIFIED
- No new public publication arrived during this session; live update processing was tested with synthetic messages, and recovery with real public history.
- Real 403/rate-limit failures were not induced. Desktop OS notification delivery was not manually observed.
- Current history endpoint still returns up to 100 messages. Full persistent history import is the next authorized task.
- Recovery scans are bounded; a stalled cursor retains state and retries rather than silently skipping a gap. Non-member public sources may rely on periodic scans.

## Run / checks
Backend: `./scripts/run-local.sh` (localhost 8080; stop it before Kotlin compilation). Frontend: `cd frontend && npm ci && npm run dev` (localhost 5173).
Checks: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :backend:build`; `npm run build --prefix frontend`; `npm test --prefix frontend` with authorized backend. Browser runtime: `cd frontend && npx playwright install chromium` once.
Local DB: `~/.hradar/research.sqlite`; TDLib session remains separate under `~/.recruitment-radar/tdlib`. Logs and verification artifacts remain under `/tmp`, outside Git.

## Next action
Commit this verified workspace separately, then implement resumable full-history background import with local text search and server-side pagination.
