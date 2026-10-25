# Telegram source series

## Status and verified baseline

[PR #11](https://github.com/PavlovILYA/hradar/pull/11) is merged into master at
`1ceb87156093c4b232561270de5f921c8fb17149`; final functional SHA
`447551a7e1ce0ba2af5d20799800676cbbc1608b`, app 0.4.0, schema 10.
[Fast CI](https://github.com/PavlovILYA/hradar/actions/runs/40187356225) and
[macOS Installers](https://github.com/PavlovILYA/hradar/actions/runs/40187356218)
both passed on that SHA, including ARM64/Intel production UI/lifecycle smoke.
Stage A is implemented and automated-test verified; live-account/manual acceptance
is still pending. This is the merged A baseline. Stage B implementation is described below; C remains pending. New work starts from freshly fetched
master in a new feature worktree; do not reuse the merged feature branch.

## Stage B — owner-local processing (0.5.0, SQLite11)

Stage B adds actual reads/search/archive/CRM by saved source key. Account-specific
live acceptance remains separate from fake-RPC/browser evidence in DEV_STATUS.

| Source | Catalog | Recent/detail/search | Explicit archive | Project / Kanban |
| --- | --- | --- | --- | --- |
| Cloud personal conversations (optional username) | Yes | Yes | Yes | Yes |
| Basic groups | Yes | Yes | Yes | Yes |
| Public/private supergroups | Yes | Yes | Yes | Yes |
| Public/private channels | Yes | Yes | Yes | Yes |
| Whole forum group | Yes | All available chat messages | Yes | Yes |
| Individual saved forum topic | Yes | Native topic scope only | Scoped | Yes |
| Discussion/comment message threads | Reserved; save rejected | C | C | C |
| Secret / special direct-message topic chats | Excluded | Unsupported | Unsupported | Unsupported |

Local reads are constrained by the authorized Telegram account's actual rights.
Selection does not join chats, import history, subscribe to monitoring or grant
Bot/MCP/AI access. Source UI exposes recent messages and manual archive actions;
Search selects saved names/types, including parent group + topic name. Legacy
public username/global search paths remain available.

### Reading and search contracts

`SourceReadService` resolves key -> saved chatId + validated SourceScope. Whole-chat
reads use `GetChatHistory`; forum reads use `GetForumTopicHistory` with native Int
forumTopicId. Search uses `SearchChatMessages.topicId = MessageTopicForum(id)`.
MESSAGE_THREAD is rejected; there is no fallback to whole chat or ID conversion.
Signatures were checked with javap against the pinned generated Java JAR.

Pagination was also checked against the pinned native implementation: at offset 0,
[`OrderedMessages::get_history`](https://github.com/tdlib/td/blob/bc9c263e2bfee06aaab41e82db51a103376030bc/td/telegram/OrderedMessage.cpp#L397-L403)
advances past an exact anchor; forum history uses
[`lower_bound` and a decrement-before-read slice](https://github.com/tdlib/td/blob/bc9c263e2bfee06aaab41e82db51a103376030bc/td/telegram/MessagesManager.cpp#L17980-L17996).
Thus normal terminal pages are empty without inventing a message ID or using an
unsupported positive offset. The reader additionally tolerates inclusive overlaps,
keeps short-page continuations, and reports anomalous repeated cursors explicitly.
These are source/API checks and fake regressions, not a live-account verification.

`/api/sources/{key}/messages` returns bounded pages with nextCursor and explicit
state; `/messages/{messageId}` fetches current details, edits and unavailable content.
Text and supported media captions share the existing message extractor. Topic IDs,
source key/title and message identity accompany results. Only TDLib-provided valid
links are shown; cloud/private messages without a link remain openable locally.

`POST /api/search` accepts mode=sources and sourceKeys (one to ten). Existing
MatchingService AND/OR/NOT rules and template query semantics are reused. Local
continuations are opaque, query/selection/template-bound, expire after ten minutes,
retain publication dedup across overlapping chat/topic pages, and are bounded to
128 states and 10000 inspected identities. Sorting uses date, chatId, messageId.
Native failures retain completed sources and the failed cursor; 429/FLOOD_WAIT
returns partial results with an explicit state. Cancellation propagates through
cancellable gateway continuations (it does not cancel Telegram server-side work).

### SQLite10 -> 11, imports and recovery

The additive transaction creates `local_publications`, `local_source_membership`,
`local_source_sync` and `local_project_publications`. The canonical local publication
key is `(chatId,messageId)`; archive membership and CRM evidence retain selected
source/scope separately. New local content never goes into `imported_messages` or
legacy `recruitment_publications`. Existing public archives are queried in place
for legacy keys, with newer local evidence taking precedence. No old JSON/history,
CRM cards, notes, ratings, Bot state or public import cursors are rewritten.

Explicit imports default to 1000, with 5000/10000 and deliberately chosen null
(full accessible history). `SourceArchiveService` persists atomic pages/checkpoints,
limits, USER/LIMIT pause, retry count and flood-wait deadline. Pause joins the worker;
startup resumes RUNNING only. Source removal during RPC prevents late writes and
cannot restore a deleted selection. Access loss retains archived rows/cursor and
stops safely. Local imports start independently at newest, leaving old public jobs
untouched. COMPLETED is an idempotent snapshot; it does not automatically rescan
new posts or reconcile later deletions. Current details can re-read an individual
message, and authoritative re-imported edits update the shared local row. Moving
messages to another topic invalidates stale membership/evidence for the old topic.

Before real upgrade, stop the single backend and back up the complete consistent
profile with owner-only permissions, including SQLite WAL/SHM consistency. Schema11
is forward-only: older binaries refuse it. Recovery restores the stopped pre-upgrade
profile backup; never lower user_version or drop application tables in place.

### Local CRM and privacy

ProjectInput.sourceKeys is additive to the existing sources (legacy chat IDs).
Local/mixed project searches reuse project criteria, budgets, continuation dedup
and per-project evidence; source/scope is rechecked before creating a card. Saved
Kanban cards keep their snapshots, stages, notes and rating across restart/removal.
A project with local keys cannot subscribe; switching an existing subscribed project
to local selection pauses its old public rule. Merely linking a source/template
never enables new monitoring.

Public `repo.channels()`, RecruiterService, CapabilityService, Bot workspaces/outbox
and MCP grants retain their existing public-only inputs. Their archive/publication
queries cannot read the new local tables. Owner-local REST retains exact Host/Origin,
desktop capability/CSRF and no-store protections. No body/title is logged by new
services; native errors are sanitized. Existing public-only external policies are
not expanded by B; future monitoring/export changes require the C permission design.

### Verification and manual acceptance

See DEV_STATUS and STAGE_B_HANDOFF for exact final build/CI evidence. Automatic
tests use synthetic sources and fake TDLib requests, temporary SQLite and offline
Playwright. `scripts/test-source-browser.sh` exercises real local REST/services/
SQLite behind the browser with fake native calls and never starts a TDLib client.

Manual owned-chat acceptance: after a stopped-profile backup and controlled single
backend upgrade, save each supported chat type plus two forum topics. Put distinct
synthetic text/captions into both topics; verify whole-chat versus topic reads,
search by name with no username, bounded import/pause/resume/restart and duplicate
prevention. Create a project, search/archive/add a card, save stage/note/rating and
restart. Revoke access and confirm safe errors with retained archive/card. Confirm
Bot/MCP lists and notification queues acquire no selected local sources/content.

## Identity and native API

Canonical `telegram_sources` uniqueness is `(chat_id, scope_kind, scope_id)`.
`chatId` is TDLib Long; usernames are optional mutable metadata. An opaque stable
row key is only a local REST handle. Forum IDs are Int; thread IDs are Long.
`CHAT`, `FORUM_TOPIC` and `MESSAGE_THREAD` are distinct; they never convert into
each other. Publication identity remains `(chatId,messageId)` in existing tables;
B uses separate owner-local shared rows with scoped membership; legacy public rows remain intact.

Pinned TDLib: bc9c263e2bfee06aaab41e82db51a103376030bc. Signatures were checked
against the matching generated Java JAR and the [official pinned schema](https://github.com/tdlib/td/blob/bc9c263e2bfee06aaab41e82db51a103376030bc/td/generate/scheme/td_api.tl).

A uses `LoadChats`/`GetChats` (main/archive), `SearchChats` and
`SearchChatsOnServer` (known chats only), `GetChat`, `GetUser`, `GetBasicGroup`,
`GetSupergroup`, `GetForumTopics`, `GetForumTopic`. Explicit metadata DTOs exclude
native `Chat.lastMessage`, drafts, user phone fields and `ForumTopic.lastMessage`.
There is no generic TDLib executor endpoint.

A browse request has bounded load budgets (100 more chats per list), snapshots up
to 500 known IDs per main/archive list, and emits up to 25 accepted entries while
examining at most 50 IDs. App cursors are opaque, query/filter-bound, expire after
three minutes, and hold fixed ID snapshots rather than pretend native offsets.
At most 32 cursors are kept in memory. Short native lists are not proof of
exhaustion; the UI states that the list is a bounded sample. Refresh loads another
native batch; search locates known chats outside that sample (100 per native search).
Forum paging uses all three native returned offsets; topic ownership is rechecked.

Membership and observed metadata access distinguish AVAILABLE, UNKNOWN and
UNAVAILABLE. Left/nonmember is not automatically classified as banned: cached or
public access may remain. UNKNOWN can save metadata with a warning, never imply
message-reading permission. Read calls in B must verify actual access again.
Access recheck is explicit; stored state is last-observed, not a permanent grant.
A delayed recheck cannot recreate an entry removed in another tab.

## Privacy boundary

`/api/sources` and `/api/source-catalog` are owner-local web endpoints, protected by
existing desktop capability cookies, exact Host/Origin, mutation CSRF and no-store.
Client input cannot grant legacy status, monitoring or export permissions. New
selected sources never populate `monitored_channels`, `mcp_sources`, archives,
subscriptions, matches, bot menus or queues. No source titles or bodies are logged.
A legacy public entry cannot silently be reclassified as private through catalog
save; it must first be explicitly removed from the old public source surface.

`repo.channels()` remains the old public-only adapter. Broadening that method would
leak private metadata to bot menus and make existing ALL subscriptions monitor new
sources; B/C must introduce scoped local read ports and explicit external permissions
before doing so. Existing public-to-private access changes still require actual
read-time validation in B/C; a metadata snapshot is not an export permission.

## SQLite 9 -> 10 and recovery

The migration creates one additive table in a transaction, copies old sources by
already stored chat ID and adds an optional FK back to the old channel row. It does
not resolve usernames or rewrite existing JSON/history/CRM/notification/bot rows.
Old public save/rename/delete paths synchronize catalog rows atomically; deleting a
legacy source preserves existing old deletion semantics and archives. New source
saves are idempotent across retries and preserve the opaque key after username changes.

Tests include a SQL fixture generated by the **actual 0.3.0 Repositories** using an
empty temporary database; synthetic archive/CRM/queue/cursor data are compared
before/after/reopen. A forced DDL conflict rolls back the schema number and allows
retry; malformed scopes and cross-parent topics are rejected. No real profile was
read. A packaging smoke expected schema10; B smoke expects schema11 and refuses future schema12.

Before upgrading a real profile, stop its single backend and make an owner-only
backup of the complete profile/database including SQLite WAL/SHM consistency. Do
not copy an actively written SQLite file. The app migration is forward-only; old
binaries refuse schema10. Recovery means stopping the new app and restoring a
consistent pre-upgrade backup, never decrementing user_version or dropping tables.
Source metadata stays in `~/.hradar/data/research.sqlite`, with owner-only 600
file permissions inside existing 700 profile directories. No new credentials
store is introduced. Storage layout stays `~/.hradar`; no directory migration was added.

## Series checklist: B implementation and future C

The following records the agreed series scope; current B evidence is above and in DEV_STATUS. Keep the A catalog,
public-channel adapters, matching rules, one TDLib session and local auth intact.
Do not add external sharing as a side effect of local processing.

### B — local reading, search, archive and CRM

1. Trace `SourceModels`/`SourceCatalogService`, `TdlibGateway`, `SearchService`,
   `HistorySyncService`/`HistoryRoutes`, `RecruitmentProjectService`, `Repositories`,
   `HistoryArchive`/Projects and all shared consumers before changing contracts.
   Introduce a typed selected-source read reference resolving the saved key to its
   chatId/scope. Keep legacy username REST/search paths as compatibility adapters;
   reject unselected scopes rather than falling back to the whole chat.
2. Implement bounded whole-chat reads using `GetChatHistory`, forum reads using
   `GetForumTopicHistory(chatId, forumTopicId, ...)`, and source search using the
   pinned `SearchChatMessages(chatId, MessageTopic, ...)`. Verify generated bindings
   again; use typed scope arguments and reject missing scope fields rather than
   silently searching the whole chat. Use `MessageTopicForum`, never substitute a thread/root ID. Keep global
   public search separate with its existing limits and no implicit joins.
3. Recheck actual source access at read time. Support cloud personal chats, basic
   groups, public/private supergroups/channels and selected forums/topics. Handle
   text/captions, short inclusive pages, duplicates, cancellation, no cursor progress,
   inaccessible history, 429/FLOOD_WAIT and connection loss. Short pages alone do
   not mean exhaustion. Fetch original links only through supported TDLib results;
   show unavailable links honestly. Do not promise history Telegram withholds.
4. Design the next forward-only migration before writes: scope-aware archive jobs,
   cursor/checkpoints and scope membership referencing shared `(chatId,messageId)`
   publication rows. Existing schema10 history/CRM/template references use legacy
   IDs/usernames; add stable source references without deleting or guessing identity.
   Preserve all v10 data and add a real v10 fixture plus rollback/reopen tests.
5. Wire source selection into recent messages, one/multiple-source search, bounded
   explicit history import, archive filters and project criteria. Keep Kanban, notes,
   ratings/manual classification and old public projects working. Local project
   storage/read support must not silently activate monitoring before C. Adjust
   Dashboard guidance/counts to distinguish catalog selection from processing.
6. Audit privacy **before** private bodies enter shared tables: `repo.channels()`,
   `RecruiterService`, BotWorkspace/BotInbox, notifications/outbox, saved results,
   MCP capability/archive/publication-request routes and original-message context.
   Leave `channels()` public-only; use scoped local ports. Shared archive/CRM stores
   must not make private bodies/metadata visible to bot operators or existing MCP
   grants. Also check sources changing public -> private; no blanket export grant.
7. Acceptance: fake port tests for every supported type, two topics and whole-chat
   overlap, short/duplicate/repeated imports, access loss/recovery, cancellation and
   reopen, v10 preservation, cross-chat identical message IDs, denied Bot/MCP reads.
   Browser tests must exercise recent/search/archive/project/cards and safe errors,
   not just selectors. Run existing regressions and both hosted installer jobs.

B stops at local reads/search/archive/CRM. Do not enable new private subscriptions,
external exports or discussion-thread selection while claiming B complete.

### C — explicit monitoring, topics and discussion threads

1. Start from merged B, inspect its actual schema/read ports and plan another forward
   migration only if needed. Extend `SubscriptionService`/`NotificationService` via
   those ports; preserve deterministic AND/OR/NOT, durable state and cancellation.
2. Add an explicit per-source local monitoring switch and scoped subscriptions.
   New/private sources default off. Adding a source/project or an old ALL rule must
   not auto-enable it. Establish a checkpoint when enabled; never notify all history.
3. Route live `Message.topicId` updates and bounded recovery to selected scopes.
   Persist cursors per subscription/scope; atomically deduplicate shared publications
   and matches so whole-group + topic overlap does not duplicate notifications.
   Cover restart, lost permissions/left/banned groups, rate waits, unavailable
   history, pauses and explicit resume without dropping or resending old events.
4. Enable discussion selection only from actual TDLib-provided accessible message
   metadata: check `GetMessageProperties.canGetMessageThread`, use
   `GetMessageThread` -> `MessageThreadInfo.chatId/messageThreadId`, then
   `GetMessageThreadHistory`. Its documented `messageId` anchor argument must not
   be assumed interchangeable with the stored thread ID. Channel comments may
   belong to a linked supergroup. Preserve returned identity/access; never
   construct thread IDs or t.me links.
   Forum `forumTopicId:Int` remains distinct from `messageThreadId:Long`.
5. Separate local notifications from external delivery at both query and enqueue/
   send boundaries. Private source content defaults to no Bot/MCP/AI/export access.
   Any external sharing requires an explicit separately reviewed per-source and
   recipient permission decision, current-access revalidation and queue revocation
   semantics. If this policy is not authorized, keep sharing unavailable; local
   monitoring can still be implemented without granting it.
6. Acceptance: fake live/recovery/restart/thread/topic cases, duplicate and out-of-order
   updates, bounded backlog, permission/rate failures, source switch and revocation,
   overlap exactly-once match creation, and explicit absence of private Bot/MCP data.
   Browser tests cover local switches/status/notifications and correct publication
   opening/read state. Require backend/frontend/Python/version/privacy checks and
   both hosted architectures; document remaining real delivery limits honestly.

For each behavior-changing PR, bump one MINOR from then-current master via
`gradle.properties` + `scripts/version.py sync/check` (B would be 0.5.0 if still
0.4.0). Preserve compatibility; owner must separately decide any incompatible
pre-1.0 contract/migration change. No automatic merge, manual tag or Release.

## Manual E2E still required on owned test chats

After backup and a controlled launch with one account/session:
1. Create your own cloud conversation, ordinary group, private/public supergroup,
   private/public channel and a forum with two topics. Do not use candidate data.
2. Open Sources -> Add from Telegram; check filters/search/main/archive sampling.
3. Select each type and both forum scopes; reopen the app and verify stable saved
   rows, optional username, no duplicates and distinct topic IDs.
4. Rename/remove a username, reselect/recheck; verify identity remains chat ID.
5. Remove access to a test group; use Check access and verify safe state/error.
6. Verify templates, Bot and MCP did not acquire any new source/subscription. Old
   public search/archive/project/card and notification flows must still work.
7. Until B/C merge, verify new source controls clearly explain processing limits;
   do not interpret selection success as message/history/monitoring support.
8. In B/C acceptance, add known text/captions to both topics, use short/duplicate
   pages, repeat imports, restart monitoring and verify no cross-topic or repeated
   publication/notification and no bot/MCP delivery without explicit permission.


## Stage A verification checkpoint (PR #11)

A includes the metadata catalog for cloud users, groups, channels and forum topics,
explicit save/delete/recheck, stable chat/scope identity without required username,
schema9->10 preservation and restart, and isolation from public Bot/MCP adapters.
It does not include B's private/scoped message search/archive/CRM or C's monitoring
and discussion-thread processing. These must remain separate sequential PRs.

The failed macOS run 40155481458 exposed two architecture-independent UI issues:
ARM64's offline navigation test observed a late archive initialization overwrite
an edited limit; Intel's actual production smoke selected duplicate text spanning
the hidden cached Dashboard and active Projects screen. The fixes preserve drafts
and scope the smoke locator to the active screen, with an explicit Dashboard
hydration check to keep the duplicate-text scenario covered. No workflow test or
lifecycle assertion was removed; SQLite10 and native packaging checks had passed.

A's saved-list Refresh now retries the catalog, and native forum access loss (or a
forum ceasing to support topics) records UNAVAILABLE without reading topic content
or changing its identity. Known Telegram access-denial errors are handled without
publishing their raw text; transient/rate-limit and identity errors are not silently
reclassified. Recheck can recover availability after access returns.

Automated coverage at this checkpoint: 162 Kotlin tests, 61 offline browser tests,
29 Python safeguards and version/JAR/build checks. Manual Telegram account E2E
above is still not claimed. Catalog lists remain bounded samples of known chats;
secret/special topic chats are excluded, UNKNOWN is not a read grant, and no
private source is automatically subscribed or exported. The successful PR workflow links above verify the final functional SHA. They do
not imply that a later master workflow, tag, signed release or physical Mac test
has succeeded; check those separately.
