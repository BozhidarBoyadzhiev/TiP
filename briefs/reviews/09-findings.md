# Brief 09 findings

The thread between the passes of Brief 09. Every finding of the review, its severity and where it stands.

## Where we are

- Pass 1 (audit, then Critical): done. Audit over backend, static pages, firmware, build, CI, config, migrations and tests. Baseline `./gradlew clean build` green before any change (696 tests); green after every commit of the pass (708 tests at the end). gitleaks 8.30.1 over the full history (174 commits, every branch): no leaks. Every Critical fixed, each with tests that failed before the fix.
- Pass 2 starts with the High findings in order: 09-F005, 09-F006, 09-F007, 09-F008, 09-F010 (09-F009 waits for the lead). Then the open Medium ones.
- Not yet covered in depth, for pass 2: the README files (read only for setup steps, not checked line by line against the code), `docs/DEPLOYMENT.md`, and a line by line read of `owner.js`, `live.js` and `panda.js` (scanned for markup sinks, storage and outside origins only).

## Counts

| Severity | Found | Fixed | Open | Lead decision |
|---|---|---|---|---|
| Critical | 4 | 4 | 0 | 0 |
| High | 6 | 0 | 5 | 1 |
| Medium | 9 | 1 | 4 | 4 |
| Low | 8 | 3 | 5 | 0 |

## Critical

### 09-F001 [Critical] A webhook can capture any tip for an amount that was never paid
Where: dev.tip.tipping.PayPalWebhookProcessor.findTip, PayPalWebhookEvent
What: Capture events are matched to a tip by `resource.custom_id` first, and the captured amount is never compared with the tip's. `custom_id` is whatever the order's creator put there. Our PayPal client id is public (`/api/public/devices/{slug}`), and the PayPal JS SDK lets anyone create and capture an order on our merchant account from a browser with only that client id, with any amount and any `custom_id`. PayPal then sends a genuine, correctly signed `PAYMENT.CAPTURE.COMPLETED` to our webhook.
Why it matters: Create a tip of £100 through our API (it stays `CREATED`, its order unpaid), then pay £0.01 on an order of your own whose `custom_id` is that tip id. The webhook moves the £100 tip to `CAPTURED`: the owner's totals show £100, the panda plays the biggest pattern, and a model call is made. The same trick with a `DENIED` event fails a stranger's tip in flight if its id is known. Money recorded wrong.
Fix: Match capture events only through ids we stored ourselves: the related order id against `tip.paypal_order_id`, then the capture id against `tip.paypal_capture_id`; never by `custom_id`. Refund events only through the refunded capture's id. `COMPLETED` and `PENDING` also need the event's amount and currency to equal the tip's, otherwise the event is recorded and the tip left alone, with a WARN.
Status: fixed in d029ce4

### 09-F002 [Critical] Anonymous requests of any size are read whole into memory
Where: every `@RequestBody` endpoint, most of all `POST /webhooks/paypal` (`@RequestBody String`), `POST /api/public/devices/{slug}/tips`, `POST /judges/resume`; form posts (`/owner/login`, `/judges/session`) up to Tomcat's 2 MB default
What: Nothing limits the size of a request body. The webhook reads the whole body into a `String` before anything is checked, and Jackson builds every string field of a JSON body before Bean Validation looks at its length. Cloudflare passes bodies up to 100 MB.
Why it matters: A few concurrent 100 MB posts to `/webhooks/paypal` need several hundred MB of heap on a 512 MB Render instance (heap at 75 %): OutOfMemoryError, the app stops serving, the demo breaks. No credentials needed.
Fix: One filter in `shared` that answers 413 problem details for a body over a small fixed limit (64 KB, far above any real request) by `Content-Length`, and stops reading a chunked body at the same limit; `server.tomcat.max-http-form-post-size` lowered to the same size.
Status: fixed in 0237ee9

### 09-F003 [Critical] The sign in limiter keeps every email it is sent, at any length, even after refusing
Where: dev.tip.owners.LoginRateLimiter.tryAcquire
What: Each sign in attempt puts its `email` field, as typed, into an in memory cache of up to 100 000 buckets kept 5 minutes. The email bucket is created even when the IP bucket already refused the attempt, and the field may be as long as the form allows (2 MB today).
Why it matters: About 400 sign in posts of 1 MB each with different emails, from one IP, inside five minutes hold some 400 MB of heap: OutOfMemoryError. Anonymous crash, independent of 09-F002 because the per IP limit does not stop it.
Fix: Key the email bucket by a bounded value: an email longer than 254 characters, which no owner can have, shares one bucket.
Status: fixed in 5b3cab2

### 09-F004 [Critical] A capture PayPal confirms after the tip was abandoned is never recorded
Where: dev.tip.tipping.TipRepository.markCaptured, AbandonedTipsJob
What: `AbandonedTipsJob` marks `CREATED` tips older than 6 hours `ABANDONED`. `markCaptured` moves only `CREATED` or `PENDING`. If our capture call reached PayPal but its answer was lost (timeout) and the tipper did not retry, the tip waits for the `PAYMENT.CAPTURE.COMPLETED` webhook; PayPal retries webhooks for days with growing gaps, so after an outage of our webhook endpoint the event can arrive after 6 hours. It then changes nothing, and its event id is stored, so PayPal's retries are no ops too. The same holds for a `FAILED` tip.
Why it matters: Money taken at PayPal and missing from our database for good, with nothing to reconcile it. Unlikely, but exactly the "payment not recorded" case.
Fix: A capture that PayPal confirms (capture response, order lookup or verified webhook, after 09-F001's checks) moves `CREATED`, `PENDING`, `FAILED` or `ABANDONED` to `CAPTURED` and publishes `TipCaptured` like any capture. A tipper's capture request for a `FAILED` or `ABANDONED` tip keeps answering 409 `TIP_CLOSED` without calling PayPal.
Status: fixed in e6f0212

## High

### 09-F005 [High] Per IP limits treat every IPv6 address as its own client
Where: dev.tip.shared.ClientRateLimiter, owners.LoginRateLimiter, devices.HandshakeFailureLimiter, devices.ObserverConnectionLimiter
What: Buckets are keyed by the full client address. An IPv6 client usually holds a whole /64, 2^64 addresses, and Cloudflare forwards each as its own `CF-Connecting-IP`.
Why it matters: Every per IP limit can be sidestepped from one home connection: tip creation (each a PayPal API call and a row), captures, sign in by IP, judge codes, demo sessions, resume posts, observer connections, device handshake failures. With free sandbox payments (09-F011) it also lifts the cap on model calls.
Fix: One helper that keys an IPv6 address by its /64 prefix and an IPv4 address as it is, used by every limiter.
Status: open

### 09-F006 [High] Anyone can make the server call PayPal without limit through the webhook
Where: dev.tip.tipping.PayPalWebhookController, paypal.WebhookSignatureVerifier
What: `/webhooks/paypal` has no rate limit. Any post with the five `PAYPAL-*` headers and a JSON object body makes an outbound verify call to PayPal (and an OAuth token call when none is cached).
Why it matters: A flood of fake webhooks spends our PayPal app's API quota; once PayPal throttles us (429), order creation and capture fail for real tippers and judges.
Fix: A failure only limiter per client IP on webhooks PayPal rejects (as `HandshakeFailureLimiter` does for devices), so PayPal's own deliveries, which never fail verification, are never limited; and a `PAYPAL-CERT-URL` whose host is not PayPal's rejected before any call.
Status: open

### 09-F007 [High] The capture response is applied without checking what was captured
Where: dev.tip.tipping.TipService.apply, paypal.PayPalOrders.fromOrder, paypal.api.CaptureResult
What: `CaptureResult.Completed` carries only the capture id. The amount and currency of the capture are never compared with the tip's.
Why it matters: The server creates the order for the tip's amount, so this only matters if a payer can change an order between approval and capture (the JS SDK's client side order patch). Defence in depth for the one number the owner sees, and the same rule the webhook gets in 09-F001.
Fix: Carry the captured amount and currency in `Completed` and `Pending`; `TipService` applies only a capture that matches the tip, otherwise answers `PAYPAL_CAPTURE_FAILED` and logs a WARN.
Status: open

### 09-F008 [High] A tipper who sees "We could not confirm your payment" may pay twice
Where: static/js/tip.js onApprove
What: When the capture answers 502 (`PAYPAL_CAPTURE_FAILED`, for example a PayPal timeout), the page shows an error with no Try again. A network failure offers Try again; a 5xx does not. A capture whose answer timed out may well have succeeded at PayPal, and the webhook later records it.
Why it matters: The tipper sees a failure, taps the PayPal button again and pays a second time. A judge can hit this on a slow sandbox.
Fix: Offer Try again for any 5xx answer of the capture (retrying is safe: same `PayPal-Request-Id`), with wording that does not say the payment failed.
Status: open

### 09-F009 [High] A declined capture can make the tipper's retry impossible
Where: dev.tip.tipping.PayPalWebhookProcessor (`PAYMENT.CAPTURE.DECLINED` on a `CREATED` tip), TipService.capture
What: On a declined card the page calls `actions.restart()` so the tipper can pick another method, and the tip stays `CREATED`. If PayPal also sends `PAYMENT.CAPTURE.DECLINED` for that attempt, the webhook moves the tip to `FAILED`, and the retried capture answers 409 `TIP_CLOSED`: the tipper is told to start again.
Why it matters: A judge testing a declined sandbox card and then a good one may hit a dead end. Whether PayPal sends `DECLINED` for an `INSTRUMENT_DECLINED` capture has to be checked against the sandbox.
Fix: lead decision: let `DENIED` and `DECLINED` fail only a `PENDING` tip (a pending capture that PayPal later refuses); a `CREATED` tip's decline is already reported to the tipper, who can retry. This changes the state diagram in `docs/ARCHITECTURE.md`.
Status: lead decision

### 09-F010 [High] Out of order refund or reversal leaves a refunded tip showing as captured
Where: dev.tip.tipping.PayPalWebhookProcessor
What: `PAYMENT.CAPTURE.REFUNDED` or `REVERSED` that arrives while the tip is still `CREATED` or `PENDING` (its `COMPLETED` delayed) changes nothing and is stored as processed. The late `COMPLETED` then captures the tip, which stays `CAPTURED`.
Why it matters: The owner's totals count money that went back, and the refund button offers a refund PayPal will refuse.
Fix: Answer 503 (not stored, PayPal retries later) for a refund or reversal whose tip is known but not yet captured, so the event is applied once the capture landed.
Status: open

## Medium

### 09-F011 [Medium] Free sandbox payments let anyone drive model calls
Where: dev.tip.ai (classification per captured tip), threat model "Running up the AI bill"
What: Production takes PayPal sandbox payments, which cost nothing; anyone can make a sandbox buyer account. "Every classification is paid for by a real tip" therefore does not hold: each tip with a message on the real panda is one model call, bounded only by 10 orders a minute per IP (and see 09-F005).
Why it matters: Spend up to the console limit, after which the breaker rests the model and tips react `PLAIN` for everyone.
Fix: lead decision: keep relying on the console spend limit (standing decision), or add a global hourly cap on classification calls (for example 300 an hour) past which tips react `PLAIN` once as `UNAVAILABLE`. Recommendation: the cap, it is small and keeps the demo reacting.
Status: lead decision

### 09-F012 [Medium] A partial refund made in the PayPal dashboard marks the whole tip refunded
Where: dev.tip.tipping.PayPalWebhookProcessor (`PAYMENT.CAPTURE.REFUNDED`)
What: Any refund event moves the tip to `REFUNDED`, whatever its amount.
Why it matters: Owner totals wrong after a partial refund done outside TiP. TiP itself only makes full refunds.
Fix: lead decision: accept (TiP never makes partial refunds), or move to `REFUNDED` only when the refunded total reaches the tip's amount.
Status: lead decision

### 09-F013 [Medium] A pending capture whose final webhook never arrives stays pending for good
Where: dev.tip.tipping (no reconciliation job)
What: A `PENDING` tip waits for `COMPLETED`, `DENIED` or `DECLINED`. Nothing asks PayPal again if that webhook is lost.
Why it matters: Today's numbers and requests count `PENDING`; a lost webhook leaves it there.
Fix: lead decision: a daily job that looks up the order of `PENDING` tips older than a day through `lookupOrder` and applies the result.
Status: lead decision

### 09-F014 [Medium] The firmware has no task watchdog
Where: firmware/src/main.cpp, network_task.cpp, nfc_task.cpp
What: No task is registered with the ESP-IDF task watchdog. The network task's own silence check recovers a dead connection, but a call that never returns (a stuck TLS read, a driver hang) leaves the panda dark until someone power cycles it.
Why it matters: The panda sits unattended until the end of 2026.
Fix: lead decision: register the network and NFC tasks with the task watchdog (for example 60 s) so a hang restarts the board. Needs flashing.
Status: lead decision

### 09-F015 [Medium] The observer channel accepts handshakes without an Origin
Where: dev.tip.devices.WebSocketConfig, docs/ARCHITECTURE.md, docs/DEVICE_PROTOCOL.md
What: `setAllowedOrigins(publicOrigin)` lets a client without `Origin` through (the class comment says so, for display scripts), while the architecture says "only the origin of `TIP_PUBLIC_BASE_URL`".
Why it matters: Harmless (the channel is public and carries labels only), but the docs promise more than the code does.
Fix: Correct the docs to say "the public origin, or no origin".
Status: open

### 09-F016 [Medium] Webhook events and abandoned tips are kept for ever
Where: `paypal_webhook_event`, `tip` rows in `ABANDONED` and `FAILED`
What: Nothing deletes them. Every created but unpaid tip (10 a minute per IP) stays.
Why it matters: Slow growth of a small Render database until the end of 2026.
Fix: A daily job deleting webhook events and unpaid tips older than 30 days.
Status: open

### 09-F017 [Medium] Unexpected errors are logged with their full message
Where: dev.tip.shared.ProblemDetailsAdvice.handleUnexpected
What: `log.error("Unexpected error", e)` logs the exception message and cause chain. A database exception can carry the SQL and PostgreSQL's detail (`Key (...)=(...)`).
Why it matters: Today no path is known to put a tip message into such an exception, but nothing prevents it.
Fix: Log the exception class and the stack only for non data exceptions, or accept. Small.
Status: open

### 09-F018 [Medium] CI downloads gitleaks without checking it
Where: .github/workflows/ci.yml (secrets-scan)
What: The gitleaks tarball is fetched with curl and run without a checksum check; actions are pinned by tag, not commit.
Why it matters: A tampered release would run in CI with the repository checked out. Low impact (read only token), but visible in a public repo.
Fix: Verify the published SHA-256 of the tarball before extracting.
Status: open

### 09-F026 [Medium] NoAiBeforeCaptureTest failed whenever it ran alone
Where: src/test/java/dev/tip/ai/NoAiBeforeCaptureTest
What: Found while fixing 09-F001. The test waited until the tip's AI status left `PENDING`, which `PROCESSING` already does, then counted the requests the model stub had received. In the full suite the warm JVM made the call in time; run alone, the first call of a cold JVM had not reached WireMock yet, and the test failed every time, on the untouched baseline too.
Why it matters: A flaky test around the one guarantee that no model call happens before a confirmed capture.
Fix: Wait for a final AI status.
Status: fixed in 775202c

## Low

### 09-F019 [Low] The webhook logs a sender supplied header at WARN
Where: dev.tip.tipping.PayPalWebhookController
What: `Rejected PayPal webhook with transmission id {}` logs the raw `PAYPAL-TRANSMISSION-ID` of an unverified request.
Fix: Log it truncated, or not at all.
Status: open

### 09-F020 [Low] The CSP's WebSocket origin comes from the Host header
Where: dev.tip.shared.SecurityDefaults.webSocketOrigin
What: `connect-src` names `ws(s)://<Host>`. A forged Host only changes the policy of the forger's own response, and pages are `no-store`.
Fix: Use `TIP_PUBLIC_BASE_URL`'s origin, or accept.
Status: open

### 09-F021 [Low] `PayPalWebhookEvent.customId` becomes unused once 09-F001 is fixed
Where: dev.tip.tipping.PayPalWebhookEvent
Fix: Remove it with 09-F001.
Status: fixed in d029ce4

### 09-F022 [Low] Demo sessions are created freely
Where: `POST /demo/session`
What: 30 sessions a minute per IP, each an HTTP session held in memory for its idle timeout.
Fix: Accept; covered by 09-F005 for IPv6.
Status: open

### 09-F023 [Low] Actions pinned by tag
Where: .github/workflows/ci.yml
Fix: Pin to commit SHAs, or accept for a hackathon repo.
Status: open

### 09-F024 [Low] The tip page shows the amount the browser sent, not the one the server charged
Where: static/js/tip.js (`state.sentMinor`)
What: The success note formats the amount the page posted. The server validates and charges exactly that amount, so they agree today.
Fix: Accept, or return the amount from the capture.
Status: open

### 09-F025 [Low] The test webhook builder always says 2.00 GBP
Where: src/test/java/dev/tip/WebhookEvents
What: Every test webhook carries `2.00 GBP` whatever the tip, which hid the missing amount check (09-F001).
Fix: Take the amount as a parameter, done with 09-F001.
Status: fixed in d029ce4

### 09-F027 [Low] The friendly detail for 413 never applied
Where: dev.tip.shared.GenericProblems
What: Found while fixing 09-F002. The detail switch named `HttpStatus.PAYLOAD_TOO_LARGE`, but `HttpStatus.valueOf(413)` resolves to `CONTENT_TOO_LARGE`, so a framework 413 got "Content Too Large." instead of "The request is too large."
Fix: Name `CONTENT_TOO_LARGE`.
Status: fixed in 0237ee9

## Checked and fine

- Amounts: `long` minor units everywhere, currency fixed by `TIP_CURRENCY`, `BigDecimal.valueOf(minor, digits)` for PayPal's decimal string, bounds checked on the server. The capture uses the order id stored on the tip, never one from the client.
- Idempotency: `PayPal-Request-Id` on create, capture and refund; unique order and capture ids; webhook event id unique, inserted in the transition's transaction; every status change a conditional update. Refund twice: same request id, then the conditional update.
- No PayPal or Anthropic call inside a database transaction.
- Owner scoping: every dashboard, moderation, digest and refund query is scoped by `device.owner_id` in SQL with one 404; `SandboxIsolationTest` covers it.
- Public demo: one central filter answers 403 `DEMO_READ_ONLY` to every non GET under `/api/owner`; `PublicDemoTest` sweeps every mapping.
- Sessions: id changes on every sign in (form login and `OwnerSignIn`); CSRF on in the owner chain; cookies `HttpOnly`, `Secure`, `SameSite=Lax`.
- Codes and tokens: access codes compared with `MessageDigest.isEqual` against every entry; device secrets hashed and compared in constant time; resume tokens single use by one conditional update, spent only by a post.
- Devices: handshake authenticated, ack scoped to the session's own device, inbound size and invalid message limits, replacement and close ordering as documented; observers get label, repeats and time only.
- AI: message only in the user turn inside delimiters, structured output re-checked in Java, failures never retried, breaker on 4xx, no tip text or model output logged at INFO or above.
- Pages: nonce on every script, no inline handlers, no `innerHTML` with data, framing rules as decided, `no-store` on pages and private API answers.
- Config: every required variable validated at startup; secrets kept out of `toString`.
- Migrations V1 to V8: constraints and indexes the code relies on exist.
- Firmware: JSON parsing through a filtered fixed size document, NDEF and APDU lengths bounded, secrets only in the git ignored `secrets.h`.
- Licences: Fredoka OFL present, third party notices complete for what is shipped.
