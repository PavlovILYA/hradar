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

## Prereleases and reviewed tags

Use e.g. `appVersion=0.3.0-rc.1`, then sync/check and build the reviewed commit.
Its matching tag is `v0.3.0-rc.1`; the final release uses `0.3.0` / `v0.3.0` and a
later build number. Build metadata is supported, but should not be used to bypass
SemVer precedence or the increasing build-number rule.

Tags, pushes, signing and publication require explicit owner authorization. Once
a reviewed tag already exists, the manual promotion workflow verifies its exact
commit, the canonical version **at that commit**, signed build provenance,
checksums, architecture and equal build numbers. It never creates a tag and never
overwrites assets. Its publish switch creates a GitHub prerelease even for a
plain `vX.Y.Z` tag; promoting to a stable public release is a separate owner action.
No release/tag/publication is performed by version synchronization or normal CI.

Only after explicit release authorization, from the reviewed commit with a passing
version check (the tag target and canonical version must match):

```sh
HRADAR_VERSION=$(python3 -B scripts/version.py get)
git tag -a "v$HRADAR_VERSION" <reviewed-commit> -m "hradar $HRADAR_VERSION"
git push origin "v$HRADAR_VERSION"
```

These are manual release instructions, not commands to run during a normal PR.
