# Telegram Inbox, notifications and subscription controls

The optional Bot API adapter uses HTTPS long polling and the existing application
services. It has a separate bot identity; TDLib remains a single read-only **user**
client. No webhook, tunnel, second TDLib session, LLM or Codex execution is involved.

## Setup and real verification

1. Finish Telegram sign-in in hradar. Open **Интеграции → Telegram Bot**. Create a
   bot with [@BotFather](https://t.me/BotFather), paste its token into the local
   form, choose **Проверить токен**, then **Сохранить подключение** and **Включить
   бота**. hradar reads the current account's numeric ID from its authorized TDLib
   session and adds it automatically. No external ID bot or backend restart is needed.
   Use a bot without another getUpdates consumer or webhook.
2. Open the connected bot from hradar and send `/start` in its **private** chat.
   Telegram does not allow a bot to start the conversation. The owner's access is
   granted on enable, but delivery begins only after this opt-in.
3. To add another person, create a one-use invitation link (valid for 10 minutes)
   and send it privately. It works without a public username; Telegram supplies the
   sender's numeric ID after they press Start. You may also find a public @username,
   confirm the displayed account and ask that person to open the bot and send `/start`.
   Revoke access or outstanding invitations from the same screen. Every authorized
   operator can see all monitored sources/subscriptions and pause/resume shared rules.
4. The prior `TG_BOT_TOKEN` and `TG_BOT_ALLOWED_USER_IDS` environment setup still
   works when no web configuration has been saved. Once web settings exist, they
   take precedence even if legacy environment values remain. Connecting the same
   bot through the web interface retains previously allowed IDs and their queue.
   The token is kept in
   a private-permission local file and never returned by status endpoints.
5. From an authorized account, use the bot's **private** chat. After `/start`,
   The bot confirms registration. In hradar open **Интеграции → Telegram Bot**:
   check connection, registered-recipient count and queue status. `/status` reports
   TDLib/monitoring/active subscriptions. `/subscriptions`, `/pause`, `/resume`
   show paginated inline buttons; a click changes the same rule visible in React.
   Before `/start`, other commands are ignored. Commands targeting another bot,
   unlisted senders, bots, groups and mismatched sender/chat IDs are ignored.
6. For an actual notification, save a public test source you control or are explicitly
   permitted to monitor, create a matching template and enable its subscription in
   hradar. Publish a **new** matching test post yourself, after `/start` and after
   enabling the subscription. Do not use third-party channels for test publication.
   The existing monitoring worker receives TDLib updates where available and performs
   bounded recovery polling otherwise (normally about one minute). The adapter does
   not join channels or introduce a second channel poller.
7. Confirm one match in the UI, its outbox delivery, and the actual message on your
   phone. Verify the filter name, source, excerpt and optional original link. A Bot
   API send success means Telegram accepted the message; it does not prove that your
   phone displayed/read it. `/pause` should prevent new matches for the selected
   subscription; `/resume` starts from its new subscription time. Already queued
   notifications remain deliverable after pause.

If blocked by Telegram (403), unblock the bot, check the allowlist and send `/start`
again for future events. Permanently rejected 400/403 deliveries remain failed for
inspection; they are not retried indefinitely or automatically resent by `/start`.
A 401 stops requests until the token is corrected in the web settings.
For 409, stop the competing poller or remove a pre-existing webhook yourself using
Telegram's documented procedure. hradar never silently deletes another integration's
webhook. Do not run ad-hoc getUpdates while hradar is polling.

## Delivery and persistence

- Schema v9 adds `bot_access` and `bot_invitations`; schema v5 added
  `bot_recipients`, `bot_state` and `bot_outbox`. Migration is forward
  and transactional; existing archive, templates, notifications and MCP settings
  remain intact. Before a production upgrade, stop the app and back up the local
  database. Older binaries that do not understand v9 reject the newer schema; rollback requires that backup,
  not deleting or modifying the current database.
- `NotificationService` is the only matching/notification pipeline. New match,
  in-app notification and opted-in bot outbox rows commit in one SQLite transaction.
  Unique `(bot_id,event_key,user_id)` plus existing match uniqueness prevent duplicate
  event creation. No historical matches are backfilled; publications dated before
  recipient registration are not queued by recovery. Registration is idempotent.
- Commands, replies and getUpdates cursor commit atomically. Repeated updates do not
  reapply pause/resume or create another reply. Cursors, recipients, attempts and
  retry deadlines survive restart. Callback acknowledgements are best effort; the
  command result remains in the durable reply queue.
- The single delivery worker spaces attempts by at least 1.1 seconds. Network errors
  use exponential backoff (2 seconds up to 5 minutes); 429 honors Telegram's
  `parameters.retry_after`, persisting cooldowns and preserving the pending event.
  Permanent 400/403 errors become failed deliveries. Removing an ID from the
  allowlist cancels that recipient's pending deliveries and registration; re-adding
  it requires `/start`. Changing bot identity keeps its recipients/queue separate.
- Delivery is **at least once for retryable failures**, not transactional exactly once:
  a crash/cancellation after Telegram accepts sendMessage but before SQLite confirms
  success can deliver a duplicate. Permanent errors cannot guarantee delivery.
  Sent/cancelled rows keep deduplication keys but discard their excerpt/reply payload;
  pending/failed rows contain only a short excerpt or command reply, not full history.
- Original links come from TDLib `GetMessageLink` (bounded optional lookup). Failed
  lookups result in a message without a link. TDLib message IDs are never converted
  into speculative t.me post numbers. Messages use plain text with previews disabled.
- Closing the browser does not stop bot delivery; stopping/sleeping/offlining the Mac
  does. On restart the saved queue resumes. Disabled configuration creates no new
  bot events and does not drain existing events. It never forwards all old matches
  after enabling.

`GET /api/integrations/bot` is a read-only operational summary: state, safe notice,
counts, retry deadline and last successful poll. It never returns the token, raw Bot
API errors, message bodies, user IDs or local files. Queue counts include command
replies. No arbitrary Bot API transport or arbitrary-chat sender is exposed. MCP can request a constrained publication only through the consent flow below.
`GET /api/integrations/bot/settings` additionally returns bot identity, owner and
authorized user IDs, registration states and pending invitation IDs/expiry, but never
tokens or invitation links. All bot-setting mutations require the desktop cookie when
enabled, exact local Host/Origin and CSRF header. A new invitation link is returned
only by its creation response; the database stores its SHA-256 hash.

## Automated checks and external evidence

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :backend:build
npm run build --prefix frontend
npm run test:ci --prefix frontend
```

Backend tests use a fake BotGateway, synthetic updates and temporary SQLite files;
no bot token, live TDLib account or internet is required. They cover allowlist/private
chat checks, opt-in/no backfill, matching/outbox deduplication, network/429 retries,
restart, commands/callbacks, cancellation, migration and the disabled REST status.
Browser tests mock the bot status endpoint and exercise refresh/error/queue display.
Real getUpdates/sendMessage and delivery to a phone require the manual steps above;
fake tests alone do not establish those external outcomes.

Official protocol references: [Bot API](https://core.telegram.org/bots/api#getupdates),
[sendMessage](https://core.telegram.org/bots/api#sendmessage),
[BotFather](https://core.telegram.org/bots/features#botfather).
Invitation payloads follow Telegram's [64-character deep-link limit](https://core.telegram.org/bots/features#deep-linking): `i_` plus 43 base64url characters from 32 random bytes.

## Telegram Inbox and agent publication

After `/start`, `/help` describes commands. `/matches` opens saved SQLite matches,
10 per page, newest first. Inline buttons open a publication, switch to the adjacent
newer/older publication, return to the list, and mark it read. These callbacks edit
that bot message through the durable outbox; synthetic callbacks without a message
ID fall back to a new reply. Read state is shared with the web Inbox and all operators;
opening a match does not silently mark it read. Deleting a match makes old buttons
return an explicit unavailable message. The excerpt is limited to 600 characters.
“Оригинал” uses only a validated TDLib-provided t.me link; failed lookup omits it.
`/sources` lists saved sources in pages of 15 without joining/importing them.
`/digest` summarizes the current unread backlog (up to 15 source counts), not a
scheduled send or an online search.

Agent publications have two independent gates:

1. The same opted-in operator sends `/agent_publish on` in their private bot chat.
   This enables publication requests only, not model execution or arbitrary sends.
2. MCP `bot_publish_matches` requests 1–10 existing match IDs from the MCP source
   allowlist for that recipient. The bot shows the concrete selection with bounded
   excerpts and detail buttons. Review buttons open separate cards and preserve the
   confirmation keyboard. That operator must press “Подтвердить” within ten
   minutes; only then are publication results queued. “Отмена” permanently rejects
   that request. Other users cannot confirm it. Confirmation survives restart and
   repeated callbacks cannot enqueue another result for the same request.

`/agent_publish off` revokes permission and cancels pending previews/results.
Disabling MCP or revoking any source cancels all pending agent publications
conservatively, including confirmed results not yet sent. Delivery rechecks current
permission and source allowlist. Removing the operator from the bot allowlist clears
publication permission; re-adding requires `/start` and new explicit permission.
There is no arbitrary text/destination argument, user-account send or agent execution.
Three requests per recipient per rolling ten-minute window bound preview spam;
MCP returns the request ID and awaiting-confirmation state, never “delivered”.
A send already in flight cannot be recalled; Bot API crash/retry limitations apply.

Schema v6 adds `bot_publish_permission` and `bot_publish_request` transactionally,
without modifying archived content or existing recipient/outbox records. Back up the
stopped database before upgrading. A v5 binary rejects v6: recovery requires restoring
that backup and restarting the older binary, not downgrading tables in place.
Expired request references are cleaned on the next publication request; request
payloads store match IDs, not duplicate publication text. Excerpts use existing
outbox retention rules. Failed edits (e.g. a deleted bot message) remain inspectable
as failed deliveries; reopen `/matches` to continue.

Manual check after deployment: stop the existing backend, back up the database,
start exactly one backend from this branch, then use `/start`, `/help`, `/matches`,
`/sources`, `/digest`. Open two saved matches, navigate, mark one read and verify
web read state. Verify the original link. Grant one synthetic public source in MCP,
use `/agent_publish on`, request its saved matches, cancel once and confirm a new
request; verify actual phone delivery. Revoke permission/source before another
confirmation and verify denial. Do not launch a second backend or send a test post
from hradar. Live steps have not been performed by automated tests.

Agent runtime proposal: [AGENT_BRIDGE.md](AGENT_BRIDGE.md). Inline keyboard protocol:
[official Bot API](https://core.telegram.org/bots/api#inlinekeyboardbutton).

## Mobile recruiter workflow (`/find`, `/watch`, `/saved`)

The current product priority is the operator's phone workflow. The previously shipped
MCP publication consent flow remains available but is not needed to search, save,
create subscriptions or review a digest.

### Search and review

Send `/find java` (or another query, up to 256 characters). Choose **В Telegram** or
**В локальном архиве**, then a saved public source or **Все сохранённые**. Telegram
uses the existing SearchService in tracked-source mode, never global/private-chat
search. Archive uses the existing SQLite history search: a literal normalized phrase,
not an AND query parser. Neither mode joins channels, imports history or claims to
search all Telegram. An archive with no imported messages is honestly empty.

Cards offer previous/next, original link, Save, Not suitable, and Create subscription.
One explicit next-page action reads at most ten results; at most five pages/50 cards
are retained per search, including empty/duplicate pages. Narrow the query with a new
`/find` after reaching the limit. Saved/rejected publications are omitted from new
unprocessed searches for that operator. “Не подходит” is durable per `(bot identity,
operator, source, TDLib message ID)`, independent of other operators and templates.
Inbox/digest listings and subsequent duplicate-publication notifications also respect
that operator's rejection. Saved publications remain available through `/saved`.
Removing a saved publication sets REMOVED, so it may be reconsidered in a future search.

The original link is validated, obtained from TDLib when available and never guessed
from a TDLib ID. Optional link lookups remain bounded; missing links are omitted.
Resolved links are retained with the workflow/saved snapshot for the React view.

### Create a real subscription

Send `/watch` or choose **Создать подписку** on a search card:

1. Enter a filter name (1–100 characters).
2. Enter required words/phrases separated by commas (AND), or `-` to skip.
3. Enter alternatives separated by commas (OR), or `-` for none. At least one
   required or alternative term must exist. Each list allows up to 20 terms,
   100 characters per term and 700 characters total so the preview fits on one card.
4. Enter exclusions separated by commas (NOT), or `-` for none.
5. Toggle saved sources, or choose all; at least one and at most ten are required.
6. Review the exact conditions, source usernames and Telegram query, then confirm.

The first required term (or first alternative) becomes the bounded Telegram search
query; the existing MatchingService checks the full criteria. SubscriptionService
validates/saves the actual SearchTemplate with monitoring enabled and a fresh
subscription time. It appears in React's templates and existing `/subscriptions`,
`/pause`, `/resume`. Creation does not backfill historical notifications. To test a
new notification, personally publish a fresh matching post in an authorized test
source after confirmation. No independent matching or notification engine exists.

`/cancel` abandons the draft without creating a rule. Only explicit text responses
inside the active `/watch` dialogue are interpreted as form fields; publication
content/free text outside it is never an executable prompt. Each rendered keyboard
has a new operator-bound nonce, preventing a stale card from saving another card's
publication. Workflows expire after one hour and survive normal restart; expire/delete
cleanup is lazy on the next workflow update. `/start` is still required.

### Saved publications and digest

`/saved` shows only that operator's saved cards, with remove/reject/original controls
and bounded pagination. SQLite stores a maximum 4,000-character publication excerpt,
status and a unique operator/publication identity; duplicate Save is an upsert.
React **Избранное** reads the same SQLite records, shows statuses and paginated cards,
and offers refresh. It aggregates local operators without exposing their numeric IDs;
it is a review list, not candidate profiles or an ATS. No browser mutation of another
operator's decisions is exposed.

`/digest` defaults to today, with buttons for today or rolling last seven days. Today
uses the backend's local timezone (shown in the response). Counts are real saved
monitoring matches by publication date, with the existing shared read flag for unread
count; they are not the favorites count or generated recommendations. Open individual
matches from the digest buttons and use **Прочитано** to update shared React/bot read
state. Empty periods and earlier pages are explicit. Operator-rejected posts are excluded.

### Persistence, migration and manual acceptance

Schema v7 adds `bot_workspace` and unique `publication_decisions`; it also backfills
an indexed publication-date column on existing matches. Migration is transactional,
retains archive/rules/bot registration/cursors/outbox and does not backfill events.
Back up the stopped database before upgrading. A v6 binary cannot read v7: restore
that backup for rollback, never delete/downgrade tables in the production database.
Saved decisions retain excerpts until a separate retention/deletion policy is added;
no external AI receives them. Existing outbox crash/retry/edit limitations still apply.

Phone acceptance: `/find java` → choose Telegram and an authorized saved source →
next/previous → Save → `/saved` → Create subscription (from a new `/find` card) or
`/watch` → form → preview/confirm → check React templates → personally publish a
fresh matching synthetic post in the authorized source → receive bot notification →
`/digest` today/seven days → open match and mark read. Also test archive mode, repeat
Save, Not suitable followed by the same search, cancel, restart and a stale keyboard.
Fake-port tests establish application behavior, not actual phone delivery or search
availability on a live Telegram account.

MCP additions are **read-only** `list_saved_results` and `get_saved_result`, clipped
and filtered by the separate MCP source allowlist. `/ask` reports that agent execution
is not connected. Its scoped runtime specification is in [AGENT_BRIDGE.md](AGENT_BRIDGE.md).
