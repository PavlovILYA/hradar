# hradar — Development status / session handoff

## Mobile recruiter checkpoint — 2026-10-11

This checkpoint supersedes runtime and task status below. Branch `feat/bot-workspace`;
previous Inbox work preserved in `dfa8aa0` and `b5fbcdd`. Feature push authorized;
no master merge, CI configuration or TDLib build-script changes.

VERIFIED:
- `/find` reuses SearchService/TDLib for saved public sources and existing SQLite
  archive reads. Explicit mode/source selection, ten results per page, five-page
  budget, navigation, original links, per-operator save/reject and watch creation.
  No claim of global Telegram coverage. Archive query is a literal phrase.
- `/watch` persists the dialog, previews and confirms a genuine SearchTemplate via
  SubscriptionService. React refreshes shared templates on opening its tab and with
  “Обновить подписки”. `/saved` and React “Избранное” expose durable decisions.
- SQLite v7 preserves archive/templates/recipients, adds unique operator/publication
  decisions, expiring bot workspace and match publication dates. Rejects suppress
  untreated results and future notification of that publication for that operator.
  Digest uses actual matches for today/rolling seven days; read state remains shared.
- Read-only MCP `list_saved_results`/`get_saved_result` enforce the existing source
  grants. There are 14 tools including the preserved separately consented publication
  tool. `/ask` remains unconnected; isolated-runtime specification in AGENT_BRIDGE.md.
- Backend build exit 0: 75 tests, zero failures/errors. Built an isolated copy with
  identical backend sources and existing matching TDLib JAR, without credentials,
  native session or a second backend. Tests use fake BotGateway/Telegram and temporary
  SQLite, covering migrations, bounded pagination, duplicate save/events, rejection,
  stale callbacks, cancellation/restart, denied access and transaction rollback.
- React production build exit 0. Offline Playwright: 16 passed. Temporary config used
  an owned frontend at 127.0.0.1:5174 with mocked APIs; removed after verification.
- Stopped old backend/frontend before deployment, backed up SQLite privately at
  `~/.hradar/research.pre-mobile-v7-*.sqlite`, then started this checkout. Exactly one
  backend listens at 127.0.0.1:8080 (PID 54482); frontend at 127.0.0.1:5173.
  Health 200, TDLib authorized/Ready, Bot API running with successful polling.
  Actual v6→v7 migration preserved 8942 archive messages, one template and one bot
  recipient. Real bounded `java` search in one saved public source returned five
  Telegram results with next cursor; archive returned five of 1681 matches.
  Live saved endpoint and browser view respond correctly with empty saved state.

NOT VERIFIED:
- No real phone command/callback or outgoing test message was initiated. Complete
  phone workflow, newly created real subscription, fresh matching publication delivery
  and period digest navigation require operator verification. Current match count is
  zero; fake-port notification evidence does not establish real phone delivery.
- No Codex/AI runtime is executed from Telegram. Agent Bridge remains a specification.

NEXT ACTION:
- In the registered private bot chat send `/find java`, choose Telegram and a saved
  public source, navigate and save a card; check `/saved` and React “Избранное”.
- Use `/watch`, complete the fields/source selection and confirm the preview. Open
  React “Шаблоны” or press “Обновить подписки”; verify the same active subscription.
- Personally publish a new matching post in an authorized monitored test source;
  verify the existing notification arrives, then `/digest` today/seven days and open
  the match. Use `/help` and docs/BOT.md for cancellation and limits.
- For rollback stop the backend and restore the stopped-database backup before
  running an older binary; do not downgrade v7 in place or run a second TDLib client.

## Runtime follow-up — 2026-10-11

VERIFIED: User reported new commands unavailable. Listener PID 68501 was still the
old adjacent `hradar` checkout; its live MCP listing had nine tools. Stopped that
backend with SIGTERM, verified port release, created a private-permission SQLite
backup (`~/.hradar/research.pre-inbox-*.sqlite`) and started exactly one backend from
`feat/bot-workspace` at `dfa8aa0` (listener PID 50722). Existing ignored local config
and compatible JNI were reused; no native build or tracked scripts were changed.
`/health`=200; live MCP lists 12 tools including the three new operations; Bot API
state=running, no notice, successful poll recorded; TDLib authorization/connection
Ready. Production migration to v6 preserved 8,942 archive messages, one template,
one bot recipient and the prior match/source counts. There are currently zero saved
matches and zero MCP source grants; empty Inbox is expected, not a search failure.

NOT VERIFIED: Phone display and new command/callback responses still require the
operator to send commands. No ad-hoc outgoing message or competing getUpdates was
issued during diagnosis. Earlier statements below that this branch was not deployed
refer to the preceding checkpoint and are superseded by this runtime follow-up.

NEXT ACTION: In the existing registered private bot chat, resend `/help`, `/sources`,
`/matches`. To populate Inbox, enable monitoring and personally publish a fresh
matching post in an authorized test source. MCP match reads additionally need explicit
source grants. No push or merge.


Current checkpoint: 2026-10-11, Telegram Inbox and safe MCP publication, branch
`feat/bot-workspace`, based on `ae7b322`. No push, merge, CI/script changes or backend
restart. Previous checkpoint evidence is retained below as historical context.

## VERIFIED — current task
- `/matches`, `/sources`, `/digest`, `/help`, inline saved-publication navigation,
  read marking shared with React, and verified original-link buttons implemented.
  Normal navigation edits the current bot card through the existing durable outbox;
  proposal review opens separate cards to preserve confirmation controls.
- MCP has 12 registered operations: eleven reads, including `list_recent_matches`
  and `get_match_details`, plus non-read-only `bot_publish_matches`. Reads filter
  saved matches by the existing source allowlist before pagination; no Telegram
  call is needed. Structured match text is clipped to 2000 characters.
- Publication accepts only 1–10 existing allowed match IDs for a registered operator.
  Separate `/agent_publish on` and per-request private-chat confirmation required.
  Ten-minute expiry, recipient binding, three-request budget, transactional outbox
  creation, replay protection and restart persistence verified with fake ports.
  Permission/source revocation cancels pending publications. Delivery rechecks before
  optional original-link lookup and immediately before Bot API send.
- SQLite v6 migration adds publication consent/request tables and expiry index.
  v1/v4/v5 migration fixtures preserve existing research and bot queue/cursor records.
  Confirmation/outbox rollback tested with a rejecting SQLite trigger. Recovery and
  older-binary rollback require a stopped-database backup, documented in BOT.md.
- Final `./gradlew :backend:build --console=plain`: exit 0, **62 tests, 0 failures,
  0 errors**, JDK 21 toolchain. Existing TDLib Java JAR reused from adjacent checkout;
  no JNI/native build. Tests use isolated temporary databases and fake Telegram ports.
- `npm run build --prefix frontend`: exit 0. Offline Playwright: **14 passed** via
  `npm run test:ci --prefix frontend -- --config playwright.inbox.local.config.ts`.
  Temporary config used an owned frontend on 127.0.0.1:5174 to avoid the running
  checkout's UI; all test APIs mocked, no second backend. Config removed afterwards.
- Agent Bridge proposal in `docs/AGENT_BRIDGE.md` defines consent, source scope,
  isolated bounded worker, cancellation/restart and review stages. No model/CLI/shell
  execution is connected to Telegram.

## NOT VERIFIED — current task
- Real bot command/edit/phone delivery and MCP publication confirmation were not
  exercised against Telegram. Fake API success does not prove phone display.
- Existing backend PID 68501 was identified in the adjacent `/Users/pavlov/Projects/hradar`
  checkout and left running. This branch has not been deployed; production SQLite
  was not migrated. No messages were sent to real recipients.
- Bot API has no exactly-once send guarantee; a send already in flight cannot be
  recalled by revocation. Deleted/uneditable bot cards can produce a permanent edit
  failure; reopening `/matches` provides a fresh card.

## NEXT ACTION — current task
Stop the one existing backend; back up its SQLite database; deploy this branch with
compatible existing TDLib artifacts and start exactly one backend. Follow the manual
Inbox, original-link, consent/cancel/confirm/revoke and phone checks in `docs/BOT.md`.
Do not run a competing getUpdates consumer. Push requires separate user approval.

---

## Previous checkpoint — 2026-10-10

Verified 2026-10-10. Milestone: optional Telegram Bot API notifications and controls.
Existing documentation changes preserved separately in checkpoint `b3440d0`.
Previous CI fix: `ce6363e`; CI files, TDLib build scripts and config/tdlib.json were not changed. No git push.

## VERIFIED
- Independent HTTPS BotGateway / BotService / BotUpdateHandler use getUpdates long polling and shared application services. One TDLib user client remains read-only. No webhook, public endpoint, LLM/Codex execution or MCP bot-send tools.
- TG_BOT_TOKEN is optional. Missing/invalid allowlist blocks all bot processing; token is never serialized/logged. Every message/callback checks numeric sender ID, private-chat type, chat/user identity and bot sender flag. /start is explicit opt-in. /status, /subscriptions, /pause and /resume use bounded inline menus and the same SubscriptionService as REST; all operators control shared subscriptions.
- SQLite v5 migration adds bot recipients, bot-specific cursor/cooldowns and outbox. Match, existing in-app notification and opted-in delivery rows commit atomically through the existing NotificationService/repository pipeline. Unique event/recipient identity prevents duplicate creation; old matches are not backfilled and pre-registration publications are skipped. Command action/reply/update cursor commit together; replays cannot reapply actions.
- Durable retries handle network failures with backoff and global 429/retry_after cooldown (including callback errors). Permanent 400/403 sends become failed; 401 stops requests until restart. Delivery attempts are spaced by at least 1.1 seconds. Cancellation leaves pending events intact. Removing allowed users cancels pending deliveries; bot identities are isolated.
- Optional original link is obtained through GetMessageLink with a bounded lookup and validated Telegram HTTPS URL. TDLib IDs are never converted to guessed post numbers. Payloads contain only a short excerpt; sent/cancelled payloads are cleared while deduplication keys remain.
- React “Интеграции → Telegram Bot” shows safe status, registration count, pending/sent/failed messages and retries; refresh and periodic updates work. /api/integrations/bot contains no token, user IDs or message content. .env.example, README, ARCHITECTURE, ROADMAP and docs/BOT.md describe setup and real verification.
- Backend build passed: 56 Kotlin tests, zero failures/errors (16 bot tests plus all existing 40, including MCP). Fake Bot API tests cover opt-in/denial, no historical backfill, match dedup, transactional rollback, network/429/callback cooldowns, restart, shared pause/resume, cancellation, permanent failures, migration preservation, safe response decoding and disabled status REST. Tests need no bot token, internet or authorized TDLib account.
- Frontend TypeScript/production build passed; all 14 offline Playwright tests passed, including bot status/queue/error/refresh and existing UI/MCP regressions. Six live-Telegram frontend scenarios were not rerun.
- Single local backend restarted successfully without bot configuration. Real /health, /api/integrations/bot (disabled), /api/telegram/me and latest-N @telegram history returned 200; authorization/connection Ready and 10 real messages. A real browser displayed bot status from the actual backend.
- Local v4 → v5 migration retained 8,942 archive messages and existing template, notification and MCP source counts. A private-permission local pre-migration backup was created outside Git. TDLib/session/native artifacts were reused; no native rebuild or local-data deletion.

## NOT VERIFIED / limitations
- Follow-up runtime restart: previous backend PID 61565 stopped with SIGTERM, port released, ./scripts/run-local.sh relaunched detached with current .env. Current listener PID 68501; /health=200 and TDLib authorization/connection Ready. Real Bot API identity/getUpdates succeeded: state=running, no notice, successful poll timestamp, zero registered recipients. No token or secrets printed.
- Real sendMessage and notification arrival/read on a phone are still NOT verified; no outgoing test message was sent. Fake API success and Bot API acceptance do not prove phone display.
- Bot API cannot guarantee exactly-once delivery: crash/cancellation between Telegram acceptance and SQLite acknowledgement can redeliver. Permanent errors may prevent delivery; failed rows are not automatically resent by /start. Queue counts include command replies.
- Backend/Mac must remain running/online for delivery; browser may be closed. Existing bounded monitoring recovery is reused, not a global/public-message stream. Pausing a rule stops future matching, not already queued events.
- All allowlisted operators can see/control all existing monitored subscriptions. This is separate from agent/MCP source permissions; no Agent Bridge or unrestricted bot sender exists. Credentials/allowlist changes require local configuration and restart.
- v5 is forward-only; an older binary requires restoration of the pre-upgrade backup. Existing history import, global-search, browser-notification and MCP limitations remain; see their documentation.
- Native ARM64 CI and trusted-release bootstrap remain separate pending work. Previous native run failed before compilation on unavailable CMake 3.31.8; the 3.31.10 fix was committed separately. No new CI execution or publication was performed here; real artifact checksums remain unfilled.

## Run / checks
Backend: `./scripts/run-local.sh`; frontend: `npm run dev --prefix frontend`.
Checks: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :backend:build`; `npm run build --prefix frontend`; `npm run test:ci --prefix frontend`.
Stop the backend before rebuilding/restarting; never run two clients against the same session or two pollers for the bot.
Bot setup and manual phone check: [BOT.md](BOT.md). Existing MCP: [MCP.md](MCP.md). Separate CI bootstrap: [CI.md](CI.md).

## Single next action
Bot API is now running with current local configuration. Send /start from an allowlisted private account, then personally publish a fresh matching test post in an authorized test source and verify delivery on your phone using docs/BOT.md.
