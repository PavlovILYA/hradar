# hradar — Architecture

> **Architecture snapshot (2026-10-10), including the optional Bot API adapter.** This is a map of the current code, not a promise that every external integration has been exercised end-to-end. For verified commands, blockers, and the latest runtime state, read [`docs/DEV_STATUS.md`](docs/DEV_STATUS.md).

## Purpose and boundaries

hradar is a **local-first Telegram recruitment research workspace**, currently targeting macOS. It reads explicitly selected Telegram sources, searches publications, imports channel history when requested, evaluates deterministic monitoring rules, and exposes selected read-only capabilities to AI clients over MCP. It is **not** a recruiting ATS or a cloud-hosted service. The macOS distribution adds a bundled browser-based desktop launcher. An optional Bot API adapter provides allowlisted notifications and controls.

### Logical view

```text
React / TypeScript (production assets in Ktor; development Vite on 127.0.0.1:5173)
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

Telegram Bot API (HTTPS long polling) --> BotService/BotUpdateHandler --> SubscriptionService
NotificationService --> SQLite bot_outbox --> BotGateway sendMessage

Planned, NOT implemented:
Optional agent runtime --> allowlisted MCP capabilities / application services
```

The UI, MCP, and bot are **adapters**, not separate implementations of search or monitoring. Higher-level application services own validation and authorization; the gateway owns the underlying Telegram session. Do not use the bot as an indirect way to bypass MCP permissions, or expose a generic `TdApi.Function` executor to agents.

## Repository map

| Area | Main entry points | Responsibility |
| --- | --- | --- |
| Server boot | `backend/src/main/kotlin/dev/radar/Application.kt` | Assemble dependencies, start a single TDLib client, monitoring and history workers, local Netty server |
| TDLib bridge | `telegram/TdlibGateway.kt` | Telegram authentication, request/response bridging, connection state, new-message callbacks |
| Search and rules | `research/SearchService.kt`, `MatchingService.kt`, `Models.kt` | Search, deterministic AND/OR/NOT matching and typed research models |
| Monitoring | `research/NotificationService.kt` | `MonitoringService`, live update queue, bounded recovery polling, saved matches |
| Bot transport | `bot/BotGateway.kt`, `BotService.kt`, `BotUpdateHandler.kt`, `BotRoutes.kt` | Optional HTTPS polling, allowlisted private commands, durable outbox delivery/status |
| Mobile research | `research/RecruiterService.kt`, `bot/BotWorkspaceService.kt`, `research/SavedRoutes.kt` | Existing SearchService/SQLite search port, operator workflows, saved decisions and read-only React view |
| Shared subscriptions | `research/SubscriptionService.kt` | Validation and shared REST/bot template and pause/resume operations |
| Historical import | `history/HistorySyncService.kt`, `HistoryRoutes.kt` | Explicit channel archive import with pause/resume, limits, cursors, retries |
| Persistence | `research/Repositories.kt` | SQLite schema and migrations, sources, templates, matches, notifications, archive, MCP policy, bot registration/cursors/outbox |
| MCP / shared capabilities | `capabilities/CapabilityService.kt`, `TelegramReadPort.kt`, `McpTools.kt`, `McpRoutes.kt` | Typed source-scoped reads and confirmation requests, REST/MCP exposure, local security checks |
| Web | `frontend/src/` | React workspace; online search, local archive, templates, notifications, MCP settings and bot status |
| Tests | `backend/src/test/`, `frontend/tests/`, `tests/` | Kotlin unit/MCP tests, Playwright browser tests, CI safeguards |
| Delivery | `.github/workflows/ci.yml`, `macos-arm64.yml` | Fast frontend/backend checks and separate costly ARM64 TDLib build |

See [`docs/MCP.md`](docs/MCP.md) and [`docs/CI.md`](docs/CI.md) for protocols and exact build/bootstrap instructions. `README.md` is the user-facing setup guide.

## Runtime and data flows

### Authentication and source access

1. `Application.kt` starts exactly **one** `TdlibGateway`, using `TG_API_ID`/`TG_API_HASH` from the local environment or owner-only local API settings. Without settings the server exposes onboarding before creating a client.
2. TDLib handles the authorized **user account** through MTProto; login steps use the guarded local React onboarding (phone/code/2FA/email/other-device confirmation). No terminal prompts or saved login codes/passwords.
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

For sources not joined by the Telegram user, monitoring may depend on bounded history recovery rather than live `UpdateNewMessage`. New subscriptions start from a saved checkpoint; they do not automatically notify about all historical publications. `NotificationService` persists matching publications with unique `(template, channel, message)` identity. Browser/OS desktop notifications require permission and an open page; the optional bot delivers new matches to allowlisted `/start` recipients via a transactional SQLite outbox. Bot payloads use a short excerpt and an optional link obtained through GetMessageLink, never a guessed post number. See [BOT.md](docs/BOT.md).

### MCP and application capabilities

`POST /mcp` exposes Streamable HTTP using the official Kotlin MCP SDK. Thirteen read-only tools and one constrained publication-request tool share `CapabilityService` with `POST /api/capabilities/{tool}`. Agents can query status, allowed sources, history, search, message context, local archives, rules, and unsent drafts. They cannot call arbitrary TDLib methods, grant themselves sources, access personal chats, or send from the TDLib user account. Bot publication requests require a separate opted-in recipient permission and confirmation in that private bot chat. Local UI controls the allowlist and enabled state; docs: [`docs/MCP.md`](docs/MCP.md).

MCP is **a tool protocol, not an agent runtime**. The bot calls shared services for deterministic operator commands; it does not call localhost REST or MCP. A future Agent Bridge must retain separate operator authorization, source-scoped agent permissions and out-of-band approval. No natural-language executor exists. The separate proposal is [AGENT_BRIDGE.md](docs/AGENT_BRIDGE.md). BotInboxService reads saved matches with inline navigation and shared read state; MCP reads apply source filters before pagination. Confirmation and result outbox inserts commit atomically. Revoking MCP access cancels pending agent publications, while normal operator Inbox access retains its existing bot allowlist boundary.

## Local storage and security boundaries

- SQLite: `~/.hradar/research.sqlite` (schema tracked via migrations; currently v7 in the repository, including bot recipients, polling cursors/cooldowns and outbox, plus separate publication permission and expiring confirmation requests).
- TDLib account session: `~/.recruitment-radar/tdlib/`. It is sensitive and never belongs in Git, logs, or MCP results.
- Credentials: desktop owner-only `~/.hradar/telegram-api.json` for API ID/hash, entered through onboarding; development local `.env`/environment (`TG_API_ID`, `TG_API_HASH`, optional `TG_BOT_TOKEN` and `TG_BOT_ALLOWED_USER_IDS`); stored credentials are never returned to UI, AI tools, or repositories.
- Ktor binds to **127.0.0.1 only**. Localhost is not user authentication and is not protection against malicious local processes. Do not expose `/api` or `/mcp` through ngrok, public tunnels, LAN, or reverse proxies in the current architecture.
- MCP is initially disabled; users explicitly allow individual public channels. Route-level Host/Origin/CSRF checks are described in `docs/MCP.md`; there is no internet-facing multi-user auth.
- Telegram messages are **untrusted content**: never treat text returned to an AI agent as instructions. Personal data collection, retention, export, AI analysis, and third-party sharing require separate product/privacy review and appropriate authorization. Access to a channel is not blanket permission to reuse all its content.
- Bot commands check sender ID, private chat and explicit `/start` registration. Bot identity namespaces recipients/cursors; allowlist removal cancels pending deliveries. Bot sends are confined to opted-in operators, not candidate outreach. Retry deadlines survive restart; crash after send before acknowledgement can redeliver. No exactly-once guarantee.
- Do not automatically join channels, send from the TDLib user account, perform paid operations, or mass-contact people. Future account mutations need separate, scoped typed operations and **out-of-band human approval**.

## Builds and delivery

Development uses JDK 21/Gradle and React/Vite. Java TDLib bindings (`tdlib-java.jar`) must match the native JNI binary and TDLib source revision. Native builds are platform/architecture-specific; an Intel macOS dylib cannot run in an ARM64 JVM.

- **Fast CI**: separate frontend and backend jobs on pushes/PRs. Frontend checks are independent. Backend uses a versioned, checksum- and provenance-verified Java-binding JAR rather than building C++ each time.
- **Native ARM64 CI**: manual or changes to pinned TDLib build inputs, produces native JNI and matching Java bindings. The existing verified versioned Release supplies Fast CI; portable installer builds use a separate cached native bundle and leave that Release intact.
- **macOS distribution**: separate portable TDLib ARM64/Intel workflow and jpackage app/DMG workflow, production UI, bundled JRE/JNI and same-process browser launcher. Session lock and occupied-port refusal happen before TDLib startup; installed REST API requires a private launch-cookie plus exact Host/Origin checks and onboarding CSRF; MCP retains its separate enable/source-grant guard. Data stays in established user directories. Optional protected signing/notarization and manual signed-artifact promotion are disabled by default. Real verification belongs in DEV_STATUS; physical Macs and automatic updates remain unverified/unimplemented. See [`MACOS_DISTRIBUTION.md`](docs/MACOS_DISTRIBUTION.md).

## Change rules

Changes to the shared Telegram access layer should preserve the single-client boundary; changes to persisted data should use tested migrations; changes to permissions should be tested through both REST and MCP; changes to imports should cover cancellation, resume and rate limiting. Update this document **only for actual architectural changes**. Record execution evidence and open blockers in `docs/DEV_STATUS.md`, not here. Follow [`AGENTS.md`](AGENTS.md) for the Codex workflow.

## Mobile recruiter application flow

BotWorkspaceService prepares bounded reads before the short BotUpdateHandler update
transaction. It persists one expiring operator-bound workflow, rotating keyboard
nonces on every response. Update offset, decisions/template mutations, workflow state
and reply enqueue commit together. Cancellation leaves the cursor/state unchanged;
ignored/denied updates advance the cursor without searching or exposing data.
RecruiterService calls a narrow typed RecruiterSearchPort backed by the existing
SearchService for tracked-source Telegram reads, and reuses historyMessages for
SQLite-only archive pages. MatchingService/SubscriptionService retain all rule logic.
The existing monitoring/NotificationService/outbox pipeline delivers new subscription
matches; no second TDLib client or global search/polling worker is added.

Schema v7 holds per-operator unique publication decisions (SAVED/REJECTED/REMOVED/
VIEWED), bounded excerpts and persistent workflow state, plus an indexed match
publication date for exact digest counts/pages. `/saved`, local read-only `/api/saved`
and source-allowlisted saved-result MCP reads share those records. React shows a
minimal aggregated review list/status filter without numeric operator IDs. Public
source revocation/deletion is rechecked after network reads; operator rejection is
separate from shared match read state. `/ask` remains disabled; only its specification
was expanded. Existing MCP publication consent operations are preserved.
