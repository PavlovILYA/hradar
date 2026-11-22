# hradar 0.10 — recruiter workflow and QA

## Audit and information architecture

PR #18 was verified merged into master at `c879bb6`. Work starts from that commit
in `feat/ux-workflow-0.10`, never from the previous PR's branch. The original
checkout, untracked `hradar_stop.py`, installed application, session and real
SQLite were not used as test fixtures.

The primary navigation is Overview, Search, Saved searches, Matches, Saved
publications and Projects. Saved chat management and the explicit local archive
are under Local data; integration settings and application diagnostics remain
separate. Existing routes, SearchTemplate identifiers and public/local adapter
boundaries remain compatible. SQLite remains **v13**.

The audit found three functional failures beyond visual inconsistency:

- Legacy saved searches inherited the current UI search mode. Selected-source
  dispatch happened before the backend resolved the template. Template settings
  now determine scope/query independently of stale UI inputs, including global
  rules with old local-selection fields. Manual execution also works while tracking
  is paused; it does not activate the rule.
- An SSE event arriving during an in-flight desktop claim could be dropped. A
  deterministic delayed-response test reproduced the previous ARM64 failure
  (`Expected: 1, Received: 0`). Claims and inbox refreshes now drain pending events;
  older reads cannot overwrite a newer inbox read. Backend durable claims remain
  authoritative. No timeout increase or disabled checks is used.
- Adding a main-search/inbox/bookmarked local publication to a project previously
  required a search inside that project first. The project service now accepts
  trusted owner evidence from inbox/bookmarks/fresh bounded cache, checks the current
  exact scope/topic and revalidates the project atomically when adding. Another
  project's search evidence does not become globally available. Existing indexes
  support inbox/bookmark lookups; there is no migration or extra Telegram RPC.

## User workflow

1. **Search → Where**: browse/filter chats and forum topics, select up to ten scopes.
   The catalog scrolls within its panel; selected names stay visible. Selection
   saves metadata only and does not join chats or import history.
2. **What**: enter a Telegram query and optional AND/OR/NOT phrases, each on a new
   line. Back/next preserves parameters. A one-time search does not require saving.
3. **Results**: review compact excerpts, open details, save a publication or continue
   bounded pagination. Partial failures retain successful results; cancel does not
   replace the last complete page. Cache use is explicit and labelled.
4. **Save search**: confirm the name, tracking and this search's popup preference.
   The form shows text conditions and exposes global delivery and browser permission
   controls when needed. Successful saving offers the saved-search list; failed list
   refresh does not invite creating the same search again.
5. **Saved searches**: scope names, query/conditions, tracking and delivery settings;
   search now, configure, start/stop tracking, or confirm deletion. New chat rules
   own their scopes; legacy local pairs keep their public flags and checkpoints.
6. **Matches**: group the same `(chatId,messageId)` within the loaded pages and show
   all matching rules. Identical text alone never groups posts. Storage identity
   remains `(templateId,chatId,messageId)`; the unread badge counts matching records.
   Reading a grouped publication reads its loaded matching records individually.
7. **Publication details**: a shared modal opens over search, matches, overview,
   bookmarks, archive or project, preserves navigation and restores keyboard focus.
   Original links are requested on detail open, never guessed from IDs. Attachment
   metadata does not download files or expose local paths; unsupported/old snapshots
   explicitly show missing metadata. Technical IDs are under Additional information.
8. **Project**: choose scopes directly, add requirements, search, inspect publications,
   add to the board and edit stage/notes/rating/classification. It is a publication
   review board, not an inferred candidate profile. Main-search details can add
   existing owner evidence to an eligible active project; project selection is paged.

## Preserved limits

- Telegram query syntax is used for one-time Telegram search. Tracking checks the
  saved deterministic text conditions. If no positive AND/OR phrases are supplied,
  saving uses the entire query as an explicit required phrase; exclusions remain.
  This is shown before confirmation. No semantic AI matching is introduced.
- A fresh publication found through the legacy public-search API may need a search
  inside the target project before adding: that adapter does not populate owner cache.
  Existing saved public evidence remains usable. The default chat-search workflow
  supports direct additions from its bounded owner evidence.
- Local-chat project monitoring still requires a separately configured saved search.
  The project explains this limitation. Linked search results are not automatically
  converted to project cards. Legacy public project subscription behavior remains.
- Global popup opt-in, per-rule permission and browser permission remain distinct.
  Denial or disabling popup does not pause tracking or erase matches. Permission is
  requested only by the user's button. Browser preferences stay consistent across
  already-open sections.
- Native macOS Notification Center delivery with the browser closed is not implemented.
  Browser popup still requires an open tab and browser/macOS permission/Focus settings.
- Delivery claims remain at most one attempt per matching record. A crash after claim
  can lose popup delivery, never the saved match. Different matching rules may have
  distinct delivery attempts even though the inbox groups their publication visually.
- Ten explicit scopes, bounded pagination/FLOOD_WAIT and cancellation remain. Cache
  holds up to 50 pages/100 excerpts per page/24 hours, separate from durable archive,
  bookmarks, projects and notes. Clearing cache does not clear those data.

## Manual verification for 0.10

The user's real Intel/Telegram/Chrome findings apply to **0.9**, not a new 0.10 run.
Automated fake TDLib/SSE/browser checks are not proof of an actual Telegram post or
macOS notification. With a single authorized 0.10 backend:

1. Select hradar QA, search Java, continue pages and save a uniquely named query.
2. Explicitly enable tracking and popup/global/browser permissions. Personally post
   a fresh synthetic Java message, then a non-match. Check one appropriate saved
   match and an actual Chrome/macOS popup; inspect click behavior and original link.
3. Pause/resume and restart the one app. Check durable checkpoints, unread state,
   no reprocessed duplicate identities and no repeated delivery of the same claim.
4. Repeat with two equally named rules; inspect their scope and grouped publication.
5. Disable popup and deny permission: new matches should still be saved. Check Focus,
   permission changes and browser closing physically; closed-browser native delivery
   remains unsupported.
6. Open details from all sections, close with Escape and verify section/draft/focus
   retention. Save a post, clear only cache and verify bookmarks/archive/cards/notes.
7. Create a project, add a post from main search or matches, move it through stages,
   save notes/rating, refresh/restart and verify persistence. Inspect local monitoring
   guidance and public legacy subscription separately.
8. Check the 40px approved logo on a Retina display in both themes, Finder installation
   and actual installers for both architectures. Unsigned/ad-hoc smoke is not Developer
   ID/notarization or a physical ARM64 test.

Local automated outcomes are recorded in DEV_STATUS; exact-head Actions are linked
from the single Draft PR. Per the user's later instruction, Actions are inspected
once after push, without waiting or polling pipelines. Merge/Release/tags and
installer publication are not performed.
