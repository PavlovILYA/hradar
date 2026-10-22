# hradar — Development status / session handoff

## Unified local storage — 2026-10-23

VERIFIED: `feat/unified-storage` was created from fetched `origin/master`
`29b1078` in the separate `hradar-storage` worktree. The migration and all
automatic tests used temporary fixtures; no real user storage or Telegram
session was opened. The application now prepares `~/.hradar/data/`, `config/`
and `tdlib/` and copies recognized legacy data with private backups, integrity
checks, conflict detection and an idempotent completion marker. Original data
is retained. See `docs/STORAGE_MIGRATION.md` for layout and rollback.

VERIFIED: JDK 21 `:backend:build :backend:stageDesktop -PbundledWeb=true`
passed (134 tests, no failures/errors/skips); React production build, 32
offline Playwright scenarios, seven Python safeguards and script syntax checks
passed. The staged diff must be checked for secrets before the authorized push.

NOT VERIFIED: physical upgrade, TDLib authorization continuity and Telegram's
server-side API application title; native macOS packaging on Intel and Apple
Silicon is pending PR CI. The local worktree has the checksum-matched Java TDLib
JAR but no native JNI bundle, so no local DMG smoke was run.

NEXT ACTION: inspect staged diff for leaks, commit, push only this feature branch,
open a PR to master, then inspect its CI. Do not merge or release.

## CRM master synchronization — 2026-10-12

VERIFIED: PR #4 was merged by the maintainer as origin/master `0a24f2e`.
Clean distribution worktree retained local integration `ba417de` and all prepared
CRM/desktop changes. Merged master into feat/macos-distribution only; four conflicts
were the previously integrated React navigation, desktop-aware CRM guard and docs.
Resolved with the verified integration versions; application code remains identical
to ba417de. Ports 8080/5173 free. No user profile/session/installed app touched.

VERIFIED: pushed integration head `1e8de4f`; Fast CI 38175051557 succeeded:
React build, 32 offline Playwright scenarios, 7 safeguards and Kotlin build.
Installer 38175051744 passed both native jobs and Intel packaging/mounted-DMG
CRM v8 lifecycle. ARM packaging found a fake-auth browser race: real SSE Credentials
status overwrote mocked HTTP Ready status. Reproduced locally; isolate SSE in the
CRM fake-auth context, preserve separate real onboarding and actual CRM REST/SQLite.

NOT VERIFIED: final dual-architecture installer run after the test correction.
NEXT ACTION: authorized push only to distribution, verify both installer artifacts,
checksums and provenance. No master merge, tag or Release publication.

## CRM v8 / macOS integration — 2026-10-11

Prepared in `feat/macos-distribution` from distribution `381e4c8` and CRM
`c754b63`. PR #4 and PR #5 remain OPEN; master/AI branch, live backend PID 72388,
user SQLite/TDLib session and secrets untouched. No PR merge or Release publication.

VERIFIED:
- Initial worktree clean; fetched origin and confirmed both PR heads/base master
  `077c974`. PRs mergeable. CRM Fast run 38145621920 and distribution Fast
  38167019291 / installer 38167019519 succeeded; both hosted architecture jobs
  passed. These prior installer artifacts do NOT contain CRM.
- CRM services/routes, migrations v8, production React project/Kanban navigation
  integrated with desktop onboarding/shutdown and localhost cookie/origin guard.
  Installer metadata declares schema v8 / recruitment-crm; both package jobs now
  run offline Playwright. Future-schema preservation test now uses v9.
- JDK 21 isolated `:backend:build :backend:stageDesktop -PbundledWeb=true` with
  checksum-verified Intel TDLib JAR: exit 0, 117 tests, zero failures/errors/skips.
  Migration matrix v1–v7 → v8/reopen and failed v8 DDL transaction rollback pass.
  React build exit 0; offline Playwright on owned port 5175: 32 passed; Python
  safeguards: 7 passed. `git diff --check` clean.
- Local Intel DMG/app ZIP created; hdiutil integrity, signatures, Mach-O linkage,
  bundled JVM/JNI and byte-for-byte mounted-DMG comparison (254 files) passed.
  Mounted-DMG smoke exit 0 on isolated port 18082/temporary home: real CRM REST
  creation/archive search/card notes/stage; production browser CRM (only Telegram
  authorization status mocked); v8 reopen preserves exact CRM/archive/source/template
  rows, integrity_check=ok / foreign_key_check empty; future v9 refused with rows
  intact; cookie/origin/CSRF denials, duplicate/busy-port refusal and shutdown pass.
- Local Homebrew JDK packaging correctly refused its external giflib dependency.
  Local smoke instead used the checksum-verified existing CI Intel app runtime;
  repository workflow continues to build with pinned Temurin 21.0.12+8-LTS.
  Initial overlapping isolated Gradle staging failed; sequential build succeeded.
  Browser smoke's incorrect status mock URL fixed and final check succeeded.

NOT VERIFIED:
- New GitHub Actions runs / hosted ARM64 and Intel installer artifacts for this
  integration: pending explicit branch-push authorization required by AGENTS.md.
  Local Intel smoke is not hosted dual-architecture CI evidence.
- Physical/quarantined installation, Developer ID/notarization, real Telegram
  onboarding/session continuation, notifications and real-user upgrade remain
  unverified. No real user database was opened or migrated. macOS 15+ only.

NEXT ACTION:
- After owner authorizes pushing this prepared distribution branch, push only
  `feat/macos-distribution`; run Fast CI and unsigned macos-distribution workflows
  on its exact head, verify both jobs/artifact checksums and strict provenance.
  Do not merge either PR, tag or publish a Release without separate approval.
- For manual upgrade stop old backend, back up stopped data/session privately,
  replace the app and verify CRM records/session on each physical architecture.
  Rollback restores the stopped pre-upgrade backup; never downgrade user_version.

## Standalone macOS distribution — 2026-10-11

Branch `feat/macos-distribution`, functional commit `d03b10a84525e7c70c074dcfeba6e03c647030fc`.
[PR #5](https://github.com/PavlovILYA/hradar/pull/5), targeting master; no merge, tag
or Release publication. This checkpoint supersedes the initial distribution notes.

VERIFIED:
- [Installer CI 38165466566](https://github.com/PavlovILYA/hradar/actions/runs/38165466566)
  concluded **success** on GitHub-hosted `macos-15` ARM64 and `macos-15-intel` x86_64.
  Both native and both packaging jobs passed; optional signing was skipped.
  [Fast CI 38165466300](https://github.com/PavlovILYA/hradar/actions/runs/38165466300)
  also succeeded. Original Fast/native-ARM workflows and `config/tdlib.json` are
  unchanged; the existing verified TDLib Release remains intact.
- Real installer artifacts, each containing DMG, executable/symlink-preserving app
  ZIP, SHA256SUMS and distribution/provenance metadata:
  [ARM64 artifact 11711454728](https://github.com/PavlovILYA/hradar/actions/runs/38165466566/artifacts/11711454728),
  [Intel artifact 11711887163](https://github.com/PavlovILYA/hradar/actions/runs/38165466566/artifacts/11711887163).
  Both are **unsigned internal beta** (ad-hoc seal, no Developer ID/notarization),
  product version 0.1.0, internal macOS bundle version 1.0.0, minimum macOS **15.0**.
- Both official pinned native builds passed architecture checks, account-free JNI
  execution, source identity, SHA256 and provenance. Separate portable build/cache
  namespace and static checksum-pinned OpenSSL 3.5.9; linkage lists only system
  libraries and `@rpath/libjvm.dylib`, provided by the bundled JVM. Independently
  downloaded native bundles and verified both binary hashes and exact-source,
  exact-workflow GitHub-hosted attestations. Public Java API and TDLib constructor
  IDs match across all 3426 classes; bytecode debug attributes/ZIP hashes can differ.
- Native jobs in subsequent installer runs restored successful architecture-specific
  bundle caches and skipped C++ compilation. First ARM native job took about 20 min;
  Intel about 39 min. Their original caller run 38161372825 was later cancelled
  after successful native uploads/cache saves to stop obsolete packaging. Final
  run rechecked/re-attested cached bundles and all installer jobs succeeded.
- Each installer job built production React/Kotlin, used Temurin 21.0.12+8-LTS,
  checked all Mach-O dependencies/OS targets and executed the bundled JVM/JNI.
  CI mounted the actual read-only DMG, compared **182 app files byte-for-byte** with
  app ZIP (including resource seals), then repeated native/runtime and real
  production-browser/lifecycle checks from the mounted volume. DMG creation uses
  hdiutil to preserve the app; jpackage's DMG repacking was found to change seals.
- Backend build: 78 tests, zero failures/errors/skips; React production build passed;
  17 offline Playwright tests passed, including fake phone/code/error/2FA/email and
  other-device-confirmation onboarding. No real Telegram credentials/client/session
  in automatic tests. Packaged smoke used isolated temporary homes, scrubbed TG
  environment and restricted PATH: cookie/Host/Origin/CSRF denial, duplicate and
  legacy-port refusal, SQLite/session sentinel preservation across restart,
  newer-schema refusal without downgrade/data loss, and graceful UI shutdown.
- Independently downloaded final DMG/ZIP pairs: all four SHA256 checks and all four
  provenance checks passed with repository, signer workflow, exact PR merge source
  `a1e7bdb0810d213ba15ed22e773ff25c69a3eb1a`, refs/pull/5/merge and denied self-hosted
  runners. Earlier successful installers also passed local hdiutil integrity checks
  and Intel mounted-DMG fake lifecycle smoke on macOS 15.5. App archives contain
  runtime/JNI/production code, not session/SQLite/API settings.
- Existing user backend PID 72388 on 127.0.0.1:8080 was left running; no real TDLib
  startup, authorization, user-data deletion, unrelated branch mutation or paid
  resource creation. Owned local test processes/temporary mounts were stopped.

NOT VERIFIED:
- Downloaded/quarantined installation on **physical** Intel and Apple Silicon Macs,
  Finder/LaunchServices browser activation/relaunch, real Telegram onboarding and
  continuation of a real existing session, live monitoring/notifications, sleep/
  network recovery and real-user upgrade. No Telegram authorization was attempted.
- Apple Developer ID signing, hardened-runtime operation and notarized/offline
  Gatekeeper acceptance: protected optional jobs are implemented but no paid Apple
  credentials supplied or used. Automatic updates are not implemented. CI tested
  macOS 15; later versions and all macOS <15 are not established as compatible.
- Current application supports SQLite through schema v7. A newer feature database
  is visibly refused and retained; use a build with that newer schema/features.
  Optional Bot API credentials remain environment-based, outside user-account UI
  onboarding. Existing MCP default-disabled/source-grant boundaries are retained.

NEXT ACTION (owner/manual QA): download the architecture-matching artifact, verify
checksums, stop the old development backend, open DMG and drag hradar.app into
Applications on macOS 15+. Confirm startup/onboarding and shutdown, then test a
stopped-data backup/upgrade preserving archive/subscriptions/session. Do not bypass
newer-schema refusal by deleting data. Configure protected Apple environments/secrets
and validate signed builds on both physical Macs before any separately authorized
public release. Exact setup/limitations: [MACOS_DISTRIBUTION.md](MACOS_DISTRIBUTION.md).

## CRM technical QA audit — 2026-10-11

Branch `feat/recruiting-crm`; PR #4 OPEN. Full report: [CRM_QA_REPORT.md](CRM_QA_REPORT.md).

VERIFIED:
- Reproduced and fixed actual search defects: swallowed link 429, lost completed
  sources on later 429, optional-link timeout, linked-template duplicates across
  pages, stale list template IDs and cross-project matching explanations.
- Atomic monitoring writes recheck current rules/saved sources. Removing a source
  during history RPC no longer writes revoked evidence or crashes recovery with FK.
- React regressions fixed: lost visible unsaved notes/rating, stale monitoring link,
  criteria caret mutation, delayed open overwriting another project, ineffective
  column retry, pending-save navigation/repeated submit and misleading link errors.
- Final isolated JDK 21 backend build: **114 tests, zero failures/errors/skips**,
  exit 0. v1–v7 migration/reopen matrix, failed-v8 DDL rollback, ten-source full
  bounded traversal, concurrent continuation and criteria-change races covered.
- Frontend build exit 0; offline Playwright **31 passed**; real REST/temporary SQLite/
  production SearchService with fake RPC browser workflow **1 passed** (desktop/mobile).
- 10k/50k archive and card data: bounded pages/filter/stats/search measured 300/1318 ms
  aggregate; 50k publications/100k linked notifications: two pages 1206 ms aggregate.
  These local Intel measurements are not a production latency/ARM64 guarantee.
- Pushed code head `4c2d018`: [Fast CI 38145471459](https://github.com/PavlovILYA/hradar/actions/runs/38145471459)
  completed successfully, both jobs green; logs confirm backend build and 31 browser tests.
- Schema remains v8, no user database operations or live Telegram tests. Live backend
  class files were not rebuilt; CI/native/AI/MCP/Bot production files unchanged.

NOT VERIFIED: live Telegram cursor changes/rate limits and original links; actual
in-flight TDLib RPC cancellation. Backend PID 72388 remains `0e833b7`, not restarted.
Search tokens remain ephemeral and limited to 10k inspected identities per chain;
matching uses existing 4000-character excerpts. See report for other practical limits.

NEXT ACTION: Review audit commits and final-head Actions in PR #4. Merge is
recommended only with green final CI; no merge is performed.
Manual testing needs a normal restart of the single backend, not a competing session.

## Project search and UX checkpoint — 2026-10-11

Branch `feat/recruiting-crm`; PR #4 remains OPEN against master. AI branch/module,
MCP security, native TDLib and CI workflows were not changed.

VERIFIED:
- Project search fills up to ten matches by traversing at most five raw pages /
  fifty publications per step, with a twenty-second source-fetch budget. Required
  terms remain AND, exclusions NOT, desired terms do not gate matches.
- Opaque continuations retain the last completed source cursor and seen publication
  identities. Overlapping pages, unchanged cursors, source/query/project changes,
  foreign/malformed/restarted continuations, partial failures, 429/FLOOD_WAIT,
  timeout and parent cancellation have automated coverage.
- Per-step unique scanned/matched counts, limit/exhaustion state, actual required /
  desired words and a bounded SQL lookup for already-added publications. Empty
  selected archive and missing saved sources have distinct states. No schema change:
  v8 remains current, existing migration/rollback/10,000-card regressions pass.
- UI orders sources before requirements, explains the next step, distinguishes
  archive/Telegram and failure/empty/budget states, offers continuation and cancellation,
  links actual originals and disables duplicate additions. Navigation remains unchanged.
- JDK 21 `./gradlew -p .tdlib-build/crm-search-check :backend:build`:
  **96 tests, zero failures/errors/skips**, exit 0. Source copy excludes native,
  credentials and user databases; live backend class files were not rebuilt.
- `npm run build --prefix frontend`: exit 0. Offline Playwright on owned port 5175:
  **23 passed**, including six search UX scenarios and existing sections.
- `scripts/test-recruitment-browser.sh`: real Ktor routes + temporary SQLite + fake
  Telegram, **1 passed**. Traverses thirty incomplete archive publications, then
  fifty Telegram non-matches followed by continuation to the real synthetic match;
  adds/reviews/moves the card, reloads notes, exercises mobile and archiving.
  Fixture compilation is now isolated from running backend classes; owned processes
  exit cleanly. No live Telegram/account/database operation was used for these checks.

NOT VERIFIED: Fresh live Telegram behavior under the new pagination. The existing
backend PID 72388 still runs the earlier `0e833b7` deployment; it was not restarted
or queried during this task. Frontend dev server serves working-tree UI changes.
Continuations are ephemeral (10 minutes, 128 cached states, max 10,000 inspected
identities per chain), not durable search snapshots. Evidence uses existing 4,000-
character excerpts; counts are not channel totals. See PR #4 checks for pushed-head CI.

NEXT ACTION: Review PR #4 and its checks. To manually try the new server behavior,
restart the single existing backend from this branch without starting a competing
TDLib client, then follow RECRUITMENT_PROJECTS.md. This task deliberately did not
redeploy against the user's database or merge master.

## CRM runtime deployment — 2026-10-11

VERIFIED: At the user's explicit request, stopped the old backend (PID 54482,
feat/bot-workspace `2e3f336`, adjacent hradar-feature checkout) and old frontend.
Confirmed both ports released before starting feat/recruiting-crm `0e833b7` from
hradar-crm. Reused the existing matching Java/JNI pair and private runtime environment;
no native rebuild, credential output, session deletion or competing TDLib client.

Created private-permission stopped-database backup
`~/.hradar/research.pre-crm-v8-20261011-135535.sqlite`; backup integrity check is `ok`.
Actual v7→v8 migration preserved 8942 imported_messages, one search_template,
zero matches, one bot_recipient and two publication_decisions. Projects endpoint
returns HTTP 200 with zero existing projects. Backend PID 72388 listens only on
127.0.0.1:8080; frontend PID 72372 serves this checkout on 127.0.0.1:5173, including
Projects.tsx. Health 200; TDLib authorized / connection Ready. No test project,
Telegram send, join or history import was initiated during deployment.

NOT VERIFIED: User's real project creation/review workflow and fresh live subscription
notification remain for manual testing. Automated CRM/SQLite/Kanban evidence is below.

NEXT ACTION: Open http://127.0.0.1:5173/, hard-refresh, choose Projects and follow
RECRUITMENT_PROJECTS.md. Create Senior Java Backend, find/add an existing publication,
save notes/rating/stage, refresh and verify persistence; compare stages in two projects.
Do not restore the pre-v8 backup without stopping the backend; restoration would remove
project work created after this deployment.

## Recruitment CRM checkpoint — 2026-10-11

Branch `feat/recruiting-crm`, based on master `077c974` (Bot PR #2 already merged).

VERIFIED:
- Typed RecruitmentProjectService and local REST CRUD, ACTIVE/ARCHIVED projects,
  mandatory/desired/excluded terms, saved source selection and template associations.
  Shared RecruiterService/SearchService, MatchingService and SubscriptionService;
  one stable explicit subscription per project, synchronized criteria and archive pause.
- React Projects list/form/settings and five-stage publication Kanban, notes, rating,
  manual classification correction with automatic reason, source/technology filters,
  text/notes search, sorting, statistics and bounded independent column pagination.
  Archive/Telegram discovery, saved publications and linked-template matches remain
  distinct; add is manual and duplicate-safe. Same publication has independent project state.
- SQLite v8 adds four tables and indexes; existing v1/v4/v5/v6/v7 fixtures survive
  forward migration. Temporary-database restart persistence, injected transaction rollback,
  duplicate identity, wrong-project/missing-resource denial and 10,000-card pagination pass.
  Source removal preserves existing cards and blocks new additions from removed sources.
- Final `./gradlew :backend:build --console=plain` exit 0: **86 tests, 0 failures,
  0 errors, 0 skipped**, JDK 21 test/toolchain. Existing Java bindings reused locally;
  no native build or live account used. `npm run build --prefix frontend` exit 0.
- `npm run test:ci --prefix frontend -- --config playwright.projects.config.ts`
  exit 0: **17 passed**, with all APIs mocked and owned frontend on 127.0.0.1:5175.
  `./scripts/test-recruitment-browser.sh` exit 0: **1 passed** against actual Ktor
  project REST and temporary SQLite, other APIs mocked. Scenario covers create/search/add,
  notes/rating/classification/stage change, filters, reload, 390px phone controls and archive.
  Script stops its fixture on 18081 and frontend on 5175; ports verified released.
- No BotService/BotUpdateHandler/MCP/Agent Bridge/LLM/agent-jobs.sqlite, CI or TDLib build
  script changes. Existing backend/session and user database were not modified/restarted.
  `git diff --check` passes. Guide: docs/RECRUITMENT_PROJECTS.md.
- Feature commit `8972aeb` pushed only to feat/recruiting-crm. [PR #4](https://github.com/PavlovILYA/hradar/pull/4)
  targets master. [Fast CI 38098840366](https://github.com/PavlovILYA/hradar/actions/runs/38098840366)
  completed **success** for `8972aeb3664abed288e926b78e96dc3d9f563beb`: frontend production
  build / 17 offline browser tests and backend pinned/attested Java JAR bootstrap,
  safeguard tests / Kotlin build and account-free tests. No merge or release.

NOT VERIFIED:
- Production v7→v8 startup and live project Telegram search/monitoring were deliberately
  not run against the user's existing session. Live delivery is not established by fixtures.
- Online/saved excerpts keep the shared 4,000-character limit; full text is in the original.
  Classification is conservative phrase matching, not semantic understanding. Offset pages
  can shift under concurrent edits. No drag and drop; accessible select/save controls exist.

NEXT ACTION:
- Review PR #4; no merge/release is performed or authorized. The documentation follow-up records the verified feature commit/run above.
- For manual deployment stop the single existing backend, make a stopped-database/SQLite
  backup, then start this checkout once. Follow docs/RECRUITMENT_PROJECTS.md. Rollback
  requires restoring the pre-v8 backup, not changing user_version in place.

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
