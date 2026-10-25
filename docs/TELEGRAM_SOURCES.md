# Telegram source series

## Scope and current support (PR A, 0.4.0)

This is the first of three sequential PRs. It establishes actual persistent source
identity, account-chat selection and migration, while preserving the working public
channel application. It does **not** claim the full all-chat processing goal is done.

| Source | Catalog/select/save/access recheck | Read/search/archive/CRM/monitoring |
| --- | --- | --- |
| Existing public channels added by username | Yes, migrated by existing chat ID | Existing functions preserved |
| New catalog-selected public/private channels | Yes | B/C pending; no implicit legacy subscription |
| Cloud personal chats, ordinary groups, public/private supergroups | Yes | B/C pending |
| Forum supergroups / individual topics | Whole group or native forumTopicId | B/C pending |
| Discussion/comment threads | Scope model reserved; saving explicitly rejected | C pending |
| Secret chats / direct-message special topic types | Excluded | Unsupported, no fallback to whole chat |

The UI marks new catalog sources as metadata-only and explains that reading,
projects and monitoring arrive in the next stages. No join, import, subscription,
message export or external AI call is caused by adding a source. Dashboard's source
metric explicitly counts the currently operational public search/project sources.

## Identity and native API

Canonical `telegram_sources` uniqueness is `(chat_id, scope_kind, scope_id)`.
`chatId` is TDLib Long; usernames are optional mutable metadata. An opaque stable
row key is only a local REST handle. Forum IDs are Int; thread IDs are Long.
`CHAT`, `FORUM_TOPIC` and `MESSAGE_THREAD` are distinct; they never convert into
each other. Publication identity remains `(chatId,messageId)` in existing tables;
B/C will share these rows and deduplicate overlapping whole-chat/topic processing.

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
read. Packaging/lifecycle smoke expects schema10 and refuses future schema11.

Before upgrading a real profile, stop its single backend and make an owner-only
backup of the complete profile/database including SQLite WAL/SHM consistency. Do
not copy an actively written SQLite file. The app migration is forward-only; old
binaries refuse schema10. Recovery means stopping the new app and restoring a
consistent pre-upgrade backup, never decrementing user_version or dropping tables.
Source metadata stays in `~/.hradar/data/research.sqlite`, with owner-only 600
file permissions inside existing 700 profile directories. No new credentials
store is introduced. Storage layout stays `~/.hradar`; no directory migration was added.

## Next PRs / manual E2E on owned test chats

B: typed source references in reads/search/archive and CRM; nullable usernames;
`GetChatHistory`, `SearchChatMessages(chatId, MessageTopic, ...)`, captions, bounded
pages, scoped archive cursors and source loss. `GetForumTopicHistory` uses actual
forumTopicId. No private data reaches bot/MCP merely because local processing works.

C: explicit local monitoring switches, cursor/restart/access-loss tests, shared
publication deduplication across whole groups/topics, comment/linked discussion
mapping via `GetMessageProperties.canGetMessageThread`, `GetMessageThread` and
`GetMessageThreadHistory`. Do not substitute a root message ID for forumTopicId.
External sharing needs a separately reviewed permission policy and actual access.

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
