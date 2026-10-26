# Telegram Sources Stage C handoff — 2026-10-26

## Branch and boundaries

Worktree `/Users/pavlov/Projects/hradar-sources-c`, branch
`feat/telegram-sources-stage-c`, based on fetched master6760635 (merged PR#13).
Original checkout's untracked hradar_stop.py and every other worktree were left
untouched. Another agent owns local URL/port behavior; C changes no port, hostname,
DesktopRuntime, browser URL or runtime configuration. DesktopRoutes only adds the
new owner-local notification endpoints to the existing CSRF/no-store predicate.

User permits commits, push and DRAFT PR only; no merge/tag/Release. Do not poll CI
or re-dispatch workflows. Inspect a failed job only at explicit user request. Real
account/profile/session/message operations and parallel production startup are not
part of C verification. Automatic checks use fake TDLib and temporary SQLite only.

## Architecture map

- `LocalMonitoringService`: explicit(sourceKey,templateId) pairs; first enable reads
  current head/time, pause preserves checkpoints and resume recovers bounded backlog.
  Native callbacks enqueue only; worker validates actual source/scope/access through
  SourceReadService.observe. Live hints never advance recovery checkpoint. Polling
  keeps durable checkpoint/recoveryCursor/recoveryHead; four pages of at most100
  publications per pair per turn. A reader page may fetch up to5 native short pages.
- SQLite12 adds local_subscriptions/local_matches/local_monitoring_limits. Generation,
  source and rule snapshots are compared transactionally before writes. Publication
  identity(chat,message); localmatchidentity(template,chat,message), merging same-rule
  whole-chat/topic overlap. Read state and sequences remain stable across duplicates.
  Global FLOOD deadline persists even when first activation fails without any pair;
  per-pair statuses/deadlines/checkpoints survive reopen. No external outbox path.
- `SourceReadService.discussion`: current selected message -> native properties ->
  GetMessageThread -> saved target chat/thread with original anchor fields separate.
  `TdlibSourceCatalog.thread` handles linked-group identities and rechecks them on
  refresh/read. Thread history takes original anchor chat/message; scoped search takes
  targetchat/MessageTopicThread. ForumtopicInt stays separate; native forums are not
  accepted as discussionthreads. No manually fabricated thread scope is accepted by
  catalog save. Repository typed scopes extend archive/CRM membership for threads.
- `TdlibGateway.localMessageListener` routes new native updates to local queue alongside
  existing legacy listener, sharing the same client/session. Application wires local
  start/close and existing local source routes. Public services/tables remain separate.
- Owner-local `/api/sources/{key}/monitoring` GET/POST and
  `/api/sources/{key}/messages/{id}/discussion` POST; local-only
  `/api/local-notifications` list/read/read-all. Desktop capability/Host/Origin/CSRF/
  no-store retained. No generic native executor, joins or Telegram mutations.
- UI: LocalMonitoring panel in Sources, distinct LocalNotifications view in existing
  Notifications section, DiscussionSource action from native-confirmed message detail.
  New sources/projects/legacyALL do not enroll local subscriptions. Existing templates'
  AND/OR/NOT are reused; template global/public source modes do not grant local access.

## Privacy and retained data

Local publications/subscriptions/matches never enter repo.channels, imported_messages,
matched_messages/notifications, Bot operator lists/workspaces, MCP grants/tools or
outbox. There is no local source external-delivery permission or send API. Revoking
local access cannot leave a local external message queued because none is created.
Legacy external permissions and revocation behavior remain in their existing path.
Owner-local saved matches/archive/cards remain local historical evidence after source
access loss/removal; current detail reads fail safely. No external exactly-once claim.

## Native thread proof

Pinned TDLib bc9c263e2bfee06aaab41e82db51a103376030bc; javap checked matching JavaJAR.
GetMessageProperties.canGetMessageThread gates discovery. GetMessageThread returns
native chatId/messageThreadId; original selected message remains the history anchor.
Pinned MessagesManager.cpp#L17999-L18108 resolves channel anchors to linked groups
internally; #L914-L920 sends originalpeer/originalpost to getReplies. Ordinary root
messages receive native MessageTopicThread when reply metadata exists (#L4877-L4889).
No null-topic fallback or invented root/thread ID arithmetic is used. All references
are under https://github.com/tdlib/td/blob/bc9c263e2bfee06aaab41e82db51a103376030bc/td/telegram/MessagesManager.cpp .

## Verification and remaining execution

App0.6.0 (MINOR from latest master0.5.0), SQLite12. Latest master before completion
remained6760635; no parallel URL/port change was merged at that checkpoint.
Targeted backend41passed; targeted offline Playwright2passed. Single final JDK21
`./gradlew :backend:build :backend:stageDesktop -PbundledWeb=true` exit0:219tests,
0failures/errors/skips. Frontend production build exit0; full offline65passed;
Python safeguards29passed and version.py check passed. Source/native libraries
weren't rebuilt; all account-facing dependencies stayed fake during checks.
See DEV_STATUS and the user report for branch SHA/PR and pending hosted CI.
Targeted tests cover activation/no flood, rules, duplicate/out-of-order updates,
whole+two topics/discussion identities, bounded missed backlog, restart, pauses,
source/rule revocation races, cancellation before commit, access errors, durable429,
read state, actual fake-native source-type matrix, HTTP security and public isolation.
Authentic schema11.sql was generated from Stage B Repositories against an empty
synthetic temporary SQLite; migration tests seed synthetic archive/cards/cursors/
queue and compare every old row before/after/reopen; DDL failure rolls back and retries.
UI targeted fixtures exercise actual request contracts and distinct source scopes.

A scoped feature review found Retry returning unchanged state rather than performing
requested recovery. Fixed to recover only the requested pair under the serial lock,
after deadlines, and covered by real service tests; scoped re-review resolved it.
No whole-repository audit was performed. Full local suites ran once before PR.
Initial targeted compiler snapshots found missing new APIs while implementation was
in progress; the final frozen targeted and full trees both passed. No check disabled.
Dashboard text only clarifies public metrics versus separate local monitoring/matches.
Automatic synthetic updates do not prove actual Telegram, OS or phone delivery.

## Owner QA and recovery

After explicit authorization for a real upgrade, stop the single backend and back up
its consistent full profile/database (including WAL/SHM), settings and session with
owner-only permissions outside Git. Start one new runtime. Upgrade11->12 is forward
only; recovery restores the stopped pre12 backup, never user_version decrements.

Use owned synthetic private conversation/basic group/private channel/supergroup plus
forum/two topics and a permitted channel discussion. Save sources, define active
AND/OR/NOT rule, explicitly enable each source, verify no historical notification flood.
Publish synthetic matching/nonmatching/caption posts, inspect only local matches.
Enable same rule wholeforum+topic, verify one overlap match and correct scope.
Pause/publish/resume/restart, verify bounded recovery/no repeats. Revoke access, verify
safe status/checkpoint preservation; restore access and retry after permitted deadline.
Open discussion from confirmed detail and check linked chat/thread identity, reads,
search/archive/CRM. Verify Bot/MCP/public subscriptions/outbox acquired no local content.

Remaining deliberate limits: bounded recovery cannot recover history Telegram withholds;
search cursors remain ephemeral as in B, completed archives remain snapshots; no new
external grants/delivery; native request cancellation discards processing rather than
remotely cancelling Telegram's server work. Inspect latest master before final SemVer
and merge/rebase if the parallel URL/port PR landed; rerun impacted checks after that.
