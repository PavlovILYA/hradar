# hradar 0.10 UX Workflow Implementation Plan

> For agentic workers: use superpowers:executing-plans for inline integration and dispatching-parallel-agents for independent components.

**Goal:** Implement the recruiter workflow from the user specification in one Draft PR.
**Architecture:** Existing owner-local services and SQLite v13; shared React dialog and design rules.
**Tech Stack:** Kotlin/JVM21, React19/TypeScript, CSS, Playwright, Python.
**Spec:** ../specs/2026-11-22-ux-workflow-design.md

## Global constraints
Preserve user data and root edits, one TDLib runtime, no dependency/runtime upgrades, no schema change unless demonstrated necessary, no merge/release/tags. Version appVersion 0.9.0 -> 0.10.0 once, sync/check.

## Review focus
- Template scope independent of previous UI mode, legacy all/selected/global/chats.
- Events during in-flight claim or fetch must schedule another read without duplicate delivery.
- Dialog async response cannot overwrite a later selection; focus returns to opener.
- Popup denial/global disabled does not affect saved matches or tracking.
- Local project sources must not masquerade as supported public project subscriptions.

## 1. Compatibility and correctness
- [ ] Add failing regression for saved-template dispatch, fix central service and UI request, run Kotlin tests.
- [ ] Reproduce delayed SSE claim/fetch, coalesce pending work, test reconnect/dedup/read.
- [ ] Commit focused fixes.

## 2. Search and saved searches
- [ ] Implement ChatSearch three panels preserving selection/query/results and bounded cancellation/pagination. Add optional AND/OR/NOT through supported DTOs only.
- [ ] Templates four actions, explicit save, scope labels, tracking/delivery guidance, delete confirmation, compatibility.
- [ ] Update/add offline workflow tests, run build and relevant Playwright, commit.

## 3. Shared details and navigation
- [ ] PublicationDetails props: message: ResearchMessage, onClose, onSelectSource; common onSelect callback for every section. Use native dialog, safe lazy original lookup, bookmark/project actions.
- [ ] Contextual dismissible errors; common status popover; overview metrics from unified inbox and local monitoring.
- [ ] Navigation terminology/local-data group, approved PNG asset, design tokens and responsive layout.
- [ ] Test context/focus, desktop/laptop/mobile and both themes; commit.

## 4. Projects
- [ ] Reuse ChatPicker in project creation; preserve legacy selections.
- [ ] Simplify discovery and board layout; local monitoring guidance points to saved searches and states actual limitation. Common details via onSelect prop.
- [ ] Exercise creation/search/card add/move/notes/rating, commit.

## 5. Integration and evidence
- [ ] Bump appVersion once and version.py sync/check.
- [ ] Run Kotlin build, frontend build/full offline Playwright, Python tests, desktop smoke with synthetic profile.
- [ ] Update DEV_STATUS with VERIFIED / NOT VERIFIED / NEXT ACTION and manual acceptance steps.
- [ ] Push feature and create one Draft PR with all 18 acceptance statuses. Inspect Fast CI and Intel/ARM64 at final SHA, fix failures, record exact outcomes.
