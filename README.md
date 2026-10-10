# Recruitment Radar

Local Telegram recruitment research prototype: **Kotlin/JVM 21 + Ktor 3.6.0 + official TDLib Java/JNI**.
Local MVP: authorize a Telegram user locally and browse recent public-channel messages in a React + TypeScript web interface.

## Requirements (macOS)

Build TDLib on the machine where you will run it: Intel binaries cannot be
reused by an ARM JVM on Apple Silicon.

- JDK 21 (check `java -version`, `echo "$JAVA_HOME"`, `uname -m`)
- Xcode Command Line Tools: `xcode-select --install`
- Homebrew: `brew install cmake gperf openssl@3` (Gradle is provided by the wrapper)
- Telegram API credentials: obtain `api_id` and `api_hash` at https://my.telegram.org

TDLib is **not** an ordinary pure-Java dependency: its official Java API requires a compiled native JNI library. The script below follows the upstream two-stage Java build.

## Run

From repository root:

```bash
./scripts/build-tdlib-macos.sh   # one-time TDLib compilation, takes time and disk space
cp .env.example .env
# edit .env and fill YOUR api_id and api_hash
./scripts/run-local.sh
```

The launcher selects JDK 21 and loads `.env` before invoking the Gradle 8.14.3
wrapper. The wrapper downloads Gradle on first use and verifies its SHA-256.
For build commands, set `JAVA_HOME="$(/usr/libexec/java_home -v 21)"` and use
`./gradlew :backend:build` after compiling TDLib.

The terminal will request your phone number, Telegram login code, and if applicable 2FA password or email code. This project never asks you to put login codes in a public webpage or commit them. After authorization, the TDLib session is stored under `~/.recruitment-radar/tdlib` and can be reused on later starts. **Protect that directory: it contains account session data.**

TDLib native binaries and generated bindings are intentionally excluded from Git. Never commit `.env`, account sessions or candidate personal information.

## Web interface

Requires Node.js 22.12+ (or 24+) and npm. Keep the backend running in its terminal;
do not start another backend against the same TDLib session.

In a second terminal:

```bash
cd frontend
npm ci
npm run dev
```

Open **http://127.0.0.1:5173**. The interface shows Telegram connection status,
lets you enter a public channel username and a limit from 1 to 100, and displays
message text/media captions and dates. Search filters the currently loaded messages.
History is fetched only when you press the load button. This history view stays in
browser memory; templates and notification matches are persisted locally in SQLite.

The frontend serves only on localhost and proxies `/api` to the existing Ktor
backend on port 8080 (see [Vite proxy documentation](https://vite.dev/config/server-options#server-proxy)).
Authentication remains in the backend terminal. No API keys are passed to the browser.

```bash
cd frontend
npm run build                 # TypeScript check and production bundle
npx playwright install chromium  # once, for browser tests
npm test                      # backend must be running and Telegram authorized
npm run preview               # serve the built bundle locally, with API proxy
```

Browser tests include real read-only requests to `@telegram`, a nonexistent
username, mocked error/empty responses, safe rendering, and mobile layout.

## API

The HTTP server binds **only to `127.0.0.1:8080`**.

```bash
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/api/telegram/status
curl http://127.0.0.1:8080/api/telegram/me
curl 'http://127.0.0.1:8080/api/telegram/chats/durov/messages?limit=10'
```

The channel endpoint resolves public usernames. It can still fail for channels whose history is restricted or inaccessible to the account. TDLib might return fewer messages than the requested limit. The sample does not join chats or send messages.

## Security / scope

- Do not publish the `.env` file, TDLib directory, account credentials, logs with authentication data, or candidates' profiles.
- The first milestone is **read-only**. No auto-contacting people, bulk scraping, or exporting personal profiles.
- Respect Telegram's API terms and applicable data privacy laws when extending the project.
- This prototype is local and has no API authentication. Don't expose port 8080 publicly or to your LAN.
- For a production-grade app, move auth into a secure desktop onboarding flow, add error/timeout handling and robust shutdown/state reconciliation, and securely store session files.

## Architecture

```text
Ktor REST API --> TdlibGateway --> generated Java TdApi + Client --> JNI --> Telegram
```

## Research workspace

The interface has sources, Telegram-style message cards, filters, publication details,
light/dark themes, and a notification center. Save a source after opening its history.
Up to 10 explicit public sources are supported; saving a source never joins it.

Templates support required words/phrases (AND), alternatives (OR), exclusions (NOT),
activation, editing, deletion and subscriptions. Each template has a separate Telegram
query. Local matching normalizes Unicode, case, whitespace and ё/е, respects word
boundaries, and explains matched conditions. Template searches apply these conditions
to the candidates returned by that query; they do not claim to search every Telegram
post satisfying the local rules.

Search uses `SearchChatMessages` in one channel or selected saved sources, with
cursor pagination, source metadata and `GetMessageLink` where available. Global
`SearchPublicPosts` checks `GetPublicPostSearchLimits` and always uses `starCount=0`.
Unsupported/free-limit states are explicit. Global templates are manual only; no
continuous global notification stream is promised.

Subscriptions monitor `UpdateNewMessage` and recover gaps with bounded history scans
(maximum four 100-message pages per source per minute). New subscriptions start now;
only matching publications are persisted. Recovery cursors, templates, read state and
notifications survive restarts; unique constraints prevent duplicate notifications.
Rate-limit recovery waits are persisted. Public non-member channels may rely on the
periodic recovery scan rather than live updates. Keep the backend running to monitor.

Local SQLite: `~/.hradar/research.sqlite` (schema migration version 4). Account credentials
stay in `.env` and the separate TDLib session; they are not stored in research tables.
`/api/events` streams updates with SSE. Browser desktop notifications need explicit
permission and an open page; no external push server or Telegram sends are used.

Useful local API endpoints:

- `GET/POST /api/channels`, `DELETE /api/channels/{id}`
- `GET/POST /api/templates`, `PUT/DELETE /api/templates/{id}`
- `POST /api/search` (mode: channel/tracked/global, query, usernames, templateId, limit, cursor)
- `GET /api/notifications?limit=50&before=<sequence>`
- `POST /api/notifications/{id}/read`, `POST /api/notifications/read-all`
- `GET /api/monitoring`, `GET /api/events`

Stop the backend before recompiling its classes; then run `./scripts/run-local.sh`
once with the existing session. Never run two instances against the same TDLib database.

## References

- Official TDLib: https://github.com/tdlib/td
- Official Java sample/build: https://github.com/tdlib/td/tree/master/example/java
- Ktor: https://ktor.io/docs/

## Full channel-history archive

Choose a public channel and open **Настроить загрузку истории** or
**Сохранённая история**. Select 1000 (default), 5000, 10000 messages, or explicitly
choose **Вся доступная история** for unlimited loading, then start the import. The backend imports available history in pages of up to
100 messages, stores unique `(channelId, messageId)` records in SQLite, and commits
messages and the oldest-message cursor together. Short pages do not end the import.
Only an empty TDLib response marks completion; repeated nonadvancing cursors fail
with a resumable error. Two channels can import concurrently; start/stop operations serialize and prevent
multiple workers for one channel. A shared archive request gate spaces requests by
at least 700 ms and respects persisted retry waits. FLOOD_WAIT/retry-after waits
use Telegram’s requested duration; retries are bounded and cancellable. No channels are joined.

`RUNNING` imports resume automatically after backend restart. **Остановить загрузку**
cancels the background coroutine, preserves records and cursor, and persists `PAUSED`.
User-paused imports stay paused across restarts. **Продолжить загрузку** resumes
`PAUSED` or `FAILED` imports. Reaching the selected total archive size also pauses
the import; increase the limit to continue without skipping any page remainder. Starting a completed archive
is a no-op: this is a historical snapshot, not continuous ingestion of newer posts
or synchronization of edits/deletions. Existing source monitoring is independent.
The archive is not automatically passed through recruitment templates yet.

```bash
curl -X POST http://127.0.0.1:8080/api/history/telegram/sync \
  -H 'Content-Type: application/json' -d '{"maxMessages":1000}'
curl -X POST http://127.0.0.1:8080/api/history/telegram/stop
# Resume via the same POST /sync, optionally raising maxMessages to 5000/10000.
# Explicit unlimited choice: JSON {"maxMessages":null}. An empty body defaults to 1000.
curl http://127.0.0.1:8080/api/history/telegram/sync
curl http://127.0.0.1:8080/api/history/syncs
curl 'http://127.0.0.1:8080/api/history/telegram/messages?limit=50&query=telegram'
# For the next page, pass the returned nextCursor as &before=<messageId>.
```

POST returns 202 after channel resolution; importing runs in the background.
Status includes `IDLE`, `RUNNING`, `PAUSED`, `COMPLETED`, or `FAILED`, count, cursor,
maxMessages, pauseReason (USER/LIMIT), nextRetryAt and errors;
there is no invented completion percentage. Local text search normalizes Unicode
and case and matches literal substrings across the saved archive. Results use
server-side keyset pagination, maximum 100/API and 50/UI messages per page.
Stop the running backend before compiling Kotlin, then start exactly one instance.

## Online search, archive and subscriptions

Use the central **Поиск в Telegram** tab for direct `SearchChatMessages` / public
post API searches. Query, scope and action are in the same workspace. **Локальный
архив** searches only imported SQLite records; it never substitutes an online
query. Each mode labels its result source. Channel selection is retained when
switching modes; saved sources can also switch the archive without a Telegram call.

Save a public source, open **Шаблоны**, select its ID in a template and enable
**Уведомлять**. Required/alternative/excluded phrases apply to new publications.
**Пауза** temporarily disables the template; activation restores monitoring for
new matches. **Отписаться** disables subscription without deleting the template
or previous matches. Subscriptions, checkpoints and notifications persist in SQLite.

TDLib updates are used when the account actually receives them. Resolving a public
username does not join or subscribe the account. Non-member public sources rely
on a bounded check every minute, up to four history pages per source with at least
700 ms between recovery requests. Errors are isolated per source; Telegram account
rate limits pause requests for the specified time. Recovery resumes saved cursors
and deduplicates matches without copying a source's full history.

SSE supplies connection/unread summaries every three seconds; EventSource reconnects
automatically and the frontend refreshes durable notification records. After explicit
browser permission, desktop notices show the source and a short fragment; clicking
opens that publication in hradar, with its original Telegram link where available.
OS delivery is browser-dependent and requires the page to remain open; backend
monitoring can continue with the browser closed and matches remain in the app.

## MCP integration

Open **Интеграции → MCP** in the existing UI, enable access and manually allow
public sources. MCP starts disabled; saved channels do not automatically receive
agent access. Nine read-only tools expose status, permitted sources, bounded
history/search/context, local archive, monitoring rules and an unsent draft.
The typed capability implementation is shared by REST and MCP.

```bash
codex mcp add hradar --url http://127.0.0.1:8080/mcp
codex mcp list
```

Private chats, arbitrary TDLib methods, automatic joins/sends and paid operations
are unavailable. The server remains local; Host/Origin checks and UI-only CSRF
management protect the allowlist. See [MCP setup, schemas and security](docs/MCP.md).

## Continuous integration

- **Fast CI** (`ci.yml`) runs on pushes/PRs to master: independent frontend
  build/offline browser tests and Java-only Kotlin build/unit tests. No Telegram
  credentials/session or JNI is required. npm/Gradle caches are enabled.
- **Native ARM64** (`macos-arm64.yml`) runs manually or on master changes to the
  listed TDLib workflow/scripts/configuration. It builds the pinned official source,
  verifies ARM64/JNI/dependencies, tests Kotlin and uploads checksummed binaries
  with GitHub provenance. React/Kotlin changes alone do not start native compilation.

Fast CI fetches only a SHA-pinned, attested Java JAR from a permanent versioned
hradar Release. **Initial bootstrap is still required:** until a successful native
run is manually published and its hash/build commit are pinned, the backend job
fails explicitly; the frontend job runs independently. No unverified binary or
expired workflow-artifact fallback is used.

Run offline frontend checks with `npm run test:ci --prefix frontend`. Six `@telegram`
integration tests remain separate (`npm run test:telegram --prefix frontend`).
See [CI setup, bootstrap publication, retry and TDLib updates](docs/CI.md).
