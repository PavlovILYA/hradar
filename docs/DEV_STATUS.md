# hradar — Development status / session handoff

Verified 2026-10-10. Full channel-history archive implemented; research workspace checkpoint: `4d88b85`.

## VERIFIED
- Explicit background full-history import via existing TDLib `GetChatHistory`, pages up to 100. Short pages continue; inclusive cursor messages deduplicate. Empty successful page completes; stalled cursor fails after bounded retries and remains resumable.
- SQLite migration v2 preserves research tables and adds unique `(channelId,messageId)` archive records plus per-channel status/cursor/retry state. Page and cursor commit atomically. Two concurrent channel imports maximum; HTTP start returns 202 without waiting for full history.
- Durable RUNNING imports resume automatically after restart; FAILED imports resume on explicit start; COMPLETED starts are idempotent. Telegram rate-limit waits persist. No joins, sends, Stars spending or native TDLib rebuilds.
- REST: POST `/api/history/{username}/sync`; GET same path for state/count; GET `/api/history/syncs`; GET `/api/history/{username}/messages?limit=50&before=<id>&query=<text>`. Server-side literal normalized substring search across saved history, keyset pagination, maximum 100 results/API.
- React archive: start/resume buttons, status/count/errors without percentage, archive selector, whole-history search, previous/next pages of 50 messages and publication details. Existing sources/templates/search/notifications/themes preserved.
- Backend build succeeded; all 20 Kotlin tests passed, including seven archive tests for short/inclusive pages, cursor persistence and automatic recovery, nonprogress failure/manual resume, transient errors, atomic rollback/deduplication, local search/pagination and v1 migration.
- Frontend build succeeded; all 13 Playwright tests passed. Real archive paging/search verified through frontend/backend; failed-import retry/progress checked with synthetic responses. Previous 11 research/browser tests also passed.
- Real authorized public test source @telegram: initial import interrupted after one saved message. Restart automatically continued without another POST and completed with 430 unique messages. API pagination enumerated exactly 430 distinct IDs; local `telegram` search found 231 matches. Repeated start retained completed cursor/count. Another restart preserved all 430 messages and completed state.
- Telegram authorization/connection Ready after restarts; GetMe HTTP 200 checked without printing profile values; old latest-10 channel API still returned 10 messages. One backend instance uses the existing session.
- Existing matching/template persistence, notification uniqueness/recovery and global `starCount=0` safety remain tested. This account's real global query previously returned explicit `free_limit`.

## Limits / NOT VERIFIED
- Archive imports the history accessible to the current account, including messages without text. Deleted/restricted messages cannot be recovered. Real rate-limit/403/network failure was not deliberately induced; retries/nonprogress were tested with synthetic responses.
- Completed archives are snapshots: no automatic ingestion of newer messages or reconciliation of edits/deletions. Existing source monitoring is separate. Historical archive records are not yet passed through Matching Engine.
- No real new public publication arrived during verification; live notifications use synthetic update tests plus previously verified public-history recovery. Desktop OS notification delivery remains unobserved.
- Substring search currently scans the selected local archive; SQLite FTS/indexing can be added for substantially larger archives.

## Run / checks
Backend: `./scripts/run-local.sh` (localhost 8080). Stop it before Kotlin compilation; never run two instances against one TDLib session.
Frontend: `cd frontend && npm ci && npm run dev` (localhost 5173). Open “Сохранённая история” or “Загрузить всю историю” for an explicitly selected public source.
Checks: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :backend:build`; `npm run build --prefix frontend`; `npm test --prefix frontend` with authorized backend and completed @telegram archive. Install Chromium once with `cd frontend && npx playwright install chromium`.
Manual: `curl -X POST http://127.0.0.1:8080/api/history/telegram/sync`; then GET that URL for state, and GET `/api/history/telegram/messages?limit=50&query=telegram` for saved results.
SQLite: `~/.hradar/research.sqlite`; session: `~/.recruitment-radar/tdlib`. Secrets, databases, native binaries, build outputs and temporary verification artifacts are excluded from Git. Logs/check scripts remain under `/tmp`.

## Next action
Add explicit incremental archive refresh for posts newer than the completed snapshot, with tests and a separate task commit. Do not automatically rescan entire archives.
