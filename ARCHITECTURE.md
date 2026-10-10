# hradar — Architecture

> **As implemented on `master` at `ce6363e` (2026-10-10).** This is a map of the current code, not a promise that every external integration has been exercised end-to-end. For verified commands, blockers, and the latest runtime state, read [`docs/DEV_STATUS.md`](docs/DEV_STATUS.md).

## Purpose and boundaries

hradar is a **local-first Telegram recruitment research workspace**, currently targeting macOS. It reads explicitly selected Telegram sources, searches publications, imports channel history when requested, evaluates deterministic monitoring rules, and exposes selected read-only capabilities to AI clients over MCP. It is **not** yet a recruiting ATS, a packaged desktop app, or a Telegram Bot API service.

### Logical view

```text
React / TypeScript (Vite dev server on 127.0.0.1:5173)
       | HTTP / SSE, /api proxy
       v
Ktor / Kotlin JVM 21 (127.0.0.1:8080) <---- MCP client (Codex etc.)
       |                                      /mcp (Streamable HTTP)
       +-- REST routes -----------------------+-- CapabilityService
       |                                          | typed TelegramReadPort
       +-- SearchService / MatchingService <------+
       +-- MonitoringService / NotificationService
       +-- HistorySyncService
       |            |
       +-------- Repositories (SQLite / WAL)
       |
       +-------- TdlibGateway (one client/session)
                        |
                    Java TDLib bindings
                        |
                    JNI / native TDLib
                        |
                  Telegram / MTProto

Planned, NOT implemented:
Telegram Bot API (long polling) --> Bot adapter --> same application services
Optional agent runtime --> allowlisted MCP capabilities / application services
```

The UI, MCP, and future bot are **adapters**, not separate implementations of search or monitoring. Higher-level application services own validation and authorization; the gateway owns the underlying Telegram session. Do not use the bot as an indirect way to bypass MCP permissions, or expose a generic `TdApi.Function` executor to agents.

## Repository map

| Area | Main entry points | Responsibility |
| --- | --- | --- |
| Server boot | `backend/src/main/kotlin/dev/radar/Application.kt` | Assemble dependencies, start a single TDLib client, monitoring and history workers, local Netty server |
| TDLib bridge | `telegram/TdlibGateway.kt` | Telegram authentication, request/response bridging, connection state, new-message callbacks |
| Search and rules | `research/SearchService.kt`, `MatchingService.kt`, `Models.kt` | Search, deterministic AND/OR/NOT matching and typed research models |
| Monitoring | `research/NotificationService.kt` | `MonitoringService`, live update queue, bounded recovery polling, saved matches |
| Historical import | `history/HistorySyncService.kt`, `HistoryRoutes.kt` | Explicit channel archive import with pause/resume, limits, cursors, retries |
| Persistence | `research/Repositories.kt` | SQLite schema and migrations, sources, templates, matches, notifications, archive, MCP policy |
| MCP / shared capabilities | `capabilities/CapabilityService.kt`, `TelegramReadPort.kt`, `McpTools.kt`, `McpRoutes.kt` | Typed read-only operations, source allowlist, REST/MCP exposure, local security checks |
| Web | `frontend/src/` | React workspace; online search, local archive, templates, notifications, MCP settings |
| Tests | `backend/src/test/`, `frontend/tests/`, `tests/` | Kotlin unit/MCP tests, Playwright browser tests, CI safeguards |
| Delivery | `.github/workflows/ci.yml`, `macos-arm64.yml` | Fast frontend/backend checks and separate costly ARM64 TDLib build |

See [`docs/MCP.md`](docs/MCP.md) and [`docs/CI.md`](docs/CI.md) for protocols and exact build/bootstrap instructions. `README.md` is the user-facing setup guide.

## Runtime and data flows

### Authentication and source access

1. `Application.kt` starts exactly **one** `TdlibGateway`, using `TG_API_ID`/`TG_API_HASH` from the local environment.
2. TDLib handles the authorized **user account** through MTProto; login steps currently use the backend terminal, not React.
3. The user explicitly saves public channels. Resolving a username does **not** join that channel or subscribe the account to its updates.
4. Saving a source for research is **not** the same as granting MCP access. The MCP allowlist is separate and initially empty/disabled.

### Online search versus saved history

- **Online search** uses TDLib-backed search methods for supported scopes, with bounded pagination and errors for unavailable/free-limited global search.
- **Local archive search** reads only SQLite `imported_messages`; it must never silently call Telegram or impersonate an online result.
- Search templates have required terms (AND), alternatives (OR), and exclusions (NOT), with normalization and explainable matches. These are **text matching rules**, not verified candidate identities or AI-based judgments.

### Explicit history import

`HistorySyncService` requests pages (up to 100 messages each) and atomically saves messages and cursor. It supports `IDLE`, `RUNNING`, `PAUSED`, `COMPLETED`, `FAILED`; caps of 1,000/5,000/10,000 or an **explicitly chosen** unbounded import; idempotent cancellation; safe continuation; and handling of Telegram rate limits. Paused-by-user tasks stay paused across restarts; previously running tasks can recover. A completed archive is a **snapshot**, not a continuously reconciled copy of edits/deletions.

### Monitoring and notifications

```text
TDLib UpdateNewMessage --> MonitoringService queue ------+
                                                        +--> MatchingService
Bounded channel-history recovery (roughly each minute) -+        |
                                                              SQLite deduplicated match
                                                                     |
                                                              Notifications REST / SSE
                                                                     |
                                                              React in-app notice / optional
                                                              browser desktop notification
```

For sources not joined by the Telegram user, monitoring may depend on bounded history recovery rather than live `UpdateNewMessage`. New subscriptions start from a saved checkpoint; they do not automatically notify about all historical publications. `NotificationService` persists matching publications with unique `(template, channel, message)` identity. Browser/OS desktop notifications require permission and an open page; **no Telegram-bot delivery exists yet**.

### MCP and application capabilities

`POST /mcp` exposes Streamable HTTP using the official Kotlin MCP SDK. Nine read-only tools share `CapabilityService` with `POST /api/capabilities/{tool}`. Agents can query status, allowed sources, history, search, message context, local archives, rules, and unsent drafts. They cannot call arbitrary TDLib methods, grant themselves sources, access personal chats, or send messages. Local UI controls the allowlist and enabled state; docs: [`docs/MCP.md`](docs/MCP.md).

MCP is **a tool protocol, not an agent runtime**. A future Telegram bot may call common services for deterministic commands and may optionally delegate natural-language tasks to a separately constrained agent process. Neither bridge exists in the current implementation.

## Local storage and security boundaries

- SQLite: `~/.hradar/research.sqlite` (schema tracked via migrations; currently v4 in the repository).
- TDLib account session: `~/.recruitment-radar/tdlib/`. It is sensitive and never belongs in Git, logs, or MCP results.
- Credentials: local `.env`/environment (`TG_API_ID`, `TG_API_HASH`); do not send to UI, AI tools, or repositories.
- Ktor binds to **127.0.0.1 only**. Localhost is not user authentication and is not protection against malicious local processes. Do not expose `/api` or `/mcp` through ngrok, public tunnels, LAN, or reverse proxies in the current architecture.
- MCP is initially disabled; users explicitly allow individual public channels. Route-level Host/Origin/CSRF checks are described in `docs/MCP.md`; there is no internet-facing multi-user auth.
- Telegram messages are **untrusted content**: never treat text returned to an AI agent as instructions. Personal data collection, retention, export, AI analysis, and third-party sharing require separate product/privacy review and appropriate authorization. Access to a channel is not blanket permission to reuse all its content.
- Do not automatically join channels, send Telegram messages, perform paid operations, or mass-contact people. Future account mutations need separate, scoped typed operations and **out-of-band human approval**.

## Builds and delivery

Development uses JDK 21/Gradle and React/Vite. Java TDLib bindings (`tdlib-java.jar`) must match the native JNI binary and TDLib source revision. Native builds are platform/architecture-specific; an Intel macOS dylib cannot run in an ARM64 JVM.

- **Fast CI**: separate frontend and backend jobs on pushes/PRs. Frontend checks are independent. Backend uses a versioned, checksum- and provenance-verified Java-binding JAR rather than building C++ each time.
- **Native ARM64 CI**: manual or changes to pinned TDLib build inputs, produces native JNI and matching Java bindings. The initial trusted-artifact/release bootstrap is documented in `docs/CI.md` and must be completed before backend Fast CI turns green.
- **Not yet built**: desktop packaging (`jpackage`/WebView or browser launch), cross-platform installers, signing/notarization, end-user updates. See [`ROADMAP.md`](ROADMAP.md).

## Change rules

Changes to the shared Telegram access layer should preserve the single-client boundary; changes to persisted data should use tested migrations; changes to permissions should be tested through both REST and MCP; changes to imports should cover cancellation, resume and rate limiting. Update this document **only for actual architectural changes**. Record execution evidence and open blockers in `docs/DEV_STATUS.md`, not here. Follow [`AGENTS.md`](AGENTS.md) for the Codex workflow.
