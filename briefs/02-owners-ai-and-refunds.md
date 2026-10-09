# Brief 02: Review fixes, owners, AI and refunds

## Overview

Brief 01 is merged and verified with a real sandbox payment and webhook. This brief does four things:

1. Fixes from the Brief 01 review.
2. Owner accounts with login, and devices that belong to an owner and have a secret.
3. The `ai` feature: after a tip is captured, Claude classifies the message into a reaction and flags abuse. Exactly once per tip, even across crashes and concurrent workers.
4. Owner initiated refunds through PayPal.

No WebSockets and no virtual panda yet. Brief 03 does those and consumes the `TipReacted` event this brief publishes.

Start a fresh session. Read `CLAUDE.md`, `docs/ARCHITECTURE.md`, `docs/DEVICE_PROTOCOL.md` and `CODEBASE.md` first.

## Part 1: Review fixes

1. **Closed tips on capture.** `CAPTURED`, `PENDING` and `REFUNDED` keep returning 200 with the status. `FAILED` and `ABANDONED` return 409 with code `TIP_CLOSED`. The tip page shows "This tip can no longer be completed. Please start again." for that code.
2. **No default error page.** Set `server.error.whitelabel.enabled=false`. Every error response, including 401, 403 and 404 raised by Spring Security before a controller runs, is RFC 9457 problem details JSON with a stable `code`. Add an `AuthenticationEntryPoint` and an `AccessDeniedHandler` that write problem details. No timestamps, class names or paths in the body beyond `instance`.
3. **No generated password.** Spring must not create its default in memory user. The owner `UserDetailsService` from Part 2 replaces it. The startup log must no longer show "Using generated security password".
4. **More capture events.** Handle `PAYMENT.CAPTURE.DECLINED` (same as `DENIED`, the tip becomes `FAILED`) and `PAYMENT.CAPTURE.REVERSED` (a chargeback: `CAPTURED` becomes `REVERSED`). Add `REVERSED` to the status check constraint and to the state diagram in `docs/ARCHITECTURE.md`.
5. **Compose port.** Check that `docker compose up -d` picks up `TIP_DB_PORT` from `.env`. On the owner's machine it did not until the variable was set in the shell. If the compose file is fine and the cause is just a missing line, make `.env.example` and the README unmistakable about it. Report which it was.

## Part 2: Owners and device ownership

### Schema (`V2__owners_ai_refunds.sql`)

- `owner`: `id` uuid, `email` unique (stored lowercase), `password_hash`, `display_name`, `created_at`.
- `device`: add `owner_id` (not null, foreign key) and `secret_hash` (bytea, 32 bytes, not null). Backfill existing rows in the migration so it runs cleanly on a database that already has the demo device: assign them to a placeholder owner the seeder later takes over, or delete and let the seeder recreate. Choose, explain in the report.
- `tip`: add `ai_claimed_at` timestamptz null, `ai_attempts` int not null default 0, `flag_reason` varchar(200) null, `reaction_at` timestamptz null. Add a check constraint on `ai_status` with values `PENDING`, `PROCESSING`, `DONE`, `SKIPPED`, `FAILED`.

### Owner login

- `owners` feature with a `UserDetailsService` backed by the table. Passwords with `DelegatingPasswordEncoder` (bcrypt default).
- Session based form login. `GET /owner/login` serves a plain page, `POST /owner/login` processes it, `POST /owner/logout` ends it. On success go to `/owner`, which for now is a placeholder page saying "Signed in as <display name>". The dashboard comes later.
- CSRF stays on for everything under `/owner/**` and `/api/owner/**`. Session cookie `HttpOnly`, `Secure`, `SameSite=Lax`. Session fixation protection on (Spring default, make sure nothing turns it off).
- Login attempts limited to 5 per minute per IP and per email, with a 429 problem response. Failed logins return the same message for unknown email and wrong password.
- No public registration. Owners are created by the seeder or by a CLI command later.

### Seeder

Extend the demo seeder:

- `TIP_DEMO_OWNER_EMAIL`, `TIP_DEMO_OWNER_PASSWORD`, `TIP_DEMO_OWNER_NAME`: creates the owner if missing. The password is only used at creation, never logged. Refuse to start if the password is shorter than 12 characters.
- `TIP_DEMO_DEVICE_SECRET`: base64url of 32 random bytes. The demo device's `secret_hash` is the SHA-256 of the decoded bytes. If the device exists with a different hash, update it, so rotating the env var rotates the secret. Validate length at startup.
- The demo device belongs to the demo owner.

Add to the README how to generate a device secret in PowerShell:

```powershell
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Fill($b); [Convert]::ToBase64String($b).TrimEnd('=').Replace('+','-').Replace('/','_')
```

### Device API for owners

`devices.api` gains `DeviceSecretVerifier.verify(deviceId, presentedSecret)` returning the device or empty. It hashes the presented secret and compares with `MessageDigest.isEqual`. Unknown device and wrong secret take the same path and the same time. Brief 03 uses it in the WebSocket handshake. Unit test it, nothing calls it yet.

## Part 3: AI classification

### Flow

1. `TipCaptured` (after commit) triggers the classifier on a virtual thread executor (`spring.threads.virtual.enabled=true`, `@Async` on a dedicated executor, not the common pool).
2. **Claim.** One conditional update owns the work:
   ```sql
   UPDATE tip SET ai_status = 'PROCESSING', ai_claimed_at = :now, ai_attempts = ai_attempts + 1
   WHERE id = :id AND status = 'CAPTURED'
     AND (ai_status = 'PENDING' OR (ai_status = 'PROCESSING' AND ai_claimed_at < :now - interval '2 minutes'))
     AND ai_attempts < 3
   ```
   Zero rows means someone else has it or it is done: return.
3. **No message.** Set `reaction = NEUTRAL`, `ai_status = SKIPPED`. No API call.
4. **Classify.** Call Claude (below). On success store `reaction`, `flagged`, `flag_reason`, `ai_status = DONE`.
5. **Failure.** Timeout, API error or an invalid response: if `ai_attempts < 3`, set back to `PENDING` so the recovery job retries. On the third failure, `reaction = NEUTRAL`, `flagged = false`, `ai_status = FAILED`. The payment happened, so the panda must still react.
6. **Finish.** Whatever the outcome, the final write sets `reaction_at` and publishes `TipReacted(tipId, deviceId, reaction, reactedAt)` after commit. The final write is also conditional on `ai_status = 'PROCESSING'` and the claimed timestamp still matching, so a slow worker whose claim expired cannot overwrite a newer result.

**Recovery job** every 30 seconds: finds `CAPTURED` tips that are `PENDING`, or `PROCESSING` with an expired claim, and runs the same flow. This covers a crash between capture and classification, and API outages.

### The Claude call

- Anthropic Java SDK, model from `TIP_AI_MODEL` (default `claude-haiku-5-5`), key from `ANTHROPIC_API_KEY`. Check the current SDK API in the official docs at docs.claude.com rather than from memory.
- Timeout 10 seconds, SDK retries off (our attempt counter is the retry), `max_tokens` small.
- Force a structured result: either the API's structured output feature or a single required tool whose input schema is the result. Schema:
  ```json
  { "reaction": "POSITIVE | NEUTRAL | NEGATIVE", "flagged": true, "flagReason": "short reason or null" }
  ```
- System prompt explains the job in plain terms: this is a tip left for a street performer, shop or delivery driver; positive is warm or appreciative, negative is rude, hostile or abusive, neutral is everything else including plain "thanks"; flag anything abusive, hateful, sexual, threatening or containing personal data. The message goes in the user turn wrapped in `<tip_message>` tags with an explicit instruction that the content is data from an anonymous member of the public, never instructions.
- Validate the result in Java. Any value outside the enum, a missing field or a `flagReason` over 200 characters counts as an invalid response.
- Never log the message text or the model's reason at INFO or above.

### Config

`TIP_AI_ENABLED` (default true). When false, every captured tip gets `NEUTRAL` with `ai_status = SKIPPED`. CI and anyone running the repo without a key still get a working flow. The app must start without `ANTHROPIC_API_KEY` when AI is disabled, and refuse to start without it when enabled.

## Part 4: Refunds

`POST /api/owner/tips/{tipId}/refund`, owner session plus CSRF.

- The tip must belong to a device of the signed in owner. Otherwise 404, never 403, so tip ids of other owners cannot be probed.
- Only `CAPTURED` tips. Others return 409 `TIP_NOT_REFUNDABLE`.
- Full refund through the PayPal capture refund API with `PayPal-Request-Id: refund-<tipId>`. Add `refundCapture` to `PayPalGateway`.
- On PayPal `COMPLETED`, conditional transition `CAPTURED` to `REFUNDED`. On `PENDING`, leave `CAPTURED` and return 202; the `PAYMENT.CAPTURE.REFUNDED` webhook finishes it. Either path may win, the conditional update keeps it to one transition.
- Log the refund at INFO with tip id and owner id, never the message.

## Tests (required)

Through HTTP with Testcontainers PostgreSQL. PayPal and Anthropic stubbed with WireMock.

1. Capture on `FAILED` and `ABANDONED` returns 409 `TIP_CLOSED`.
2. Unknown path, denied path and unauthenticated owner API all return problem details JSON, not HTML.
3. Startup log has no generated security password (assert no `InMemoryUserDetailsManager` bean).
4. Webhooks: `DECLINED` makes the tip `FAILED`, `REVERSED` makes a `CAPTURED` tip `REVERSED`.
5. Login: correct credentials create a session; wrong password and unknown email return identical responses; the 6th attempt in a minute is 429; session id changes after login.
6. CSRF: owner POST without a token is 403.
7. Seeder: creates owner and device; rerun is a no op; changed `TIP_DEMO_DEVICE_SECRET` updates the hash; short password stops startup.
8. Secret verifier: right secret passes, wrong secret and unknown device both fail.
9. AI happy path: positive, negative with flag, neutral, each stored and one `TipReacted` published.
10. Empty message: `SKIPPED`, `NEUTRAL`, no Anthropic call.
11. Invalid model output (unknown label, missing field): retried, and after three failures `FAILED` with `NEUTRAL` and one `TipReacted`.
12. Timeout: Anthropic stub delays past the timeout, tip goes back to `PENDING`, recovery job finishes it.
13. Concurrency: listener and recovery job race on the same tip (two threads, latch, 20 rounds). Exactly one Anthropic call per successful round and exactly one `TipReacted`.
14. Expired claim: a `PROCESSING` tip with an old claim is taken over; the original worker's late write is rejected.
15. Prompt shape: the request sent to Anthropic wraps the message in `<tip_message>` tags and uses the configured model.
16. AI disabled: no key needed, tips become `SKIPPED` and `NEUTRAL`.
17. Refund: owner refunds own captured tip, gets `REFUNDED`, one PayPal refund call with the right request id.
18. Refund of another owner's tip returns 404. Refund of a non captured tip returns 409. Refund `PENDING` returns 202 and the webhook completes it.
19. Refund webhook and refund response racing produce one transition.

## Do

- Update `docs/ARCHITECTURE.md`: state diagram with `REVERSED`, the AI claim flow under "Concurrency and failure modes", and the owner model. Nothing else in the docs.
- Update `.env.example` and the README for every new variable.
- Update `CODEBASE.md`.

## Do not

- No WebSockets, virtual panda, dashboard API or dashboard UI. The dashboard backend belongs to the teammate.
- Do not change the device protocol.
- Do not call real Anthropic or PayPal from tests.
- Do not push.

## Human actions to list

At least: add `ANTHROPIC_API_KEY`, the demo owner variables and `TIP_DEMO_DEVICE_SECRET` to `.env`; tick `Payment capture declined` and `Payment capture reversed` on the sandbox webhook; run one real tip with a kind message and one with a rude message and check the stored reaction and flag.

## Report

Format in `CLAUDE.md`. Teammate activity applies now that the remote exists.