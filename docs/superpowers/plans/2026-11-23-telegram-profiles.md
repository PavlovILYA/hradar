# Telegram profiles plan

**Goal:** Switch accounts without sharing recruiter data or losing sessions.
**Architecture:** Profile registry + isolated paths; serial graph restart and GetMe gate.
**Stack:** Existing Kotlin/TDLib/SQLite14/React, no new framework.
**Spec:** ../specs/2026-11-23-telegram-profiles-design.md

- [ ] Private profile registry/paths, safe staged migration/backup and synthetic recovery tests.
- [ ] Verified identities, bounded avatars and TDLib logout/close acknowledgment tests.
- [ ] Serial graph lifecycle, route/generation/identity gates, bot isolation, fake lifecycle tests.
- [ ] Account management/onboarding/avatar UI and stale React/SSE regression tests.
- [ ] Version sync/check; backend/frontend/Playwright/Python/native packaging checks.
- [ ] Docs/recovery/two-account manual guide; one Draft PR and single CI snapshot.
