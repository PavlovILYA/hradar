# Telegram files implementation plan

**Goal:** Find a downloaded resume by document content and retain its source publication.
**Architecture:** Owner-only document service, existing SQLite/gateway, bounded parser subprocess.
**Stack:** Kotlin/JVM21, PDFBox, SQLite FTS5, React/Playwright.
**Spec:** ../specs/2026-11-22-telegram-files-design.md

Constraints: preserve real profile/session, no automatic downloads, one gateway, no external AI,
no Bot/MCP document access, atomic forward migration, one 0.11 version bump/Draft PR.

Review focus: forged file references, parser bombs, cancellation races, crash/restart states,
multiple publication identities sharing content, protected file endpoints, no logs of private text.

- [ ] Backend document metadata/lifecycle/storage/repository migration and synthetic tests.
- [ ] Resource bounded PDF/DOCX/TXT extractor and synthetic format tests.
- [ ] Document UI, scoped discovery/consent, local search, shared details, SSE popover tests.
- [ ] Integrate services/routes/boot/shutdown, version sync/check, security review.
- [ ] Full Kotlin/frontend/Playwright/Python and local macOS packaging verification.
- [ ] Docs evidence/manual limits, small commits, push and one Draft PR, CI snapshot once.
