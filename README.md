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

Local SQLite: `~/.hradar/research.sqlite` (schema migration version 2). Account credentials
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

Choose a public channel and press **Загрузить всю историю**, or open
**Сохранённая история**. The backend imports available history in pages of up to
100 messages, stores unique `(channelId, messageId)` records in SQLite, and commits
messages and the oldest-message cursor together. Short pages do not end the import.
Only an empty TDLib response marks completion; repeated nonadvancing cursors fail
with a resumable error. Two channels can import concurrently, with bounded retries
and persisted Telegram rate-limit waits. No channels are joined.

`RUNNING` imports resume automatically after backend restart. `FAILED` imports
continue from their saved cursor when started again. Starting a completed archive
is a no-op: this is a historical snapshot, not continuous ingestion of newer posts
or synchronization of edits/deletions. Existing source monitoring is independent.
The archive is not automatically passed through recruitment templates yet.

```bash
curl -X POST http://127.0.0.1:8080/api/history/telegram/sync
curl http://127.0.0.1:8080/api/history/telegram/sync
curl http://127.0.0.1:8080/api/history/syncs
curl 'http://127.0.0.1:8080/api/history/telegram/messages?limit=50&query=telegram'
# For the next page, pass the returned nextCursor as &before=<messageId>.
```

POST returns 202 after channel resolution; importing runs in the background.
Status includes `IDLE`, `RUNNING`, `COMPLETED`, or `FAILED`, count, cursor and errors;
there is no invented completion percentage. Local text search normalizes Unicode
and case and matches literal substrings across the saved archive. Results use
server-side keyset pagination, maximum 100/API and 50/UI messages per page.
Stop the running backend before compiling Kotlin, then start exactly one instance.
