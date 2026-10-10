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
