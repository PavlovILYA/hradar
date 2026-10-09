# hradar — Instructions for Codex

## Project
- **hradar** is a local Telegram recruitment research tool, initially for macOS (Apple Silicon).
- Backend: Kotlin/JVM 21, Ktor, Gradle Kotlin DSL, official TDLib Java bindings via JNI.
- Later: SQLite, deterministic rule-based matching (no AI API), React + TypeScript frontend.
- Preserve the existing codebase and architecture; do not rewrite or introduce Spring Boot.
- Current priority: get the existing backend building and running locally. Do NOT work on the frontend or additional features yet.

## Work method
1. At the start of a new session, read `docs/DEV_STATUS.md`, then inspect only relevant build files, scripts, and source files. Treat the status file as a handoff, not proof that a command succeeded.
2. Check `git status` before edits. Preserve uncommitted work and user configuration. Make the smallest changes required to unblock execution.
3. Prefer direct, observable checks (compiler exit code, running server, HTTP response) over assumptions. Never claim success without verification.
4. For long native/Gradle builds: check for existing running build processes before starting another. Run once, redirect verbose output to a local ignored log, wait for completion without frequent polling. Inspect errors or a short log tail only. Do not repeatedly stream full logs into model context.
5. Avoid broad repository reads, repeated searches, excessive planning, and progress narration. After an error, identify its cause before retrying. Use concise replies.
6. Do not modify unrelated files, add new frameworks, auto-contact users, or implement bulk Telegram collection during this milestone.
7. Request user intervention only for actions that genuinely require it (OS dialogs, Telegram verification, credentials); explain the exact action.

## Security
- Never print, commit, or request that the user paste `.env`, `TG_API_HASH`, session databases, Telegram codes/passwords, or candidate personal data into chat.
- Keep the server bound to localhost (`127.0.0.1`); keep Telegram operations read-only during initial integration.
- Do not delete session directories, build caches, or existing native artifacts without a compelling verified reason.

## Definition of done — current milestone
- Kotlin backend compiles using the project's actual Gradle entry point.
- Native TDLib/JNI artifacts exist, load correctly, and match the JVM architecture.
- Ktor starts on localhost and `/health` responds.
- `GetMe` and accessible channel-history API are tested if account credentials and authorization are available; otherwise document the exact manual verification step.

## Session handoff
- Update `docs/DEV_STATUS.md` **once after a meaningful milestone or before ending the session**, recording verified results, outstanding blockers, and the single next action.
- Keep the handoff brief; distinguish VERIFIED from NOT VERIFIED. Do not include secrets, full logs, or speculative completion dates.
