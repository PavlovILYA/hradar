# Telegram login

hradar uses one official TDLib client. Telegram decides how a code is delivered;
a working SIM/eSIM does not guarantee SMS delivery to a third-party application.
The UI describes only the delivery method reported by Telegram. Unknown delivery
is shown explicitly. This is not evidence of an account restriction or a broken SIM.

## Recovering a login step

- For an incorrect code, correct the value and submit again. Failed requests keep
  the form input. A successful transition clears temporary code/password fields.
- For an expired code, use **Отправить повторно** when Telegram permits it. The
  next delivery method and remaining server timeout are shown. Without a next
  method TDLib does not permit an ordinary code resend.
- Email codes can also be resent. This TDLib version exposes no email resend
  timeout; the screen explains that hradar applies a local 30-second interval.
  A server rate limit takes precedence over that local interval.
- **Изменить номер** opens an editor without pretending the TDLib state changed.
  Submit the new number to begin authentication for it. **Назад к коду** cancels
  editing and preserves the previous input. From 2FA/email the same supported
  phone replacement operation is used. No logout, destruction or session reset
  is performed. From states that do not support replacement, follow the displayed
  Telegram instruction; closing/reopening hradar preserves the local session.
- A request in progress disables duplicate submission. Stale forms and concurrent
  requests are rejected by the backend, including requests from another tab.

## Delivery and errors

Supported delivery descriptions: Telegram service message, numeric SMS, SMS word,
SMS phrase, voice call, flash call, missed call, Fragment, official Android/iOS
Firebase authentication and email. The desktop app does not emulate mobile
attestation or automatically read calls/SMS. Official-app-only methods require the
appropriate Telegram client. hradar cannot force a different delivery method.

Known failures have fixed safe messages: invalid/expired code, invalid 2FA password,
invalid phone/email, too many attempts/FLOOD_WAIT, resend unavailable, network/server
failure, changed state and additional confirmation. Unknown errors use a generic
message. Error 406 is never interpreted. Numeric FLOOD_WAIT intervals are enforced
across login, phone replacement and resend; no raw Telegram text is returned/logged.

The structured error response also contains `diagnostics` with exactly four fields:
`telegramCode` (numeric TDLib error code in 100–599, or null), `identifier` (closed
enum whitelist), `state` (known current authorization stage or `Unknown`) and
`retryAfterSeconds`. Known exact errors retain identifiers such as
`PHONE_CODE_INVALID` and `PHONE_CODE_EXPIRED`; retry formats normalize to
`FLOOD_WAIT`. Unknown non-rate-limit text, including known prefixes with appended data, becomes
`UNKNOWN`. Error 406 retains only its numeric code and `UNKNOWN`. Local failures
use `LOCAL_*` identifiers with no fabricated Telegram code.

**Технические подробности** in the UI shows the safe code/identifier, current stage
and current wait. **Скопировать диагностику** exports only these four fields as
JSON. The frontend independently validates code/identifier/stage and constructs a
fresh object; it never copies the full response, form, error message, destination,
CSRF/launch token or credentials. Even masked phone/email are excluded. Clipboard
failure produces a fixed message without browser exception details.

## API and privacy

The guarded local endpoints remain finite: GET `/api/onboarding`, POST
`/credentials`, `/login`, `/phone`, `/resend`. Phone/login/resend payloads use state,
optional revision and a value. The current UI always sends the revision; an old
revision cannot act on a newer code challenge. Desktop cookie, exact Host/Origin,
CSRF, bounded requests and `no-store` remain enforced. No generic executor exists.

The DTO contains a delivery category, masked destination, code length, next method,
remaining cooldowns, request status and available phone action. Full phone/email,
code fragments, password hints, Firebase tokens and raw errors are excluded.
Credentials and session keep their existing private `~/.hradar/` locations.

The method signatures and allowed states were checked against the pinned
[TDLib schema](https://github.com/tdlib/td/blob/bc9c263e2bfee06aaab41e82db51a103376030bc/td/generate/scheme/td_api.tl).
In this pin `ResendAuthenticationCode(null)` handles both code and email-code
states; `ResendAuthenticationEmailCode` does not exist. Phone replacement uses
`SetAuthenticationPhoneNumber(newNumber, null)` in its documented login states.

## Physical Mac check

Use a controlled account and a stopped-profile backup. Run only one hradar instance.
Complete API setup and phone login, compare the displayed delivery method with the
actual message, try an incorrect/expired code, resend after the countdown, edit a
mistyped number and complete email/2FA if requested. Verify light/dark appearance
and a narrow window. Do not reset the session to force a different delivery method.
Actual code delivery and account-specific Telegram limits cannot be proved by fake
tests. Automatic tests use synthetic data and never start a real TDLib client;
the test JVM removes inherited `TG_*` environment variables.
