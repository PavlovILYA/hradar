# hradar — Development status / session handoff

Verified 2026-10-10. Controllable channel-history synchronization ready. Previous archive implementation: `b495f45`; research checkpoint: `4d88b85`. Working tree was clean at task start, so no new checkpoint was needed.

## VERIFIED
- History UI has “Остановить загрузку” and “Продолжить загрузку”, status, persisted count/errors and a limit selector: 1000 (default), 5000, 10000, or explicitly selected unlimited history. Opening settings does not start an import. No invented progress percentage.
- Coroutine cancellation is awaited before persisting PAUSED/USER. Already committed messages and cursor remain intact; repeated stop is idempotent. Start/stop operations serialize and prevent concurrent workers for the same channel. RUNNING resumes after restart; user-paused work stays paused until explicit continuation.
- Limits apply to the total archive count. Reaching the exact limit persists PAUSED/LIMIT; raising it resumes without skipping the unprocessed remainder of a TDLib page. Unique channel/message keys preserve deduplication.
- SQLite migration v3 preserves research/archive records and allows PAUSED. Limit, pause reason, cursor and retry timestamps persist.
- Archive requests share a 700 ms minimum start interval. FLOOD_WAIT/retry-after waits use Telegram’s duration; persisted waits survive stop/resume and restarts. Retries remain bounded and cancellation interrupts waiting.
- POST `/api/history/{username}/sync` accepts JSON `{"maxMessages":1000}` (5000/10000 also supported); explicit `null` means unlimited. Empty body defaults to 1000. POST `/api/history/{username}/stop` stops safely. Existing GET state/count/archive/search/pagination and latest-N APIs remain available.
- Backend build succeeded: all 26 Kotlin tests passed, including cancellation/in-flight stop, concurrent starts/idempotent stops, resume/deduplication, exact cap/page remainder, durable limits, FLOOD_WAIT and shared request spacing. Prior cursor recovery/search/migration/matching/notification/global-safety tests passed.
- Frontend build succeeded; all 15 Playwright tests passed. New synthetic UI cases verify stop/resume, limit selection, explicit unlimited requests and no automatic import. Existing real history/search/archive pagination and template CRUD passed.
- Real public @telegramtips verification: stopped after one saved message; repeated stop retained identical state. Backend restart preserved PAUSED, count and cursor. Explicit resume with limit 5000 completed with 447 unique messages; server-side pagination confirmed no duplicates. Invalid limit returned HTTP 400.
- Existing @telegram archive remains COMPLETED with 430 messages. Telegram authorization/connection are Ready; GetMe returned 200 without printing profile values; latest-10 API returned 10. One backend on localhost 8080 and one frontend dev server on localhost 5173. Native TDLib/JNI reused; no joins, sends or paid operations.

## Limits / NOT VERIFIED
- Actual Telegram FLOOD_WAIT was not induced; wait/cancellation behavior was verified with synthetic errors. Exact 1000-message cap was tested with synthetic records, not by collecting a larger real channel.
- Completed archives remain snapshots; no automatic ingestion of newer messages or reconciliation of edits/deletions. Existing source monitoring is independent; historical archive messages are not yet passed through Matching Engine.
- SQLite substring search scans the selected archive; FTS can be added for much larger datasets. Deleted/restricted messages cannot be recovered.
- Real desktop OS notification delivery remains unobserved; this account’s global search previously returned explicit free_limit, with starCount=0 enforced.

## Run / checks
Backend: `./scripts/run-local.sh`; frontend: `cd frontend && npm ci && npm run dev`. Both bind to localhost. Stop backend before Kotlin compilation; never run two instances against one TDLib session.
Checks: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :backend:build`; `npm run build --prefix frontend`; `npm test --prefix frontend` with authorized backend and completed @telegram archive. Install Chromium once with `cd frontend && npx playwright install chromium`.
Manual: POST `/api/history/<username>/sync` with JSON maxMessages; POST `/api/history/<username>/stop`; GET `/api/history/<username>/sync` for state. In the browser open “Настроить загрузку истории”.
SQLite: `~/.hradar/research.sqlite`; TDLib session: `~/.recruitment-radar/tdlib`. Secrets, databases, native/build artifacts and logs remain excluded from Git. Verification scripts/logs are under `/tmp`.

## Next action
Add explicit incremental refresh for newer posts in completed archives, with tests and a separate task commit; avoid rescanning entire archives automatically.
