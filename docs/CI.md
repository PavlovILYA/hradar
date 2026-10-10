# CI and pinned TDLib binaries

## Two independent workflows

- **Fast CI** (`.github/workflows/ci.yml`): pushes to master and
  ci/tdlib-bootstrap, pull requests to master, and manual dispatch.
  The bootstrap branch can be verified without merging or modifying master.
  Frontend and backend are separate jobs without `needs`; one failure does not skip
  the other. Ubuntu 24.04, Node 24, JDK 21 and the checked-in Gradle wrapper are used.
  npm/Gradle caches accelerate checks but are not dependency sources.
- **Native ARM64** (`.github/workflows/macos-arm64.yml`): manual dispatch, or a push
  to master changing its workflow, TDLib configuration or listed native build scripts.
  React/Kotlin-only changes do not start this expensive workflow. macos-15 must
  report arm64; its source build uses CMake 3.31.10 and JDK 21.
  The CMake wheel is pinned to 3.31.10; the effective PATH executable and exact
  version are checked before building and included in the toolchain cache identity.
  Toolchain-specific CMake/source caches are optional; a clean run checks out the same official SHA.
  Manual `use_cache=false` disables intermediate restoration. Job timeout is 100
  minutes; native compilation timeout is 80. Fast jobs have 15/20-minute timeouts.

Official actions are pinned to reviewed release commit SHAs. The Gradle version
and distribution checksum remain in the project's wrapper; no system-Gradle fallback.

## Latest verified runs (2026-10-11)

[Native run 38045478478](https://github.com/PavlovILYA/hradar/actions/runs/38045478478)
completed successfully on master at `ce6363e9da5d1d7bf2069241f8738ad4303b18ce`.
GitHub job steps confirm ARM64 architecture, JNI smoke verification, Kotlin build,
build provenance attestation and artifact upload all passed. Artifact
`11667860623` is not expired (retained until 2027-01-08). Do not rebuild TDLib
to complete this bootstrap; download and independently verify these outputs.

[Fast run 38050957209](https://github.com/PavlovILYA/hradar/actions/runs/38050957209)
at `ae7b3227cf82aeb870c09f3041b3d29b093a5474` passed frontend build and offline tests.
Backend exited 1 at the bootstrap guard, before Gradle compilation or tests.
The bootstrap branch run [38093744547](https://github.com/PavlovILYA/hradar/actions/runs/38093744547)
also passed frontend and stopped backend at the same guard before pinning.
The GitHub Releases API returned no releases and the proposed v1 tag is absent.
Publication requires explicit maintainer confirmation.

The successful run's downloaded binaries were independently verified locally:
`SHA256SUMS` and metadata match both files; `gh attestation verify` exited 0 for
each with the exact repository, signer workflow, master ref, build commit and
`--deny-self-hosted-runners`. Verified certificate identities also name run
38045478478, GitHub-hosted execution and that build commit. The JAR identity/classes
check and ARM64 `lipo` check passed. Schema and original/patched Java CMake hashes
match the pinned official upstream sources. `otool -L` confirms external JDK 21
and Homebrew OpenSSL dependencies; the dylib was not loaded on the Intel host.

| Asset | Verified SHA256 |
| --- | --- |
| `tdlib-java.jar` | `f94f26b5ff60d254c51ce728079b600aa6c76b80cca009aa2969f81c6972adad` |
| `libtdjni.dylib` | `d2a124be93e6a9e5000a8e8ca6a2826991cbe68145d2a21db9f2e4d3dbd19dfa` |

`config/tdlib.json` now pins the verified JAR hash and build commit. The Release
download/fetch path and green backend Fast CI remain pending publication and
push of the pinning commit. No TDLib rebuild is needed.
Local JDK 21 `./gradlew :backend:build --no-daemon --console=plain` with this
verified JAR exited 0: 56 tests, zero failures/errors/skips, no JNI or Telegram
credentials/session. This local result does not establish green GitHub Fast CI.

## Previous failure diagnosis

Commit `4aabd8249`: [native run 38045009513](https://github.com/PavlovILYA/hradar/actions/runs/38045009513)
failed before TDLib compilation: pip could not find cmake==3.31.8; its index listed
3.31.10. The replacement uses the available pinned binary wheel, without changing
the TDLib SHA. PyPI provides a macOS universal2 wheel; a local temporary-venv
installation returned `cmake version 3.31.10`. An old local pip 19.2.3 did not
recognize that wheel; upgrading pip inside the verification venv resolved it.
The workflow uses setup-python 3.12 and checks the installed executable explicitly.
[Fast run 38045009526](https://github.com/PavlovILYA/hradar/actions/runs/38045009526)
passed the frontend job; backend stopped at the intentional bootstrap guard.
No checksum/provenance checks are bypassed and no placeholder hashes are acceptable.
The corrected native workflow succeeded in run 38045478478 above.

## Failure and compatibility fix

At official TDLib commit `bc9c263e2bfee06aaab41e82db51a103376030bc`, the TL schema
contains `replyMarkupInlineKeyboard rows:... force_reply:Bool`, generating a
Java constructor `(InlineKeyboardButton[][], boolean)`. The same commit's
`Example.java:296` still passes only the rows. This is upstream demonstration/API
source drift, not an ARM compiler problem. See the pinned
[schema](https://github.com/tdlib/td/blob/bc9c263e2bfee06aaab41e82db51a103376030bc/td/generate/scheme/td_api.tl)
and [example](https://github.com/tdlib/td/blob/bc9c263e2bfee06aaab41e82db51a103376030bc/example/java/org/drinkless/tdlib/example/Example.java).

Upstream CMake has no switch to disable the demonstration. `prepare-tdlib-java.py`
removes only `${JAVA_EXAMPLE_FILES}` from its javac command in the ignored third-party
checkout. Client.java, generated TdApi.java, API generation, Javadoc, JNI linking and
installation remain enabled. An exact pinned CMake SHA-256 is required before the
patch; the exact patched variant is accepted on retries. Any unexpected source change
fails before expensive native compilation. No hradar application source is patched.
The TDLib SHA is unchanged and centralized in `config/tdlib.json`.

Native checks require TdApi/Client classes, embedded commit identity, absence of
example classes, ARM64 `lipo` verification (input file precedes `-verify_arch`),
`otool -L`, and an actual JVM library load plus synchronous `GetTextEntities` call.
The smoke check creates no account client or session and needs no Telegram credentials.
The dylib depends on platform libraries/Homebrew OpenSSL and JDK 21; it is not a
self-contained distribution for arbitrary Macs. Intel local builds remain supported
with explicit host architecture and separate commit/architecture build directories.

## Fast CI dependency and initial bootstrap

**Backend Fast CI remains blocked until the verified native build is published.**
The bootstrap branch now has reviewed `jarSha256` and `buildSourceDigest` values
in `config/tdlib.json`; previous commits have null values and fail with explicit
bootstrap instructions. The new configuration requires the permanent Release.
No dummy JAR, skipped Kotlin tests or expired-run artifact fallback is used. The
frontend job still runs independently.

After bootstrap, `fetch-tdlib-java.py` downloads only `tdlib-java.jar` from the exact
versioned **PavlovILYA/hradar GitHub Release**, verifies the committed SHA-256, then
verifies GitHub build attestation: repository, native signer workflow, master ref,
recorded build source commit, and GitHub-hosted runner. Finally it checks embedded
TDLib commit and required classes before atomic installation. JNI is never downloaded
for Fast CI. The backend compilation/unit tests require only generated Java types;
`checkTdlib` still requires JNI for actually running the app.

Native workflow uploads the flat directory `.tdlib-build/release/` under artifact
name `hradar-tdlib-macos-arm64-<full TDLib SHA>` (90-day retention):
`tdlib-java.jar`, `libtdjni.dylib`, `SHA256SUMS`, `tdlib-artifact.json`.
Metadata records pinned source, schema/patch checksums, architecture, build commit
and run URL. Both binaries receive build provenance attestations. A provenance failure
still allows diagnostic artifact upload, but the workflow fails and those artifacts
must not be promoted. Only a completely successful run is eligible for publication.
Fast CI uses permanent Release assets, never these expiring uploads.

First publication is a **manual maintainer action**, not automatic tag/release overwrite:

1. The native CI commit is already on master and run 38045478478 succeeded.
   Reuse its artifacts for this bootstrap. The agent works only on
   ci/tdlib-bootstrap; pushing that branch requires maintainer authorization.
2. For a future native source/toolchain update, start a new workflow on master
   (GitHub Actions UI or authenticated GitHub CLI). For the current bootstrap,
   skip dispatch/watch and use `RUN_ID=38045478478` for download:

   ```bash
   gh workflow run macos-arm64.yml --repo PavlovILYA/hradar --ref master
   gh run list --repo PavlovILYA/hradar --workflow macos-arm64.yml
   gh run watch <RUN_ID> --repo PavlovILYA/hradar --exit-status
   gh run download <RUN_ID> --repo PavlovILYA/hradar \
     --name hradar-tdlib-macos-arm64-bc9c263e2bfee06aaab41e82db51a103376030bc \
     --dir /tmp/hradar-tdlib-release
   ```

3. In that download directory, inspect metadata (arm64, expected TDLib SHA, master,
   correct run/build commit). Run `cd /tmp/hradar-tdlib-release` before all following
   checksum, attestation and publication commands, then run `shasum -a 256 -c SHA256SUMS`. Verify provenance
   for both files, replacing `<BUILD_SHA>` with metadata `buildSourceDigest`:

   ```bash
   gh attestation verify tdlib-java.jar --repo PavlovILYA/hradar \
     --signer-workflow PavlovILYA/hradar/.github/workflows/macos-arm64.yml \
     --source-ref refs/heads/master --source-digest <BUILD_SHA> --deny-self-hosted-runners
   gh attestation verify libtdjni.dylib --repo PavlovILYA/hradar \
     --signer-workflow PavlovILYA/hradar/.github/workflows/macos-arm64.yml \
     --source-ref refs/heads/master --source-digest <BUILD_SHA> --deny-self-hosted-runners
   ```

4. Ensure the chosen tag/release does not exist. Publish a new release targeted at
   that verified build commit, without force or clobber flags:

   ```bash
   gh release create tdlib-bc9c263e2bfee06aaab41e82db51a103376030bc-v1 \
     tdlib-java.jar libtdjni.dylib SHA256SUMS tdlib-artifact.json \
     --repo PavlovILYA/hradar --target <BUILD_SHA> \
     --title 'Pinned TDLib macOS ARM64 v1' --notes 'Verified native workflow; see artifact metadata.'
   ```

5. Copy metadata `assets["tdlib-java.jar"]` into `jarSha256` and `buildSourceDigest`
   into the corresponding config field. Review and commit those public checksums
   separately, then push the bootstrap configuration. Backend Fast CI can now build.
   Run `python3 -B scripts/fetch-tdlib-java.py` with authenticated `gh` to check the
   exact download/provenance path. Release publication and this fetch have not yet
   been verified against a real successful ARM64 run.

Do not rerun the original failing run expecting new code: GitHub reruns use the old
commit. After fixing/pushing code, dispatch a **new** run on master. For a clean retry:

```bash
gh workflow run macos-arm64.yml --repo PavlovILYA/hradar --ref master -f use_cache=false
```

## Tests and local builds

Frontend `npm run test:ci --prefix frontend` runs 13 synthetic browser tests. Every
API request is intercepted; unexpected requests fail the test. No backend, SQLite,
credentials or Telegram session is needed. Six tests tagged `@telegram` cover real
history, missing-channel handling, template CRUD, Telegram search, local archive and
backend SSE; run separately with `npm run test:telegram --prefix frontend` against
one authorized local backend and a populated @telegram test archive. `npm test` keeps
both groups for full local regression. Live tests never belong to Fast CI.

Backend `./gradlew :backend:build` uses fake gateways and temporary test databases;
no app startup or Telegram login. Native `checkTdlib` is enforced by `:backend:run`.
Stop the running backend before recompiling its classes. The Java-only build was
also checked in an isolated checkout without `.env`, JNI or account credentials.

```bash
python3 -B -m unittest discover -s tests -v
bash -n scripts/build-tdlib-macos.sh scripts/verify-tdlib-macos.sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :backend:build
npm ci --prefix frontend
npm run build --prefix frontend
npm run test:ci --prefix frontend
```

A local TDLib checkout at another SHA is preserved and rejected. For a fresh pinned
Intel/ARM build choose a new checkout path, e.g.
`TDLIB_SOURCE_DIR="$PWD/.tdlib-build/td-pinned" bash scripts/build-tdlib-macos.sh`.
Do not delete your existing native artifacts/session to retry CI.

To update TDLib: explicitly review a new official SHA, check schema compatibility,
update `commit` and the reviewed original Java CMake hash, adjust/review the minimal
patch if needed, reset binary hashes to null and choose a **new** release tag. Run the
native workflow, verify artifacts/attestation and manually publish/pin them as above.
Old releases/tags remain intact. Changing only frontend/Kotlin code requires neither
TDLib version changes nor a native rebuild.
