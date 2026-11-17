# Search, templates and local inbox

Design for 0.9.0: reuse the current catalog, SourceReadService and LocalMonitoringService
behind an owner-local workflow. Public tables/adapters remain the compatibility and
external-permission boundary. New chat templates never enter public subscriptions.

Search selection resolves metadata into internal source keys without asking the user
to manage sources. No join, history import or external permission is implied.
Choose up to ten chats/topics; “all” means the first ten supported entries in the
currently loaded catalog snapshot, displayed before enabling monitoring. More chats
remain available through catalog filtering/pagination. This is not an exhaustive
search of Telegram. Search uses existing bounded sequential pagination and explicit
partial/rate-limit states. Monitoring never silently expands as dialogs are added.

Template changes snapshot new heads before an atomic template/subscription write.
Failure leaves the previous template/subscriptions intact; stale observations are
rejected. Existing pairs retain checkpoints through pause/resume and restart.

Schema13 projects both match tables into a deduplicated owner-local inbox keyed by
(template, chat, message). Old rows are backfilled without desktop delivery. Shared
read actions synchronize both underlying records. Public Bot/MCP reads keep their
original repositories and allowlists; they never query the new inbox/cache.

Optional original links resolve when opening details; search pages do not spend their
budget on per-message link lookups. Actual FLOOD_WAIT deadlines share durable local
monitoring state.

Cache holds only already-returned search pages, clipped excerpts, at most fifty pages
and a twenty-four-hour TTL. Cache reads are explicit and show fetch time. Clearing
cache deletes only the cache table, never archives, favorites, project cards or notes.

Desktop delivery: global opt-in, per-template opt-out, hide all identifying notification
content by default. Claim before browser delivery persists at most one attempt across
multiple tabs/restarts. A crash after claim can lose a popup; the inbox remains durable.
No historical import/backfill delivers. Browser permission and an open tab are required.
Native Notification Center delivery while the browser is closed remains a separate
integration: the existing Java21 AWT launcher is not sufficient (CTrayIcon uses an
AWT popup; it does not implement native permission/delegate/click lifecycle).
Do not claim native delivery from fake/browser tests. Avoid an osascript workaround
with another identity and no reliable click routing. A future small bundled native
UserNotifications adapter needs packaging, permission, click/deep-link and physical-Mac QA.

Migration recovery: quit hradar and retain an owner-only consistent pre-upgrade backup
before upgrading the real profile. Migration is forward-only and transactional.
To use an older binary, stop the new process and restore the backup; do not downgrade
user_version or run old/new clients concurrently. New inbox/cache/settings are local.

Manual QA on hradar QA: choose the group directly in Search, search Java, save a Java
AND template with that group and monitoring enabled. Publish a fresh synthetic message
personally. Expect one unread match, no second source toggle. Publish a non-match;
verify no event. Enable global browser notifications and permission, publish another
fresh match, verify one popup and click navigation. Disable the template's notifications
and repeat; inbox still records, popup does not. Pause/resume and restart once, verify
read state/checkpoints and no repeat popup. Check macOS/browser permission denial.
Native closed-browser delivery is not implemented. Never use real candidate content
for automated QA or send it through Bot/MCP.

Review decisions: keep the application facade and existing workers, retain ten-scope
bounds, and implement browser delivery with honest native limitations rather than
an unverified platform bridge. Legacy local-only rules keep their public subscription
flag false when edited; existing local enablement is retained, and active pauses it.
New chat rules are excluded from Bot subscriptions/callbacks and MCP rule lists.
A mixed legacy rule retains its established public delivery scope; owner-local
subscriptions do not expand it.
