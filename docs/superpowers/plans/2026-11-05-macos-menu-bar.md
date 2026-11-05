# macOS Menu Bar implementation plan

Goal: installed hradar remains visible and controllable without Dock/browser.
Architecture: Java 21 SystemTray/TrayIcon/PopupMenu, LSUIElement agent bundle;
DesktopRuntime owns browser destinations/capability, existing shutdown hook owns
cleanup. No native dependencies, public endpoints, session or schema changes.
The user's detailed task is the implementation specification and authorizes execution.

- [x] Add testable desktop lifecycle/actions; AWT adapter on EDT only, packaged-only
  activation, native template monochrome multi-resolution icon. Test lifecycle,
  disabled actions, repeated quit, startup failure, dev/headless gating.
- [x] Route initial/repeated UI and settings opens through DesktopRuntime. Existing
  UI has an application settings screen but no deep link: add a fixed allowlisted
  query destination, retained across launch-fragment consumption; browser test.
- [x] Connect real HTTP readiness, stopping and icon disposal to Application cleanup;
  keep the existing hook/exit sequence, close all resources even after one failure.
- [x] Set LSUIElement/template JVM flag before final signing; verify plist, JVM
  options and bundled glyph. Build real Intel app and run isolated packaged smoke.
- [x] Run backend/frontend/offline/security checks, review diff, bump compatible
  feature once to 0.6.0, add Intel/ARM manual QA and verified handoff. Publication follows this verified checkpoint: commit/push only this branch,
  create Draft PR and inspect CI once without waiting.

Review focus: quit during startup; AWT shutdown deadlock; failed tray setup leaves
no invisible backend; duplicate launches create no second tray; secret stays out of
menu/logs/child argv; settings survives handshake. GUI appearance, light/dark,
Retina and physical ARM remain explicit manual checks if unavailable locally.


Review checkpoint: independent reviewer found blocking startup adapters under the
cleanup lock. A real child-JVM regression failed with `health shutdown hung` before
the fix. Synchronization is now limited to resource initialization; bounded health
and browser adapters run outside it. Both stalled adapters must exit via menu quit.
No separate native layer or public control endpoint was introduced.

Final local evidence: 199 Kotlin tests, 64 offline browsers, 29 Python safeguards;
real tray/actions/disposal plus two stalled-adapter shutdown children; Intel package
and mounted-DMG full lifecycle/security/settings smoke passed on isolated port17129.
A false port-leak assertion was diagnosed as TCP TIME_WAIT (no listener, rebind with
SO_REUSEADDR succeeds); smoke now tests the same reuse semantics as a restart.
ARM64, physical clicks/appearance/Dock and signed public installation remain manual.
