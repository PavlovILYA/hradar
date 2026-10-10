# hradar — Development status / session handoff

Verified 2026-10-10. Read-only MCP MVP completed. Previous commit: `8daaf6b`.
Working tree was clean at task start; no checkpoint needed. No git push.

## VERIFIED
- Official Kotlin MCP SDK 0.14.0 works with existing Kotlin 2.2.20 / Ktor 3.6.0. Streamable HTTP JSON transport at `http://127.0.0.1:8080/mcp`, stateless sessions; SDK handles initialize, tools/list and tools/call. TDLib/JNI was reused, not rebuilt.
- Nine tools: get_telegram_status, list_chats, resolve_chat, get_chat_history, search_chat_messages, get_message_context, search_local_archive, list_monitoring_rules, prepare_message. Typed capability DTOs/service are shared by MCP, new REST `/api/capabilities/<tool>` and the integration UI; existing SearchService, repositories and the single TDLib client are reused.
- Read-only, disabled by default, manual public-channel allowlist (max 25). Unlisted/private IDs are rejected before Telegram lookup. No personal-chat enumeration, universal TDLib executor, sends, joins, payments or permission-management MCP tools. Drafts are ephemeral, unsent and require future separate human approval for writes.
- Exact Host/Origin checks protect local MCP, capability REST and integration management. UI mutations also require a per-process CSRF nonce; UI framing is blocked. Permission changes during a call discard its result. Trusted local OS processes remain outside this browser/MCP security boundary.
- SQLite v4 migration adds enabled policy, allowed sources and bounded metadata-only audit (last 500 calls). Existing archive/templates/notifications remain intact. SDK logging is disabled; audit contains no arguments, message contents or credentials. Allowlist/disabled policy persistence and archive preservation are tested after reopening storage; actual restart retained the restored disabled/empty policy.
- Limits: 1–50 results, bounded context, history cursor deduplication/partial-page handling, 2000-character clipped texts, 32 KiB MCP request body, 256 KiB capability result, four concurrent calls and 30-second timeout. Errors are sanitized; FLOOD_WAIT exposes retry duration. Telegram content is explicitly untrusted data.
- Compact “Интеграции → MCP” UI provides enabled status, endpoint, tool descriptions, manual source grants/revocation, copied Codex command and a real shared-REST status check. Checkbox state updates immediately and rolls back from server state on failure. Existing reading/search/import/templates/notifications remain functional.
- Ktor serializer conflict resolved with existing REST ContentNegotiation on the routing root and SDK serializer only on /mcp. StatusPages serializes legacy error envelopes explicitly; real missing-channel errors still return JSON and the original frontend message.
- Final backend build succeeded: all 40 Kotlin tests passed (10 MCP tests plus existing 30). Covers catalog/schemas, strict inputs, limits/cursors/deduplication, scoped archive/rules, sanitized Telegram errors, unsent drafts, persisted permissions, Host/Origin/CSRF boundaries, SDK initialize/list/call and identical REST/MCP results, plus legacy error compatibility.
- Final frontend build succeeded: all 19 Playwright tests passed. MCP controls/shared API test added; real history/search/archive/template CRUD, error handling, mobile layouts, import controls, notifications and SSE regressions remain green.
- Official Node MCP SDK client initialized and discovered all nine tools, then called every tool against the actual backend. @telegram resolved; history returned 10 messages and an older page without duplicates; Telegram-word search and selected-message context worked; SQLite search returned 231 matches. Unlisted positive ID, excessive limit and unregistered send_message were rejected; prepare_message did not send. Only the explicit public @telegram source was temporarily allowed through the UI; original disabled/empty settings restored and verified.
- Codex CLI registration added: `hradar` → local /mcp URL; `codex mcp list --json` confirmed registration. No Codex agent was automatically launched. Real /health and GetMe returned 200, authorization/connection Ready, original latest-N returned 10. Completed archives preserved: @telegram=430, @telegramtips=447. One backend on localhost 8080.

## Limits / NOT VERIFIED
- MCP supports only explicitly allowed public channels; private chats and write operations are intentionally unavailable. Context may be partial; text truncation is explicit. GET/SSE and DELETE on /mcp return 405 for stateless transport; existing app notification SSE remains separate.
- Real Telegram rate limiting/timeouts were not induced; synthetic Telegram errors verify sanitized retry hints. No OS desktop delivery or fresh external publication was observed in this task; existing monitoring tests remain green. Browser notices still require an open page.
- Existing limits remain: global search may report free_limit with starCount=0; archives are snapshots without automatic recruitment matching or edit/deletion reconciliation. No new full-history collection was performed.
- This is a local OS trust boundary, not remote authentication or isolation from arbitrary same-user shell access. Do not expose/tunnel the ports. New write use cases must require human approval outside MCP.

## Run / checks
Backend: `./scripts/run-local.sh`. Frontend: `npm run dev --prefix frontend` → http://127.0.0.1:5173.
Open “Интеграции → MCP”, enable access and explicitly select public sources before agent calls.
`codex mcp add hradar --url http://127.0.0.1:8080/mcp`; `codex mcp list`.
Stop backend before compilation; never run two instances against one session.
Checks: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :backend:build`; `npm run build --prefix frontend`; `npm test --prefix frontend` with authorized backend.
The final backend verification used `--no-daemon -Dorg.gradle.jvmargs='-Xmx768m -Dfile.encoding=UTF-8' -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process` after a verified broken incremental cache; only ignored JVM build outputs were cleaned. Native/session/user databases were retained.
MCP setup, arguments and safety: [MCP.md](MCP.md). SQLite: `~/.hradar/research.sqlite`; session: `~/.recruitment-radar/tdlib`. Verification clients/scripts/logs stay under /tmp; secrets/data/build artifacts excluded from Git.

## Next action
Manually enable MCP and select the public sources the user wants Codex to research; connect the registered hradar server. Future safe additions: bounded allowlisted source/media metadata, retaining the same access policy.
