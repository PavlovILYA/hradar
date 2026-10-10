# CI and pinned TDLib binaries

## Two independent workflows

- **Fast CI** (`.github/workflows/ci.yml`): every push and pull request to master.
  Frontend and backend are separate jobs without `needs`; one failure does not skip
  the other. Ubuntu 24.04, Node 24, JDK 21 and the checked-in Gradle wrapper are used.
  npm/Gradle caches accelerate checks but are not dependency sources.
- **Native ARM64** (`.github/workflows/macos-arm64.yml`): manual dispatch, or a push
  to master changing its workflow, TDLib configuration or listed native build scripts.
  React/Kotlin-only changes do not start this expensive workflow. macos-15 must
  report arm64; its source build uses CMake 3.31.8 and JDK 21. Toolchain-specific
  CMake/source caches are optional; a clean run checks out the same official SHA.
  Manual `use_cache=false` disables intermediate restoration. Job timeout is 100
  minutes; native compilation timeout is 80. Fast jobs have 15/20-minute timeouts.

Official actions are pinned to reviewed release commit SHAs. The Gradle version
and distribution checksum remain in the project's wrapper; no system-Gradle fallback.

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

**Initial state: backend Fast CI is intentionally blocked until the first successful
native build is published and reviewed.** `jarSha256` and `buildSourceDigest` in
`config/tdlib.json` are null, so the fetcher fails with explicit bootstrap instructions.
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

1. Push the reviewed CI commit to master yourself; the agent has not pushed it.
2. Start the new workflow on master (GitHub Actions UI or authenticated GitHub CLI):

   ```bash
   gh workflow run macos-arm64.yml --repo PavlovILYA/hradar --ref master
   gh run list --repo PavlovILYA/hradar --workflow macos-arm64.yml
   gh run watch <RUN_ID> --repo PavlovILYA/hradar --exit-status
   gh run download <RUN_ID> --repo PavlovILYA/hradar \
     --name hradar-tdlib-macos-arm64-bc9c263e2bfee06aaab41e82db51a103376030bc \
     --dir /tmp/hradar-tdlib-release
   ```

3. In that download directory, inspect metadata (arm64, expected TDLib SHA, master,
   correct run/build commit) and run `shasum -a 256 -c SHA256SUMS`. Verify provenance
   for both files, replacing `<BUILD_SHA>` with metadata `buildSourceDigest`:

   ```bash
   gh attestation verify tdlib-java.jar --repo PavlovILYA/hradar \
     --signer-workflow PavlovILYA/hradar/.github/workflows/macos-arm64.yml \
     --source-ref refs/heads/master --source-digest <BUILD_SHA> --deny-self-hosted-runners
   # Repeat the same verification for libtdjni.dylib.
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
