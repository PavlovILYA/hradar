# Workspace overview

The overview is read-only. Sources, projects and subscriptions are never created
by opening it. It uses the navigation/screens from PR #9 and the existing
notification-open/read operation. Telegram login remains separate and unchanged.

| Indicator | Existing data source | Meaning |
| --- | --- | --- |
| Active projects | `/api/projects?limit=3&offset=0&status=ACTIVE` | Exact SQL total; three most recently updated active projects |
| New matches | `/api/notifications?limit=3` | Exact unread notification total, not search-result or archived-message count |
| Latest matches | Same bounded notification page | Three recent notifications, including read records; source, template, local time and excerpt |
| Sources | `/api/channels` | Saved-channel metadata count |
| Monitoring | `/api/templates` plus saved sources and Telegram status | Active subscribed non-global templates; missing/deleted sources and disconnected Telegram reported separately |
| App status | `/api/telegram/status` plus the existing `/api/events` stream | Actual authorization/connection, successful reads and fresh event delivery |

No new REST endpoints, backend changes, database migrations or history scans.
There are five fixed parallel reads per overview refresh, not one per project or
notification. Existing parent workspace reads/event subscription remain shared
with other screens; the overview does not open a second event stream.

Refresh occurs on entry, manual retry, notification revision, returning to a
visible browser tab, and every 30 seconds while the overview is visible. Leaving
aborts outstanding requests. Per-card failures retain labelled previous values,
never invent zeroes. Snapshot freshness expires after 60 seconds; event freshness
after 15 seconds. The common Telegram header also expires old connection evidence.
The interface does not claim overall delivery health from a count alone.

Workspace guidance uses saved facts: source -> active project -> criteria with
existing project sources -> usable subscription. The criteria step refers
explicitly to the most recently updated active project, not all projects in the
workspace. Terms may be required, desired or excluded. A template subscription
is a separate setting from project criteria. Source/global-template exclusions
match existing monitoring semantics; no subscription is enabled automatically.
Guidance is below metrics and compact once a project exists; it disappears when
these steps are complete. Partial API failures suppress guidance until its facts
are known. Archived projects do not count as active hiring work.

Quick actions open real source/search/notification screens or the existing new
project form. Project previews can open their actual board; criteria guidance
opens that project's editor. Ordinary screen navigation preserves existing form
drafts. Opening a recent match invokes the existing handler to show the exact
publication and mark that notification read.

Verification is synthetic only. Real Telegram availability, OS notifications and
physical-Mac installation are separate checks. Counts indicate saved configuration
and observed service state, not proof of end-to-end Telegram delivery.
