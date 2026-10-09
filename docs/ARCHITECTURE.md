# TiP architecture

## What it is

TiP turns a physical object into a PayPal tip jar that reacts. A street performer, a small shop or a delivery driver puts a TiP device on the counter. A customer taps it with their phone, picks an amount, optionally writes a message and pays with PayPal. Claude reads the message. The device reacts with a light pattern, and the owner sees every tip, an AI summary of the day, and flagged messages they can refund.

A plain PayPal QR code already takes money. What it cannot do is react, filter abuse, or tell the owner what people are saying. That is the gap TiP fills.

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
7. The `ai` feature classifies the message off the request thread and stores the reaction.
8. The `devices` feature pushes the reaction to the device channel and to any observers of that device.

## Tip states

```
CREATED ──▶ CAPTURED ──▶ REFUNDED
   │  ╲
   │   ╲──▶ PENDING ──▶ CAPTURED | FAILED
   ▼
 FAILED          CREATED older than 6h ──▶ ABANDONED (scheduled job)
```

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

**Crash between capture and AI.** `TipCaptured` is published after commit and handled asynchronously. If the process dies in between, the tip is `CAPTURED` with `ai_status = PENDING`. A scheduled job picks up tips stuck in `PENDING` for more than two minutes and processes them again. The handler is idempotent on tip id.

**AI slow or down.** Claude calls have a 10 second timeout and one retry. On failure the tip gets `NEUTRAL` with `ai_status = FAILED`. The payment already happened, so the device must still confirm it.

**Device offline.** Reactions are stored on the tip. When a device reconnects, it receives reactions from the last 60 seconds that it has not acknowledged. Older ones are dropped, because a light reacting to a tip from an hour ago means nothing to anyone standing there.

**Single instance.** WebSocket sessions live in an in-memory registry, so the backend runs as one instance. For several instances, dispatch would go through PostgreSQL `LISTEN/NOTIFY` or Redis pub/sub. This is a deliberate limit for the hackathon, not an oversight.

## Data model (core)

| Table | Key columns |
|---|---|
| `device` | `id` uuid, `slug` unique, `display_name`, `owner_id`, `secret_hash`, `created_at` |
| `tip` | `id` uuid, `device_id`, `amount_minor` bigint, `currency` char(3), `message` varchar(280) null, `status`, `paypal_order_id` unique, `paypal_capture_id` unique null, `reaction` null, `flagged` bool, `ai_status`, `created_at`, `captured_at` |
| `paypal_webhook_event` | `event_id` unique, `event_type`, `resource_id`, `received_at` |
| `owner` | `id`, `email` unique, `password_hash`, `created_at` |

## Threat model

| Threat | Mitigation |
|---|---|
| Tipper changes the amount in the browser | Server creates the order from validated input. Capture uses the order id stored on the tip, never one sent by the client. |
| Fake "payment done" from the browser | State only changes after PayPal confirms the capture, either in the capture response or a verified webhook. |
| Forged webhook | Every webhook is verified with PayPal's signature verification API against our webhook id. Unverified requests get 400 and change nothing. |
| Replayed webhook | Unique event id. A replay is a no op. |
| Spamming tip creation | Rate limit per client IP. Creating a tip costs us one PayPal API call and no AI call. |
| Running up the AI bill | AI only runs after a captured payment. Every AI call is paid for by a real tip. |
| Prompt injection in the message | The message is wrapped as untrusted data, the model must return a fixed schema, and anything outside the allowed labels becomes `NEUTRAL`. The worst a successful injection can do is pick the wrong light pattern. Summaries are only shown to the owner and the model has no tools. |
| Abusive messages | AI flags them. Flagged messages are hidden from public views and the owner can refund the tip. |
| Stored XSS through messages | Output is always rendered with `textContent`. A strict Content Security Policy that allows only our origin and the PayPal SDK domains. |
| Someone impersonates a device | Each device has a 256 bit random secret, sent over TLS, stored as a SHA-256 hash and compared in constant time. SHA-256 is right here, not bcrypt, because the secret is random with full entropy and cannot be brute forced. Bcrypt exists to slow down guessing of human passwords. |
| Someone watches a device's observer feed | The observer channel carries only the reaction label and a timestamp. No amounts, no messages, no payer data. |
| Tipping remotely through a shared URL | Accepted for v1. v2 rotates a one time token on the tag through the PN532 after every tip, so only someone physically at the device can tip. |
| Secrets leak through the public repo | Secrets only in environment variables, `.env` in `.gitignore`, gitleaks scan in CI. |
| Device skips TLS checks | Firmware verifies the server certificate with the ESP32 built in CA bundle. `setInsecure()` is never used. |

## Decisions

1. **Server side order creation and capture.** The browser never decides the amount or declares a payment complete.
2. **Capture response and webhook both drive state.** The capture response is fast for the tipper's success screen. The webhook catches cases the browser never reports, like a closed tab or a delayed `PENDING` capture. The conditional update makes the double path safe.
3. **Device opens an outbound WebSocket.** Devices sit behind home and shop routers with NAT, so the server cannot call them. Polling would cost battery and add delay. One long lived outbound connection reacts within a second and needs no open ports.
4. **Separate observer channel.** The virtual panda for judges and any public display subscribe read only by slug. Nobody needs a device secret to watch, and the real device channel stays authenticated.
5. **AI runs after commit and off the request thread.** The tipper's success screen never waits on a model.
6. **PostgreSQL everywhere, including tests.** Testcontainers runs the same database the app runs in production, so tests catch constraint and locking behaviour an in-memory database would hide.
7. **Monolith with feature packages.** One thing to deploy and run, with boundaries enforced by tests.
