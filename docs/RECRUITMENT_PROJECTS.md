# Projects and publication review

The Projects tab keeps one hiring brief and one publication board per vacancy.
It stores evidence from actual saved sources, not inferred candidate identities.
No profile creation, outreach, joins, external models or extra Telegram client are involved.

## Manual walkthrough

1. Save an authorized public source in the existing Telegram search section.
   Archive search requires previously imported history; project creation never imports it.
2. Open **Проекты → Создать проект**. Name it `Senior Java Backend`, enter the vacancy
   description, select permitted Telegram sources, then required `Java, Spring Boot, PostgreSQL`,
   desired `Kafka, Docker` and exclusions such as `junior`. Optionally link existing templates.
3. Save. Open **Найти и добавить публикации**, choose local archive or Telegram and
   search `Java`. The shared matching rules require all required terms and reject
   exclusions. Desired terms are explained but do not gate results. Each step
   returns up to ten matches after checking at most five source pages (up to fifty
   publications) within a twenty-second fetch budget. “Продолжить поиск” resumes
   after the last completed source page. Zero matches with a continuation means
   this part of the search has no matches, not that no suitable publications exist.
   Check the actual required/desired words shown for each result; these are textual
   occurrences, not verified skills. Empty archive, missing sources, restrictive
   requirements, request failure, rate limiting and budget exhaustion have distinct hints.
4. Add a result to the board. You can also choose a saved publication or an existing
   match from a linked template. Clicking add again preserves the existing card.
5. In the card select a stage, add notes, choose an interest rating 0–5 and save.
   Stages: New, Interesting, Contacted, In progress, Rejected. All controls work on
   a phone without drag and drop. Change classification manually when a rule is wrong;
   the automatic reason remains visible. “Automatically” removes the override.
6. Filter by source or technology, search publication text and notes, or sort by latest
   changes, interest or creation time. Each column pages twenty cards independently.
   Technology filtering searches publication text, not claims inferred from notes.
7. Follow the saved original link. For archive publications without a link, explicitly
   request the original link through the existing Telegram endpoint. No TDLib message
   ID is converted into a guessed URL. This lookup needs an authorized Telegram client.
8. Reload or restart the application and reopen the project: cards, notes, stages,
   ratings, classification corrections and criteria persist in SQLite. Add the same
   publication to another project to verify its stage is independent.
9. Edit the criteria and archive the project. Archived projects are readable and card
   mutations are denied. Restore to Active before changing the board.

**Monitoring is explicit.** “Включить мониторинг по критериям” uses SubscriptionService
and creates or updates one stable SearchTemplate per project. Required/excluded terms
and sources stay synchronized with project edits. Archiving pauses this owned template;
restoring the project does not silently enable monitoring: press the monitoring button
or use the existing Templates controls. Linking an existing template does not change
its rules or enable it. Stored linked-template matches are available through the
project's discovery panel, with source checks before pagination. They are added to
Kanban manually; historical archive data is never silently imported into the board.
Deleting a project preserves original archive, saved decisions and monitoring history;
its unshared owned template is paused, not deleted.

## Local REST API

RecruitmentProjectService owns validation and delegates search to RecruiterService /
SearchService, rules to MatchingService, subscriptions to SubscriptionService. Routes
perform JDBC operations on Dispatchers.IO and enforce local Host/Origin (mutations
require an approved local Origin). JSON is typed, malformed requests return 400,
missing resources return 404; project responses use `Cache-Control: no-store`.

| Endpoint | Purpose |
| --- | --- |
| `GET /api/projects` | `limit` 1–50, `offset`, optional `status` ACTIVE/ARCHIVED, `query`; newest changes first |
| `POST /api/projects` | Create ProjectInput; returns 201 |
| `GET /api/projects/{id}` | Project with current template associations |
| `PUT /api/projects/{id}` | Replace validated criteria/status; preserve creation time and cards |
| `DELETE /api/projects/{id}` | Delete project/cards, preserve original publications |
| `GET /api/projects/{id}/cards` | `limit`, `offset`, optional `stage`, `source`, `query`, `technology`, `sort` updated/interest/oldest |
| `POST /api/projects/{id}/cards` | Existing publication identity `{channelId,messageId}`; idempotent add |
| `PUT /api/projects/{id}/cards/{card}` | `{stage,notes,interest,manualKind}`; project-bound lookup |
| `DELETE /api/projects/{id}/cards/{card}` | Remove project association only |
| `GET /api/projects/{id}/statistics` | Total and five stage counts |
| `POST /api/projects/{id}/search` | `{mode:archive/telegram,query,cursor}`; shared bounded search |
| `POST /api/projects/{id}/subscription` | Explicit idempotent monitoring setup |
| `GET /api/projects/{id}/matches` | Source-scoped linked-template matches, `limit` and `before` cursor |

ProjectInput contains `name`, `description`, `required`, `desired`, `excluded`,
`sources`, `templateIds`, `status`. At least one saved source is required. Name:
1–100 characters; description: up to 20,000; each term list: up to twenty 1–100
character terms; notes: up to 10,000. Project UUIDs are generated by the service.
Cards store immutable publication identity and text evidence with per-project state.
Normal card pages execute one count query and one bounded row query, with no per-card
publication/source/template lookups. Stable ID tie-breaks make equal-time sorts deterministic.
Offset pagination can shift under concurrent edits; reload the column after moving cards.

## Filtered search continuation

`POST /api/projects/{id}/search` returns ProjectSearchPage: `messages`, `cursor`,
`state`, `notice`, `scanned`, `matched`, `pagesScanned`, `limitReached`, `exhausted`,
`alreadyAdded`. Counts describe **unique publications checked in this response**,
returned by the underlying query; they are not totals for the entire channel/archive.
An empty source archive is checked with one SQL aggregate. Board membership is checked
with one bounded SQL query, not a request/query per result. Existing board cards remain
independent of search pagination. Matching evidence is saved transactionally.

Raw page size shrinks to the remaining result slots, so a matching publication is
never discarded beyond a full output page. Deduplication uses `(channelId,messageId)`
across the continuation chain. Stalled/cycling cursors and partial responses stop the
step immediately. 429/FLOOD_WAIT causes no automatic retry. Fetch timeouts return
completed matches and retain the last completed cursor; parent cancellation propagates.
The twenty-second budget bounds source fetches; final SQLite writes are separate.

Cursors are opaque random tokens bound to project snapshot, query and mode, with
10-minute TTL and a cache of at most 128 continuations. Restart, expiration, eviction
or changed criteria require a new search (400 with a readable hint); projects/cards
remain durable in SQLite. A continuation chain is capped at 10,000 unique inspected
publications to bound deduplication memory; reaching this cap asks for a narrower new
search. A successful full result page can still have a continuation. Search is a
bounded traversal, not a frozen snapshot of live Telegram: edits/new publications
can change the underlying results. Matching uses existing 4,000-character excerpts.

## Migration, recovery and retention

Schema **v8** adds `recruitment_projects`, `recruitment_templates`,
`recruitment_cards`, `recruitment_publications` and indexes for project/stage/source
queries. It does not rebuild or delete any v7 table. Each migration transaction
advances `user_version` only after the DDL succeeds. Project writes, association
replacement and owned subscription changes commit together; injected failure is tested
for rollback. Foreign keys cascade only project/card/template associations. Publication
identity is unique per project, allowing distinct stages across projects.

`recruitment_publications` retains bounded, explicitly requested online result pages
so an add operation resolves server-observed evidence and rejects arbitrary client text.
Archive card text comes from the local archive. Online/saved excerpts reuse the existing
RecruiterService 4,000-character limit; the original link is the source for complete text.
Removing a card/project does not erase the original archived/saved/search evidence.
There is no automated retention/export/profile/AI processing policy in this feature.

Before the first production v8 startup, stop the single backend and back up research.sqlite
using SQLite's backup mechanism or a stopped-database copy including its WAL if present.
Do not operate on the user's database during automated tests. An older application
rejects v8: rollback requires stopping the backend and restoring the pre-migration backup;
never downgrade `user_version` in place. Forward restart restores projects/cards from v8.
The separate Agent Bridge database is untouched.

## Automated verification

- `./gradlew :backend:build`: temporary databases and fake search ports, no account/JNI.
- `npm run build --prefix frontend`.
- `npm run test:ci --prefix frontend`: existing offline suite plus mocked Kanban scenario.
  If an existing frontend owns 5173, use `-- --config playwright.projects.config.ts`
  to run this checkout on 5175, with all APIs intercepted.
- `./scripts/test-recruitment-browser.sh`: same Kanban scenario against **real Ktor
  project routes and temporary SQLite**, with other APIs mocked. Requires installed
  frontend dependencies, matching TDLib Java JAR, JDK 21 toolchain, curl and lsof.
  Compiles in an isolated ignored directory so a running backend’s classes stay untouched.
  Starts a test fixture on loopback 18081 and a frontend on 5175, checks occupied ports,
  and stops owned fixture processes on exit. It never constructs TdlibGateway.

The integration browser scenario creates the Java project, skips thirty incomplete archive publications,
then traverses fake Telegram past fifty non-matches using the continuation, finds
the matching publication, adds a card, changes stage/notes/rating/classification, filters, reloads, changes stage
on a 390px viewport and archives. Backend tests separately reopen SQLite to verify
restart persistence and page 10,000 synthetic cards. Live Telegram search/notification
behavior remains a separate authorized operational check.
