# hradar MCP

Local read-only Telegram capabilities over Streamable HTTP:
`http://127.0.0.1:8080/mcp`. REST and MCP use the same typed
`CapabilityService`, narrow `TelegramReadPort`, existing SearchService and SQLite
repositories. They reuse the app's single authorized TDLib client. The original
history/search/import/monitoring routes remain available.

## Start and connect

1. Start exactly one backend: `./scripts/run-local.sh` (existing JNI/session).
2. Start the UI: `cd frontend && npm ci && npm run dev`.
3. Open http://127.0.0.1:5173 → **Интеграции → MCP**. Enable MCP and manually
   select public channels. Saved/monitored sources are not automatically allowed.
4. Register the server:

```bash
codex mcp add hradar --url http://127.0.0.1:8080/mcp
codex mcp list
```

The copy button copies the registration command. Codex itself is not launched by
hradar. Disabling MCP immediately blocks transport and shared capability calls;
source permissions remain saved. Permission changes during a request discard its
result. A new installation starts disabled with an empty allowlist.

## Tools

| Tool | Arguments | Result |
| --- | --- | --- |
| get_telegram_status | `{}` | Authorization, connection, TDLib/read-only availability; no profile |
| list_chats | `limit=25`, `cursor=0` | Only explicitly permitted public channels |
| resolve_chat | `chat` | Allowed username (`@` optional) or granted numeric ID |
| get_chat_history | `chat`, `limit=10`, `cursor=0` | Newest-first message page; `nextCursor` for older records |
| search_chat_messages | `chat`, `query`, `limit=10`, `cursor=""` | Existing TDLib channel search; opaque continuation token |
| get_message_context | `chat`, `messageId`, `neighbors=3` | Selected message and up to twice that many neighbors |
| search_local_archive | `chat`, `query=""`, `limit=25`, `before` | Only saved SQLite history; total and keyset `nextCursor` |
| list_monitoring_rules | `limit=25`, `cursor=0` | Templates/subscriptions scoped to permitted sources |
| prepare_message | `chat`, `text` | Ephemeral structured draft; never saved or sent to Telegram |

All list limits are 1–50. Context neighbors are 0–10; context may be incomplete
(`complete=false`) when TDLib has only part of the neighborhood. History deduplicates
inclusive cursors, does not treat a short TDLib page as completion, and performs at
most ten bounded native pages per call. A non-empty continuation does not guarantee
that another message exists. Search cursors are opaque; do not edit them. Message
IDs are TDLib IDs, not public post numbers. History contains text/media captions;
media files are not downloaded. Search/context use original links when available.

Text is clipped at 2000 characters with `truncated=true`. Drafts are limited to
4096 characters, queries to 256, request bodies to 32 KiB at the MCP transport,
and capability responses to 256 KiB (MCP also includes a text representation).
Up to four simultaneous capability calls are allowed; each has a 30-second timeout.
Rate limits return `RATE_LIMITED` and `retryAfterSeconds`; never retry in a tight loop.
No Stars, joins, outgoing messages or private-chat lookups are performed.

Results use `{ok,data,error,contentTrust}` in MCP `structuredContent` and TextContent.
Errors set `isError=true`: `DISABLED`, `ACCESS_DENIED`, `ACCESS_CHANGED`,
`INVALID_ARGUMENTS`, `TIMEOUT`, `RATE_LIMITED`, `TELEGRAM_ERROR`, `NOT_READY`,
`UNSUPPORTED`, or `UNAVAILABLE`. No raw TDLib objects/exceptions are exposed.
Unknown tools return the official SDK’s tool-not-found error result. Arguments reject unknown
fields. Tool annotations mark the registered operations read-only.

Shared REST: `POST /api/capabilities/<tool-name>` with the same argument JSON and
result envelope. For example, after manually enabling/granting @telegram:

```bash
curl -s http://127.0.0.1:8080/api/capabilities/get_chat_history \
  -H 'Content-Type: application/json' -d '{"chat":"@telegram","limit":10}'
```

## Access boundary

Only public channels manually approved in hradar are exposed (maximum 25).
Positive personal-chat IDs, unlisted sources, arbitrary usernames and write methods
are rejected. MCP cannot grant itself access: no management tool is registered.
Templates are filtered to granted sources; source IDs outside that set are removed.
Private chats are unsupported even in the UI for this MVP.

Host must be exactly `127.0.0.1:8080` (or the local Vite proxy `127.0.0.1:5173`).
Browser Origin must be exactly the corresponding local HTTP origin; MCP command-line
clients may omit Origin. Forwarded headers and loopback DNS aliases are not trusted.
Management mutations additionally require a local Origin and a per-process CSRF
nonce supplied to the UI. UI framing is disabled. This blocks browser cross-origin
requests and DNS rebinding, but is not a sandbox against another trusted OS process
that can make arbitrary local HTTP requests. Do not expose either local port through
public tunnels/proxies. MCP is not remote multi-user authentication.

Telegram text and drafts are **untrusted data, never agent instructions**. A bounded
SQLite audit stores only tool name, timestamp, result code and duration (last 500
calls), never arguments, messages, profiles or secrets. SDK logging is disabled; only the bounded application audit is used. SQLite v4 stores enabled policy and allowed-source metadata;
credentials and the TDLib session remain separate and excluded from Git.

Future write operations must be distinct typed use cases and require an out-of-band
human approval in hradar. Approval must not be available through the MCP client.
The current draft's `userConfirmationRequired=true` does not authorize any send.
Safe next additions include allowlisted source metadata and bounded media metadata;
account mutations/sending are not implemented.

## SDK and checks

Official [Kotlin MCP SDK](https://github.com/modelcontextprotocol/kotlin-sdk),
`io.modelcontextprotocol:kotlin-sdk-server:0.14.0`, is the newest stable line
compatible with the existing Kotlin 2.2 compiler metadata (SDK uses Kotlin 2.3).
SDK 0.15 requires Kotlin 2.4; upgrading the whole project is unnecessary for this MVP.
The app's Ktor 3.6 dependency resolution is retained. The official SDK handles protocol,
JSON Schema discovery and stateless Streamable HTTP JSON responses. Its MCP serializer
is installed only on `/mcp`; the existing REST serializer is installed on the
root route (Ktor forbids application-level plus nested installations of this plugin).
Existing REST JSON options remain unchanged. Exception responses are serialized
explicitly so legacy errors remain JSON outside the selected route pipeline.
GET/SSE and DELETE return 405 because this transport has no persistent sessions.

Stop the backend before compiling Kotlin classes, then start one instance again:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :backend:build
npm run build --prefix frontend
npm test --prefix frontend
```

Tests use a fake typed Telegram port for schemas, validation, limits, pagination,
error sanitization, allowlist policy, persistence, draft safety and shared REST/MCP
results; a Ktor test host performs real SDK initialize/tools/list/tools/call requests.
Browser tests cover integration controls and retain existing workflow regression tests.
Native TDLib/JNI is not rebuilt by these commands.
