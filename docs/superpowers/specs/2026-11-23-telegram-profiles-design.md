# Telegram profiles 0.12

User specification is authoritative. PR20 merged8583075, isolated feature worktree.
One active user client and bot, separate profile UUID/session/SQLite/documents/bot config;
API credentials remain global. No account_id retrofit, no SQLite schema bump needed.

Use sequential full service-graph restart in the process after explicit switch/add/logout:
freeze owner routes, stop all services, TDLib Close (LogOut only for explicit logout),
close DB/leases, then boot selected graph. A new runtime generation protects API, SSE,
file/image URLs. Cookie/CSRF rotate; frontend blanks old workspace before action and
reloads instead of retaining previous React state. Global application lease prevents
second process with another profile/port. Failure remains on chosen profile, no fallback.

GetMe identity is an access gate before workspace APIs and before workers/Bot/MCP start.
Bound profile UID cannot change; duplicate UID profiles reject binding. Existing unbound
legacy workspace can bind only to its already-authorized retained session, never a newly
entered different account. Explicit owner confirmation quarantines legacy data until then.
Logout keeps all local data and shared API settings, stops workers/bot before LogOut,
then restarts same bound profile for reauthentication. Local deletion is a separate
future operation, no delete endpoint in this release; UI explains retention/backups.

Current0.11 workspace migration: closed files/session, verified consistent backup,
private staged profile publication, atomic registry and resumable manifest, originals
retained. Existing documents/FTS14/bot grants remain together. Legacy external session
is offered for explicit import only, never automatic after reset/logout. Unknown owner
remains quarantined. Shared environment API config has precedence disclosed without hash.
Bot environment tokens must not automatically populate new profiles; existing migrated
saved configuration stays profile-local and owner rebinds are forbidden.

Account menu and accounts screen show GetMe name/username, active profile and connection,
IDs in details; API setup then account login then workspace, optional bot later. Avatars
use bounded owner-only lazy requests via same gateway, profile cache and safe image
normalization, fallback initials, no external services or raw paths.

Tests use fake TDLib/temp profiles only: A-B-A, expected identity mismatch, logout,
shutdown order, generation isolation, bot lifecycle, migrations14+FTS/recovery/legacy
nonresurrection, synthetic avatar limits, responsive UI and existing regressions.
Real source update follows tested migration + backup; no automated live login/logout.
One Draft PR,0.12 once; no merge/release/tags/publishing; CI snapshot once, no waiting.
