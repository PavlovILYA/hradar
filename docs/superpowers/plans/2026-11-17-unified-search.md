# Unified search and monitoring implementation plan

Goal: Search → save template → monitor → one local inbox, preserving external permissions.
Architecture: Add an owner-local application facade over catalog/read/monitoring services.
Keep legacy workers and external adapters isolated. SQLite forward migration adds a
local inbox projection, bounded search cache and durable desktop delivery claims.
Stack: existing Kotlin/Ktor/SQLite/React; no additional runtime dependency.
Spec: owner's task dated 2026-11-17.

- [x] Regression tests: local matching must appear in the shared notification service;
  overlap deduplicates, read state survives restart, no Bot/MCP effects.
- [x] Schema13 and inbox projection: backfill old matches without delivery, transactional
  insertion for both workers; preserve public adapters and synchronize reads.
- [x] Owner workflow: select catalog chats without a save step, bounded ten-scope search,
  automatic template subscription snapshots with atomic writes and race checks.
- [x] Bounded cache: only returned search pages, timestamp/TTL, explicit cache reads,
  clear only cache. No history jobs or external exposure.
- [x] UX: shared chat picker, templates with monitoring/notification controls, single inbox.
- [x] Desktop: durable at-most-once delivery claim, opt-in global setting, hidden text by
  default; evaluate existing macOS tray support, document unsupported click/delivery.
- [x] Run backend build, frontend build, offline browser tests, migration tests and review.
- [ ] Sync version0.9.0, update architecture/status/manual QA; commit/push/Draft PR;
  inspect exact-head CI. No merge/release/user-data cleanup.

Review focus: initialization failure must leave no half-active template; changed rules
or sources during reads must invalidate writes; overlapping whole-chat/topic/public
matches count once; cache clearing must preserve cards; disabled notifications must
never enqueue a delivery or leak local content into external adapters.


Evidence: final backend248/248, offline Playwright69/69, Python32/32 and production
frontend build pass. Read-only review findings fixed with regression coverage.
Ruling: native Notification Center adapter deferred under the owner's explicit
fallback allowance; browser requires permission and an open tab. Cost: closed-browser
native delivery is unavailable. Ruling: select-all snapshots up to ten visible scopes,
with explicit coverage; cost: it is not exhaustive all-dialog discovery. Ruling:
retain workers/public stores and project one local inbox; cost: compatibility stores
remain internally duplicated. Real source profile upgrade12→13 and health200 verified
with consistent private backup; fresh Telegram/OS notification QA remains manual.
