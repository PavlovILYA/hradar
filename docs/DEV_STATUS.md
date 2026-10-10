# hradar — Development status / session handoff

Verified 2026-10-10. Current milestone: TDLib build correction and independent CI workflows.
Previous completed MCP commit: `0303568`; its detailed handoff remains in Git history and [MCP.md](MCP.md).
Working tree was clean at task start; no checkpoint needed. No git push.

## VERIFIED
- Pinned official TDLib SHA remains `bc9c263e2bfee06aaab41e82db51a103376030bc`. Its Example.java calls the one-argument inline-keyboard constructor, while its schema generates the additional `forceReply` boolean. Upstream CMake has no switch to exclude the demonstration. A minimal exact-hash-checked, idempotent checkout patch removes only Example.java from javac inputs; Client.java, generated TdApi.java, JNI targets and checks remain enabled. Applied to the actual pinned CMake in a temporary fixture.
- Fast CI (`ci.yml`): independent frontend/backend jobs on push/PR to master. Native ARM64 CI: manual or changes to its workflow, native build configuration/scripts and TDLib pin. Actions are SHA-pinned; Gradle wrapper, dependency caches and timeouts are configured. Native outputs include manifest source identity, checksums and GitHub attestations; Release publication is manual.
- Fast CI fetches only the Java JAR from a permanent, versioned project Release, verifying SHA256, signer workflow, master source commit, hosted-runner provenance and manifest TDLib SHA. It never downloads JNI. Bootstrap deliberately fails before network access until reviewed release hashes are pinned; frontend remains independent.
- Seven Python safeguard tests passed: compatibility patch, unexpected-source rejection, binding identity, bootstrap, checksum, provenance and atomic installation protections. Both workflow YAML files passed actionlint 1.7.12 (shellcheck unavailable); bash -n and Python syntax checks passed.
- Backend build and all 40 Kotlin tests passed in an isolated workspace without .env, TG credentials, JNI library or session/database files. Runtime still requires JNI; only compilation/unit tests now require the Java JAR alone.
- npm ci, TypeScript check and production frontend build passed. All 13 offline Playwright tests passed with backend stopped, then passed again after npm ci. Six live-Telegram scenarios are explicitly tagged and discovered separately; existing tests were retained.
- Existing Intel x86_64 dylib architecture, dynamic dependencies, JNI loading, inline-keyboard constructor and synchronous TDLib call passed. Fixed lipo argument ordering; ARM64 workflow runs the same actual loading check. No native rebuild or session/data deletion performed.
- Original backend restored as a single localhost instance: /health and /api/telegram/me returned 200, authorization and connection Ready. Existing MCP/read/search/import/monitoring implementations were not rewritten.

## NOT VERIFIED / bootstrap limitations
- Corrected GitHub-hosted ARM64 workflow has NOT run: changes are local and push was not authorized. Native fix is not declared successful on ARM64. Rerunning the old failed run would use old code.
- A successful ARM64 artifact/Release does not yet exist. config/tdlib.json intentionally has null jarSha256/buildSourceDigest; Fast backend CI will clearly fail until bootstrap publication and reviewed hash pinning. Real Release download/attestation verification remains unverified; safeguards are unit-tested.
- Existing local TDLib checkout/JAR use SHA `42e6a5259551178d1dab54a22ad96d14bd906e20`, not the CI pin. They were preserved. Local Kotlin checks do not establish compatibility with a freshly generated pinned CI JAR; the native workflow must prove that.
- Six live frontend scenarios were not rerun for this CI task. Earlier MCP milestone tested all 19 and actual MCP client discovery/calls; no new Telegram publications, rate limits or browser OS delivery were exercised here.

## Run / checks
Backend: `./scripts/run-local.sh`; frontend: `npm run dev --prefix frontend`.
Checks: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :backend:build`; `npm run build --prefix frontend`; `npm run test:ci --prefix frontend`; `python3 -B -m unittest discover -s tests -v`.
Live frontend checks: `npm run test:telegram --prefix frontend`, with an authorized local backend. Never run competing backend instances against one TDLib session.
Detailed CI bootstrap, artifact publication, source updates and clean retries: [CI.md](CI.md). Secrets, local databases, sessions, binaries and build outputs remain excluded from Git.

## Single next action
After reviewing and manually pushing this commit, dispatch the updated macos-arm64.yml on master; follow [CI.md](CI.md) to verify the successful run, manually publish its versioned Release and pin the reviewed JAR checksum/source digest for Fast CI.
