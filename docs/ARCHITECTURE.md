# TiP architecture

## What it is

TiP turns a physical object into a PayPal tip jar that reacts. A street performer, a small shop or a delivery driver puts a TiP device on the counter. A customer taps it with their phone, picks an amount, optionally writes a message and pays with PayPal. Claude reads the message and decides what kind it is: warm, playful, a request (or feedback the owner can act on), or plain. The device plays a light pattern for that kind. The owner sees every tip, an AI summary of the day, short notes on requests and feedback, and flagged messages, hidden by default, that they can read, overrule or refund.

The panda reacts to the kind of message, never to abuse. Almost nobody pays to insult someone, and the few who do are trolls, for whom a dramatic reaction is a reward: so a flagged message plays the same plain blink as a tip without a message, and the troll gets nothing to film.

A plain PayPal QR code already takes money. What it cannot do is react, filter abuse, or tell the owner what people are saying and asking for. That is the gap TiP fills.

## Components

```
 Phone (browser)                    Backend (Spring Boot, one instance on Render)          Devices
 ┌───────────────┐   HTTPS   ┌──────────────────────────────────────────────┐   WSS   ┌──────────────┐
 │ Tip page      │──────────▶│ tipping   create tip, capture, state machine │◀───────│ ESP32 panda  │
 │ PayPal JS SDK │           │ paypal    Orders v2, webhooks, refunds       │        └──────────────┘
 └───────────────┘           │ ai        classify message, daily summary    │   WSS   ┌──────────────┐
                             │ devices   device channel, observer channel   │◀───────│ Virtual panda│
 Owner (browser)   HTTPS     │ dashboard tip history, summaries, moderation │        │ (browser)    │
 ┌───────────────┐──────────▶│ owners    login                              │        └──────────────┘
 │ Dashboard     │           └───────────────┬──────────────────────────────┘
 └───────────────┘                           │
                              PostgreSQL ◀───┘──▶ PayPal sandbox API, Anthropic API
```

One deployable with feature packages. Features talk through `api` subpackages and Spring application events, and ArchUnit keeps those boundaries honest. That gives two people and two agents clear lanes without the cost of microservices.

## Main flow

1. The NFC tag on the coin holds `https://<host>/t/{deviceSlug}`. A tap opens the tip page.
2. The page calls `POST /api/public/devices/{slug}/tips` with amount, currency and optional message. The server validates them, stores a tip in `CREATED`, creates a PayPal order for exactly that amount, and returns `tipId` and `paypalOrderId`.
3. The tipper approves in the PayPal popup.
4. The page calls `POST /api/public/tips/{tipId}/capture`. The server looks up the order id itself and captures it.
5. PayPal also sends `PAYMENT.CAPTURE.COMPLETED` to `/webhooks/paypal`.
6. Whichever of step 4 or 5 moves the tip from `CREATED` to `CAPTURED` first publishes `TipCaptured`, after the transaction commits.
7. The `ai` feature classifies the message off the request thread and stores its kind, the reaction (`PLAIN` when flagged, otherwise the kind), the flag and, for a request, a short owner note. In the same step it decides `repeats` (1 to 5) from the tip amount, always 1 when flagged, so bigger tips get a longer pattern.
8. The `devices` feature stores a delivery row with the reaction and its repeats, pushes it to the device channel and to any observers of that device, and resends it until the device acknowledges it.

## Tip states

```
CREATED ──▶ CAPTURED ──▶ REFUNDED
   │  ╲         │
   │   ╲        └──────▶ REVERSED (chargeback)
   │    ╲──▶ PENDING ──▶ CAPTURED | FAILED (denied or declined)
   ▼
 FAILED (no order)   CREATED older than 6h ──▶ ABANDONED (scheduled job)
```

`PAYMENT.CAPTURE.DENIED` and `PAYMENT.CAPTURE.DECLINED` move only `PENDING` to `FAILED`: a pending capture that PayPal refused in the end. For a `CREATED` tip they change nothing: the tipper's capture already answered `INSTRUMENT_DECLINED`, the page let them pick another payment method, and failing the tip would turn their retry into 409 `TIP_CLOSED`. A `CREATED` tip fails only when PayPal cannot create its order or no longer knows it. `PAYMENT.CAPTURE.REVERSED` moves `CAPTURED` to `REVERSED`. A capture request for a `FAILED` or `ABANDONED` tip gets 409 `TIP_CLOSED`. A capture PayPal confirms (the capture answer, an order lookup or a verified webhook) also moves a `FAILED` or `ABANDONED` tip to `CAPTURED`: the money moved, for example after a lost capture answer whose webhook came after the 6 hours, so it is recorded.

Every transition is a conditional update:

```sql
UPDATE tip SET status = 'CAPTURED', paypal_capture_id = ?, captured_at = ?
WHERE id = ? AND status IN ('CREATED', 'PENDING', 'FAILED', 'ABANDONED')
```

The caller that gets one affected row owns the transition and is the only one that publishes the event. Anyone who gets zero rows reads the current state and returns it.

## Concurrency and failure modes

**Capture response and webhook race.** Both arrive within milliseconds and both say the payment completed. Without care the panda reacts twice or the tip is double counted. The conditional update above makes exactly one winner, enforced by PostgreSQL row locking, not by application code. A test runs both paths on two threads against real PostgreSQL and asserts one event.

**PayPal retries webhooks.** Every event id is inserted into `paypal_webhook_event` with a unique constraint in the same transaction as the state change. A duplicate hits the constraint and we return 200 without doing anything. If processing fails, the transaction rolls back, the event row is not kept, and PayPal's retry gets a clean second attempt.

**Lost answers and webhooks.** Two jobs settle a tip whose capture answer and webhook both went missing, asking PayPal through the order lookup outside any transaction, 20 tips a run, oldest first, and counting a capture only for exactly the tip's amount and currency. Every hour, a tip `PENDING` for more than an hour is looked up: a completed capture records it, a declined one fails it, anything else waits for the next run. Every 10 minutes, a `CREATED` tip older than 6 hours is abandoned, but only a tip whose capture was attempted (`tip.capture_attempted_at`, set before the first capture call) is looked up first: a completed capture records it, a pending one pends it, anything else abandons it. A tip whose capture was never attempted was never approved, so its order is never looked up.

**Our own retries to PayPal.** Order creation and capture send a `PayPal-Request-Id` derived from the tip id, so a timeout followed by a retry cannot create two orders or two captures.

**AI exactly once per tip.** `TipCaptured` is published after commit and handled on a virtual thread. The capture listener and a recovery job can both reach the same tip, and a worker can crash or hang mid call, so ownership of the work is a claim in PostgreSQL, not application state:

```sql
UPDATE tip SET ai_status = 'PROCESSING', ai_claimed_at = :now, ai_attempts = ai_attempts + 1
WHERE id = :id AND status = 'CAPTURED'
  AND (ai_status = 'PENDING' OR (ai_status = 'PROCESSING' AND ai_claimed_at < :now - interval '2 minutes'))
  AND ai_attempts < 3
```

```
PENDING ──claim──▶ PROCESSING ──▶ DONE        (classified)
   ▲                   │      ──▶ SKIPPED     (no message, or AI disabled: PLAIN, no kind, no API call)
   │                   │      ──▶ UNAVAILABLE (the call failed or the model rests: PLAIN once, never retried)
   └─ invalid answer ──┘      ──▶ FAILED      (third invalid answer: PLAIN, no kind, not flagged)
                       (past the classification cap: UNAVAILABLE without a call)
       (attempts < 3)
```

Zero rows means another worker has the tip or it is finished. The final write is conditional on `ai_status = 'PROCESSING'` and the worker's own `ai_claimed_at`, so a slow worker whose claim expired and was taken over cannot overwrite the newer result. Only the final write that changes the row sets `reaction_at` and publishes `TipReacted`, after commit, so every captured tip gets exactly one reaction event.

**Crash between capture and AI.** If the process dies after the capture commits, the tip stays `CAPTURED` with `ai_status = PENDING`. If it dies during a call, the tip stays `PROCESSING` until its claim expires after two minutes. A recovery job runs every 30 seconds and runs the same flow for both. A tip whose worker died on the third attempt is failed as `PLAIN` by the same job once that claim expires.

**AI slow or down.** Claude calls have a 10 second timeout and SDK retries are off. A call that fails, whatever the reason (a timeout, a lost connection, 429, 5xx, a spend limit or any other 4xx), is never made again: the tip is stored at once with `ai_status = UNAVAILABLE` and plays `PLAIN` once, whatever the amount, because the payment already happened and the device must still confirm it, while repeating calls during an outage would only multiply them. Only an answer outside the schema puts the tip back to `PENDING` for the recovery job, up to three attempts, after which it gets `PLAIN` with `ai_status = FAILED`.

**Classification cap.** Production takes PayPal sandbox payments, which cost nothing, so a paid tip alone does not bound how many messages anyone can make the model read. `ClassificationCap` allows the whole app 200 classification calls a UTC hour and 1000 a UTC day, in memory. Past either, a tip is not classified, now or later: it is stored at once as `UNAVAILABLE` and plays `PLAIN` once, like a failed call, and the first refusal in each window logs one WARN `ai_cap_reached`. Tips without a message take nothing from it; the digest is bounded on its own and does not count.

**AI spend limit.** The hard cost limit is the spend limit in the Anthropic console. Any 400, 401, 402, 403 or 404 from the model API opens a breaker, whatever its wording: these cover the spend limit, the credit balance, authentication, permissions and a missing model, none of which a retry fixes, and matching on the API's message text would break when the wording changes. For 10 minutes no call is made, and tips react `PLAIN` once as `UNAVAILABLE`; then one trial call goes through, and its success closes the breaker while another such answer rests it for 10 more minutes. 429, 5xx and timeouts stay per call: that tip is `UNAVAILABLE`, the next one calls as usual. `ai_breaker_opened` logs the status and the API's error type and message, which describe the request, never the prompt or a tip, cleaned and capped at 200 characters. The digest job goes through the same breaker and keeps the stored notes. `GET /api/owner/me` says `aiResting: true` meanwhile. Every call and failure is counted by the hour in `ai_usage` for the daily summary log line.

**Refund or reversal before its capture.** PayPal does not promise the order of its webhooks. A `PAYMENT.CAPTURE.REFUNDED` or `REVERSED` whose tip is still `CREATED` or `PENDING` (its `COMPLETED` delayed) answers 503 `WEBHOOK_BEFORE_CAPTURE` and the event is not stored, so PayPal delivers it again later, after the capture has landed, and the tip ends `REFUNDED` or `REVERSED` instead of showing as captured. A refund whose capture no tip has stored is acknowledged and changes nothing.

**Refund response and webhook race.** An owner's refund calls PayPal with `PayPal-Request-Id: refund-<tipId>`. A `COMPLETED` answer and the `PAYMENT.CAPTURE.REFUNDED` webhook both try the conditional `CAPTURED` to `REFUNDED` update, so exactly one makes the transition. A `PENDING` answer leaves the tip `CAPTURED` and the webhook finishes it.

**Device offline.** Every reaction gets a `reaction_delivery` row. When a device reconnects, it receives reactions from the last 60 seconds that it has not acknowledged, oldest first. Older ones are dropped, because a light reacting to a tip from an hour ago means nothing to anyone standing there.

**Duplicate reactions.** `reaction_delivery` has the tip id as its primary key and the dispatcher inserts with `ON CONFLICT (tip_id) DO NOTHING`. Only the insert that creates the row sends, so a repeated `TipReacted` neither stores nor sends twice.

**Lost acks.** Delivery is at least once. A reaction without an ack is sent again 5 seconds after its last send, up to 3 times, while it is under 60 seconds old. The device does not play repeats again, by tip id, but acks every reaction it receives, so a lost ack costs one resend, not three. An ack is a conditional update on the session's own device, so a device cannot acknowledge another device's tip:

```sql
UPDATE reaction_delivery SET acked_at = :now
WHERE tip_id = :id AND device_id = :sessionDevice AND acked_at IS NULL
```

**Reconnect race.** A device whose WiFi drops may reconnect before the server notices the old connection is gone. The new session replaces the old one in the registry, and the old one is closed with 4000. Its close callback runs later and removes the entry only if it still holds that same session (`remove(deviceId, session)`), so a late close can never evict the new connection.

**Concurrent sends.** The dispatcher, the resend job, the keepalive and the connect replay can all send to one session at the same time. A raw WebSocket session is not safe for that, so every stored session is wrapped in Spring's `ConcurrentWebSocketSessionDecorator` (5 second send limit, 64 KB buffer). A session that cannot keep up is closed rather than blocking the senders.

**Dead connections.** A device that loses power leaves a half open TCP connection the server cannot see. The server pings every session every 20 seconds and closes a session that has not answered for 45 seconds, two missed pings, with 1001. The device then gets its reactions through replay when it is back.

**Deploys.** On shutdown the server closes every device and observer session with 1001 (going away), so devices reconnect with their backoff to the new instance instead of waiting for a dead connection to time out.

**Close codes do not survive the proxy.** Render's proxy delivers every server close to the client as 1005 (no status), so a device cannot read 4000 or 1001. Before closing a device session for replacement, a missed keepalive or shutdown, the server sends `{"type":"closing","reason":...}`, and the device acts on that. On `replaced` it waits at least a minute before reconnecting: two clients with the same identity otherwise replace each other every second, which happened in Brief 05b's end to end run with the fake device and the panda both connected as the demo device.

**Delivery dispatch is in memory.** The dispatcher runs after commit on its own virtual thread executor. Run synchronously on the publisher's thread, it would hold the committed transaction's connection while taking a second one, and enough simultaneous reactions exhaust the pool. Known limit: a reaction handed to that executor is lost if the process dies before the task runs. The tip is safe, it is captured and its reaction is stored on it; only that one light pattern is missed. A reaction already in `reaction_delivery` survives a restart through replay on connect.

**Single instance.** WebSocket sessions live in an in-memory registry, so the backend runs as one instance. For several instances, dispatch would go through PostgreSQL `LISTEN/NOTIFY` or Redis pub/sub. This is a deliberate limit for the hackathon, not an oversight.

## Data model (core)

| Table | Key columns |
|---|---|
| `device` | `id` uuid, `slug` unique, `display_name`, `owner_id`, `secret_hash`, `created_at`, `last_seen_at` null |
| `tip` | `id` uuid, `device_id`, `amount_minor` bigint, `currency` char(3), `message` varchar(280) null, `status`, `paypal_order_id` unique, `paypal_capture_id` unique null, `capture_attempted_at` null, `message_kind` null (`WARM`, `PLAYFUL`, `REQUEST`, `PLAIN`), `reaction` null (same values, `PLAIN` when flagged), `flagged` bool, `flag_reason` null, `owner_note` varchar(100) null (requests only), `message_language` varchar(2) null, `message_english` varchar(280) null, `requested_song` varchar(80) null, `requested_song_key` varchar(80) null (partial index), `owner_verdict` null or `NOT_ABUSIVE`, `owner_verdict_at` null, `ai_status`, `demo` (a sample tip), `created_at`, `captured_at` |
| `paypal_webhook_event` | `event_id` unique, `event_type`, `resource_id`, `received_at` |
| `owner` | `id`, `email` unique and lowercase (null for a sandbox and the public demo), `password_hash` (likewise null), `display_name`, `created_at`, `time_zone` (default `Europe/London`), `kind` (`REAL`, `SANDBOX`, `PUBLIC_DEMO`), `code_label` and `expires_at` (sandboxes) |
| `resume_token` | `token_hash` (SHA-256 of a 128 bit token) primary key, `owner_id` (cascade), `created_at`, `expires_at`, `used_at` null |
| `ai_usage` | `hour` primary key, `calls`, `failures` |
| `owner_digest` | (`owner_id`, `local_day`) primary key, `bullets` jsonb null, `request_groups` jsonb null, `message_count`, `source_watermark` null, `generated_at` null, `stale`, `claimed_at` null |
| `reaction_delivery` | `tip_id` primary key, `device_id`, `reaction` (`WARM`, `PLAYFUL`, `REQUEST`, `PLAIN`), `repeats` (1 to 5), `reacted_at`, `sent_count`, `last_sent_at` null, `acked_at` null; partial index on (`device_id`, `reacted_at`) where `acked_at` is null |

`reaction_delivery` belongs to `devices`, so delivery state is a table of its own instead of more columns on `tip`. It has no foreign key to `tip`, because `devices` does not own that table. Deleting an owner cascades to their devices, and a device to its tips and deliveries, as well as to the owner's digests and resume tokens; only the sandbox cleanup job deletes owners.

## Device channels

```
TipReacted (after commit) ──▶ ReactionDispatcher (delivery executor)
                                 1. INSERT reaction_delivery ON CONFLICT DO NOTHING ── no row: duplicate, stop
                                 2. device connected? send reaction, sent_count + 1
                                 3. broadcast {type, reaction, repeats, at} to the device's observers
ReactionResendJob (every 1 s) ──▶ unacked, sent_count < 4, last send 5 s ago, under 60 s old ──▶ send again
Device connects ──▶ register pending (closing a replaced session: closing "replaced", then 4000), hello, ready for live sends, replay unacked of the last 60 s
Device acks ──▶ conditional UPDATE acked_at for its own device
SessionKeepalive (every 20 s) ──▶ ping, or close with 1001 after 45 s without a pong (devices get closing "timeout" first)
```

The device channel `/ws/device/{deviceId}` authenticates in a handshake interceptor: the bearer secret goes through `DeviceSecretVerifier`, and every failure is the same empty 401. Failed handshakes are limited to 10 per minute per IP. The channel keeps Spring's same origin check; the ESP32 sends no `Origin`, and a browser cannot set an `Authorization` header on a WebSocket, so a cross site page cannot use this endpoint.

The observer channel `/ws/observe/{deviceSlug}` is public and read only: 404 for an unknown slug, at most 5 open connections per IP, and only the origin of `TIP_PUBLIC_BASE_URL`, where the virtual panda page `/p/{slug}` is served. Observers get live reactions only, never a replay: the reaction label and its repeats, so the virtual panda plays what the LED plays. Never a tip id, amount, message, message kind, flag or payer data. The repeats tier shows publicly that a bigger tip landed, which is intended.

The registries are in memory: one `ConcurrentHashMap<UUID, WebSocketSession>` for devices and a map of concurrent sets by slug for observers.

## Owner dashboard

Everything an owner sees is scoped to them in SQL: each query joins the tip's device and keeps only rows whose `owner_id` is the signed in owner, so another owner's data never leaves the database and an unknown id and another owner's id get the same 404. Times are stored in UTC and computed in the owner's `time_zone`: today runs from local midnight and counts tips and requests in `CAPTURED` or `PENDING` only, the requests panel covers the last seven local days in every visible status, refunded included, and the busiest hour chart steps through local hours with `ZonedDateTime`, so the day the clocks change has 23 or 25 hours.

The classification also names the message's language, translates it when it is not in English, and picks out a requested song, in the same call. The song key (lowercase, no accents or punctuation, no leading "the") is computed in Java, so "Wonderwall!" and "The Wonderwall" count as one song. A flagged message gets the same read and keeps it, so the owner can overrule the flag without another call.

**Hidden messages.** A flagged message is hidden from its owner by default: every owner endpoint returns null for its message, note, language, translation and song, and it counts only in totals. The owner can read it on demand (`GET /api/owner/tips/{id}/message`, `no-store`, the text with its language and translation, kept in the page's memory only) and overrule the AI with "Not abusive", which sets `tip.owner_verdict`; "Hide again" clears it. The AI's `flagged` and `flag_reason` are never changed. Hidden means `flagged AND owner_verdict IS NULL`, defined once in `HiddenMessages`, and every owner query (the tip list and its filters, today, requests, the digest's input, the hidden panel, every count) goes through it; a test fails any other dashboard query that reads the flag. A verdict is one conditional statement scoped by owner and the AI's flag, so another owner's tip, an unflagged tip and an unknown id all get the same 404, and concurrent verdicts run one after the other on the row lock. Nothing of it reaches a device or an observer: the panda already played `PLAIN` once and the reaction is never sent again.

**The panda's notes.** A digest of the day's shown messages and the week's request notes, written by one model call:

```
TipReacted (after commit) ──▶ owner_digest of the owner's local day: stale = true
DigestJob (every 2 min) ──▶ for each stale digest of an owner's today:
    claim: UPDATE ... SET claimed_at = now, stale = false
           WHERE stale AND (claimed_at IS NULL OR claimed_at < now - 2 min) AND (generated_at IS NULL OR generated_at <= now - 10 min)
    fewer than 3 shown messages, or nothing reacted or unhidden since the stored watermark ──▶ release, no call
    call the model (outside any transaction) ── failure ──▶ release with stale = true, the stored digest stays
    store: UPDATE ... WHERE claimed_at = :claimedAt AND (source_watermark IS NULL OR source_watermark < :watermark) ──▶ {type: digest} to the owner
Hide again ──▶ in the verdict's transaction: bullets, groups and watermark cleared, claim ended, stale = true ──▶ {type: digest}
```

The claim keeps two instances from calling the model for the same digest; the watermark (the latest `reaction_at` or `owner_verdict_at` the call read) keeps a slow old call from overwriting a newer digest, and an overrule moves it like a new tip. Hiding a message again drops the day's digest and its request groups at once, before the next run, so the message cannot linger in the notes; until they are written again `GET /api/owner/digest` answers `{"status": "rewriting"}` instead of `null` when AI is on and 3 shown messages are left, so the page does not claim there are too few messages; ending the claim means a call that read it can no longer store. `generated_at` stays, so the regenerated digest still waits for the 10 minute limit and an owner flipping a verdict cannot run up calls. A reaction during a call marks the digest stale again, so the next run after the 10 minute limit picks it up. With AI disabled there is no digester and the job does nothing. Request groups come from the digest when there is one, and notes it has not seen are grouped by their exact text, so the panel always works.

**The owner channel.** `/ws/owner` (`docs/OWNER_CHANNEL.md`) pushes ids and states, never content, so the page fetches through the owner API and authorisation stays in one place. Device presence comes from `devices` as `DeviceStatusChanged` events; a device is reported offline only after 10 seconds gone, so a replaced connection or a quick reconnect never flickers.

## Owners

An owner owns devices. Every device has exactly one owner and every tip belongs to a device, so a tip's owner is the owner of its device. There is no public registration. A `REAL` owner is a person with a login, created by the demo seeder or, later, by a CLI command. A `SANDBOX` owner is made by an access code for a judge, has no email or password and expires; the one `PUBLIC_DEMO` owner is the shared read only demo (decision 19). Sandbox and demo devices are virtual only: the device channel turns them away like an unknown device.

Owners sign in with email and password through a session based form login. Passwords are hashed with bcrypt through Spring's `DelegatingPasswordEncoder`. Sign in attempts are limited to 5 per minute per IP and per email, and an unknown email gets the same answer as a wrong password. Every owner request needs the session cookie (`HttpOnly`, `Secure`, `SameSite=Lax`) and a CSRF token. An owner who asks for another owner's tip gets 404, never 403, so tip ids cannot be probed.

## Threat model

| Threat | Mitigation |
|---|---|
| Tipper changes the amount in the browser | Server creates the order from validated input. Capture uses the order id stored on the tip, never one sent by the client, and a capture answer counts only when PayPal captured exactly the tip's amount and currency, like a webhook. |
| Fake "payment done" from the browser | State only changes after PayPal confirms the capture, either in the capture response or a verified webhook. |
| Forged webhook | Every webhook is verified with PayPal's signature verification API against our webhook id. Unverified requests get 400 and change nothing. A certificate URL that is not https on a PayPal host is rejected without a call, and after 10 rejected webhooks a minute an IP gets 429 before the next verification call, so fake webhooks cannot spend our PayPal API quota; PayPal's own deliveries never fail verification. |
| Replayed webhook | Unique event id. A replay is a no op. |
| A genuine webhook for an order someone else made | Our client id is public, so anyone can create and pay an order on our merchant account with any `custom_id` and amount, and PayPal signs its webhooks. A webhook finds its tip only through the order or capture id TiP stored, never `custom_id`, and a completed or pending capture counts only for exactly the tip's amount and currency. |
| Spamming tip creation | Rate limit per client IP. Creating a tip costs us one PayPal API call and no AI call. |
| Running up the AI bill | AI only runs after a PayPal confirmed capture (the capture response or a verified webhook), never for a created, pending or declined tip; a test proves it. Sandbox payments cost nothing, so a global cap of 200 classifications an hour and 1000 a day bounds them; past it tips react `PLAIN` once without a call. A failed call is never retried. The spend limit in the Anthropic console is the hard cap; reaching it rests the model (a breaker) instead of failing tips. Digests run at most every 10 minutes per owner. |
| Guessing an access code | 100 random bits, only their SHA-256 configured, compared against every entry in constant time; 10 attempts an hour per IP; a wrong and an expired code get the same answer as a wrong password. A leaked code makes sandboxes of at most 72 hours, never past the code's own expiry, each isolated like any owner, and is revoked by removing its entry. |
| Using the public demo to publish | The demo is read only: one central check answers 403 `DEMO_READ_ONLY` to every change under `/api/owner`, a test sweeps every mapping, and the demo panda takes no tips. Its data is invented and renewed every night. |
| A resume link leaks | 128 random bits, stored hashed, single use through one conditional update, 10 minutes, bound to its sandbox, carried in the URL fragment, so it never reaches the server, its logs, a proxy or a Referer, and taken out of the address bar by the page; spent only by a post on a tap, never by fetching the page, so link previews and mail scanners cannot use it up; `no-store` and `no-referrer`. |
| Prompt injection in the message | The message is wrapped as untrusted data, the model must return a fixed schema checked again in Java, and anything outside it is a failed attempt that ends as `PLAIN`. The worst a successful injection can do is pick the wrong light pattern or write a misleading owner note, which the owner reads as a note, never as an instruction. Summaries are only shown to the owner and the model has no tools. See decision 11. |
| Abusive messages | AI flags them. A flagged message plays a single plain blink whatever the amount, the same as a small tip without a message, so abuse gets no reward and nothing to film, and a big tip cannot buy a big reaction for an insult. Observers cannot tell it apart from any other `PLAIN`. Flagged messages are hidden from public views and, by default, from the owner, who can read one on demand, overrule the AI or refund the tip. Overruling changes only what the owner sees, never what the panda or an observer got. |
| Stored XSS through messages | Output is always rendered with `textContent`. A strict Content Security Policy that allows only our origin and the PayPal SDK domains. |
| Someone impersonates a device | Each device has a 256 bit random secret, sent over TLS, stored as a SHA-256 hash and compared in constant time. SHA-256 is right here, not bcrypt, because the secret is random with full entropy and cannot be brute forced. Bcrypt exists to slow down guessing of human passwords. |
| A page on another site opens the owner channel with the owner's cookie | The handshake needs the owner's session and an `Origin` exactly equal to `TIP_PUBLIC_BASE_URL`'s origin, missing included, so cross site WebSocket hijacking gets 403. The channel carries ids and states only, so even a leaked socket shows no tip content. |
| A message steers the translation, song or digest | All model output is untrusted: structured output, then Java checks (a known language code, length caps, a translation only for a message in another language, a song only on a request, digest tip ids only from the input, at most one group per tip, bullets without contact details or quotes over five words), then `MessageSanitizer`. A bad extra is dropped without touching the kind or the flag. None of it reaches a device or an observer. Hidden messages are never shown and never sent to the digest; one the owner unhid goes through the digest's delimiters and checks like any other. See decision 13. |
| Someone watches a device's observer feed | The observer channel carries only the reaction label, its repeats and a timestamp. Repeats reveal a coarse tier (1 to 5), on purpose, never the amount. A flagged tip always has repeats 1, so it looks like any small plain tip. No messages, kinds, flags or payer data. |
| Tipping remotely through a shared URL | Accepted for v1. v2 rotates a one time token on the tag through the PN532 after every tip, so only someone physically at the device can tip. |
| Secrets leak through the public repo | Secrets only in environment variables, `.env` in `.gitignore`, gitleaks scan in CI. |
| Device skips TLS checks | Firmware verifies the server certificate with the ESP32 built in CA bundle. `setInsecure()` is never used. |

## Decisions

1. **Server side order creation and capture.** The browser never decides the amount or declares a payment complete.
2. **Capture response and webhook both drive state.** The capture response is fast for the tipper's success screen. The webhook catches cases the browser never reports, like a closed tab or a delayed `PENDING` capture. The conditional update makes the double path safe.
3. **Device opens an outbound WebSocket.** Devices sit behind home and shop routers with NAT, so the server cannot call them. Polling would cost battery and add delay. One long lived outbound connection reacts within a second and needs no open ports.
4. **Separate observer channel.** The virtual panda for judges and any public display subscribe read only by slug. Nobody needs a device secret to watch, and the real device channel stays authenticated. It carries the reaction label and repeats, nothing more.
5. **AI runs after commit and off the request thread.** The tipper's success screen never waits on a model.
6. **PostgreSQL everywhere, including tests.** Testcontainers runs the same database the app runs in production, so tests catch constraint and locking behaviour an in-memory database would hide.
7. **Monolith with feature packages.** One thing to deploy and run, with boundaries enforced by tests.
8. **Reaction scales with the amount, in capped tiers.** `repeats` 1 to 5 from the amount charged, decided once with the reaction and stored with the delivery, so a replay sends what was decided. Flagged tips always get 1.
9. **Ack means received.** Devices ack on receipt and queue playback, because patterns now run up to 10 seconds and resends come every 5. The connect order (register pending, hello, ready, replay) stays, accepting a rare duplicate that the device acks and does not play, rather than delaying real reactions.
10. **On Render the client IP comes from `CF-Connecting-IP`.** Render sits behind Cloudflare, which appends its own edge address to `X-Forwarded-For`, so the rightmost-entry default would rate limit Cloudflare edge nodes instead of clients. Cloudflare rejects with 403 any request that already carries `CF-Connecting-IP`, so a client cannot forge it. Production sets `TIP_CLIENT_IP_HEADER=CF-Connecting-IP`. The `X-Forwarded-For` default stays for hosts whose proxy appends the client's address last. Every per IP limit counts an IPv6 client by its /64 prefix (`ClientKeys`): one home connection usually holds a whole /64, and Cloudflare forwards each of its addresses as its own client.
11. **The tip message is untrusted data.** Anyone who pays can write anything, including text that pretends to be the system, the owner or a classification result, so the defences do not depend on the model obeying. In order of strength: structured output limits what the model can return to the four kinds, a flag, a reason and a note; `ClassificationParser` checks that again in Java (kind, types, note only on `REQUEST`, length caps), and anything else is a failed attempt that ends as `PLAIN`; the flag override means a flagged tip always plays `PLAIN` once, whatever kind the model picked; owner notes are capped at 100 characters, and the dashboard must render them as text and label them as derived from the tipper's message; the prompt puts the message only in the user turn inside `<tip_message>` delimiters (lookalike tags inside it are turned into `[tip_message]` so it cannot close its own block) and tells the model the text is data, that claims of authority inside it are part of the message, and that a message only trying to change the result is `PLAIN` with no note unless the flag rule applies. Delimiter neutralising is best effort, not a security boundary: the boundary is structured output, `ClassificationParser` and the flag override. Lookalike letters from other alphabets (such as a Cyrillic `т` in `tip_message`) slip past it, a known and accepted gap. Remaining risk: a crafted message can still sway the kind of an unflagged message, which only changes which harmless pattern plays, or shape the wording of an owner note within its cap. `aiEval` carries injection cases and must keep the flag at 100 %.
12. **The owner tip history sorts without an index for the order.** The query filters through device ownership and sorts by `created_at DESC, id DESC` across all of an owner's devices, so PostgreSQL does a top N sort, and no per device index can serve the order. Accepted at demo scale. If it is ever needed, the fix is an `owner_id` column on `tip` with an index on `(owner_id, created_at DESC, id DESC)`.
13. **Language, translation and song ride on the classification call.** One call per tip already happens, so asking for three more fields costs a few output tokens and no extra latency or request. They are extras: the parser drops a field that breaks its rule instead of failing the classification, so a weak translation can never cost the panda its reaction or change the flag. The translation is the tipper's words, never a reply, and a message asking to translate or repeat something is still just a message.
14. **The digest is regenerated on change, not on a timer.** A reaction marks the day's digest stale, and a job regenerates stale digests at most every 10 minutes per owner and only from 3 unflagged messages, which bounds the cost to a few calls per busy day. The claim and the watermark make it safe with several instances and slow calls, the same pattern as the AI columns of `tip`. Tips go to the model as short references (`t1`, `t2`) mapped back in Java, because a model copying UUIDs makes mistakes, and an id it invents is simply dropped.
15. **The owner channel carries no content.** It says which tip changed, and the page fetches it through the owner API. One place decides what an owner may see, and a socket never holds data a revoked session should no longer get. Signing out closes every socket of the owner; the servlet container closes a socket whose HTTP session ends, and the keepalive backs that up.
16. **Device offline is reported after 10 seconds.** A replacement registers the new session before the old one closes, and a device on flaky WiFi often reconnects within seconds; reporting each of those would make the dashboard flicker. The time of each connect and disconnect is stored in `device.last_seen_at` so "last seen" survives a deploy.
17. **Owner times are computed in the owner's zone, stored in UTC.** `owner.time_zone` defaults to `Europe/London` and is read with `ZoneId`. Nothing sets it yet; whatever does later must check it with `ZoneId.of` first.
18. **Flagged messages are hidden by default; the owner can reveal and overrule.** This replaces "flagged messages are hidden from the owner". The model flags honest but blunt messages now and then, and an owner who could never read them lost real feedback and requests. So classification keeps the whole read of a flagged message, the owner can read it when they choose and mark it not abusive, and "Hide again" undoes that. The owner's verdict lives in its own columns and never changes the AI's flag or reason, so what the AI decided stays on record. Hidden is defined in one place for every owner query. Public pages, observers and devices are unaffected: a flagged tip played `PLAIN` once and that is never replayed or changed. Legacy flagged tips stored before this have no note, translation or song, so an overrule shows them with their stored kind, or as Plain without one.
19. **Three access levels until the end of 2026.** TiP must stay usable without anyone looking after it until 31 Dec 2026; access closes 2027-01-01 00:00 Europe/London. Anyone can tip the real demo panda, watch it and look around a shared demo dashboard that is read only and renewed every night. Holders of an access code (judges, the owner, the teammate) get a private sandbox: a full owner with a virtual panda and the shared sample tips, for 72 hours, deleted afterwards. The real owner and the physical panda are unchanged and out of reach of both: other owners see the same 404 for everything that is not theirs, and virtual devices cannot connect as hardware. Codes live only as hashes in an environment variable, so there is no admin interface to secure. Every limit is sized so an honest person never meets it; the hard limits are the classification cap and the Anthropic spend limit, and the app keeps taking tips when either is reached.
20. **A failed model call is never retried.** Retrying during an outage or after the spend limit multiplies calls exactly when they cost most and help least. A tip whose call failed reacts `PLAIN` once as `UNAVAILABLE`, which replaces the earlier rule of three attempts on any failure; only an invalid answer is tried again. Any 400, 401, 402, 403 or 404 rests the model for 10 minutes before one trial call.
