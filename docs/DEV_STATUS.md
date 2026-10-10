# hradar — Development status / session handoff

## CI bootstrap checkpoint — 2026-10-11

Worktree `/Users/pavlov/Projects/hradar-ci`, branch `ci/tdlib-bootstrap` only.

- **VERIFIED:** GitHub native run [38045478478](https://github.com/PavlovILYA/hradar/actions/runs/38045478478)
  succeeded at `ce6363e9da5d1d7bf2069241f8738ad4303b18ce` on master, including
  ARM64/JNI checks, Kotlin tests, provenance creation and artifact upload.
  Artifact `11667860623` is available until 2027-01-08. Latest Fast run
  [38050957209](https://github.com/PavlovILYA/hradar/actions/runs/38050957209)
  passed frontend; backend log shows bootstrap guard exit 1 before Kotlin build.
  Downloaded artifact via authenticated CLI. Both SHA256 values match SHA256SUMS/metadata;
  both `gh attestation verify` commands exited 0 with exact repository, signer,
  master ref, build commit and GitHub-hosted runner requirements. Certificates
  identify that exact run. JAR classes/source identity, ARM64 `lipo`, and pinned
  upstream schema/original/patched CMake hashes passed independent checks.
  `otool -L` confirms external JDK/Homebrew OpenSSL dependencies. Verified hashes
  and build commit are now pinned in config/tdlib.json (see docs/CI.md).
  Seven Python safeguard tests passed again. JDK 21 `./gradlew :backend:build
  --no-daemon --console=plain` exited 0 with the downloaded verified JAR, no JNI,
  no Telegram credentials/session: 56 tests, zero failures/errors/skips.
  Fast CI supports bootstrap branch
  pushes and manual dispatch; no native workflow or product code changed.
  Commit `4b8272d` was pushed with explicit user authorization. Its Fast run
  [38093744547](https://github.com/PavlovILYA/hradar/actions/runs/38093744547)
  passed frontend and stopped backend at the unpinned bootstrap guard.
  With explicit user approval, the [first TDLib Release](https://github.com/PavlovILYA/hradar/releases/tag/tdlib-bc9c263e2bfee06aaab41e82db51a103376030bc-v1)
  is published with all four original assets. Uploaded asset digests match all
  originals, and the tag resolves to the exact verified native build commit.
  Local `fetch-tdlib-java.py` exited 0 using the permanent Release URL and
  installed the SHA256/provenance/class-verified JAR without JNI.
  Pinning commit `20d646d` is pushed to ci/tdlib-bootstrap. Its
  [Fast CI 38095719750, attempt 2](https://github.com/PavlovILYA/hradar/actions/runs/38095719750/attempts/2)
  concluded success: frontend production build and 14 offline Playwright tests;
  backend verified Release fetch, seven Python safeguard tests, Kotlin build and
  account-free unit tests (`BUILD SUCCESSFUL in 1m 15s`). No check was bypassed.
- **NOT VERIFIED:** master Fast CI with these pins (no merge performed).
  Intel local checks do not replace the GitHub ARM64 JNI smoke test; ARM64 JNI
  was not loaded locally. No TDLib rebuild, backend startup, master modification
  or merge occurred. Application processes in other worktrees were left untouched.
- **NEXT ACTION:** maintainer review of [PR #1](https://github.com/PavlovILYA/hradar/pull/1)
  from ci/tdlib-bootstrap to master. Bootstrap is verified on the feature branch;
  merge remains a separate maintainer action, outside agent authorization.

The product runtime handoff below is preserved; its previous native-CI blocker
is superseded by the evidence above.

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
