# Stage B handoff — 2026-10-25

## Identity and safety

- Worktree: `/Users/pavlov/Projects/hradar-sources-b`, branch
  `feat/telegram-source-reading`, based on fetched `origin/master` `565edd8`.
- PR #11 merge `1ceb871` was independently verified as an ancestor of master.
- Original `/Users/pavlov/Projects/hradar` stayed clean/on its original master.
- At task start existing production backend PID50217 ran frozen447551a from
  `hradar-sources/build/local-runtime/447551a`, frontend from hradar-sources.
  Recheck process/listeners rather than reusing these PIDs. No production restart,
  real profile read/write, native rebuild, Telegram account read/send/join occurred.
- Commit/push/PR to master authorized by user; merge/tag/Release not authorized.
- App0.5.0 (MINOR) synchronized by version.py; SQLite11 is independent.

## Implementation map

- `SourceReadService`: saved key->chat/scope, current metadata/access then native
  history/search/detail. No SearchPublicChat for saved private sources; secret and
  MESSAGE_THREAD rejected. Topic uses native GetForumTopicHistory/MessageTopicForum.
  Bounded recent pagination, short/inclusive anomaly handling, cancellation,
  text/captions, explicit unavailable/unsupported, TDLib-only links.
- `SearchService`: additive sources mode/sourceKeys delegates to a persistent local
  reader instance (cursor cache must survive calls). Global/username paths retained.
  Cursor tokens are ephemeral10min/128entries/10000 identities; results dedup by
  chat+message across wholechat/topic and keep completed pages on partial/rate errors.
- `Repositories`: migration10->11 adds four local tables transactionally. One local
  publication per(chat,message), scoped membership, durable per-source jobs and
  per-project/source evidence. No legacy archive/card/body rewriting. Existing
  imported_messages queried in place only for explicit legacy source keys.
- `SourceArchiveService`: explicit1000/5000/10000 or null(full), atomic checkpoints,
  user/limit pauses, cancellation/join, bounded retries, persisted FLOOD_WAIT,
  restart of RUNNING jobs, safe deletion/access loss. Independent of legacy jobs.
  Authoritative moved-topic upsert invalidates incompatible membership/evidence.
- `LocalProjectSearchService`: local/mixed archive/Telegram search separate from
  Bot/public RecruiterService. ProjectInput.sourceKeys additive; old sources valid.
  RecruitmentProjectService retains deterministic criteria/budgets/seen/notes/
  ratings/stages; local evidence is project+scope bound before manual card creation.
  Local-source subscriptions rejected; changing public subscribed project to local
  sources pauses its existing valid public rule.
- `SourceRoutes`: messages/detail/archive/sync/stop under existing owner-local
  no-store/Host/Origin/desktop capability/CSRF protections. Application assembles
  services around same gateway and closes/resumes owned import workers.
- `SelectedSources.tsx`, Sources/main/Projects: name/type/topic source pickers,
  actual recent/search/archive, safe details without guessed private URLs; no nav
  or Dashboard redesign, private monitoring disabled.
- `StorageMigration`: recognizes11 and validates new table set; desktop lifecycle
  smoke expects11 and verifies future12 refusal.

## Privacy boundary

Local bodies are isolated from imported_messages, recruitment_publications,
notifications/matches, saved Bot decisions and Bot outbox. repo.channels remains
legacy public only; new selections never enter MCP grants/ALL rules/operator lists.
Existing public external permission policy is not broadened. Future changes for
monitoring, delivery, reclassification/revocation and explicit export permissions
belong to C and require their own design. No external AI or second client added.

## Verification ledger

- Initial clean baseline backend build exit0 (162 pre-feature tests).
- Shared RED compile captured absent new service/repository/DTO APIs.
- First integration186 tests7fail: StorageMigration rejected11 (6), deleted-cloud
  user metadata denied actual readable history(1). Both corrected with regressions.
- Intermediate full build188 tests0fail/0errors/0skipped before final review regressions.
- Final post-review JDK21 bundled backend build/stage exit0:194tests,0failures/errors/skips;
  final frontend build0 and full offline63passed, Python29passed.
- Independent final review mixed-project legacy evidence regression fixed and re-reviewed
  with no outstanding actionable findings (archive/favorite/template/wrong-source tests).
- A conversion regression fixture initially omitted required terms and failed before
  conversion; corrected fixture passed with preserved valid disabled public rule.
  Mixed-project legacy evidence regression independently reproduced ResourceNotFound
  before its fix, then passed in final full suite. No failed check was disabled.
- Independent read/CRM and archive reviews caught progressed-stall data discard,
  stale topic membership, exact seen-budget and invalid cursor handling; fixes and
  regression tests included. Final reviewer evidence recorded in DEV_STATUS.
- Frontend63offline Playwright passed; selected2 repeated after CSS; production
  build0. Final combined commands/results and CI URLs recorded in DEV_STATUS.
- Real browser: `JAVA_HOME=$(/usr/libexec/java_home -v21) bash scripts/test-source-browser.sh`
  exit0,1passed using actual Ktor/services/temporary SQLite/fake TDLib. It covers
  topic recent/detail/search/archive, project add/stage/note/rating and browser
  reload. Port18082 fixture and5175 Vite were stopped after check.
- Real HTTP workflow also tested in SourceWorkflowRoutesTest with private cloud
  source, SQLite reopen, public archive/recruiter/MCP access denial.
- Python safeguards29passed; canonical version check and staged secret check pass.
- No live Telegram/manual account acceptance claimed. Hosted macOS results must be
  checked on the exact pushed PR SHA; local Intel builds do not establish ARM64.

## Runtime limits and next session

- COMPLETED imports are snapshots/idempotent starts; they do not auto-refresh new
  posts or reconcile later edits/deletions. Explicit current detail reads show
  current edits/unavailability; reobserved/imported canonical rows update atomically.
- Imported local jobs start newest independently of old public cursors; old archive
  remains visible via fallback and unchanged. Null full history is explicit only.
- Telegram can hide older history; completeness means accessible native history.
- Native pinned C++ excludes the exact history anchor at offset0 (despite ambiguous
  schema wording); repeated-only anomalous pages report stalled rather than fake
  exhaustion. No inferred message ID arithmetic or forum/thread substitution.
- Search continuations are in-memory, not restartable snapshots; import checkpoints
  and CRM state are durable. Native client requests cannot be remotely cancelled
  after sending; cancellation prevents processing/late writes.
- For real manual acceptance: stop single backend, make consistent owner-only full
  profile backup, upgrade once, use owned synthetic chats/types/two forum topics;
  exercise pagination, media captions, scope search, bounded/full/pause/restart,
  access loss and project/card persistence. Verify external surfaces unchanged.
  Recovery restores stopped pre11 backup; never decrement schema number.
- C remains: explicit scoped private monitoring, checkpoint/recovery/live update
  routing/overlap dedup, actual discussion-thread semantics, local notification vs
  external delivery permission design, queue revocation and explicit export grants.
