# Stage B universal local Telegram sources implementation plan

Goal: selected chat/topic reading, search, explicit durable import and recruitment CRM without expanding public adapters.
Spec: user's Stage B acceptance criteria and docs/TELEGRAM_SOURCES.md.

## Constraints and decisions
- Worktree hradar-sources-b at origin/master 565edd8; preserve running hradar-sources/profile.
- Kotlin/JVM21, pinned Java TDLib, one gateway; no native rebuild or live-account tests.
- Source keys resolve saved chatId + SourceScope. Forum topic Int uses MessageTopicForum and GetForumTopicHistory.
- Preserve legacy username APIs, repo.channels(), public recruiter/Bot/MCP behavior. New local bodies must not enter public archive/read adapters.
- Additive SQLite11 local archive publications keyed (chatId,messageId), scoped membership and durable jobs; leave legacy rows intact.
- ResearchMessage gains optional sourceKey/forumTopicId/scopeTitle and availability. ProjectInput gains sourceKeys, preserving sources.
- 0.5.0 via version.py; no tag/merge. Commit/push/PR explicitly authorized.

## Tasks
- [x] Native local read/search service and fake RPC tests: per-type reads, correct topic APIs, inclusive/short pages, duplicates, access/errors/rate/cancellation, deleted detail, safe links. Add SearchRequest.sourceKeys and local selected mode; leave global behavior intact.
- [x] SQLite11 and durable source imports: isolated shared local publication table, scoped membership, atomic checkpoint/cancel/restart/retry/dedup; preserve v10/legacy rows; fake tests and migration recovery.
- [x] Local CRM: retain legacy sources and add selected keys; local search/archive port never broadens RecruiterService; validate project/source scopes and evidence, preserve saved cards; reject local monitoring pending C.
- [x] UI: selected source recent/search/archive and project source keys, honest capabilities/topic labels/details; offline browser workflow and regressions.
- [ ] Verify backend build, frontend build/offline browsers, Python safeguards/version; review privacy and final diff. Update architecture/source guide/status and detailed handoff, commit/push/open master PR and inspect both Fast/macOS CI.

## Review focus
- Whole chat/topic overlap must not duplicate publications or allow topic evidence in another scope.
- Removal during suspended reads/writes must not recreate sources or persist revoked evidence.
- Shared Bot/MCP routes must not acquire local data through archive/publication fallbacks.
- Native short pages must preserve continuation; failed pages must not advance cursor.
- Existing CRM source lists/cards and schema10 content must survive reopen unchanged.
