# Application versions

`appVersion` in root `gradle.properties` is the source of truth. The navigation
feature introduces **0.2.0**. Application versions follow SemVer 2.0.0; SQLite's
schema version (currently 9), pinned TDLib revision and JDK version are independent.

- PATCH: compatible bug fixes, for example `0.2.0` → `0.2.1`.
- MINOR: compatible functionality, for example `0.2.1` → `0.3.0`.
- MAJOR: incompatible supported behavior/API changes after 1.0.
- Before 1.0, ask the owner to choose the version for an incompatible change;
  do not silently assume either a minor bump or 1.0.

Examples for hradar: fixing a login countdown, a copy button or a small CSS issue
is PATCH. New navigation sections, web bot configuration or a new archive workflow
are MINOR. Incompatible REST payloads, storage formats requiring manual migration
or supported behavior requiring client updates are MAJOR after 1.0. Before 1.0,
keep MAJOR at 0 and document such incompatibilities for a separate owner decision.
Increasing MINOR resets PATCH to 0; increasing MAJOR resets MINOR and PATCH to 0.

Bump once per PR that changes application behavior, including UI behavior. Multiple
commits and fixes within that PR use the same version. Documentation-only,
test-only and CI-only changes do not require a bump. The intended bump belongs in
the PR description. Do not modify historical release versions or tags.

After editing the canonical value, run:

```sh
python3 -B scripts/version.py sync
python3 -B scripts/version.py check
```

`sync` updates `frontend/package.json` and the root package entries in its lockfile,
without changing dependencies. CI, standalone backend resource processing and the
backend `check` task reject drift. The frontend imports its package version. Gradle
names the JAR `backend-<appVersion>.jar` and generates
`hradar-version.properties`; TDLib client metadata and the MCP server read that
resource. Package scripts derive filenames and distribution metadata from the
same source and reject JAR, plist and metadata version mismatches.

## macOS versions and build numbers

SemVer may include `-rc.1` and `+build.2`. These are preserved in application/JAR,
ZIP/DMG names and `distribution.json.version`. `CFBundleShortVersionString` uses
only numeric `MAJOR.MINOR.PATCH`; macOS version fields cannot represent SemVer
suffixes. A prerelease therefore shares its short version with the final release.

`CFBundleVersion` is an independent, increasing build counter. Apple limits its
three numeric components to 4, 2 and 2 digits, with a positive first component.
The allocator encodes UTC minutes since 2020-01-01 as
`(index / 10000 + 1).(index / 100 % 100).(index % 100)` using integer division.
It rejects overflow rather than wrapping (the horizon is around 2210). jpackage
receives this positive build version; packaging then sets the semantic short
version explicitly. `distribution.json` records both as `macBundleVersion` and
`macShortVersion`.

Local packaging allocates from the clock and `.tdlib-build/build-number` under a
file lock, using `max(clock, previous + 1)`. It handles concurrent allocations,
repeated builds within one minute and clock rollback on that working copy. Keep
this ignored counter to preserve local monotonicity. Copying/deleting counters,
using unrelated machines or an artificial future clock prevents a strict global
ordering; no global allocator or external service is introduced.

The installer workflow serializes **all refs** with one concurrency group and
allocates one number for both architectures, reused during signing and smoke
verification. Hosted runs normally last many minutes, so later completed workflow
runs receive later clock values. Fresh runners have no previous local counter:
rapidly canceled/retried allocations within the same minute can coincide. A CI
rerun of only failed jobs can also reuse the original allocation. This is a
per-distribution identity, not a guarantee of uniqueness across arbitrary local
and CI builds. For official distributions, run one full installer workflow at a
time, at least a minute after the preceding allocation; rerun the entire workflow
when a new build identity is required. Never publish two different builds with the
same version and build number. If strict global allocation becomes a release
requirement, use a durable centrally serialized counter before publication.

For controlled rebuilds, `HRADAR_BUILD_NUMBER` supplies the previously allocated
number; it must meet the platform syntax. Do not lower a previously published
build number. Verification compares that value against the packaged plist and
`distribution.json`, including both architectures before release promotion.

## Prereleases and automatic tags

Prereleases such as `0.4.0-rc.1` are opt-in: update the canonical value and sync/check.
SemVer suffixes remain in application/JAR/distribution metadata; Apple short versions
contain the numeric core. Automatic stable tags skip prerelease and build-metadata
versions. Prerelease tagging needs a separately reviewed owner decision.

The existing **Fast CI** workflow creates annotated `vMAJOR.MINOR.PATCH` tags after
successful **master push** checks, including frontend/offline Playwright, Kotlin/unit
and Python checks, and the existing dual-architecture macOS installer workflow
(reused with `workflow_call`, not copied). PR events, feature pushes and manual runs
cannot create automatic tags. No release, signing or asset publication is triggered.
The existing release-promotion workflow remains manual and separately protected.

`tag-version` checks out the exact tested `github.sha`, with credentials persistence
disabled, and uses only `GITHUB_TOKEN`. Only this new job has `contents: write`;
`pull-requests: read` verifies that a merged PR into this repository's master has
that exact merge SHA. The CI master concurrency key includes SHA, so a later merge
does not cancel an earlier verification. Tag races use create-only Git references;
there is no force update, delete or moving-master-HEAD lookup.

The version is compared with the immutable `before` SHA from the triggering
master push payload (validated as an ancestor of its exact `after`/tested SHA).
This supports merge, squash and rebase without trusting an older PR base SHA. If unchanged,
including documentation/CI-only PRs, no tag is created and an older version tag is
left intact. For a changed stable version, an existing tag peeled to the same SHA
is a no-op; one pointing elsewhere fails the workflow. A new annotated tag object
and its reference both target the verified SHA. Concurrent creation of the same
reference is accepted only after verifying its target. Failed, canceled or skipped
required jobs do not tag; rerun the complete master workflow when needed.

Never manually create/move a stable version tag during the normal release process.
Missing historical tags are not backfilled. Do not reuse a published version for
new behavior. Tagging does not bump the version and does not create GitHub Releases.

For a real release: obtain owner authorization, identify the successful exact-SHA
installer run and CI-created tag, then use the existing manual signed-installer
promotion workflow. It verifies tag/commit, canonical version, build provenance,
checksums, architecture and matching build numbers. Its publication switch creates
a GitHub prerelease; stable promotion is a separate owner action. No manual tag
command is part of ordinary PR completion.
