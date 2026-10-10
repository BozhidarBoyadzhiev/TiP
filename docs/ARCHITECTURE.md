# TiP architecture

## What it is

TiP turns a physical object into a PayPal tip jar that reacts. A street performer, a small shop or a delivery driver puts a TiP device on the counter. A customer taps it with their phone, picks an amount, optionally writes a message and pays with PayPal. Claude reads the message and decides what kind it is: warm, playful, a request (or feedback the owner can act on), or plain. The device plays a light pattern for that kind. The owner sees every tip, an AI summary of the day, short notes on requests and feedback, and flagged messages they can refund.

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
7. The `ai` feature classifies the message off the request thread and stores its kind, the reaction (`PLAIN` when flagged, otherwise the kind), the flag and, for an unflagged request, a short owner note. In the same step it decides `repeats` (1 to 5) from the tip amount, always 1 when flagged, so bigger tips get a longer pattern.
8. The `devices` feature stores a delivery row with the reaction and its repeats, pushes it to the device channel and to any observers of that device, and resends it until the device acknowledges it.

## Tip states

```
CREATED ──▶ CAPTURED ──▶ REFUNDED
   │  ╲         │
   │   ╲        └──────▶ REVERSED (chargeback)
   │    ╲──▶ PENDING ──▶ CAPTURED | FAILED
   ▼
 FAILED          CREATED older than 6h ──▶ ABANDONED (scheduled job)
```

`PAYMENT.CAPTURE.DENIED` and `PAYMENT.CAPTURE.DECLINED` move `CREATED` or `PENDING` to `FAILED`. `PAYMENT.CAPTURE.REVERSED` moves `CAPTURED` to `REVERSED`. A capture request for a `FAILED` or `ABANDONED` tip gets 409 `TIP_CLOSED`.

Every transition is a conditional update:

```sql
UPDATE tip SET status = 'CAPTURED', paypal_capture_id = ?, captured_at = ?
WHERE id = ? AND status IN ('CREATED', 'PENDING')
```

The caller that gets one affected row owns the transition and is the only one that publishes the event. Anyone who gets zero rows reads the current state and returns it.

## Concurrency and failure modes

**Capture response and webhook race.** Both arrive within milliseconds and both say the payment completed. Without care the panda reacts twice or the tip is double counted. The conditional update above makes exactly one winner, enforced by PostgreSQL row locking, not by application code. A test runs both paths on two threads against real PostgreSQL and asserts one event.

**PayPal retries webhooks.** Every event id is inserted into `paypal_webhook_event` with a unique constraint in the same transaction as the state change. A duplicate hits the constraint and we return 200 without doing anything. If processing fails, the transaction rolls back, the event row is not kept, and PayPal's retry gets a clean second attempt.

**Our own retries to PayPal.** Order creation and capture send a `PayPal-Request-Id` derived from the tip id, so a timeout followed by a retry cannot create two orders or two captures.

**AI exactly once per tip.** `TipCaptured` is published after commit and handled on a virtual thread. The capture listener and a recovery job can both reach the same tip, and a worker can crash or hang mid call, so ownership of the work is a claim in PostgreSQL, not application state:

```sql
UPDATE tip SET ai_status = 'PROCESSING', ai_claimed_at = :now, ai_attempts = ai_attempts + 1
WHERE id = :id AND status = 'CAPTURED'
  AND (ai_status = 'PENDING' OR (ai_status = 'PROCESSING' AND ai_claimed_at < :now - interval '2 minutes'))
  AND ai_attempts < 3
```

```
PENDING ──claim──▶ PROCESSING ──▶ DONE      (classified)
   ▲                   │      ──▶ SKIPPED   (no message, or AI disabled: PLAIN, no kind, no API call)
   └── attempt failed ─┘      ──▶ FAILED    (third failure: PLAIN, no kind, not flagged)
       (attempts < 3)
```

Zero rows means another worker has the tip or it is finished. The final write is conditional on `ai_status = 'PROCESSING'` and the worker's own `ai_claimed_at`, so a slow worker whose claim expired and was taken over cannot overwrite the newer result. Only the final write that changes the row sets `reaction_at` and publishes `TipReacted`, after commit, so every captured tip gets exactly one reaction event.

**Crash between capture and AI.** If the process dies after the capture commits, the tip stays `CAPTURED` with `ai_status = PENDING`. If it dies during a call, the tip stays `PROCESSING` until its claim expires after two minutes. A recovery job runs every 30 seconds and runs the same flow for both. A tip whose worker died on the third attempt is failed as `PLAIN` by the same job once that claim expires.

**AI slow or down.** Claude calls have a 10 second timeout and SDK retries are off; the attempt counter is the retry. A timeout, an API error or a response outside the schema puts the tip back to `PENDING` for the recovery job, up to three attempts. After the third failure the tip gets `PLAIN` with `ai_status = FAILED`. The payment already happened, so the device must still confirm it.

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

**Delivery dispatch is in memory.** The dispatcher runs after commit on its own virtual thread executor. Run synchronously on the publisher's thread, it would hold the committed transaction's connection while taking a second one, and enough simultaneous reactions exhaust the pool. Known limit: a reaction handed to that executor is lost if the process dies before the task runs. The tip is safe, it is captured and its reaction is stored on it; only that one light pattern is missed. A reaction already in `reaction_delivery` survives a restart through replay on connect.

**Single instance.** WebSocket sessions live in an in-memory registry, so the backend runs as one instance. For several instances, dispatch would go through PostgreSQL `LISTEN/NOTIFY` or Redis pub/sub. This is a deliberate limit for the hackathon, not an oversight.

## Data model (core)

| Table | Key columns |
|---|---|
| `device` | `id` uuid, `slug` unique, `display_name`, `owner_id`, `secret_hash`, `created_at` |
| `tip` | `id` uuid, `device_id`, `amount_minor` bigint, `currency` char(3), `message` varchar(280) null, `status`, `paypal_order_id` unique, `paypal_capture_id` unique null, `message_kind` null (`WARM`, `PLAYFUL`, `REQUEST`, `PLAIN`), `reaction` null (same values, `PLAIN` when flagged), `flagged` bool, `flag_reason` null, `owner_note` varchar(100) null (unflagged requests only), `ai_status`, `created_at`, `captured_at` |
| `paypal_webhook_event` | `event_id` unique, `event_type`, `resource_id`, `received_at` |
| `owner` | `id`, `email` unique and lowercase, `password_hash`, `display_name`, `created_at` |
| `reaction_delivery` | `tip_id` primary key, `device_id`, `reaction` (`WARM`, `PLAYFUL`, `REQUEST`, `PLAIN`), `repeats` (1 to 5), `reacted_at`, `sent_count`, `last_sent_at` null, `acked_at` null; partial index on (`device_id`, `reacted_at`) where `acked_at` is null |

`reaction_delivery` belongs to `devices`, so delivery state is a table of its own instead of more columns on `tip`. It has no foreign key to `tip`, because `devices` does not own that table.

## Device channels

```
TipReacted (after commit) ──▶ ReactionDispatcher (delivery executor)
                                 1. INSERT reaction_delivery ON CONFLICT DO NOTHING ── no row: duplicate, stop
                                 2. device connected? send reaction, sent_count + 1
                                 3. broadcast {type, reaction, repeats, at} to the device's observers
ReactionResendJob (every 1 s) ──▶ unacked, sent_count < 4, last send 5 s ago, under 60 s old ──▶ send again
Device connects ──▶ hello, register (closing a replaced session with 4000), replay unacked of the last 60 s
Device acks ──▶ conditional UPDATE acked_at for its own device
SessionKeepalive (every 20 s) ──▶ ping, or close with 1001 after 45 s without a pong
```

The device channel `/ws/device/{deviceId}` authenticates in a handshake interceptor: the bearer secret goes through `DeviceSecretVerifier`, and every failure is the same empty 401. Failed handshakes are limited to 10 per minute per IP. The channel keeps Spring's same origin check; the ESP32 sends no `Origin`, and a browser cannot set an `Authorization` header on a WebSocket, so a cross site page cannot use this endpoint.

The observer channel `/ws/observe/{deviceSlug}` is public and read only: 404 for an unknown slug, at most 5 open connections per IP, and only the origin of `TIP_PUBLIC_BASE_URL`, where the virtual panda page `/p/{slug}` is served. Observers get live reactions only, never a replay: the reaction label and its repeats, so the virtual panda plays what the LED plays. Never a tip id, amount, message, message kind, flag or payer data. The repeats tier shows publicly that a bigger tip landed, which is intended.

The registries are in memory: one `ConcurrentHashMap<UUID, WebSocketSession>` for devices and a map of concurrent sets by slug for observers.

## Owners

An owner is a person with a login who owns devices. Every device has exactly one owner and every tip belongs to a device, so a tip's owner is the owner of its device. There is no public registration: owners are created by the demo seeder or, later, by a CLI command.

Owners sign in with email and password through a session based form login. Passwords are hashed with bcrypt through Spring's `DelegatingPasswordEncoder`. Sign in attempts are limited to 5 per minute per IP and per email, and an unknown email gets the same answer as a wrong password. Every owner request needs the session cookie (`HttpOnly`, `Secure`, `SameSite=Lax`) and a CSRF token. An owner who asks for another owner's tip gets 404, never 403, so tip ids cannot be probed.

## Threat model

| Threat | Mitigation |
|---|---|
| Tipper changes the amount in the browser | Server creates the order from validated input. Capture uses the order id stored on the tip, never one sent by the client. |
| Fake "payment done" from the browser | State only changes after PayPal confirms the capture, either in the capture response or a verified webhook. |
| Forged webhook | Every webhook is verified with PayPal's signature verification API against our webhook id. Unverified requests get 400 and change nothing. |
| Replayed webhook | Unique event id. A replay is a no op. |
| Spamming tip creation | Rate limit per client IP. Creating a tip costs us one PayPal API call and no AI call. |
| Running up the AI bill | AI only runs after a captured payment. Every AI call is paid for by a real tip. |
| Prompt injection in the message | The message is wrapped as untrusted data, the model must return a fixed schema checked again in Java, and anything outside it is a failed attempt that ends as `PLAIN`. The worst a successful injection can do is pick the wrong light pattern or write a misleading owner note, which the owner reads as a note, never as an instruction. Summaries are only shown to the owner and the model has no tools. |
| Abusive messages | AI flags them. A flagged message plays a single plain blink whatever the amount, the same as a small tip without a message, so abuse gets no reward and nothing to film, and a big tip cannot buy a big reaction for an insult. Observers cannot tell it apart from any other `PLAIN`. Flagged messages are hidden from public views, carry no owner note, and the owner can refund the tip. |
| Stored XSS through messages | Output is always rendered with `textContent`. A strict Content Security Policy that allows only our origin and the PayPal SDK domains. |
| Someone impersonates a device | Each device has a 256 bit random secret, sent over TLS, stored as a SHA-256 hash and compared in constant time. SHA-256 is right here, not bcrypt, because the secret is random with full entropy and cannot be brute forced. Bcrypt exists to slow down guessing of human passwords. |
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
9. **Ack means received.** Devices ack on receipt and queue playback, because patterns now run up to 10 seconds and resends come every 5. The connect order (hello, register, replay) stays, accepting a rare duplicate that the device acks and does not play, rather than delaying real reactions.
10. **On Render the client IP comes from `CF-Connecting-IP`.** Render sits behind Cloudflare, which appends its own edge address to `X-Forwarded-For`, so the rightmost-entry default would rate limit Cloudflare edge nodes instead of clients. Cloudflare rejects with 403 any request that already carries `CF-Connecting-IP`, so a client cannot forge it. Production sets `TIP_CLIENT_IP_HEADER=CF-Connecting-IP`. The `X-Forwarded-For` default stays for hosts whose proxy appends the client's address last.
