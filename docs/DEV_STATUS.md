# hradar — Development status / session handoff

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
