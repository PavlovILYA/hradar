# Dashboard implementation plan

Goal: a factual recruiter overview and workspace setup guidance, preserving existing domain flows.
Architecture: separate Dashboard + useDashboard hook, existing read-only HTTP APIs, existing typed Screen navigation. No backend/auth/schema/monitoring changes or new dependencies.

Data inventory:
- GET /api/projects?limit=3&offset=0&status=ACTIVE: exact active total, three most recently updated projects and criteria. Setup criteria refer explicitly to the most recent active project, never all projects.
- GET /api/channels and /api/templates: saved metadata only; active subscriptions are active+subscribed, non-global templates. Source availability is reported separately.
- GET /api/notifications?limit=3: exact unread total and three recent records, not message-search counts. Opening uses existing openNotification/read behavior.
- GET /api/telegram/status: actual authorization/connection. Existing parent SSE connectivity is passed down; no second event stream.

- [x] Add offline synthetic Playwright journeys; demonstrate failure against placeholder.
- [x] Add useDashboard: parallel bounded reads, per-card errors, timestamps/stale indicator, no overlapping refresh, abort on leaving, refresh on entry/visibility and every 30s while visible. Never render unknown data as zero.
- [x] Add Dashboard/cards/setup/list/actions and responsive themed CSS. Source -> active project -> criteria for latest project -> valid subscription; steps derived only from API. Compact guidance when projects already exist; no writes on visit.
- [x] Connect Dashboard with existing navigation/notification opening and project selection intent. Preserve drafts; do not replace openNotification/read semantics.
- [x] Bump canonical appVersion to 0.3.0; sync/check; run build, all offline browsers, backend tests, Python/version checks and secret scan.
- [ ] Update DEV_STATUS; review staged diff, commit, scan before push, create PR to master. No merge/tag/release or real-session work.

Review focus: stale green status, partial failures, no-data vs unknown, deleted-source dependencies, active vs archived projects, read correctness, cancellation/rapid navigation, no N+1 or history reads.
