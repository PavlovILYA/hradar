# Agent Bridge — architecture proposal (not implemented)

No Telegram free text invokes Codex CLI, a shell, or any model in this release.
MCP remains a capability protocol; Bot API commands are deterministic. Implementing
this proposal requires a separate product/privacy decision and explicit authorization.

## Proposed boundary

Private allowlisted operator → explicit `/research` request → typed job service →
isolated agent worker → source-scoped CapabilityService → reviewable result →
BotPublish request → human confirmation → existing durable Bot API outbox.

The worker must never receive the bot token, TDLib session, local environment, SQL
connection, arbitrary filesystem access or process execution. Do not wrap raw Telegram
text in `codex exec`. Prefer a model API with a fixed tool registry and typed arguments;
if a CLI runtime is ever chosen, approve and validate its isolation separately.

## Authorization and consent

- `/start` is delivery opt-in only. It grants neither agent execution nor publication.
- Add separate, revocable agent consent and a source scope intersected with the local
  MCP allowlist. Permission cannot be granted by a tool call or model response.
- Before sending content to an external model, show provider, scope, excerpts sent,
  retention, cost budget and purpose. Review platform terms and lawful processing.
- The existing `/agent_publish on` permits publication requests only. Each exact
  selection requires confirmation by that same operator in their private bot chat.
  No arbitrary recipient, free-form outgoing body or TDLib account write.
- Recheck operator, enabled policy and sources at job start, every capability call,
  result review and delivery. Revocation cancels running jobs and queued publications.

## Job and data model

A future forward migration should store job ID, operator/bot identity, source grant
version, typed request, limits, state, timestamps and safe error code. Proposed states:
QUEUED → RUNNING → REVIEW_REQUIRED → COMPLETED, with CANCELLED/FAILED/EXPIRED.
Use per-operator concurrency 1, bounded queue, maximum duration/tool calls/tokens and
explicit cancellation. Restart must not silently repeat billable model calls or sends;
interrupted runs require review. Persist only necessary references and bounded result
excerpts with explicit expiration/deletion. Audit metadata only, no prompts or personal
content in logs. Publication must use existing match IDs or separately reviewed typed
artifacts; raw model text cannot reach BotGateway.

Telegram publications, model responses and tool-returned strings are untrusted data.
Only authenticated explicit operator commands can create jobs. Embedded instructions
cannot change tools, source grants, destinations or budgets. Tool errors and provider
rate limits must be sanitized, bounded and cancellable.

## Implementation stages and acceptance

1. Agree provider/privacy/retention and typed recruitment tasks; review threat model.
2. Implement job service with fake agent port, revocation, cancellation and persistence.
3. Add an isolated worker with bounded read-only tools; keep all execution disabled
   by default and test prompt injection, secret isolation and denied sources.
4. Add operator review UI and explicit job consent. Reuse publication confirmation.
5. Conduct one separately authorized live check with synthetic public-source content.

Acceptance requires denial before provider access, no second TDLib client, no generic
shell/tool executor, no content export before consent, no publication before human
confirmation, durable cancellation/restart semantics, and measured cost/rate limits.

## `/ask` technical specification for the mobile recruiter (proposal)

The current `/ask` command returns “not connected”; it never starts a model, Codex,
subprocess or shell. Implementation is a separate future task after the mobile
`/find` → save → watch → notification → digest workflow has been verified.

### Explicit job contract

`AskRequest(operatorKey, botIdentity, question, sourceGrantVersion, approvedSourceIds,
providerConsentId, budget)` creates one durable job only after operator authorization
and explicit provider/data-transfer consent. Limit questions to 1,000 characters;
maximum one active job per operator, five queued jobs, two minutes runtime,
20 tool calls and a configurable capped model token/cost budget. `/ask_cancel`
transitions the job to CANCELLED idempotently and cancels provider/tool coroutines.
Missing consent, empty grants or unavailable provider must deny before export.

`AskResult(jobId, boundedSummary, evidenceReferences, notice)` is data, not an action.
Every evidence reference must resolve to an existing allowed saved result/match or
allowed public-message ID. Do not infer identities or skills. Clip summaries to
2,000 characters and make unavailable/partial evidence explicit. The worker cannot
select a recipient: the application binds the response to the initiating opted-in
private bot operator and rechecks registration/allowlist before returning it.

### Runtime and permitted tools

Use a provider API behind `AgentPort`; a fake port comes first. Runtime credentials
are separate from Bot API/TDLib, injected only into that port and never passed to
prompts/tools. Do not execute `codex exec`, interpret publication text as commands,
mount `.env`, expose home directories, attach the TDLib database/JNI, or register
shell/file/general network tools. The worker can access only a fixed typed registry:

- `get_telegram_status`, `list_chats`, `list_monitoring_rules`;
- `list_recent_matches`, `get_match_details`;
- `list_saved_results`, `get_saved_result`;
- optionally bounded `search_local_archive` and `get_message_context` after separate
  agreement about exporting source content to the provider.

Every call goes through CapabilityService and the intersection of the job's snapshot
and current local source grants. MCP read-only annotations alone are not an allowlist:
exclude `bot_publish_matches`, consent/settings mutations, imports and subscription
creation explicitly. No tool grants itself access or sends bot messages. Publication
text, tool results and provider responses stay untrusted data; validation never
executes or follows instructions embedded in them.

### Return path, persistence and verification

Persist job metadata/state and minimum evidence references; no raw prompt/content in
logs. Proposed states: QUEUED, RUNNING, REVIEW_REQUIRED, DELIVERED, CANCELLED,
EXPIRED, FAILED. A result enters REVIEW_REQUIRED and is shown as a bounded review
card with explicit provenance. Human confirmation enqueues only that job's reviewed
summary to the bound operator using the durable outbox. No model-written callback
payloads or reply keyboards. Restarted RUNNING jobs become REVIEW_REQUIRED/FAILED,
not silently re-billed. Revocation cancels jobs, discards results and cancels pending
outbox rows; in-flight provider/Telegram requests cannot be recalled.

Acceptance tests must use fake AgentPort, fake BotGateway and temporary SQLite to
prove denied-source isolation, budget exhaustion, injection resistance, invalid
references, cancellation/restart, provider 429/backoff, sanitized failures, operator
binding, no tool sends and no export before consent. A separate live test requires
explicit authorization and synthetic public-source data. This specification grants
no permission to transmit source content to any model provider today.
