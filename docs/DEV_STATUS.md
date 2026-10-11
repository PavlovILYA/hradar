# AI researcher checkpoint — 2026-10-11

Branch `feat/ai-research`, based on master `077c974`. This task does not merge CRM,
change its protected repositories/routes/UI, alter CI/native scripts, restart the
existing backend or start another TDLib session.

VERIFIED:
- AgentService background worker, typed AgentPort and production OpenAI Responses
  adapter with a fixed HTTPS endpoint, strict read-only tool schemas/structured
  result, store=false, encrypted reasoning replay, cancellation and sanitized errors.
  Explicit model/positive tariff configuration is required; no default model invented.
- Separate AgentJobRepository/migration v1 in `~/.hradar/agent-jobs.sqlite`; one active
  job per operator, bounded queue/time/tools/token/cost budgets, event deduplication,
  retention and restart interruption without automatic paid retries (including 429).
  Each job retains its model/limits. No external read or AI call in a DB transaction.
- `/ask`, `/ask_status`, `/ask_cancel`, `/ask_history`, `/ask_consent`, `/ask_access`.
  Immediate acknowledgement, later summary/source cards through existing Bot outbox,
  operator binding and permission checks immediately before send. Cross-DB enqueue
  recovery uses unique job/card keys; COMPLETED is not a phone-delivery receipt.
- Independent per-operator opt-in with a selected subset of public MCP grants.
  Every read rechecks current grants/snapshot; a watcher cancels revoked running jobs.
  Fixed tools expose only bounded archive/online-source/context reads. References are
  assigned to actual returned publications, original links resolved/validated without
  guessed TDLib post numbers, model links removed. No shell/files/arbitrary HTTP,
  imports, grants, subscription mutation or candidate sends available to the model.
- React AI section: own history/status/result/links/cancel, model/budget/source display,
  explicit consent/revocation, source selection, operator pairing and mobile layout.
  Bot histories need a rotated private bearer code; only its hash is persisted and
  React keeps the code in memory. Host/Origin/CSRF/no-store guards cover the local API.
- `./gradlew :backend:build --console=plain`, JDK 21: exit 0, 92 tests, zero
  failures/errors/skips (17 new AI tests). Matching Java JAR reused as an ignored local
  dependency; no JNI loaded. Tests use temporary databases and fake providers/ports.
  Includes Bot → AgentService → fake LLM → tools → Bot outbox; denied consent/sources,
  injection/tool escalation/invalid references, 429/errors, concurrency/queue limits,
  duplicate updates, cancellation/timeouts/revocation, recovery, pagination and REST
  security. Production request/response shapes checked with account-free fixtures.
- `npm run build --prefix frontend`: exit 0. `npm run test:ci --prefix frontend --
  --config playwright.ai.local.config.ts`: 18 passed, exit 0, including two AI tests.
  Temporary config used an owned frontend at 127.0.0.1:5174; all APIs mocked. Removed
  after verification. Desktop/mobile screenshots inspected; filter drawer no longer
  overlays the AI form when opening the section. Existing runtime remained untouched.
- Official OpenAI function-calling, reasoning replay and data-control documentation
  checked; setup, retention/budgets, rollback and manual acceptance in AGENT_BRIDGE.md.

NOT VERIFIED:
- Real provider/account model compatibility, actual usage/billing, semantic recruitment
  quality, genuine Telegram links/phone command/result delivery. No real AI requests,
  real-recipient test messages or secret changes occurred. store=false does not imply
  zero service-side retention; consent text links the provider's actual data controls.
- GitHub PR/Actions at this checkpoint: pending feature push/PR creation.

NEXT ACTION:
- Push only feat/ai-research, create PR to master, inspect Fast CI to completion. No merge.
- For live use: stop/back up the one backend and both DBs, deploy this feature once,
  configure OPENAI_API_KEY/actual model/positive tariffs locally, authorize synthetic
  public sources in MCP, /start → /ask_consent → /ask_access → pair/select/consent in
  React → /ask question. Check phone/web citations, cancellation and restart manually.
  A paid provider check with source data needs separate authorization; none was run.

---

# hradar — Development status / session handoff

## Feature/master integration — 2026-10-11

VERIFIED: Clean feature worktree before `git fetch origin`; merged `origin/master`
(`44828cf`, maintainer merge of CI PR #1) into `feat/bot-workspace` only. The sole
conflict was this handoff; both CI bootstrap and current mobile Bot/MCP reports are
preserved below. Historical deployment, missing-pin and approval statements refer
to their original checkpoints. Imported workflow/config/CI documentation match
master exactly; no TDLib build scripts were edited.

Local checks: `git diff --check` and staged check pass; no unmerged files or conflict
markers. `./gradlew -p .tdlib-build/merge-check :backend:build --console=plain` exited
0: 75 tests, zero failures/errors/skips. Source copy stays inside this worktree,
uses the existing Java JAR and excludes credentials, JNI and sessions; it avoids
recompiling classes used by the running backend. React production build exited 0;
offline Playwright passed all 16 tests using an owned frontend on port 5174 and
mocked APIs. Temporary Playwright config removed. Existing backend was not restarted;
no second backend, TDLib rebuild, user SQLite/session changes or merge into master.

NOT VERIFIED at commit time: PR #2 Actions for this merge commit; pending push.
Phone verification gaps from the mobile checkpoint remain unchanged.

NEXT ACTION: Push only the feature branch, verify PR #2 is mergeable and inspect
its Fast CI frontend/backend jobs to completion. No PR merge is authorized.

## CI bootstrap checkpoint — 2026-10-11

Worktree `/Users/pavlov/Projects/hradar-ci`, branch `ci/tdlib-bootstrap` only.

- **VERIFIED:** GitHub native run [38045478478](https://github.com/PavlovILYA/hradar/actions/runs/38045478478)
  succeeded at `ce6363e9da5d1d7bf2069241f8738ad4303b18ce` on master, including
  ARM64/JNI checks, Kotlin tests, provenance creation and artifact upload.
  Artifact `11667860623` is available until 2027-01-08. Earlier Fast run
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
- **NOT VERIFIED at the CI checkpoint:** master Fast CI with these pins (no merge performed in that task).
  Intel local checks do not replace the GitHub ARM64 JNI smoke test; ARM64 JNI
  was not loaded locally. No TDLib rebuild, backend startup, master modification
  or merge occurred. Application processes in other worktrees were left untouched.
- **NEXT ACTION at that checkpoint:** maintainer review of [PR #1](https://github.com/PavlovILYA/hradar/pull/1)
  from ci/tdlib-bootstrap to master. Bootstrap is verified on the feature branch;
  merge remains a separate maintainer action, outside agent authorization.

PR #1 has since been merged by the maintainer into master (`44828cf`). Its changes
are being integrated into the Bot feature branch; no merge into master is performed
by this task. Historical bootstrap guards and missing-pin statements below are
superseded by the published Release and committed hashes above.

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

## Runtime follow-up — 2026-10-11 (historical)

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


Historical checkpoint: 2026-10-11, Telegram Inbox and safe MCP publication, branch
`feat/bot-workspace`, based on `ae7b322`. No push, merge, CI/script changes or backend
restart. Previous checkpoint evidence is retained below as historical context.

## VERIFIED — previous Inbox task (historical)
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

## NOT VERIFIED — previous Inbox task (historical)
- Real bot command/edit/phone delivery and MCP publication confirmation were not
  exercised against Telegram. Fake API success does not prove phone display.
- Existing backend PID 68501 was identified in the adjacent `/Users/pavlov/Projects/hradar`
  checkout and left running. This branch has not been deployed; production SQLite
  was not migrated. No messages were sent to real recipients.
- Bot API has no exactly-once send guarantee; a send already in flight cannot be
  recalled by revocation. Deleted/uneditable bot cards can produce a permanent edit
  failure; reopening `/matches` provides a fresh card.

## NEXT ACTION — previous Inbox task (historical)
Stop the one existing backend; back up its SQLite database; deploy this branch with
compatible existing TDLib artifacts and start exactly one backend. Follow the manual
Inbox, original-link, consent/cancel/confirm/revoke and phone checks in `docs/BOT.md`.
Do not run a competing getUpdates consumer. Push requires separate user approval.

---

## Previous checkpoint — 2026-10-10 (historical)

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
