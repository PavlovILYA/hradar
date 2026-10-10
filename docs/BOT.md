# Telegram bot: notifications and subscription controls

The optional Bot API adapter uses HTTPS long polling and the existing application
services. It has a separate bot identity; TDLib remains a single read-only **user**
client. No webhook, tunnel, second TDLib session, LLM or Codex execution is involved.

## Setup and real verification

1. In Telegram, open the official [@BotFather](https://t.me/BotFather), run `/newbot`
   and follow its instructions. Save the token **only** in your ignored local `.env`;
   do not paste it into chat, Git, command-line URLs or screenshots. Use a dedicated
   bot that is not already running another getUpdates consumer or webhook.
2. Obtain your own numeric Telegram user ID. For the account already authorized in
   hradar, open `http://127.0.0.1:8080/api/telegram/me` locally and read its `id` field.
   This is a user ID, not a channel ID, username or phone number. Register that same
   account in the bot. No third-party ID bot is required.
3. Add to `.env` (values below are placeholders):

   ```dotenv
   TG_BOT_TOKEN=<token obtained privately from BotFather>
   TG_BOT_ALLOWED_USER_IDS=<your positive numeric user ID>
   ```

   Multiple trusted operators may be comma-separated, e.g. `123456789,987654321`.
   In the actual file replace the placeholders with real values, without angle
   brackets. Every operator can see **all monitored sources/subscriptions** and
   pause/resume shared rules. Only add people authorized to receive those excerpts.
4. Stop the current backend before restarting with `./scripts/run-local.sh`. Keep
   exactly one backend/poller for this bot. Without a token the app works as before;
   missing, empty or malformed allowlist blocks the bot entirely. Credentials and
   recipient IDs are not exposed in the frontend or status REST response.
5. From the allowlisted account open the bot's **private** chat and send `/start`.
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
A 401 stops requests until configuration is corrected and the backend restarted.
For 409, stop the competing poller or remove a pre-existing webhook yourself using
Telegram's documented procedure. hradar never silently deletes another integration's
webhook. Do not run ad-hoc getUpdates while hradar is polling.

## Delivery and persistence

- Schema v5 adds `bot_recipients`, `bot_state` and `bot_outbox`. Migration is forward
  and transactional; existing archive, templates, notifications and MCP settings
  remain intact. Before a production upgrade, stop the app and back up the local
  database. Older v4 binaries reject the newer schema; rollback requires that backup,
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
replies. No Bot API transport is exposed as an MCP tool or arbitrary sender.

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
