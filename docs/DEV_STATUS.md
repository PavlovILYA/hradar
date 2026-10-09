# hradar — Development status / session handoff

Last verified: 2026-10-09.

## VERIFIED
- Existing Kotlin/Ktor backend and official TDLib/JNI preserved; native artifacts reused without rebuilding TDLib.
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --console=plain :backend:build` completed successfully. Five channel-history tests passed (limit validation, short-page pagination/deduplication and captions, non-advancing history, group rejection, unavailable-channel error propagation).
- Started one backend using `./scripts/run-local.sh`; localhost `/health` returns 200 and Telegram status is `Ready`. Existing Gradle wrapper PID 24656 had no backend child, listener or TDLib database lock before launch.
- Real TDLib HTTP request to `/api/telegram/chats/telegram/messages` returned HTTP 200 with 10 messages. `/api/telegram/chats/@telegram/messages?limit=100` returned 100 unique messages ordered newest first.
- JSON fields: `id` (TDLib message ID), `date` (Unix seconds), `text` (text/media caption, null for unsupported content), `channelId` (TDLib chat ID).
- Invalid username and invalid/non-integer/out-of-range limits return HTTP 400. A nonexistent public username returns HTTP 404 with a JSON error.
- Reading uses only `SearchPublicChat` and `GetChatHistory`; no joins or message sends. Short TDLib pages are paginated under a 20-second total deadline.

## NOT VERIFIED
- Real inaccessible/private-channel HTTP 403 and Telegram rate-limit responses were not induced. Error mapping is implemented; no private channel was probed.
- `/api/telegram/me` was not repeated in this session to avoid printing personal data; prior user verification remains the reference.

## Running / blockers
- Backend remains running on `127.0.0.1:8080`, launched through `./scripts/run-local.sh` (logs: `/tmp/hradar-server.log`). Do not start a second instance against the same TDLib database. Build log: `/tmp/hradar-build.log`.
- No blocker for public channel history. Logs/session data and local HTTP result files must stay outside Git.

## Next action
Manually verify another known accessible public channel with the same endpoint before proceeding to storage/matching work.

```bash
curl --fail-with-body 'http://127.0.0.1:8080/api/telegram/chats/telegram/messages?limit=10'
```
