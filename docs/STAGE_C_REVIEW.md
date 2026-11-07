# Stage C PR #14 review — 2026-11-01

Reviewed existing `feat/telegram-sources-stage-c`, starting head
`a1241be9e9d94cd719adf2c6530e5c4c3f742cc5`, base/master `6760635`.
Used its clean existing worktree. No other worktree, real profile, Telegram client,
credentials or messages were used. This review does not establish live acceptance.

## Proven defects and fixes

1. Fast CI run [40394120563](https://github.com/PavlovILYA/hradar/actions/runs/40394120563)
   failed the partial-setup overview test (64 passed). GitHub returned zero artifacts;
   its job log contains the reported missing heading. Local reproduction showed
   `Projects` crashing at `criteria.description.slice` when list loading beat detail
   loading. The fixture omitted `description`; actual `ProjectInput` defaults it to
   an empty string and Application serializes defaults. Added the missing fixture
   field and explicitly gated detail delivery until the list renders. Original form
   heading/criteria/reentry assertions remain. Original and forced-order cases failed
   before the fixture fix and passed after it. No timeout increase or test removal.
2. `LocalNotifications.read` caught failed read requests but returned success, so
   opening a match navigated away and hid its error while the match remained unread.
   Return false on failure. A new offline browser test observed the failure first,
   then verified the retained error/unread count, successful retry and opening details.

No proven backend correctness defect was found by the primary inspection or the
independent read-only backend review. No backend production behavior was rewritten.

## Contract audit

| Contract | Code and automated evidence |
| --- | --- |
| 1–3: explicit pair; new/private sources off; no source/project/ALL enrollment | `configure` and `configureLocalSubscription` require a saved source and active template. Local tables start empty. `explicitPairsDoNotInheritLegacyAllAndSameRuleWholeTopicDeduplicates`, migration defaults and browser explicit-enable flow cover this. |
| 4: initial checkpoint without flood | First configure snapshots current head/time before creating the pair; failed snapshots create no pair. Initial-enable and failed-activation tests cover this. |
| 5–6: chat/scope routing and overlap | One gateway enqueues local hints; `observe` checks chat and typed native topic/thread. Unique `(template,chat,message)` merges overlap. Native fake source matrix, wrong-topic and thread tests cover routing. |
| 7–8: restart, bounded pages, short/repeated cursors | Live hints do not move recovery checkpoints. Recovery stores head/cursor, processes at most four reader pages per turn; reader tolerates short/inclusive pages and reports stalls. Added a stalled-cursor → reopen → access error → reopen → deadline/recovery test; checkpoint remains behind retained backlog until completion. Existing tests cover bounded recovery and native short-page continuation. |
| 9: pause/removal/template/source changes | Transaction checks current pair/generation, source snapshot and active template snapshot. Tests race pause, source deletion, rule changes and cancellation against suspended reads; no late commit/recreation. |
| 10: errors/rates/deadlines | Global and per-pair durable deadlines gate reads; rate deadline survives failed first activation/reopen. Access/stall retry state survives two reopens in the added test. Errors contain safe fixed text. |
| 11: real discussion metadata | Properties and GetMessageThread establish target chat/thread; original anchor remains separate for history. Forum Int and thread Long use distinct native types. Thread tests cover linked targets, anchor loss, changed mapping, wrong scopes and fabricated-ID rejection. These are fake-native assertions, not live proof. |
| 12: no private export; security retained | Local writes use local tables; public notifications, Bot, MCP/recruiter and outbox retain public adapters/grants. Existing matrix/HTTP tests check isolation. Expanded route tests deny missing desktop cookie, hostile Origin and missing CSRF for monitoring/discussion/local-read mutations, and assert no-store. No Telegram send/join or external permission added. |
| 13: schema 11→12 | Additive DDL and version share a transaction. Authentic v11 fixture now includes consistent legacy subscribed rule/catalog/channel/cursor/read notification/MCP grant plus local archive/project/card/note/rating/import/outbox. Old-table snapshots match after migration/reopen; injected DDL failure rolls back and allows retry. Added direct future-schema13 refusal with unchanged old rows/version and no new tables. |

Additional atomicity regression supplies one valid matching message followed by an
invalid-scope message. The transaction rolls back publication, match and checkpoint;
a subsequent valid commit succeeds. JDBC operations are serialized by repository
monitoring; network IO is outside the SQLite transaction. Coroutine cancellation
tests prevent committing after the suspended operation is cancelled. Native request
cancellation does not cancel server-side Telegram work.

## Validation and limits

Exact final command outcomes are in DEV_STATUS. Tests use fake native requests,
synthetic HTTP/browser fixtures and temporary SQLite only. No new native build is
needed. Hosted CI evidence after push belongs to this PR's exact current head,
not the successful historical macOS run
[40394120682](https://github.com/PavlovILYA/hradar/actions/runs/40394120682).

Recovery only covers available Telegram history, with bounded work per turn.
Queue overflow drops live hints and relies on durable recovery. Source removal/access
loss retains owner-local historical evidence. Local matches do not provide OS/phone
delivery. Real account update routing, linked discussion contents, permission changes,
FLOOD_WAIT behavior and restart recovery still need owned synthetic-chat QA as
described in STAGE_C_HANDOFF. Restore a stopped consistent pre12 backup for downgrade;
never lower user_version or run an old binary against the migrated profile.

## Historical PR #15 integration risks (reviewed 2026-11-01)

Integration checkpoint (2026-11-08): #15/#16 are merged; PR #14 rebased onto
master ec9f629 for app0.8.0/schema12. Shared launcher/Menu Bar cleanup, route
authority/CSRF, encoded-path and installer-schema integration is now included.
See current DEV_STATUS for results; the following risks describe the old review.

At review time #15 is OPEN, head `3e74206353f1e5be7f259dfcd77f44e4535e4862`.
Both PRs target app0.6.0; keep C at0.6.0 until #15 actually merges. Policy bumps
once per behavior-changing PR; the present fixes need no additional bump.

- `Application.kt`: merge local worker construction/start/close with DesktopAccess
  initialization, launch/reopen and shutdown changes; preserve one user client.
- `DesktopRoutes.kt`: preserve C's `/api/local-notifications` inclusion in
  sourceSettings (CSRF), while adopting #15's desktop capability/Origin policy.
- `SourceRoutes.kt`: semantic conflict even without overlapping diff lines. Its
  current desktop predicate accepts the old numeric Origin; the future installed
  `hradar.localhost:1709` origin must follow the shared desktop policy. Do not broaden
  development/MCP allowlists to make the UI pass.
- `frontend/src/main.tsx`: keep local match/discussion handlers and views while
  adopting launch-recovery UI. Cookies, source mutations and CSRF need a production
  browser regression at the final installed origin.
- Merge DEV_STATUS/ARCHITECTURE and schema12 lifecycle expectations in
  `scripts/smoke-desktop.py` with #15's address/restart smoke changes. Keep SQLite
  schema and app version independent; coordinate `gradle.properties` and npm metadata
  (expected next MINOR0.7.0 only after actual merge, subject to then-current policy).

After #15 merges, update this existing PR from master, resolve these boundaries,
choose/synchronize version, and repeat backend/build/offline browser, security,
lifecycle/future-schema and hosted ARM64/Intel checks. No merge, rebase onto #15,
tag, release or real upgrade has been performed in this task.
