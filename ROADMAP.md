# hradar — Product and engineering roadmap

> **Planning snapshot: 2026-10-10.** This is prioritization, not a release commitment. `[x]` means implemented in the repository, **not necessarily verified end-to-end on a live account**. Actual test evidence and blockers belong in [`docs/DEV_STATUS.md`](docs/DEV_STATUS.md). Architecture: [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Product goal

Build a local-first recruiting workspace centered on **explicitly permitted Telegram sources**, with searchable publications, deterministic monitoring, actionable saved results and, later, a bot/mobile control surface and carefully permissioned AI tools. The primary value is **relevant, reviewable recruitment leads**, not the volume of scraped messages.

## Already implemented — maintain and verify

- [x] Kotlin 21 / Ktor backend with a single authorized TDLib user session; React/TypeScript research UI.
- [x] Online Telegram channel search and SQLite-only archive search with separate UI modes.
- [x] Rule templates (AND/OR/NOT), selected sources, persisted subscriptions, monitored matches and in-app/browser notifications.
- [x] Controlled full-history import: limits, pause/resume, cursor persistence, deduplication and rate-limit handling.
- [x] Read-only, source-allowlisted MCP server with nine tools and corresponding typed REST capabilities.
- [x] Optional Bot API notifications and `/start`, `/status`, `/subscriptions`, `/pause`, `/resume` operator controls; fake-API verified, real phone delivery still requires manual verification.
- [x] Split GitHub workflows for fast CI and native macOS ARM64 TDLib/JNI builds.

**Verification gaps:** real fresh-message → matching → OS/bot phone notification delivery; operational limits of channel-history recovery; published, trusted ARM64 artifacts and end-to-end Fast backend CI. See the latest CI runs rather than treating this checklist as a green-build report.

## Now — P0 reliability and first usable distribution

| Deliverable | Acceptance criteria |
| --- | --- |
| **Complete CI bootstrap** | GitHub-hosted ARM64 native workflow succeeds; validated JNI/JAR artifacts, checksums and provenance are published under a versioned release; `config/tdlib.json` pins verified digest/commit; both Fast CI jobs pass without Telegram credentials. |
| **End-to-end monitoring check** | A fresh publication in a user-controlled/authorized test source creates exactly one persisted match; UI reflects it after reconnect/restart; limits and failure handling are observable. |
| **Operational safeguards** | Clear states for rate limiting and offline Telegram; reasonable request budgets; regressions for cancellation, reconnect and deduplication; no unnecessary re-fetch of whole archives. |
| **Documentation consistency** | `README.md`, `ARCHITECTURE.md`, `ROADMAP.md`, `AGENTS.md`, `docs/CI.md`, `docs/MCP.md` describe the actual state, with `DEV_STATUS.md` holding current proof. |

## Next — P1 recruiting workflows

1. **Vacancy/project workspace:** one hiring project per vacancy, role criteria, approved sources, saved searches and review queue. Keep it lightweight rather than building an entire ATS at once.
2. **Publication classification:** distinguish *candidate seeking work* from *employer posting a vacancy*, irrelevant ads, and unknowns. Start with explainable deterministic rules and explicit uncertainty; never invent skills/experience.
3. **Reviewable candidate records:** create a candidate card **only after manual review**, attaching source links, evidence snippets, notes and processing state. Define deletion, retention and data-minimization rules before persisting personal profiles.
4. **Matching quality:** aliases/synonyms, negative criteria, per-project exclusions and useful test fixtures. Evaluate relevance and false positives with the recruiter before adding an AI model.

**Done when:** a recruiter can configure one project, triage a manageable set of matching publications, and trace every saved assertion to an authorized source.

## Next — P2 Telegram bot: notifications and controls

**First stage implemented:** separate **Bot API** adapter alongside TDLib, with `getUpdates` long polling, durable SQLite outbox and explicit allowlisted private-chat opt-in. Manual real-bot verification is still pending; see [BOT.md](docs/BOT.md).

- Deliver rule-match notifications to an **opted-in, allowlisted Telegram user ID** in a private bot chat, with publication link/short excerpt and deduplication.
- Implemented `/start`, `/status`, `/subscriptions`, `/pause`, `/resume` and paginated inline controls through shared SubscriptionService. `/sources` and `/matches` remain future work.
- Use durable delivery queue/retry/backoff; never leak unapproved private conversations or secrets through bot messages.
- Enforce operator identity; bot tokens are separate from the TDLib user session. If the Mac is asleep/offline, there is **no live delivery** until it comes back.

**Done when:** turning on an allowed subscription in React produces one deduplicated outbox event and a delivered bot notification for a new matching test message (delivery can repeat across a crash), and a button changes the same state visible in React without creating a second TDLib session.

## Later — P3 AI-enabled workflows

- Enhance MCP with high-level **recruiting** use cases (e.g. search project matches, list review queue, prepare candidate notes), scoped to approved sources and existing records.
- Add `bot_send_notification` or `bot_publish_results` as constrained application operations, **not** a generic arbitrary-chat sender.
- Explore a separately bounded agent runtime for free-text bot requests; MCP exposes tools but does not start or execute an AI model itself.
- Require explicit, out-of-band approval for user-account writes, candidate outreach, new data sources and privileged agent actions. No default autonomous personal-chat access or shell execution.
- Before using external AI on Telegram content, review platform terms, privacy basis/consent, data retention and what is transmitted to a model provider.

## Later — P4 desktop releases and platform support

1. First a **macOS ARM64 internal beta**: bundle React assets, Kotlin backend, compatible TDLib/JNI and Java runtime; move Telegram onboarding out of terminal; implement clean lifecycle and local data migration.
2. Then macOS Intel; use versioned build artifacts, smoke-tested installers and GitHub Releases with checksums.
3. Evaluate Windows x64 and Linux x64 with platform-specific native TDLib builds, installation and signing requirements.
4. Only after installation/recovery is dependable: signing/notarization, updates and public download links.

`jpackage` can bundle the JVM/launcher, but by itself does not create a native window for React. Choose system-browser launch versus a WebView intentionally.

## Explicitly out of scope for now

- Multiple Telegram user accounts, cloud sync, team access control and 24/7 cloud hosting.
- Broad scraping of private communities, contact enrichment, data resale or automatic mass messaging.
- Unrestricted MCP access to all TDLib functions, hidden agent actions or remote shell control.
- Building a paid AI ranking system before testing recruiter usefulness and privacy requirements.

## How to choose the next task

Prefer the **smallest vertical slice** with an observable user outcome. For each milestone: write acceptance criteria, implement behind shared domain services, test with fakes first, perform a limited real integration check if authorized, and record what was actually verified. Split unrelated features into separate commits/PRs. New ideas go here only after review, not automatically into the next Codex prompt.
