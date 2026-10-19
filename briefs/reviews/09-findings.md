# Brief 09 findings

The thread between the passes of Brief 09. Every finding of the review, its severity and where it stands.

## Where we are

- Pass 1 (audit, then Critical): done. Audit over backend, static pages, firmware, build, CI, config, migrations and tests. Baseline `./gradlew clean build` green before any change (696 tests); green after every commit of the pass (708 tests at the end). gitleaks 8.30.1 over the full history (174 commits, every branch): no leaks. Every Critical fixed, each with tests that failed before the fix.
- Pass 2 (High, then the lead's decisions, then Medium and Low): done. Every High fixed; the lead's decisions on 09-F009, 09-F011, 09-F012, 09-F013 and 09-F014 carried out; the new rule that a tip revived from `ABANDONED` or `FAILED` is recorded and classified but never played on the device is in (332f089). Mediums and Lows fixed as the lead listed; 09-F022 and 09-F024 accepted. Green after every commit (`./gradlew build`, 720 tests at the start of the pass, 754 at the end; `pio run` and `pio test -e native` for the firmware).
- Deferred reads done in pass 2: `docs/DEPLOYMENT.md` (one fix, 09-F033), a line by line read of `owner.js`, `live.js` and `panda.js` (Low findings only, 09-F029 to 09-F032) and the README files, checked against the code, findings only, not edited (09-F034, 09-F035).
- New in pass 2: 09-F028 (found while testing 09-F019) and 09-F029 to 09-F035.
- Pass 3 (the lead's decisions after pass 2): done. A tip plays on the device only when its capture landed within `tip.ai.playback.window` (10 minutes) of `capture_attempted_at`, replacing the revived rule for playback (877c439); 09-F006 checked and tightened (2b039bf); 09-F029 to 09-F032 fixed (d76eab7). Green after every commit (`./gradlew build`, 767 tests at the end).
- README findings: 09-F034 and 09-F035 fixed in the final docs pass. The firmware of 09-F014 needs flashing.

## Counts

| Severity | Found | Fixed | Accepted | Open |
|---|---|---|---|---|
| Critical | 4 | 4 | 0 | 0 |
| High | 6 | 6 | 0 | 0 |
| Medium | 10 | 9 | 1 | 0 |
| Low | 15 | 13 | 2 | 0 |

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
Status: fixed in 077d39c

### 09-F006 [High] Anyone can make the server call PayPal without limit through the webhook
Where: dev.tip.tipping.PayPalWebhookController, paypal.WebhookSignatureVerifier
What: `/webhooks/paypal` has no rate limit. Any post with the five `PAYPAL-*` headers and a JSON object body makes an outbound verify call to PayPal (and an OAuth token call when none is cached).
Why it matters: A flood of fake webhooks spends our PayPal app's API quota; once PayPal throttles us (429), order creation and capture fail for real tippers and judges.
Fix: A failure only limiter per client IP on webhooks PayPal rejects (as `HandshakeFailureLimiter` does for devices), so PayPal's own deliveries, which never fail verification, are never limited; and a `PAYPAL-CERT-URL` whose host is not PayPal's rejected before any call.
Status: fixed in 0270690. Pass 3 check (lead): only a definite rejection counts (our own checks, PayPal's `FAILURE`, or PayPal's 400 or 422 on the transmission); a verify call that fails (timeout, lost connection, 5xx, 429, 401, other 4xx, no status) answers 503 and is not counted. Before, a 403, 404 or a 200 without a status counted. Fixed in 2b039bf

### 09-F007 [High] The capture response is applied without checking what was captured
Where: dev.tip.tipping.TipService.apply, paypal.PayPalOrders.fromOrder, paypal.api.CaptureResult
What: `CaptureResult.Completed` carries only the capture id. The amount and currency of the capture are never compared with the tip's.
Why it matters: The server creates the order for the tip's amount, so this only matters if a payer can change an order between approval and capture (the JS SDK's client side order patch). Defence in depth for the one number the owner sees, and the same rule the webhook gets in 09-F001.
Fix: Carry the captured amount and currency in `Completed` and `Pending`; `TipService` applies only a capture that matches the tip, otherwise answers `PAYPAL_CAPTURE_FAILED` and logs a WARN.
Status: fixed in f546c57

### 09-F008 [High] A tipper who sees "We could not confirm your payment" may pay twice
Where: static/js/tip.js onApprove
What: When the capture answers 502 (`PAYPAL_CAPTURE_FAILED`, for example a PayPal timeout), the page shows an error with no Try again. A network failure offers Try again; a 5xx does not. A capture whose answer timed out may well have succeeded at PayPal, and the webhook later records it.
Why it matters: The tipper sees a failure, taps the PayPal button again and pays a second time. A judge can hit this on a slow sandbox.
Fix: Offer Try again for any 5xx answer of the capture (retrying is safe: same `PayPal-Request-Id`), with wording that does not say the payment failed.
Status: fixed in 65b5423

### 09-F009 [High] A declined capture can make the tipper's retry impossible
Where: dev.tip.tipping.PayPalWebhookProcessor (`PAYMENT.CAPTURE.DECLINED` on a `CREATED` tip), TipService.capture
What: On a declined card the page calls `actions.restart()` so the tipper can pick another method, and the tip stays `CREATED`. If PayPal also sends `PAYMENT.CAPTURE.DECLINED` for that attempt, the webhook moves the tip to `FAILED`, and the retried capture answers 409 `TIP_CLOSED`: the tipper is told to start again.
Why it matters: A judge testing a declined sandbox card and then a good one may hit a dead end. Whether PayPal sends `DECLINED` for an `INSTRUMENT_DECLINED` capture has to be checked against the sandbox.
Fix: lead decision, done: `DENIED` and `DECLINED` fail only a `PENDING` tip (a pending capture that PayPal later refuses); a `CREATED` tip's decline is already reported to the tipper, who can retry. The state diagram in `docs/ARCHITECTURE.md` is updated.
Status: fixed in dfab2a7

### 09-F010 [High] Out of order refund or reversal leaves a refunded tip showing as captured
Where: dev.tip.tipping.PayPalWebhookProcessor
What: `PAYMENT.CAPTURE.REFUNDED` or `REVERSED` that arrives while the tip is still `CREATED` or `PENDING` (its `COMPLETED` delayed) changes nothing and is stored as processed. The late `COMPLETED` then captures the tip, which stays `CAPTURED`.
Why it matters: The owner's totals count money that went back, and the refund button offers a refund PayPal will refuse.
Fix: Answer 503 (not stored, PayPal retries later) for a refund or reversal whose tip is known but not yet captured, so the event is applied once the capture landed.
Status: fixed in 5379041

## Medium

### 09-F011 [Medium] Free sandbox payments let anyone drive model calls
Where: dev.tip.ai (classification per captured tip), threat model "Running up the AI bill"
What: Production takes PayPal sandbox payments, which cost nothing; anyone can make a sandbox buyer account. "Every classification is paid for by a real tip" therefore does not hold: each tip with a message on the real panda is one model call, bounded only by 10 orders a minute per IP (and see 09-F005).
Why it matters: Spend up to the console limit, after which the breaker rests the model and tips react `PLAIN` for everyone.
Fix: lead decision, done: a global in memory cap of 200 classification calls a UTC hour and 1000 a UTC day; past it the tip reacts `PLAIN` x1 as `UNAVAILABLE` and is never classified later; one WARN `ai_cap_reached` per window.
Status: fixed in 110c7f5

### 09-F012 [Medium] A partial refund made in the PayPal dashboard marks the whole tip refunded
Where: dev.tip.tipping.PayPalWebhookProcessor (`PAYMENT.CAPTURE.REFUNDED`)
What: Any refund event moves the tip to `REFUNDED`, whatever its amount.
Why it matters: Owner totals wrong after a partial refund done outside TiP. TiP itself only makes full refunds.
Fix: lead decision: accept (TiP never makes partial refunds), documented in `docs/ARCHITECTURE.md` ("Refunds made outside TiP").
Status: won't fix (accepted by the lead), documented in bc675be

### 09-F013 [Medium] A pending capture whose final webhook never arrives stays pending for good
Where: dev.tip.tipping (no reconciliation job)
What: A `PENDING` tip waits for `COMPLETED`, `DENIED` or `DECLINED`. Nothing asks PayPal again if that webhook is lost.
Why it matters: Today's numbers and requests count `PENDING`; a lost webhook leaves it there.
Fix: lead decision, done: a job looks up, every hour, `PENDING` tips created more than an hour ago; the abandon job looks up the order before abandoning, only for tips whose capture was attempted (V9 adds `tip.capture_attempted_at`); orders never approved are never looked up. Decision made: an attempted tip whose lookup finds no capture, or fails, is abandoned as before; a capture PayPal confirms later still records it (09-F004). Open question answered by the lead in pass 3: a tip plays only when its capture landed within 10 minutes of `capture_attempted_at` (`tip.ai.playback.window`), whoever settled it; a capture TiP never attempted does not play; `tip.revived` stays as a record.
Status: fixed in b4ea341; playback window in 877c439

### 09-F014 [Medium] The firmware has no task watchdog
Where: firmware/src/main.cpp, network_task.cpp, nfc_task.cpp
What: No task is registered with the ESP-IDF task watchdog. The network task's own silence check recovers a dead connection, but a call that never returns (a stuck TLS read, a driver hang) leaves the panda dark until someone power cycles it.
Why it matters: The panda sits unattended until the end of 2026.
Fix: lead decision, done: the ESP-IDF task watchdog at 60 s on the `net`, `led` and `nfc` tasks, fed in every wait loop, and the reset reason logged on boot. Needs flashing.
Status: fixed in 01671b7, needs flashing (not flashed in pass 2: the owner kept the serial monitor open)

### 09-F015 [Medium] The observer channel accepts handshakes without an Origin
Where: dev.tip.devices.WebSocketConfig, docs/ARCHITECTURE.md, docs/DEVICE_PROTOCOL.md
What: `setAllowedOrigins(publicOrigin)` lets a client without `Origin` through (the class comment says so, for display scripts), while the architecture says "only the origin of `TIP_PUBLIC_BASE_URL`".
Why it matters: Harmless (the channel is public and carries labels only), but the docs promise more than the code does.
Fix: Correct the docs to say "the public origin, or no origin".
Status: fixed in dfb7141

### 09-F016 [Medium] Webhook events and abandoned tips are kept for ever
Where: `paypal_webhook_event`, `tip` rows in `ABANDONED` and `FAILED`
What: Nothing deletes them. Every created but unpaid tip (10 a minute per IP) stays.
Why it matters: Slow growth of a small Render database until the end of 2026.
Fix: A nightly job deleting webhook events and unpaid tips older than 30 days, 1000 rows a statement.
Status: fixed in 454a242

### 09-F017 [Medium] Unexpected errors are logged with their full message
Where: dev.tip.shared.ProblemDetailsAdvice.handleUnexpected
What: `log.error("Unexpected error", e)` logs the exception message and cause chain. A database exception can carry the SQL and PostgreSQL's detail (`Key (...)=(...)`).
Why it matters: Today no path is known to put a tip message into such an exception, but nothing prevents it.
Fix: A database error (any `DataAccessException` or `SQLException` in the cause chain) is logged as its class chain and first frame in TiP's code only. Writing the test showed Hibernate itself logs PostgreSQL's detail at WARN (`org.hibernate.orm.jdbc.error`); that logger is set to ERROR.
Status: fixed in a3f1c94

### 09-F018 [Medium] CI downloads gitleaks without checking it
Where: .github/workflows/ci.yml (secrets-scan)
What: The gitleaks tarball is fetched with curl and run without a checksum check; actions are pinned by tag, not commit.
Why it matters: A tampered release would run in CI with the repository checked out. Low impact (read only token), but visible in a public repo.
Fix: Verify the published SHA-256 of the tarball before extracting.
Status: fixed in 9248a30

### 09-F026 [Medium] NoAiBeforeCaptureTest failed whenever it ran alone
Where: src/test/java/dev/tip/ai/NoAiBeforeCaptureTest
What: Found while fixing 09-F001. The test waited until the tip's AI status left `PENDING`, which `PROCESSING` already does, then counted the requests the model stub had received. In the full suite the warm JVM made the call in time; run alone, the first call of a cold JVM had not reached WireMock yet, and the test failed every time, on the untouched baseline too.
Why it matters: A flaky test around the one guarantee that no model call happens before a confirmed capture.
Fix: Wait for a final AI status.
Status: fixed in 775202c

### 09-F028 [Medium] A header value Spring Security's firewall refuses answered 500 and was logged whole at ERROR
Where: dev.tip.shared.ProblemDetailsAdvice, every controller that reads a header (`PayPalWebhookController` first of all)
What: Found while testing 09-F019. `StrictHttpFirewall` checks a header value only when something reads it, so a value with a character it refuses (a control character, or a UTF-8 byte that reads as one, such as the middle byte of a euro sign, which Tomcat passes through) threw `RequestRejectedException` inside MVC. The catch all handler answered 500 `INTERNAL_ERROR` and logged the exception at ERROR, its message repeating the header value.
Why it matters: Anyone could fill the log with ERROR lines of their own text, and a client error looked like a server fault.
Fix: Map `RequestRejectedException` to 400 `BAD_REQUEST` problem details and log one WARN without the value.
Status: fixed in e8026d7

## Low

### 09-F019 [Low] The webhook logs a sender supplied header at WARN
Where: dev.tip.tipping.PayPalWebhookController
What: `Rejected PayPal webhook with transmission id {}` logs the raw `PAYPAL-TRANSMISSION-ID` of an unverified request.
Fix: Log it as one line of at most 36 characters (a uuid's length).
Status: fixed in f5ab28c

### 09-F020 [Low] The CSP's WebSocket origin comes from the Host header
Where: dev.tip.shared.SecurityDefaults.webSocketOrigin
What: `connect-src` names `ws(s)://<Host>`. A forged Host only changes the policy of the forger's own response, and pages are `no-store`.
Fix: Use `TIP_PUBLIC_BASE_URL`'s origin.
Status: fixed in bfd8182

### 09-F021 [Low] `PayPalWebhookEvent.customId` becomes unused once 09-F001 is fixed
Where: dev.tip.tipping.PayPalWebhookEvent
Fix: Remove it with 09-F001.
Status: fixed in d029ce4

### 09-F022 [Low] Demo sessions are created freely
Where: `POST /demo/session`
What: 30 sessions a minute per IP, each an HTTP session held in memory for its idle timeout.
Fix: Accept; covered by 09-F005 for IPv6.
Status: won't fix (accepted by the lead)

### 09-F023 [Low] Actions pinned by tag
Where: .github/workflows/ci.yml
Fix: Pin to commit SHAs, the release named beside each.
Status: fixed in db1e49d

### 09-F024 [Low] The tip page shows the amount the browser sent, not the one the server charged
Where: static/js/tip.js (`state.sentMinor`)
What: The success note formats the amount the page posted. The server validates and charges exactly that amount, so they agree today.
Fix: Accept, or return the amount from the capture.
Status: won't fix (accepted by the lead)

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

### 09-F029 [Low] The virtual panda needs Safari 15.4 or later
Where: static/js/panda.js `onMessage`
What: `Object.hasOwn` arrived in Safari 15.4 (2022). On an older iPhone it throws on every message, so the page says Live and never plays.
Fix: `Object.prototype.hasOwnProperty.call(REACTIONS, message.reaction)`, or accept.
Status: fixed in d76eab7

### 09-F030 [Low] The virtual panda keeps reconnecting after its device is gone
Where: static/js/panda.js `connect`
What: When an expired sandbox is deleted, the observer handshake answers 404 and the page retries for ever with the backoff, once a minute at most.
Fix: Accept (one handshake a minute per open tab), or stop after a 404 seen through a fresh `GET /api/public/devices/{slug}`.
Status: fixed in d76eab7

### 09-F031 [Low] The dashboard's resting strip is read once per page load
Where: static/js/owner.js `loadMe`
What: `aiResting` comes from `/api/owner/me` only at start, so the strip neither appears when the model starts resting nor goes when it is back, until a reload. Each tip still says Not read.
Fix: Read `/me` again with the `today` refresh, or accept.
Status: fixed in d76eab7

### 09-F032 [Low] The side by side page stays blank on an unreadable devices answer
Where: static/js/live.js `start`
What: A 200 from `/api/owner/devices` whose body is not JSON rejects `response.json()` without a catch: no frames, no error note.
Fix: Catch it and show `load-error`, or accept (the server always answers JSON).
Status: fixed in d76eab7

### 09-F033 [Low] DEPLOYMENT said the health check looks at the database and disk only
Where: docs/DEPLOYMENT.md, Health check
What: The health endpoint also has Spring's ping and SSL indicators (`CODEBASE.md` names them). Harmless, neither calls anything outside the app, but the text was wrong.
Fix: Name them.
Status: fixed in 5bbb62a

### 09-F034 [Low] README.md: details that no longer match the code
Where: README.md
What: Report only, the owner edits the READMEs.
- Project layout: `tipping/` says "scheduled job"; there are now three (abandoned tips, pending tips, retention). `docs/` lists "architecture, device protocol and deployment" but also holds `OWNER_CHANNEL.md` and `WORKFLOW.md`.
- Local setup step 2: the demo owner password is "12 or more characters"; the app also refuses more than 72 bytes (bcrypt reads only 72), as `docs/DEPLOYMENT.md` says.
- Paying with a test card: the menu is called **Testing Tools > Card Testing** here and **Testing Tools > Card Generator** in `docs/DEPLOYMENT.md`; one of them is stale.
- Measuring the AI classifier: "about 60 labelled tip messages"; the file has 58.
- Testing webhooks locally, step 4: unverified webhooks get 400, and after 10 a minute from one address 429 (09-F006).
- The AI behaviour it describes has gained the classification cap (09-F011): past 200 an hour or 1000 a day tips react `PLAIN` once.
Status: fixed in 730ca4f and this commit: the setup details moved to `docs/SETUP.md` with every point corrected (three tipping jobs, the full `docs/` folder, the 72 byte password cap, 58 cases, the 429 after rejected webhooks, the classification cap), and `docs/SETUP.md`, `docs/DEPLOYMENT.md` and `.env.example` now name the card testing page by its URL

### 09-F035 [Low] firmware/README.md: no word of the task watchdog
Where: firmware/README.md
What: Report only. After 09-F014 the Tasks and Behaviour sections should say that the `net`, `led` and `nfc` tasks are watched by the task watchdog (60 s, then a restart) and that the boot log names the reset reason (`Boot: reset reason ...`). The Structure list also leaves out `src/main.cpp`, `src/log.cpp` (described under Logging) and the new `src/watchdog.cpp`.
Status: fixed in 7a8a085 and this commit: `firmware/DESIGN.md` has a Task watchdog section, a watchdog line in Behaviour, and `src/main.cpp`, `src/log.cpp` and `src/watchdog.cpp` in Structure; `firmware/README.md` shows the reset reason line of a healthy start

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
- Migrations V1 to V8: constraints and indexes the code relies on exist. V9 and V10 (pass 2) add a nullable column and a column with a default, so the previous release keeps working while they run.
- `docs/DEPLOYMENT.md` read line by line against the code and the CI workflow in pass 2: variables, rate limits, log lines, deploy flow and smoke test match, apart from 09-F033.
- `owner.js`, `live.js` and `panda.js` read line by line in pass 2: every server value through `textContent`, `createTextNode` or an encoded URL; ids from the socket checked as uuids before use; CSRF header on every write; no storage. Findings 09-F029 to 09-F032 only.
- Firmware: JSON parsing through a filtered fixed size document, NDEF and APDU lengths bounded, secrets only in the git ignored `secrets.h`.
- Licences: Fredoka OFL present, third party notices complete for what is shipped.
