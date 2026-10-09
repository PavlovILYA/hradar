# Recruitment Radar

Local Telegram recruitment research prototype: **Kotlin/JVM 21 + Ktor 3.6.0 + official TDLib Java/JNI**.
No frontend yet. Milestone 1: authorize a Telegram user locally and fetch recent messages from a public Telegram channel accessible to that account.

## Requirements (macOS)

Build TDLib on the machine where you will run it: Intel binaries cannot be
reused by an ARM JVM on Apple Silicon.

- JDK 21 (check `java -version`, `echo "$JAVA_HOME"`, `uname -m`)
- Xcode Command Line Tools: `xcode-select --install`
- Homebrew: `brew install cmake gperf openssl@3` (Gradle is provided by the wrapper)
- Telegram API credentials: obtain `api_id` and `api_hash` at https://my.telegram.org

TDLib is **not** an ordinary pure-Java dependency: its official Java API requires a compiled native JNI library. The script below follows the upstream two-stage Java build.

## Run

From repository root:

```bash
./scripts/build-tdlib-macos.sh   # one-time TDLib compilation, takes time and disk space
cp .env.example .env
# edit .env and fill YOUR api_id and api_hash
./scripts/run-local.sh
```

The launcher selects JDK 21 and loads `.env` before invoking the Gradle 8.14.3
wrapper. The wrapper downloads Gradle on first use and verifies its SHA-256.
For build commands, set `JAVA_HOME="$(/usr/libexec/java_home -v 21)"` and use
`./gradlew :backend:build` after compiling TDLib.

The terminal will request your phone number, Telegram login code, and if applicable 2FA password or email code. This project never asks you to put login codes in a public webpage or commit them. After authorization, the TDLib session is stored under `~/.recruitment-radar/tdlib` and can be reused on later starts. **Protect that directory: it contains account session data.**

TDLib native binaries and generated bindings are intentionally excluded from Git. Never commit `.env`, account sessions or candidate personal information.

## API

The HTTP server binds **only to `127.0.0.1:8080`**.

```bash
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/api/telegram/status
curl http://127.0.0.1:8080/api/telegram/me
curl 'http://127.0.0.1:8080/api/telegram/chats/durov/messages?limit=10'
```

The channel endpoint resolves public usernames. It can still fail for channels whose history is restricted or inaccessible to the account. TDLib might return fewer messages than the requested limit. The sample does not join chats or send messages.

## Security / scope

- Do not publish the `.env` file, TDLib directory, account credentials, logs with authentication data, or candidates' profiles.
- The first milestone is **read-only**. No auto-contacting people, bulk scraping, or exporting personal profiles.
- Respect Telegram's API terms and applicable data privacy laws when extending the project.
- This prototype is local and has no API authentication. Don't expose port 8080 publicly or to your LAN.
- For a production-grade app, move auth into a secure desktop onboarding flow, add error/timeout handling and robust shutdown/state reconciliation, and securely store session files.

## Architecture

```text
Ktor REST API --> TdlibGateway --> generated Java TdApi + Client --> JNI --> Telegram
```

## Next milestones

1. Add `UpdateNewMessage` listener and per-channel monitoring.
2. Persist monitored channel IDs and latest processed message IDs in SQLite.
3. Add deterministic, explainable rules for candidate matching.
4. Add a React+TypeScript dashboard and CSV export.

## References

- Official TDLib: https://github.com/tdlib/td
- Official Java sample/build: https://github.com/tdlib/td/tree/master/example/java
- Ktor: https://ktor.io/docs/
