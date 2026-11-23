# Telegram profiles (0.12)

## Three separate identities

API ID/hash identify the Telegram **application**, not the person. Shared saved API
settings are under `~/.hradar/config/telegram-api.json`0600. Complete usable TG_API_ID /
TG_API_HASH environment configuration takes precedence; the UI discloses its source,
never the hash. An account is authenticated by TDLib phone/code/2FA and identified by
GetMe's user ID. The optional Bot API token is a third identity, configured only after
user authentication and kept inside that local profile. No API app registration occurs.

The reported automatic old login was possible because the previous startup migration
copied `~/.recruitment-radar/tdlib` when present. Re-entering API settings did not erase
that retained Telegram authorization. Production0.12 startup does not run that implicit
legacy restore: it offers a separate, explicitly confirmed legacy-session import.

## Storage and ownership

```
~/.hradar/
  config/telegram-api.json       shared API settings
  profiles.json                 private registry, active UUID, verified identities
  profiles/<uuid>/
    tdlib/                      one account's native session/cache
    data/research.sqlite        that profile's existing schema14, including FTS5
    documents/                  managed original files
    config/telegram-bot.json    optional profile-local bot settings
    cache/avatars/              bounded normalized images
  backups/                      consistent migration backups
  current-profile-migrated.json retained-originals receipt
```

UUIDs are validated, names never form paths, directories0700/files0600, symlinks refused.
SQLite schema stays14; separate databases preserve existing sources, archive, documents,
FTS, projects/cards/notes, saved searches, cursors, matches/bookmarks, Bot/MCP policies.
The visual theme remains a common browser preference. Phone/code/2FA inputs are not saved.

GetMe is a gate before all workspace APIs, MCP and background workers. A previously
bound user ID cannot be changed, and a duplicate account cannot own two local profiles.
Existing migrated data remain quarantined until their retained session proves identity
and the owner explicitly confirms it. A new phone login cannot claim unidentified old
data. A previously proven owner can reauthenticate even if confirmation was pending;
confirmation is still required. A known bot owner mismatch also blocks the workspace.

## Switching, logout and failure

Switching rebuilds the entire service graph sequentially inside the process: freeze
routes/UI; stop requests/monitoring/imports/documents/Bot; await TDLib Closed after
[Close](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1close.html); close SQLite
and session lease; select target UUID; create the next gateway/services; verify GetMe.
There is no concurrent client or bot poller. A global application lease and retained
legacy-session lease protect against another cooperating hradar process. An active
login operation/document download rejects switching until it finishes/is cancelled.

Logout is explicitly confirmed and uses
[LogOut](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1log_out.html), with a
network connection and Closed acknowledgement. TDLib may remove its own session/cache;
recruiter SQLite, managed documents, archive, projects and shared API settings are
outside that directory and remain. Login must return to the same bound user ID.
An unbound duplicate imported session cannot be logged out through hradar, because a
copied auth key might also terminate the original account session; switch to its
already saved profile instead. Local-data deletion is intentionally unavailable here:
it requires a separate backup/confirmation workflow, never a side effect of logout.

Every accepted transition completes even if the response socket disconnects. A settings
write failure before acceptance leaves the previous profile usable. All resource owners
are closed independently; if any teardown/TDLib acknowledgement fails, the process
terminates with exclusivity held until OS cleanup. No second graph starts in that state.
The chosen profile never silently falls back to another identity.

A runtime generation rotates together with cookie/CSRF and is required on HTTP reads,
mutations, SSE and file/image URLs. Setup/status routes have narrow exceptions; Host/
Origin/cookie checks still apply. React blanks before transition, broadcasts to other
tabs and reloads, rejecting in-flight old responses/popups. Domain409 errors retain
normal retry behavior. API changes do not reuse an old React tree or cached file URLs.

Avatars use the same gateway, lazy metadata flags, two bounded active requests, small
files/pixel limits and normalized PNG. The private cache is profile-local and bounded;
missing/deleted/unsupported photos fall back to initials. Original native paths are never
returned. No external image service is used; avatar refresh follows activation/reload.

Bot polling begins only after verified/confirmed ownership. The previous bot closes
before a new graph starts; permissions/invitations/outbox remain in that profile's DB.
Global TG_BOT_TOKEN is deliberately not inherited by new profile graphs; configure a
bot explicitly for each profile. Migrated saved bot files retain their ownership and
settings. Reusing a token is explicit, with only one active consumer within hradar.
Other applications using that token remain an external coordination concern.

## Migration and recovery

Close the old app before migration; no real session is used by automatic tests.
A current0.11 workspace is copied once to a generated UUID. The migrator takes leases,
checks open files and space, validates paths, stages a private verified full backup,
then publishes a profile and registry atomically. Originals remain. SQLite main/WAL
are copied offline into private temporary input, native SQLite backup creates a
consistent standalone DB, and integrity/foreign-key/schema/FTS checks run on the copy.
SHM is guarded for open users but is not treated as committed content or copied as
portable coordination state. The source database is never opened for the backup read.

A pending manifest records source/backup fingerprints for idempotent restart after
interruption. A completion receipt detects subsequent writes by older versions to
retained originals. Missing/corrupt registry or profile directories cause refusal,
not a new implicit import. `~/.recruitment-radar/tdlib` is imported only by the explicit
owner action and has a separate one-time receipt. Resetting a fresh app root may
make the offer visible again; it never silently restores authorization.

Do not overwrite either workspace, delete sessions, remove receipts or lower
PRAGMA user_version to force startup. Stop all versions and restore the complete
matching backup/registry set in an isolated recovery location. Keep newer work and
originals separately for reconciliation; a backup predating new work cannot contain
it. If an old DB has no trustworthy retained session/owner, keep it quarantined and
add a fresh account rather than assigning it to whoever enters a phone next.

## Manual two-account check on one Mac

1. Start0.12 once. Verify the displayed GetMe account before confirming migrated data.
   Check existing archive/projects/documents/notes and optionally the saved bot. Do not
   approve if the name is unexpected; add a fresh profile instead.
2. Add accountB, confirm switching, enter its phone/code/2FA yourself. API settings
   should remain configured. Verify B's name/username/avatar, empty separate recruiter
   workspace and optional bot setup. No old token/grants should appear.
3. Search/save/index a synthetic document and create a project in B. Return to A
   without a new code, verify only A's data and bot; return to B and verify its data.
   Inspect open tabs, SSE, file URLs and any old browser notifications for stale data.
4. With B active, confirm Logout. Verify A remains selectable and B's local records
   remain. Log B back in as the same account, verify its records; a different user must
   be blocked. Repeat wrong-code/FLOOD_WAIT/offline behavior without excessive requests.
5. Cancel/finish a document transfer before switching; verify service/lease cleanup,
   restart, API-source indicator, missing avatar fallback and mobile keyboard controls.
   Real bot polling/phone delivery and physical photos require explicit manual evidence.

No cloud profiles, simultaneous accounts, API-app registration, AI/OCR changes,
installer publication or deletion workflow is part of0.12. CI outcomes are left for
the owner: per latest instruction the agent does not inspect/wait pipelines after PR.
