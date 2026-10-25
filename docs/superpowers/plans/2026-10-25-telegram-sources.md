# Telegram source series — design and dependency map

Base: master 6c94c94, merged PR #10. Canonical version 0.3.0 -> 0.4.0 for A.

A -> B -> C (sequential reviews; no automatic merge):

A: additive metadata catalog, chatId/scoped identity, account chat browser and
forum-topic selection, SQLite 9 -> 10 migration, explicit local-only boundary.
Legacy public source paths remain operational and synchronized. New catalog
entries do not enter legacy all-source subscriptions, bot menus, MCP, searches,
archives or CRM until those adapters gain scoped access checks in B/C.

B: typed SourceRef reads; chatId recent/history/search ports; topic-specific reads;
bounded archive cursors per scope sharing publication identity(chatId,messageId);
project source-reference migration and CRM integration. Public legacy REST remains
an adapter. No new private source data is exported to Bot/MCP.

C: explicit per-source monitoring switch; scoped topic/thread subscription routing,
durable cursor/restart/access-loss handling, overlap deduplication. Distinct local
notifications and external publication permission policy. Discussion threads use
TDLib-returned linked chat/thread identity, not invented t.me links/root IDs.

Dependency map:
TDLib metadata port -> catalog service -> canonical source repository -> local REST
-> Sources/browser UI. Legacy monitored_channels -> canonical migration/sync only.
B depends on source refs/access policy; C depends on B's scoped reads and durable
identity/cursors. Bot/MCP remain public-only unless separately designed consent.

Pinned TDLib bc9c263e: GetChats/LoadChats, SearchChats/SearchChatsOnServer, GetChat,
GetSupergroup/GetBasicGroup/GetUser, GetForumTopics/GetForumTopic. Forum topic IDs
are int; discussion messageThreadId is long; Message.topicId is a discriminated
MessageTopic. MessageTopicForum and MessageTopicThread are not interchangeable.
Future B: GetForumTopicHistory/GetMessageThreadHistory/SearchChatMessages(topicId).

A implementation checklist:
- [x] Fake metadata tests and true synthetic schema9 migration fixture before code.
- [x] Add strict scope/source DTOs, bounded metadata-only TDLib catalog, no message
      reads/joins, no serializing native Chat/ForumTopic containing lastMessage.
- [x] Transactional forward schema10; preserve every existing table/row/FK/cursor;
      sync legacy public save/delete by chat ID, no username-identity guessing.
- [x] Guard /api/sources and /api/source-catalog with existing launch cookie,
      exact local Host/Origin, no-store and mutation CSRF; reject client permissions.
- [x] Source browser with query/type filters, bounded cursor pagination, whole
      forum/topics, explicit save/remove, access and staging status, fake tests.
- [x] Keep public source search/archive/projects/subscriptions/Bot/MCP regression.
- [ ] Build/tests/version/secret checks; docs/recovery/E2E; commit/push/PR A.

A limits are explicit in UI/API capability fields. New entries are real persisted
metadata, not a claim that B/C processing already works. No real chats/user SQLite
or TDLib client is used for tests. Local preview must not migrate the user's running
profile; show synthetic source build separately if backend replacement is unsafe.
