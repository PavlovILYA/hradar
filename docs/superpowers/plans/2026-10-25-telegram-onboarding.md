# Telegram onboarding implementation plan

**Goal:** Make login recoverable and explain the delivery method actually reported by TDLib.

**Architecture:** Extend the existing Onboarding service and finite REST operations.
Keep the single TDLib client, desktop cookie/Origin/CSRF protection and RuntimePaths.
Use the pinned TDLib schema, React and existing theme variables; no new dependency.

- [x] Backend: test delivery DTOs, resend deadlines, phone replacement, concurrent/stale
  requests and safe errors using fake TDLib and an injected clock. Implement in
  Onboarding.kt, add guarded resend/phone routes and typed error fields.
- [x] Frontend: replace the onboarding presentation with a responsive themed card,
  progress, delivery guidance, countdown, phone editor and preserved failed input.
  Keep backend state authoritative and reject duplicate requests synchronously.
- [x] Verify fake login journey, resend/phone transitions, cooldown, loading/error,
  keyboard and narrow-window behavior with Playwright; run Kotlin build, frontend
  build, all offline browser tests and Python safeguards. Inspect index for secrets,
  update DEV_STATUS, commit/push and open a PR using gh if available.

Design calls: changing phone opens an editor while displaying the real current
TDLib stage; submitting uses SetAuthenticationPhoneNumber and waits for TDLib.
ResendAuthenticationCode handles both WaitCode and WaitEmailCode in this pin.
Email code info has no resend timeout, so apply a clearly identified local 30-second
resend cooldown. Respect all server timeouts/FLOOD_WAIT. Do not expose raw error
messages, code fragments, password hints, Firebase receipts or arbitrary URLs.

Review focus: email without timeout; same-state updates after resend; changing
phone from 2FA; race between polling and a request; error 406 must remain opaque.
