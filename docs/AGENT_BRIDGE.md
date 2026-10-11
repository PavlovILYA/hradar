# AI researcher — implemented runtime

`/ask` invokes a bounded background worker via OpenAI Responses API. It is disabled
unless a key, model and prices are configured and the initiating operator has given
separate consent for selected MCP-approved public sources. Automated verification uses
fake providers only. Real provider execution and phone delivery are NOT VERIFIED.

## Using the bot and web application

1. Configure the optional Bot API allowlist and register in the private bot chat with
   `/start`. This does not grant AI consent.
2. In **Интеграции → MCP**, enable capabilities and explicitly grant public sources.
   Sources need appropriate rights/consent for external processing; access alone is
   not permission to reuse personal content.
3. Send `/ask_consent` to read provider, data transfer, cost and retention information.
   Send `/ask_access` and paste the returned private code in **AI-исследования → Код
   оператора**. The code opens only that operator's history/settings. A new code rotates
   the old code. Codes are kept in memory by React, never URL/localStorage; the database
   stores only SHA-256 hashes. Treat the code as a bearer credential.
4. Select sources and explicitly check the external-AI consent box, then save consent.
   Bot identity + operator identity own this consent. The local web operator is a
   separate identity; its consent never authorizes a Telegram operator.
5. Send `/ask Проанализируй Java-разработчиков, которые ищут удалённую работу, за
   последние 7 дней. Найди релевантные публикации и объясни почему.`
   The bot immediately queues an acknowledgement. A summary and bounded source cards
   arrive separately through the existing durable Bot API outbox, only to the initiator.
6. `/ask_status` shows up to ten recent/active jobs; `/ask_cancel` cancels the active
   job idempotently; `/ask_history` shows the five latest summaries. Full results,
   original links and older pages are in the web section.
7. `/ask_consent off` or **Отозвать согласие** cancels active jobs and prevents queued
   results from being sent. Viewing already saved history is local and requires the
   same operator plus current source grants; it does not require new external consent.

## Production configuration

Set locally, without committing values or pasting credentials into chat:

```dotenv
OPENAI_API_KEY=
HRADAR_AI_MODEL=
HRADAR_AI_MAX_OUTPUT_TOKENS=2048
HRADAR_AI_TOKEN_BUDGET=32000
HRADAR_AI_BUDGET_USD=0.5
HRADAR_AI_INPUT_USD_PER_MILLION=
HRADAR_AI_OUTPUT_USD_PER_MILLION=
```

Choose an actually available model from the [official model catalog](https://developers.openai.com/api/docs/models)
that supports Responses function calling and strict structured outputs. There is no
invented/default production model. Set positive current USD rates per million input
and output tokens for that model/account from [official pricing](https://developers.openai.com/api/docs/pricing).
Pricing is configured, not guessed or fetched during jobs. Missing key/model/prices
blocks execution. Invalid limit configuration fails startup without logging secrets.
Model/limit changes require renewed operator consent. Historical jobs retain their
model/limit snapshot. Restart exactly one backend after backing up stopped databases;
do not start another TDLib client or getUpdates poller.

The token and USD budgets are conservative reservations, not billing reports. Before
each request, serialized input UTF-8 bytes plus a framing margin bound input tokens;
maximum output tokens are reserved in full. All turns accumulate reservations without
refunds. Input/output rates must be accurate for the configured model. The provider's
own account/project spend limit is independent. An exhausted budget stops before the
next request; usage returned by the provider is checked against the reservation.

[Official function calling](https://developers.openai.com/api/docs/guides/function-calling)
and [Responses create reference](https://developers.openai.com/api/reference/resources/responses/methods/create)
were checked on 2026-10-11. The adapter POSTs only to `https://api.openai.com/v1/responses`,
with `store=false`, `parallel_tool_calls=false`, strict function schemas, strict final
JSON schema, `max_output_tokens` and stateless input/output replay. Encrypted reasoning
items are requested/preserved for continuation. No hosted tools or remote MCP servers
are attached. `store=false` does not imply zero provider retention: review the account's
[data controls](https://developers.openai.com/api/docs/guides/your-data), legal processing
basis and source terms before consenting.

## Boundaries and lifecycle

`BotUpdateHandler → AgentService → AgentPort → OpenAiAgentPort`

`AgentService → CapabilityService → existing archive / TelegramReadPort`

`AgentJobRepository → agent-jobs.sqlite → AgentService → existing Bot outbox`

- Separate forward-only SQLite schema v1 at `~/.hradar/agent-jobs.sqlite`; research
  schema, CRM repositories and native TDLib scripts are unchanged. Short transactions
  persist job creation/state. No model or Telegram read executes in a DB transaction.
- States: QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED, INTERRUPTED. One active job
  per operator, at most five waiting jobs, one sequential worker, 120 seconds per job,
  twelve default tool calls (hard maximum twenty), 1,000-character question,
  2,000-character summary and at most ten evidence references.
- RUNNING is durable before the first model call. Both queued and running jobs become
  INTERRUPTED after restart. They never automatically repeat a billable request.
  429, unavailable/auth/model errors, incomplete/invalid responses and ambiguous
  network failures become safe error codes. There are no automatic provider retries;
  the operator can explicitly create a new request later.
- Cancellation propagates to provider futures and capability coroutines. A periodic
  policy watcher cancels active jobs after registration, consent or source revocation;
  checks also occur before/after every model/tool call and before output persistence.
  Already in-flight external requests cannot be recalled or guaranteed unbilled.
- Authenticated bot updates retain the existing private-chat/allowlist/registration
  checks and durable update offset. The separate job event key deduplicates a request
  even if the research transaction rolled back after job insertion.
- Result enqueue uses unique per-job/per-card event keys. Marking queued results in the
  agent DB follows the durable research outbox insert; restart can repeat enqueue
  safely. Delivery rechecks job, current consent, registration and every source before
  BotGateway sends. COMPLETED means research finished, not phone delivery confirmed.
  The internal `delivered` marker means handed to the outbox (or suppressed), not a
  Telegram delivery receipt. Existing Bot API 429/backoff/permanent-error behavior
  applies; Telegram lacks exactly-once send semantics.
- Completed/error/interrupted/cancelled jobs are deleted after thirty days by the
  running worker. Consent and hashed pairing codes persist until changed; source
  content stays in the separate existing archive under its retention policy.

## Fixed read-only tools and evidence

Only `search_local_archive`, `search_chat_messages` and `get_message_context` are
registered for the model. Agent arguments are typed; all unknown fields/tools fail.
Each call requires a numeric approved source from the consent/job snapshot and current
MCP grants. CapabilityService independently checks enabled state and grants before/after
reads. Pages hold at most ten publications, excerpts at most 2,000 characters, context
has no neighbors, and the evidence registry caps at 100 unique publications per job.
The archive searches a literal phrase; online search uses the existing Telegram search
service. No tool joins, imports, alters sources/subscriptions/consent, sends to candidates,
executes commands, reads files or performs arbitrary HTTP/TDLib operations.

The application assigns evidence references only to actually returned publications.
Unknown/duplicate references and oversized/invalid results fail. Original Telegram URLs
come from existing saved metadata or the single TDLib GetMessageLink adapter, validated
as HTTPS t.me URLs; TDLib message IDs are never converted into guessed post numbers.
Missing links are explicitly displayed. The model receives reference/date/text, not
operator IDs, credentials or links it can forge. Model URLs are removed from summaries
and reasons; React renders text without HTML and Bot API uses plain text without model
buttons. Publication text is labeled untrusted data and separated from developer
instructions. These boundaries prevent tool escalation; semantic AI accuracy and
resistance to misleading source claims still require human review of the originals.

## Local REST and verification

`GET /api/agent/settings`, `PUT /api/agent/consent`, `GET/POST /api/agent/jobs`,
`POST /api/agent/jobs/{id}/cancel`. History uses a bounded 50-job monotonic creation
cursor (`before`). Responses carry no-store. Existing exact Host/Origin policy is
reused; mutations require an application-generated CSRF token. Telegram histories need
`X-Hradar-Agent-Operator` with the private pairing code. Missing code selects the local
operator, not an arbitrary bot recipient. The model never receives these endpoints.

Offline tests use isolated temporary SQLite, fake AgentPort/TelegramReadPort/BotGateway,
synthetic source content and Responses contract fixtures. Browser tests block all backend
traffic and cover consent, operator headers, status/cancel, results, safe links/text,
restart notices and mobile layout. No automated test requires credentials, JNI, a live
Telegram account, external AI traffic or a real recipient.

Manual acceptance after an authorized deployment: configure a compatible model/key and
prices; use a synthetic public source whose external processing is approved; register,
pair, consent, run one bounded `/ask`, verify provider usage/budget and final citations
against original publications on the phone and web; repeat cancellation, revoked source,
429/unavailable model and backend restart. This live paid check requires separate explicit
authorization and was not performed by this task.

For rollback stop the backend and preserve/back up both SQLite files. Restore a stopped
agent-database backup before using an older agent schema; do not downgrade tables or
remove research/archive/session data. A pre-AI binary ignores the separate agent DB.
