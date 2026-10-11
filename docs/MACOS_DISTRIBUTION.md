# macOS standalone distribution

## Scope and compatibility

Separate native installers for Apple Silicon (`arm64`) and Intel (`x86_64`).
No Rosetta fallback. **macOS 15.0 or newer is required** by this distribution's
explicit deployment target and Info.plist. CI runs on macOS 15; later OS versions,
physical Intel and Apple Silicon installation, Finder/browser activation and
Gatekeeper behavior remain NOT VERIFIED until manually tested. Do not infer macOS
11–14 support from Java 21's wider platform support.

The app contains production React resources served by Ktor, Kotlin/application
JARs, matching official Java TDLib bindings/JNI and a jlink Java 21 runtime.
Launching needs no Node, Gradle, Java installation, Homebrew, terminal or TDLib
compilation. A system browser opens localhost after the server responds to health.
The backend runs in the app launcher process; closing the browser tab leaves
monitoring running. Use **Завершить hradar** in the UI to close the server/workers,
wait for TDLib Close and release the session lock. SIGTERM also cleans up.

The product/artifact version is 0.1.0; macOS bundle version is 1.0.0 because
jpackage rejects a leading zero in CFBundleVersion. This is an internal packaging
number, not a claim of a stable 1.0 product release.

Unsigned artifacts use an ad-hoc resource seal for macOS execution, without a
Developer ID identity or notarization. They are internal test builds, **not a signed
public release**.
Gatekeeper may refuse downloaded unsigned apps. Do not globally disable Gatekeeper;
use the OS's per-app approval process only for an artifact you independently trust.

## Local data and existing installations

Data remains outside `.app`/DMG in the established directories:

- `~/.hradar/research.sqlite`: archive, subscriptions, projects and other existing
  repository data. SQLite migrations retain their existing forward-only behavior.
- `~/.recruitment-radar/tdlib/`: existing Telegram authorization/session.
- `~/.hradar/telegram-api.json`: API ID/hash, owner-only mode 0600, in an owner-only
  directory (0700). This file is plaintext protected by macOS filesystem permissions;
  it is not a password/code store or Keychain item. Login codes/2FA passwords are
  submitted directly to TDLib, never persisted or logged by onboarding.
- Session lock and a private per-process browser launch marker are transient runtime
  control files. Never copy the session or credentials into an installer.

Existing environment API credentials remain supported for development. An installed
app does not read a checkout `.env`; API ID/hash can be entered in the UI using
credentials obtained from https://my.telegram.org/apps. Reusing the existing session
and its matching API credentials does not explicitly log out or delete the session.
Real session continuation has not been exercised automatically.

New builds lock the established session directory before starting TDLib. A duplicate
opens the existing desktop UI and exits. An occupied port 8080 (including a legacy
backend that does not know about the new lock) causes a clear refusal before TDLib
is created. Stop the old development backend yourself before manual installation;
this task leaves it untouched. Older binaries launched afterward do not honor the new
application lock; never deliberately run a legacy client against the same session.
TDLib's own SQLite locking is an additional constraint, not a replacement for this rule.

This branch currently supports SQLite schemas through v8 (including hiring projects and publication Kanban). A database created by a
newer feature build is refused with a visible message, without a downgrade or
deletion; use a build containing that newer schema/features. Existing Bot API
credentials remain optional environment configuration and are not included in this
Telegram user-account onboarding.

Updating/replacing the `.app` does not remove these directories. There is no automatic
updater or downgrade migration. Back up a stopped database/session before testing a
new schema; restore a stopped backup for rollback to an older binary.

## Onboarding and localhost protection

Supported user input: phone, Telegram code, 2FA password, login email, email code.
Other-device confirmation supplies a TDLib `tg://` link. Closing/closed/initializing
states are displayed without terminal prompts. Registration of a new Telegram
account and premium purchase remain explicit states with instructions to use the
official Telegram app. Parameter errors allow correction of API credentials without
creating a second client. Rate-limit errors ask the user to wait; no automatic
credential retry loop. Tests use synthetic input and fake TDLib responses.

A random private launch token is passed in the browser URL fragment, exchanged for
an HttpOnly SameSite=Strict cookie, and removed from the URL. Desktop API reads and
writes require that cookie; Host/Origin checks reject other authorities/origins.
Onboarding/shutdown writes additionally require a CSRF header. No-store responses
and no-referrer headers keep setup out of HTTP caches/referrers. No bearer is embedded
in the production bundle. These controls do not protect against an already compromised
macOS user account or arbitrary code running as that user.

MCP retains its separate default-disabled and explicitly granted public-source
boundary with exact Host/Origin checks, so existing local MCP clients continue to
work when the user enables it. The desktop cookie gates the general REST UI API;
it does not replace MCP's own consent/source checks or introduce a generic Telegram
executor. As in development, local MCP process identity is not authenticated merely
by being on localhost; enabling MCP deliberately grants its approved source scope
to local clients.

## Build and CI

`config/tdlib.json` remains the official source/JAR Release pin for Fast CI. The
published ARM64 release is untouched. Portable builds use the same TDLib source SHA,
a separate source/cache namespace and a reviewed optional CMake patch: omit the
stale demonstration and the demonstration target's redundant explicit JVM link. The upstream static TDLib
link interface still names `@rpath/libjvm.dylib`, resolved by the bundled JVM; no
absolute JDK/Homebrew library paths are permitted.
Pinned OpenSSL 3.5.9 is built static from its checksum-verified official tarball.
Both dependency licenses are included. `otool` rejects external Homebrew/JDK linkage.

- `.github/workflows/ci.yml`: existing Fast CI, fake backend tests/offline Playwright.
- `macos-native.yml`: separate reusable/manual expensive native workflow. ARM64 uses
  `macos-15`, Intel uses `macos-15-intel`; verifies actual architecture and account-free
  JNI execution. Immutable successful bundle cache keys include native build inputs,
  architecture and fixed JDK; React/Kotlin changes don't invalidate them. Every reuse
  checks hashes, source identity, architecture, linkage and JNI again.
- `macos-distribution.yml`: PR or manual installer workflow; production React/Kotlin,
  jpackage/JRE, architecture/linkage/JVM/JNI checks, native hdiutil DMG (preserves the
  verified/signed app), byte-for-byte DMG/ZIP comparison and production-browser/lifecycle
  smoke executed from the mounted read-only DMG, app ZIP, SHA256, metadata and GitHub provenance. No Release publication.
  ZIP preserves symlinks/executable modes. Raw .app upload is avoided.
- `macos-release.yml`: future **manual** promotion of both signed artifacts from an
  exact successful workflow-dispatch run and existing reviewed `vX.Y.Z` tag. Checks
  commit, workflow identity, signed metadata, checksum and attestations before staging.
  `publish=false` by default; publication requires explicit input and the protected
  `macos-release` environment. Never creates tags or overwrites assets/releases.

Native source/toolchain versions are fixed in the config and workflow: JDK Temurin
21.0.12+8.0.LTS, CMake 3.31.10, Node 24.11.1, Gradle wrapper and npm lockfile. Runner
Xcode/SDK/gperf vary with the hosted image; source/patch/build/run hashes are captured
in native metadata; hosted-image/compiler details are retained in Actions logs. Reproducible means pinned inputs and verified provenance, not a
claim of bit-for-bit binary reproducibility across runner-image updates. PR caches
are scoped by GitHub; the first build in another PR may need a native cache warmup.

For local build (developer tools needed **only on the build machine**):

```bash
bash scripts/build-portable-tdlib.sh
npm ci --prefix frontend
npm run build --prefix frontend
./gradlew :backend:build :backend:stageDesktop -PbundledWeb=true
bash scripts/package-macos.sh "$(uname -m)"
bash scripts/verify-macos-dmg.sh "$(uname -m)" ".tdlib-build/distribution-$(uname -m)"
```

Installer metadata records `sqliteSchemaVersion: 8` and `recruitment-crm`.
Both architecture jobs also execute the offline Playwright suite. Mounted-DMG smoke
creates synthetic CRM/archive records through real REST, preserves notes/stages and
compares stored rows after reopening an existing v8 database. It checks SQLite
integrity/foreign keys and refuses v9 without changing CRM rows. Browser CRM uses
production resources and real REST; only authorization status is mocked.

The smoke test uses an isolated temporary home, no account client, a restricted PATH
and no TG environment credentials; checks production JS/CSS, cookie/Origin/CSRF
denials, duplicate and legacy-port refusal, SQLite/session sentinel persistence,
restart and UI shutdown. Optional second argument selects an isolated smoke port
for a developer machine with an existing backend; production always uses 8080.

## Enable signed public distribution (owner action required)

1. Apple Developer Program membership, a Developer ID Application certificate/private
   key exported as password-protected P12, and an App Store Connect API key permitted
   for notarization. No paid resource is created by these workflows.
2. Create the GitHub `macos-signing` environment with required reviewers and trusted
   branch restrictions. Store secrets `APPLE_CERTIFICATE_BASE64`,
   `APPLE_CERTIFICATE_PASSWORD`, `APPLE_SIGNING_IDENTITY`, `APPLE_NOTARY_KEY_BASE64`,
   `APPLE_NOTARY_KEY_ID`, `APPLE_NOTARY_ISSUER_ID` there. Never put them in the repo.
3. Dispatch installers on a reviewed commit with `sign=true`. This separate job uses
   a temporary keychain, nested hardened-runtime codesign, `xcrun notarytool`, app
   and DMG stapling and `spctl`; credentials are removed even after failure.
   It is disabled on PRs and by default. Missing secrets fail the requested signing
   job instead of labelling an unsigned image as signed.
4. Validate signing entitlements and real installs on both physical Macs, including
   downloaded/quarantined installation, offline Gatekeeper, browser launch/relaunch,
   authentication, graceful shutdown, upgrade preserving archive/session and sleep/
   network reconnect. The unsigned CI checks cannot establish these outcomes.
5. Protect `macos-release` with required reviewers. Only with separate owner approval,
   create a reviewed tag, dispatch promotion for that exact successful signed build,
   and explicitly opt into prerelease publication. None of these publication actions
   is performed in this task.

Apple reference: https://developer.apple.com/documentation/security/customizing-the-notarization-workflow
Oracle reference: https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html
GitHub runner reference: https://docs.github.com/en/actions/reference/runners/github-hosted-runners
