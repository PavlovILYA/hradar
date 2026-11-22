# hradar 0.10 UX workflow

The user's 20-part request is the acceptance specification. Recruiters select chats, search once, save the query, explicitly enable tracking and browser delivery, review publications and work with them in hiring projects.

## Audit and approach
PR #18 merged into c879bb6. Root checkout has an unrelated untracked hradar_stop.py; work is isolated in hradar-ux / feat/ux-workflow-0.10. Installed hradar is running and must not be restarted.

Preserve Kotlin application services, REST DTOs, bounded reads, owner-local policies and SQLite v13. Rebuilding backend domains would add migration/privacy risk without addressing the observed UI bugs; cosmetic-only edits would leave incorrect template dispatch and dropped delivery triggers. Use existing APIs with focused correctness fixes and shared React presentation.

Search: three persistent panels (chats, query/optional deterministic conditions, results), scroll-bounded catalog, ten-scope limit, explicit save form with tracking and delivery explanation. Saved searches show scope, query, tracking state and four actions. Legacy API/model names remain compatible.

Navigation: overview, search, saved searches, matches, saved publications, projects. Local data group contains saved chat management and explicit archive. Diagnostics holds SSE details. Native macOS delivery with browser closed remains out of scope.

Publication dialog: one accessible modal for search/inbox/overview/bookmarks/archive/projects, context preserved, original resolved lazily, no invented links, IDs under additional details, save/project actions where allowed. Project board preserves cards, stages, notes and ratings; expose local tracking limitations honestly.

Design: existing React/CSS only, shared typography/spacing/buttons/forms/status/error/dialog rules; consistent content width, compact excerpts, responsive grids. Approved PNG supplies a transparent Retina web asset without redesign.

Correctness: template execution owns its scope; coalesce events while delivery or inbox refresh is in flight; durable backend claim remains authority. Distinct template/chat/message identities remain intact; group only in presentation. Errors dismissible and scoped, reset on navigation, no false success on write failures.

## Evidence
Run offline Playwright, Kotlin fake-port/temporary database tests, Python safeguards, version sync/check, desktop smoke; inspect final SHA Fast CI and both macOS package jobs. Physical Telegram/Chrome/Focus/restart scenarios require new real evidence; prior 0.9 user findings are recorded as user-provided evidence only. No live sends, joins/imports, real-profile migrations, merge, release or tags.
