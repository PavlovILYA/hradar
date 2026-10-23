# Local storage and upgrade

The current layout is `~/.hradar/data/research.sqlite`, `~/.hradar/tdlib/`,
`~/.hradar/config/telegram-api.json`, plus `backups/` when migration is needed.
`logs/` is reserved for future local logs and is not created unnecessarily.
Directories containing account data are mode 0700; copied files and credentials are
mode 0600. The macOS app bundle and Gradle launcher use the same paths.

## First launch after upgrade

Stop the old application first. The launcher refuses an occupied port 8080 before
opening a database or starting TDLib. It also takes a migration lock, checks the
old session lock and open files with macOS `lsof`, and refuses conflicting new
files. It never moves an active SQLite database or TDLib directory.

If present, the startup migrator recognizes only these old locations:

| Previous location | New location |
| --- | --- |
| `~/.hradar/research.sqlite` and its `-wal`/`-shm` sidecars | `~/.hradar/data/research.sqlite` and sidecars |
| `~/.hradar/telegram-api.json` | `~/.hradar/config/telegram-api.json` |
| `~/.recruitment-radar/tdlib/` | `~/.hradar/tdlib/` |

It checks available space, copies source files into a private timestamp-free
`backups/migration-<id>/` directory, verifies every copy using SHA-256, and runs
SQLite integrity, foreign-key and supported-schema checks on the copied database.
The TDLib directory is copied recursively, including binlogs and internal SQLite
files. The source files are retained. Target files are staged and published by
atomic rename. A private completion marker records fingerprints of the old files:
normal changes to the new files are allowed, while later writes by an old binary to
the retained old files cause a clear startup refusal. Interrupted runs compare any
already published file with the source and refuse conflicts. A failed or cancelled migration leaves originals
and any completed backup in place. Do not remove old files until the migrated app
has been tested and the backup is known to be usable.

Unrecognized files outside the old TDLib directory, including old logs, temporary
files and backup copies, remain where they are. A startup warning reports extra
files under the old root or `~/.hradar`. They require manual review. There is no compatibility
symlink. The SQLite schema stays at v8; copying does not apply a new SQL migration.

If the app refuses startup, close the old app and inspect the error, disk space,
permissions, `backups/`, and both path sets. Resolve conflicting files by comparing
offline copies; do not overwrite either set or change `PRAGMA user_version` to force
a start. To roll back, stop the new app and restore the complete backed-up set
(SQLite main file together with WAL/SHM, TDLib directory, configuration) to the old
paths before starting the old version. Work added after migration will not appear
in that older snapshot. Do not restore individual WAL or TDLib files separately.

The application does not log in to Telegram during migration. An already active
TDLib session cannot be fully validated without starting TDLib, so the physical
upgrade check must confirm authorization status on both Intel and Apple Silicon.

## Name displayed by Telegram

The old Kotlin `deviceModel` was `Recruitment Radar (macOS)` and is now
`HRadar (macOS)`. TDLib's [initialization parameters](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1set_tdlib_parameters.html)
provide `device_model` and `application_version`, but no `application_name`.
Telegram's [authorization record](https://core.telegram.org/constructor/authorization)
has separate `api_id`, `app_name`, `device_model` and `app_version` fields. This
strongly suggests that the application title in a new-login notice can come from
the API application registered for that API ID, while the old local string can
also appear as the device label. Changing the Kotlin string or macOS bundle name
does not establish a change to Telegram's server-side application title.

The owner should log in to [my.telegram.org → API development tools](https://core.telegram.org/api/obtaining_api_id)
using the account that owns the existing API ID, inspect its displayed application
title and use an edit control **if one is offered** to set it to `hradar`/`HRadar`.
The public Telegram API documentation describes registering credentials but does
not document a supported method for programmatically editing the registered app
title. If that page offers no edit action, contact Telegram developer support
through its official channels. Keep the same API ID/hash and existing session;
do not register a replacement app or log out merely to change the title. The
actual server-side title and a fresh login notice remain unverified here.
